import argparse
import json
import csv
import os
import time
from datetime import datetime

import pandas as pd

from evaluation.effort_utils import CountingLLM, code_size

from ragas import evaluate, RunConfig, EvaluationDataset
from ragas.dataset_schema import SingleTurnSample
from ragas.metrics._faithfulness import faithfulness
from ragas.metrics._context_precision import context_precision
from ragas.metrics._context_recall import context_recall
from ragas.metrics._answer_relevance import answer_relevancy
from langchain_ollama import OllamaLLM, OllamaEmbeddings

from systems.plain_text_rag import PlainTextRAG
from systems.ast_rag import AstRAG
from systems.ast_body_rag import AstBodyRAG
from systems.plain_graph_rag import PlainGraphRAG
from systems.arch_graph_rag import ArchGraphRAG

RAGAS_LLM = OllamaLLM(model="llama3.1:8b", temperature=0, format="json")
RAGAS_EMBEDDINGS = OllamaEmbeddings(model="mxbai-embed-large")
RAGAS_RUN_CONFIG = RunConfig(timeout=180, max_workers=1, max_retries=2)
METRICS = [faithfulness, answer_relevancy, context_precision, context_recall]

DATA_DIR = os.path.join(os.path.dirname(__file__), "..", "data")
RESULTS_DIR = os.path.join(os.path.dirname(__file__), "results")

DATASETS = {
    "petclinic": {
        "ground_truth": os.path.join(os.path.dirname(__file__), "ground_truth.json"),
        "java_src":     os.path.join(DATA_DIR, "petclinic_src"),
        "ast_json":     os.path.join(DATA_DIR, "rag-parser-output.json"),
        "graph_json":   os.path.join(DATA_DIR, "rag-parser-graph.json"),
    },
    "microservices": {
        "ground_truth": os.path.join(os.path.dirname(__file__), "ground_truth_microservices.json"),
        "java_src":     os.path.join(DATA_DIR, "microservices", "petclinic_src"),
        "ast_json":     os.path.join(DATA_DIR, "microservices", "rag-parser-output.json"),
        "graph_json":   os.path.join(DATA_DIR, "microservices", "rag-parser-graph.json"),
    },
    "mall": {
        "ground_truth": os.path.join(os.path.dirname(__file__), "ground_truth_mall.json"),
        "java_src":     os.path.join(DATA_DIR, "mall", "java_src"),
        "ast_json":     os.path.join(DATA_DIR, "mall", "rag-parser-output.json"),
        "graph_json":   os.path.join(DATA_DIR, "mall", "rag-parser-graph.json"),
    },
    "eladmin": {
        "ground_truth": os.path.join(os.path.dirname(__file__), "ground_truth_eladmin.json"),
        "java_src":     os.path.join(DATA_DIR, "eladmin", "java_src"),
        "ast_json":     os.path.join(DATA_DIR, "eladmin", "rag-parser-output.json"),
        "graph_json":   os.path.join(DATA_DIR, "eladmin", "rag-parser-graph.json"),
    },
}

ALL_SYSTEMS = ["plain_text", "ast", "ast_body", "plain_graph", "arch_graph"]
SYSTEM_CLASSES = {
    "plain_text": PlainTextRAG,
    "ast": AstRAG,
    "ast_body": AstBodyRAG,
    "plain_graph": PlainGraphRAG,
    "arch_graph": ArchGraphRAG,
}


def get_ingest_paths(dataset: str) -> dict:
    d = DATASETS[dataset]
    return {
        "plain_text": d["java_src"],
        "ast":        d["ast_json"],
        "ast_body":   d["ast_json"],
        "plain_graph": d["graph_json"],
        "arch_graph": d["graph_json"],
    }


def rows_to_dataset(rows: list[dict]) -> EvaluationDataset:
    samples = [
        SingleTurnSample(
            user_input=r["question"],
            response=r["answer"],
            retrieved_contexts=r["contexts"],
            reference=r["ground_truth"],
        )
        for r in rows
    ]
    return EvaluationDataset(samples=samples)


def eval_to_dataframe(rows: list[dict]) -> pd.DataFrame:
    """Run RAGAS once; return per-sample scores with category column attached."""
    result = evaluate(
        rows_to_dataset(rows),
        metrics=METRICS,
        llm=RAGAS_LLM,
        embeddings=RAGAS_EMBEDDINGS,
        run_config=RAGAS_RUN_CONFIG,
    )
    df = result.to_pandas()
    df["category"] = [r["category"] for r in rows]
    return df


def print_and_collect_scores(system_name: str, rows: list[dict], summary_rows: list[dict]):
    print(f"  Evaluating system: {system_name}")

    df = eval_to_dataframe(rows)

    # ALL questions
    all_means = df.mean(numeric_only=True).to_dict()
    summary_rows.append({
        "system": system_name,
        "category": "ALL",
        **{k: round(v, 4) for k, v in all_means.items()},
    })

    # per-category — slice the already-computed DataFrame, no extra RAGAS calls
    for category in ["single-class", "cross-layer", "jpa", "architectural-flow"]:
        cat_df = df[df["category"] == category]
        if cat_df.empty:
            continue
        cat_means = cat_df.mean(numeric_only=True).to_dict()
        summary_rows.append({
            "system": system_name,
            "category": category,
            **{k: round(v, 4) for k, v in cat_means.items()},
        })


def save_and_print_summary(summary_rows: list[dict], timestamp: str):
    csv_path = os.path.join(RESULTS_DIR, f"scores_{timestamp}.csv")
    if summary_rows:
        with open(csv_path, "w", newline="") as f:
            writer = csv.DictWriter(f, fieldnames=summary_rows[0].keys())
            writer.writeheader()
            writer.writerows(summary_rows)
        print(f"Scores saved to {csv_path}")

    print("\n=== SUMMARY ===")
    for row in summary_rows:
        if row["category"] == "ALL":
            print(
                f"{row['system']:15s} | "
                f"faithfulness={row.get('faithfulness', 'N/A')} "
                f"answer_relevancy={row.get('answer_relevancy', 'N/A')} "
                f"context_precision={row.get('context_precision', 'N/A')} "
                f"context_recall={row.get('context_recall', 'N/A')}"
            )


def run(selected_systems=None, limit=None, skip_eval=False, dataset="petclinic"):
    os.makedirs(RESULTS_DIR, exist_ok=True)
    run_t0 = time.perf_counter()

    d = DATASETS[dataset]
    ingest_paths = get_ingest_paths(dataset)

    with open(d["ground_truth"]) as f:
        ground_truth = json.load(f)

    if limit:
        ground_truth = ground_truth[:limit]
        print(f"Running with {limit} questions (--limit {limit})")

    selected = selected_systems or ALL_SYSTEMS
    systems = {name: SYSTEM_CLASSES[name]() for name in selected}

    # construction/query effort — folded into the real run so ingest and
    # query aren't repeated in a separate pass just to time them
    effort_rows = []
    ingest_seconds_by_system = {}
    llm_calls_during_ingest_by_system = {}

    print(f"Dataset: {dataset}")
    print("Ingesting data...")
    for name, system in systems.items():
        counting_llm = CountingLLM(system.llm)
        system.llm = counting_llm

        t0 = time.perf_counter()
        system.ingest(ingest_paths[name])
        ingest_seconds = time.perf_counter() - t0
        ingest_seconds_by_system[name] = ingest_seconds
        # snapshot now — system.query() below reuses the same self.llm and
        # would otherwise inflate this count with per-query generation calls
        llm_calls_during_ingest_by_system[name] = counting_llm.calls
        print(f"  {name} ingested ({ingest_seconds:.1f}s, {counting_llm.calls} LLM calls)")
    print("Ingestion complete.")

    size = code_size(d)
    all_results = {}
    for system_name, system in systems.items():
        print(f"\nQuerying system: {system_name}")
        rows = []
        query_latencies = []
        for qa in ground_truth:
            print(f"  Q: {qa['question'][:60]}...")
            t0 = time.perf_counter()
            answer, contexts = system.query(qa["question"])
            query_latencies.append(time.perf_counter() - t0)
            rows.append({
                "question": qa["question"],
                "answer": answer,
                "contexts": contexts,
                "ground_truth": qa["ground_truth"],
                "category": qa["category"],
            })
        all_results[system_name] = rows
        effort_rows.append({
            "dataset": dataset,
            "system": system_name,
            "javaFiles": size["javaFiles"],
            "classes": size["classes"],
            "methods": size["methods"],
            "ingestSeconds": round(ingest_seconds_by_system[system_name], 2),
            "llmCallsDuringIngest": llm_calls_during_ingest_by_system[system_name],
            "meanQueryLatencySeconds": round(sum(query_latencies) / len(query_latencies), 2),
            "minQueryLatencySeconds": round(min(query_latencies), 2),
            "maxQueryLatencySeconds": round(max(query_latencies), 2),
            "questionsTimed": len(query_latencies),
        })

    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    raw_path = os.path.join(RESULTS_DIR, f"raw_{dataset}_{timestamp}.json")
    with open(raw_path, "w") as f:
        json.dump(all_results, f, indent=2)
    print(f"\nRaw results saved to {raw_path}")

    effort_path = os.path.join(RESULTS_DIR, f"construction_effort_{dataset}_{timestamp}.csv")
    with open(effort_path, "w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=effort_rows[0].keys())
        writer.writeheader()
        writer.writerows(effort_rows)
    print(f"Construction effort saved to {effort_path}")

    if skip_eval:
        total_seconds = time.perf_counter() - run_t0
        print("Skipping RAGAS evaluation (--skip-eval)")
        print(f"\nTotal wall-clock time (ingest + query, no RAGAS): {total_seconds:.1f}s")
        _save_total_runtime(dataset, timestamp, total_seconds, includes_ragas=False)
        return

    print("\nRunning RAGAS evaluation...")
    summary_rows = []
    for system_name, rows in all_results.items():
        print_and_collect_scores(system_name, rows, summary_rows)

    save_and_print_summary(summary_rows, timestamp)
    total_seconds = time.perf_counter() - run_t0
    print(f"\nTotal wall-clock time (ingest + query + RAGAS): {total_seconds:.1f}s")
    _save_total_runtime(dataset, timestamp, total_seconds, includes_ragas=True)


def _save_total_runtime(dataset: str, timestamp: str, total_seconds: float, includes_ragas: bool):
    path = os.path.join(RESULTS_DIR, f"total_runtime_{dataset}_{timestamp}.txt")
    with open(path, "w") as f:
        f.write(f"dataset={dataset}\n")
        f.write(f"totalWallClockSeconds={total_seconds:.1f}\n")
        f.write(f"includesRagasEvaluation={includes_ragas}\n")
    print(f"Total runtime saved to {path}")


def run_eval_only(raw_path: str):
    with open(raw_path) as f:
        all_results = json.load(f)

    os.makedirs(RESULTS_DIR, exist_ok=True)
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    summary_rows = []

    print(f"Running RAGAS evaluation on {raw_path}...")
    for system_name, rows in all_results.items():
        print_and_collect_scores(system_name, rows, summary_rows)

    save_and_print_summary(summary_rows, timestamp)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--systems", nargs="+", choices=ALL_SYSTEMS,
                        help="Which systems to run (default: all)")
    parser.add_argument("--limit", type=int,
                        help="Only run first N questions")
    parser.add_argument("--skip-eval", action="store_true",
                        help="Skip RAGAS evaluation, only collect answers")
    parser.add_argument("--from-raw", metavar="PATH",
                        help="Skip querying, run RAGAS eval on existing raw JSON")
    parser.add_argument("--dataset", choices=list(DATASETS.keys()), default="petclinic",
                        help="Which dataset to evaluate (default: petclinic)")
    args = parser.parse_args()

    if args.from_raw:
        run_eval_only(args.from_raw)
    else:
        run(selected_systems=args.systems, limit=args.limit, skip_eval=args.skip_eval,
            dataset=args.dataset)
