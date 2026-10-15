
import argparse
import csv
import json
import os
import time
from datetime import datetime

import pandas as pd
from ragas import evaluate, EvaluationDataset
from ragas.dataset_schema import SingleTurnSample

from evaluation.harness import RAGAS_LLM, RAGAS_EMBEDDINGS, RAGAS_RUN_CONFIG, DATASETS, get_ingest_paths
from evaluation.effort_utils import CountingLLM
from ragas.metrics._faithfulness import faithfulness
from ragas.metrics._context_precision import context_precision
from ragas.metrics._context_recall import context_recall
from ragas.metrics._answer_relevance import answer_relevancy

ALL_METRIC_OBJS = {
    "faithfulness": faithfulness,
    "answer_relevancy": answer_relevancy,
    "context_precision": context_precision,
    "context_recall": context_recall,
}

RESULTS_ROOT = os.path.join(os.path.dirname(__file__), "results")


def classify_block(text: str) -> str:
    """Heuristic block-type tag from the fixed-format strings the graph
    systems use. AST-family variants call with force_type='fragment' instead."""
    if text.startswith("Architectural Community"):
        return "community"
    if text.startswith("Paths between seed nodes:"):
        return "path"
    return "node"


def run_variant(
    system_name: str,
    system_factory,
    ingest_path_key: str,
    dataset: str,
    metrics: list[str],
    label: str,
    force_block_type: str | None = None,
    save_community_summaries: bool = False,
    limit: int | None = None,
):
    """ingest_path_key: which key of get_ingest_paths(dataset) to feed this
    system's ingest() (e.g. 'ast' for ast_fill, reusing the AST metadata JSON)."""
    d = DATASETS[dataset]
    ingest_paths = get_ingest_paths(dataset)
    with open(d["ground_truth"]) as f:
        ground_truth = json.load(f)
    if limit:
        ground_truth = ground_truth[:limit]

    out_dir = os.path.join(RESULTS_ROOT, f"variants_{label}")
    os.makedirs(out_dir, exist_ok=True)

    scores_path = os.path.join(out_dir, f"scores_{system_name}_{dataset}.csv")
    if os.path.exists(scores_path) and not limit:
        print(f"[{system_name}/{dataset}] already complete, skipping ({scores_path})")
        return pd.read_csv(scores_path).to_dict("records")

    print(f"[{system_name}/{dataset}] ingesting...")
    system = system_factory()
    counting_llm = CountingLLM(system.llm)
    system.llm = counting_llm
    t0 = time.perf_counter()
    system.ingest(ingest_paths[ingest_path_key])
    ingest_seconds = time.perf_counter() - t0
    llm_calls_during_ingest = counting_llm.calls
    print(f"[{system_name}/{dataset}] ingested in {ingest_seconds:.1f}s, "
          f"{llm_calls_during_ingest} LLM calls")

    if save_community_summaries and hasattr(system, "community_summaries"):
        summ_path = os.path.join(out_dir, f"community_summaries_{system_name}_{dataset}.json")
        with open(summ_path, "w") as f:
            json.dump(system.community_summaries, f, indent=2, ensure_ascii=False)
        print(f"[{system_name}/{dataset}] community summaries -> {summ_path}")

    rows = []
    block_rows = []
    for qa in ground_truth:
        t0 = time.perf_counter()
        answer, contexts = system.query(qa["question"])
        latency = time.perf_counter() - t0

        for i, block in enumerate(contexts):
            block_type = force_block_type or classify_block(block)
            block_rows.append({
                "dataset": dataset, "system": system_name, "question": qa["question"],
                "block_index": i, "block_type": block_type, "n_chars": len(block),
                "block_text": block,
            })

        rows.append({
            "question": qa["question"],
            "answer": answer,
            "contexts": contexts,
            "ground_truth": qa["ground_truth"],
            "category": qa["category"],
            "query_latency_seconds": round(latency, 3),
        })

    raw_path = os.path.join(out_dir, f"raw_{system_name}_{dataset}.json")
    with open(raw_path, "w") as f:
        json.dump(rows, f, indent=2)
    print(f"[{system_name}/{dataset}] raw answers+contexts -> {raw_path}")

    blocks_path = os.path.join(out_dir, f"blocks_{system_name}_{dataset}.csv")
    if block_rows:
        with open(blocks_path, "w", newline="") as f:
            writer = csv.DictWriter(f, fieldnames=block_rows[0].keys())
            writer.writeheader()
            writer.writerows(block_rows)
        print(f"[{system_name}/{dataset}] context blocks -> {blocks_path}")

    # RAGAS scoring, only the requested metrics
    metric_objs = [ALL_METRIC_OBJS[m] for m in metrics]
    samples = [
        SingleTurnSample(
            user_input=r["question"],
            response=r["answer"],
            retrieved_contexts=r["contexts"],
            reference=r["ground_truth"],
        )
        for r in rows
    ]
    result = evaluate(EvaluationDataset(samples=samples), metrics=metric_objs,
                       llm=RAGAS_LLM, embeddings=RAGAS_EMBEDDINGS, run_config=RAGAS_RUN_CONFIG)
    score_df = result.to_pandas()

    per_question = []
    for i, r in enumerate(rows):
        rec = {
            "dataset": dataset, "system": system_name, "question": r["question"],
            "category": r["category"], "ingest_seconds": round(ingest_seconds, 2),
            "llm_calls_during_ingest": llm_calls_during_ingest,
            "query_latency_seconds": r["query_latency_seconds"],
        }
        for m in metrics:
            rec[m] = score_df.iloc[i][m] if m in score_df.columns else float("nan")
        per_question.append(rec)

    scores_path = os.path.join(out_dir, f"scores_{system_name}_{dataset}.csv")
    with open(scores_path, "w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=per_question[0].keys())
        writer.writeheader()
        writer.writerows(per_question)
    print(f"[{system_name}/{dataset}] per-question scores -> {scores_path}")

    return per_question


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--step", required=True, choices=["1", "3", "4"])
    parser.add_argument("--label", default=None, help="results folder suffix (default: step + date)")
    parser.add_argument("--limit", type=int, default=None, help="only run first N questions (testing)")
    args = parser.parse_args()

    label = args.label or f"step{args.step}_{datetime.now().strftime('%Y%m%d')}"

    if args.step == "1":
        from systems.ast_fill_rag import AstFillRAG
        from systems.ast_body_fill_rag import AstBodyFillRAG

        variants = [
            ("ast_fill", AstFillRAG, "ast"),
            ("ast_body_fill", AstBodyFillRAG, "ast"),
        ]
        metrics = ["faithfulness", "answer_relevancy", "context_precision", "context_recall"]

        all_results = []
        for dataset in ["petclinic", "microservices", "mall"]:
            for system_name, factory, ingest_key in variants:
                res = run_variant(system_name, factory, ingest_key, dataset, metrics, label,
                                   force_block_type="fragment", limit=args.limit)
                all_results.extend(res)

        combined_path = os.path.join(RESULTS_ROOT, f"variants_{label}", "per_question_scores_step1.csv")
        pd.DataFrame(all_results).to_csv(combined_path, index=False)
        print(f"\nCombined per-question scores -> {combined_path}")

    elif args.step == "3":
        from systems.arch_graph_ladder_rag import G1RAG, G2RAG, G3RAG, G4RAG

        variants = [
            ("graph_ladder_g1", G1RAG, "arch_graph"),
            ("graph_ladder_g2", G2RAG, "arch_graph"),
            ("graph_ladder_g3", G3RAG, "arch_graph"),
            ("graph_ladder_g4", G4RAG, "arch_graph"),
        ]
        metrics = ["context_precision", "context_recall"]

        all_results = []
        ladder_stats = []
        for dataset in ["petclinic", "microservices", "mall"]:
            for system_name, factory, ingest_key in variants:
                system = factory()
                res = run_variant(system_name, lambda s=system: s, ingest_key, dataset, metrics, label,
                                   save_community_summaries=True, limit=args.limit)
                all_results.extend(res)
                if getattr(system, "stats", None):
                    ladder_stats.append({"dataset": dataset, "system": system_name, **system.stats})

        out_dir = os.path.join(RESULTS_ROOT, f"variants_{label}")
        combined_path = os.path.join(out_dir, "per_question_scores_step3.csv")
        pd.DataFrame(all_results).to_csv(combined_path, index=False)
        print(f"\nCombined per-question scores -> {combined_path}")

        if ladder_stats:
            stats_path = os.path.join(out_dir, "graph_ladder_stats.csv")
            with open(stats_path, "w", newline="") as f:
                writer = csv.DictWriter(f, fieldnames=ladder_stats[0].keys())
                writer.writeheader()
                writer.writerows(ladder_stats)
            print(f"Graph ladder node/edge/component stats -> {stats_path}")

    else:
        raise NotImplementedError(f"Step {args.step} not wired up yet")


if __name__ == "__main__":
    main()
