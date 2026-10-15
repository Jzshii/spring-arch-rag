import os
import uuid
import chromadb
from langchain_text_splitters import RecursiveCharacterTextSplitter
from langchain_chroma import Chroma
from langchain_ollama import OllamaLLM, OllamaEmbeddings
from .base_rag import BaseRAG

EMBED_MODEL = "mxbai-embed-large"
GEN_MODEL = "llama3.1:8b"
COLLECTION = "plain_text"
EMBED_BATCH = 250


class PlainTextRAG(BaseRAG):

    def __init__(self):
        self.embeddings = OllamaEmbeddings(model=EMBED_MODEL)
        self.llm = OllamaLLM(model=GEN_MODEL, temperature=0)
        self.vectorstore = None
        self._collection_name = f"{COLLECTION}_{uuid.uuid4().hex[:8]}"

    def ingest(self, data_path: str) -> None:
        java_files = [
            os.path.join(root, f)
            for root, _, files in os.walk(data_path, followlinks=True)
            for f in files
            if f.endswith(".java")
        ]
        splitter = RecursiveCharacterTextSplitter(
            separators=["\nclass ", "\n\n", "\n"],
            chunk_size=1500,
            chunk_overlap=150,
        )
        docs = []
        for path in java_files:
            with open(path, "r", encoding="utf-8", errors="ignore") as f:
                text = f.read()
            splits = splitter.split_text(text)
            chunks = splitter.create_documents(
                splits,
                metadatas=[{"file": os.path.basename(path), "chunk_index": i}
                           for i in range(len(splits))]
            )
            docs.extend(chunks)

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
        contexts = [doc.page_content for doc in results]
        prompt = _build_prompt(question, contexts)
        answer = self.llm.invoke(prompt)
        return answer, contexts


def _build_prompt(question: str, contexts: list[str]) -> str:
    ctx_block = "\n\n---\n\n".join(contexts)
    return (
        f"You are a Java Spring Boot expert. Answer based only on the provided context.\n\n"
        f"Context:\n{ctx_block}\n\n"
        f"Question: {question}\n\n"
        f"Answer:"
    )
