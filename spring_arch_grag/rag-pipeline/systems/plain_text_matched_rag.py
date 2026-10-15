import os
import chromadb
from langchain_text_splitters import RecursiveCharacterTextSplitter
from langchain_chroma import Chroma
from .plain_text_rag import PlainTextRAG

EMBED_BATCH = 250


class PlainTextMatchedRAG(PlainTextRAG):
    """PlainTextRAG with reduced fragment size (700/70 instead of 1500/150),
    to match the graph systems' context volume more closely. Still 5
    fragments retrieved; same embedding model, vectorstore, prompt template
    (inherited, unchanged) — only chunk_size/chunk_overlap differ."""

    def ingest(self, data_path: str) -> None:
        java_files = [
            os.path.join(root, f)
            for root, _, files in os.walk(data_path, followlinks=True)
            for f in files
            if f.endswith(".java")
        ]
        splitter = RecursiveCharacterTextSplitter(
            separators=["\nclass ", "\n\n", "\n"],
            chunk_size=1000,
            chunk_overlap=100,
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
