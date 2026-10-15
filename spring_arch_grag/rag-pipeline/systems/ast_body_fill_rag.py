from .ast_body_rag import AstBodyRAG
from .ast_fill_rag import _query_fill


class AstBodyFillRAG(AstBodyRAG):
    """AST Body RAG with the same over-fetch fill as AstFillRAG."""

    def query(self, question: str) -> tuple[str, list[str]]:
        return _query_fill(self.vectorstore, self.llm, question)
