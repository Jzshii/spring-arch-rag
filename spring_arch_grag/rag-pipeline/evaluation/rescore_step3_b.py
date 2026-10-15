
import argparse
import csv
import json
import os

import pandas as pd
from ragas import evaluate, EvaluationDataset
from ragas.dataset_schema import SingleTurnSample

from ragas import RunConfig
from evaluation.harness import RAGAS_LLM, RAGAS_EMBEDDINGS
from evaluation.variant_harness import ALL_METRIC_OBJS, RESULTS_ROOT

METRICS = ["context_precision", "context_recall"]
SYSTEMS = ["graph_ladder_g1", "graph_ladder_g2", "graph_ladder_g3", "graph_ladder_g4"]
DATASETS = ["petclinic", "microservices", "mall"]

# Increased timeout vs. the main run's RAGAS_RUN_CONFIG (180s) — Durchlauf A
# showed sporadic multi-hundred-second items under the 180s/2-retry config.
RUN_CONFIG = RunConfig(timeout=400, max_workers=1, max_retries=2)


def rescore_one(system_name: str, dataset: str, dir_a: str, dir_b: str):
    raw_path = os.path.join(dir_a, f"raw_{system_name}_{dataset}.json")
    with open(raw_path) as f:
        rows = json.load(f)

    samples = [
        SingleTurnSample(
            user_input=r["question"],
            response=r["answer"],
            retrieved_contexts=r["contexts"],
            reference=r["ground_truth"],
        )
        for r in rows
    ]
    metric_objs = [ALL_METRIC_OBJS[m] for m in METRICS]
    result = evaluate(EvaluationDataset(samples=samples), metrics=metric_objs,
                       llm=RAGAS_LLM, embeddings=RAGAS_EMBEDDINGS, run_config=RUN_CONFIG)
    score_df = result.to_pandas()

    per_question = []
    for i, r in enumerate(rows):
        rec = {"dataset": dataset, "system": system_name, "question": r["question"]}
        for m in METRICS:
            rec[m] = score_df.iloc[i][m] if m in score_df.columns else float("nan")
        per_question.append(rec)

    scores_path = os.path.join(dir_b, f"scores_{system_name}_{dataset}.csv")
    with open(scores_path, "w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=per_question[0].keys())
        writer.writeheader()
        writer.writerows(per_question)
    print(f"[{system_name}/{dataset}] Durchlauf B scores -> {scores_path}")
    return per_question


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--label-a", required=True)
    parser.add_argument("--label-b", required=True)
    args = parser.parse_args()

    dir_a = os.path.join(RESULTS_ROOT, f"variants_{args.label_a}")
    dir_b = os.path.join(RESULTS_ROOT, f"variants_{args.label_b}")
    os.makedirs(dir_b, exist_ok=True)

    all_results = []
    for dataset in DATASETS:
        for system_name in SYSTEMS:
            scores_path = os.path.join(dir_b, f"scores_{system_name}_{dataset}.csv")
            if os.path.exists(scores_path):
                print(f"[{system_name}/{dataset}] Durchlauf B already complete, skipping")
                all_results.extend(pd.read_csv(scores_path).to_dict("records"))
                continue
            res = rescore_one(system_name, dataset, dir_a, dir_b)
            all_results.extend(res)

    combined_path = os.path.join(dir_b, "per_question_scores_step3_B.csv")
    pd.DataFrame(all_results).to_csv(combined_path, index=False)
    print(f"\nCombined Durchlauf-B scores -> {combined_path}")


if __name__ == "__main__":
    main()
