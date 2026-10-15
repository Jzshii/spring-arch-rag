
import json
import os
import time


class TimedLLM:
    """Wraps a system's real LLM; records call count and per-call wall-clock
    duration. Since every system's query() calls llm.invoke() exactly once,
    at the very end (after retrieval/PPR/reranking is complete), the last
    recorded duration after a query() call is that question's generation
    time — retrieval time = total query wall-clock minus that duration. No
    system code is modified to get this split."""

    def __init__(self, real_llm):
        self._real_llm = real_llm
        self.calls = 0
        self.durations: list[float] = []

    def invoke(self, *args, **kwargs):
        t0 = time.perf_counter()
        result = self._real_llm.invoke(*args, **kwargs)
        self.durations.append(time.perf_counter() - t0)
        self.calls += 1
        return result

    def __getattr__(self, name):
        return getattr(self._real_llm, name)


class CountingLLM:
    """Wraps a system's real LLM to count .invoke() calls without changing behavior."""

    def __init__(self, real_llm):
        self._real_llm = real_llm
        self.calls = 0

    def invoke(self, *args, **kwargs):
        self.calls += 1
        return self._real_llm.invoke(*args, **kwargs)

    def __getattr__(self, name):
        return getattr(self._real_llm, name)


def code_size(dataset_config: dict) -> dict:
    """dataset_config is one DATASETS[...] entry (needs 'ast_json' and 'java_src')."""
    with open(dataset_config["ast_json"]) as f:
        meta = json.load(f)
    classes = meta.get("classes", [])
    n_methods = sum(len(c.get("methods", [])) for c in classes)
    java_files = 0
    for root, _, files in os.walk(dataset_config["java_src"], followlinks=True):
        java_files += sum(1 for f in files if f.endswith(".java"))
    return {"javaFiles": java_files, "classes": len(classes), "methods": n_methods}
