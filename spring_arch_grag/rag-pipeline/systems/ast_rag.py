import json
import uuid
import chromadb
from langchain_chroma import Chroma
from langchain_ollama import OllamaLLM, OllamaEmbeddings
from langchain_core.documents import Document
from .base_rag import BaseRAG

EMBED_MODEL = "mxbai-embed-large"
GEN_MODEL = "llama3.1:8b"
COLLECTION = "ast_rag"
MAX_CHUNK = 1800   # mxbai-embed-large context ~512 tokens ≈ 2000 chars
EMBED_BATCH = 250  # Ollama /embed crashes on large batches (mall has 891 chunks)


class AstRAG(BaseRAG):

    def __init__(self):
        self.embeddings = OllamaEmbeddings(model=EMBED_MODEL)
        self.llm = OllamaLLM(model=GEN_MODEL, temperature=0)
        self.vectorstore = None
        self._collection_name = f"{COLLECTION}_{uuid.uuid4().hex[:8]}"

    def ingest(self, data_path: str) -> None:
        with open(data_path, "r") as f:
            data = json.load(f)

        docs = []
        for cls in data.get("classes", []):
            # class-level chunk
            docs.append(Document(
                page_content=_class_chunk(cls)[:MAX_CHUNK],
                metadata={"className": cls.get("className", ""), "granularity": "class"}
            ))
            # method-level chunks
            for method in cls.get("methods", []):
                docs.append(Document(
                    page_content=_method_chunk(cls, method)[:MAX_CHUNK],
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

    def query(self, question: str) -> tuple[str, list[str]]:
        assert self.vectorstore, "Call ingest() first"
        results = self.vectorstore.similarity_search(question, k=5)
        # deduplicate by className, keep best hit per class
        seen = set()
        contexts = []
        for doc in results:
            cls_name = doc.metadata.get("className", "")
            if cls_name not in seen:
                seen.add(cls_name)
                contexts.append(doc.page_content)
        prompt = _build_prompt(question, contexts)
        answer = self.llm.invoke(prompt)
        return answer, contexts


def _class_chunk(cls: dict) -> str:
    annotations = ", ".join(f"@{a}" for a in cls.get("annotations", []))
    fields = ", ".join(
        f"{f.get('fieldType')} {f.get('fieldName')}" for f in cls.get("fields", [])
    )
    methods = ", ".join(
        f"{m.get('methodName')}() → {m.get('returnType')}" for m in cls.get("methods", [])
    )
    deps = ", ".join(cls.get("dependencies", []))
    return (
        f"Class: {cls.get('className')} [{cls.get('role', 'UNKNOWN')}]\n"
        f"Package: {cls.get('packageName', '')}\n"
        f"Annotations: {annotations or 'none'}\n"
        f"Fields: {fields or 'none'}\n"
        f"Methods: {methods or 'none'}\n"
        f"Dependencies: {deps or 'none'}"
    )


def _method_chunk(cls: dict, method: dict) -> str:
    annotations = ", ".join(f"@{a}" for a in method.get("annotations", []))
    params = ", ".join(
        f"{p.get('parameterType')} {p.get('parameterName')}"
        for p in method.get("parameters", [])
    )
    return (
        f"Method: {cls.get('className')}.{method.get('methodName')} [{cls.get('role', 'UNKNOWN')}]\n"
        f"Signature: {method.get('signature', '')}\n"
        f"Annotations: {annotations or 'none'}\n"
        f"Parameters: {params or 'none'}\n"
        f"Returns: {method.get('returnType', 'void')}"
    )


def _build_prompt(question: str, contexts: list[str]) -> str:
    ctx_block = "\n\n---\n\n".join(contexts)
    return (
        f"You are a Java Spring Boot expert. Answer based only on the provided context.\n\n"
        f"Context:\n{ctx_block}\n\n"
        f"Question: {question}\n\n"
        f"Answer:"
    )
