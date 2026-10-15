import json
import uuid
import chromadb
from .ast_rag import AstRAG, _class_chunk, _build_prompt, MAX_CHUNK, EMBED_BATCH
from langchain_chroma import Chroma
from langchain_core.documents import Document

COLLECTION = "ast_body_rag"
BODY_TRUNCATE = 800


class AstBodyRAG(AstRAG):
    """AST-RAG extended with method bodies in chunks (state-of-the-art enriched chunking)."""

    def __init__(self):
        super().__init__()
        self._collection_name = f"{COLLECTION}_{uuid.uuid4().hex[:8]}"

    def ingest(self, data_path: str) -> None:
        with open(data_path, "r") as f:
            data = json.load(f)

        docs = []
        for cls in data.get("classes", []):
            docs.append(Document(
                page_content=_class_chunk(cls)[:MAX_CHUNK],
                metadata={"className": cls.get("className", ""), "granularity": "class"}
            ))
            for method in cls.get("methods", []):
                docs.append(Document(
                    page_content=_method_chunk_with_body(cls, method),
                    metadata={"className": cls.get("className", ""), "granularity": "method"}
                ))

        client = chromadb.EphemeralClient()
        self.vectorstore = Chroma.from_documents(
            docs[:EMBED_BATCH], self.embeddings,
            collection_name=self._collection_name,
            client=client,
        )
        for i in range(EMBED_BATCH, len(docs), EMBED_BATCH):
            self.vectorstore.add_documents(docs[i:i + EMBED_BATCH])


def _method_chunk_with_body(cls: dict, method: dict) -> str:
    annotations = ", ".join(f"@{a}" for a in method.get("annotations", []))
    params = ", ".join(
        f"{p.get('parameterType')} {p.get('parameterName')}"
        for p in method.get("parameters", [])
    )
    body = (method.get("methodBody") or "").strip()
    if len(body) > BODY_TRUNCATE:
        body = body[:BODY_TRUNCATE] + "\n// ..."
    return (
        f"Method: {cls.get('className')}.{method.get('methodName')} [{cls.get('role', 'UNKNOWN')}]\n"
        f"Signature: {method.get('signature', '')}\n"
        f"Annotations: {annotations or 'none'}\n"
        f"Parameters: {params or 'none'}\n"
        f"Returns: {method.get('returnType', 'void')}\n"
        f"Body:\n{body or 'none'}"
    )
