from abc import ABC, abstractmethod


class BaseRAG(ABC):

    @abstractmethod
    def ingest(self, data_path: str) -> None: ...

    @abstractmethod
    def query(self, question: str) -> tuple[str, list[str]]:
        """Returns (answer, list_of_context_chunks)."""
        ...