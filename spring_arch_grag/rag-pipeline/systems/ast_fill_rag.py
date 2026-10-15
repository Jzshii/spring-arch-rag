from .ast_rag import AstRAG, _build_prompt

FETCH_K = 20
WANT = 5


def _query_fill(vectorstore, llm, question: str) -> tuple[str, list[str]]:
    """Over-fetch before per-class dedup, filling back to WANT fragments
    whenever at least WANT distinct classes exist among the top FETCH_K."""
    assert vectorstore, "Call ingest() first"
    results = vectorstore.similarity_search(question, k=FETCH_K)
    seen = set()
    contexts = []
    for doc in results:
        cls_name = doc.metadata.get("className", "")
        if cls_name not in seen:
            seen.add(cls_name)
            contexts.append(doc.page_content)
        if len(contexts) >= WANT:
            break
    prompt = _build_prompt(question, contexts)
    answer = llm.invoke(prompt)
    return answer, contexts


class AstFillRAG(AstRAG):
    """AST RAG with over-fetch (k=20) before per-class dedup, so 5 fragments
    are delivered whenever >=5 distinct classes exist among the top 20.
    Fixes the under-5 dedup gap quantified in the context-length analysis."""

    def query(self, question: str) -> tuple[str, list[str]]:
        return _query_fill(self.vectorstore, self.llm, question)
