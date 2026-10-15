import json
import networkx as nx
import numpy as np
from langchain_ollama import OllamaLLM, OllamaEmbeddings
from .base_rag import BaseRAG

EMBED_MODEL = "mxbai-embed-large"
GEN_MODEL = "llama3.1:8b"
TOP_SEEDS = 3
TOP_K = 5
MAX_PATH_LEN = 5
PPR_CANDIDATES = TOP_K * 6   # nodes passed to cosine reranker after PPR
PPR_ALPHA = 0.85              # standard PageRank damping factor
MAX_NODE_TEXT = 1800          # mxbai-embed-large context ~512 tokens ≈ 2000 chars
EMBED_BATCH = 250             # Ollama /embed crashes on large batches

BASIC_RELATIONS = {
    "EXTENDS", "IMPLEMENTS", "USES", "INJECTS",
    "ONE_TO_ONE", "ONE_TO_MANY", "MANY_TO_ONE", "MANY_TO_MANY",
    "RETURNS", "ACCEPTS_PARAMETER",
}

# Higher weight = PPR flows more through this edge type / lower PCST cost.
RELATION_WEIGHTS: dict[str, float] = {
    "EXTENDS": 3.0,
    "IMPLEMENTS": 3.0,
    "INJECTS": 2.5,
    "USES": 1.5,
    "ONE_TO_ONE": 1.5,
    "ONE_TO_MANY": 1.5,
    "MANY_TO_ONE": 1.5,
    "MANY_TO_MANY": 1.5,
    "RETURNS": 0.5,
    "ACCEPTS_PARAMETER": 0.5,
}

# Class-level architectural types used as PPR seeds in plain_graph.
# arch_graph overrides to None (all embedded types become seed candidates).
_PPR_SEED_TYPES = {
    "CONTROLLER", "SERVICE", "REPOSITORY", "ENTITY",
    "CONFIGURATION", "COMPONENT", "EXCEPTION_HANDLER",
    "SERVICE_CLIENT", "EVENT_LISTENER",
}

_SKIP_TYPES = {"", "UNKNOWN"}


class PlainGraphRAG(BaseRAG):

    def __init__(self):
        self.embeddings = OllamaEmbeddings(model=EMBED_MODEL)
        self.llm = OllamaLLM(model=GEN_MODEL, temperature=0)
        self.graph = nx.MultiDiGraph()
        self._node_embeddings: dict[str, np.ndarray] = {}
        self._node_text_fn = _node_text
        self._ppr_seed_types: set | None = _PPR_SEED_TYPES
        self._allowed_relations = BASIC_RELATIONS
        self.community_map: dict[str, int] = {}
        self.community_summaries: dict[int, str] = {}

    def ingest(self, data_path: str) -> None:
        with open(data_path, "r") as f:
            data = json.load(f)

        nodes = {n["id"]: n for n in data.get("nodes", [])}
        relations = [
            r for r in data.get("relations", [])
            if r.get("relationType") in self._allowed_relations
        ]

        for node_id, node in nodes.items():
            self.graph.add_node(node_id, **node)

        for rel in relations:
            self.graph.add_edge(
                rel["source"], rel["target"],
                relationType=rel["relationType"],
                weight=RELATION_WEIGHTS.get(rel["relationType"], 1.0),
            )

        self._detect_communities()
        self._upgrade_community_summaries()
        self._embed_all_nodes(nodes)

    def _embed_all_nodes(self, nodes: dict) -> None:
        """Embed all non-layer, non-unknown nodes; store in self._node_embeddings."""
        embed_ids = [
            nid for nid, n in nodes.items()
            if not n.get("type", "").endswith("_LAYER")
            and n.get("type", "") not in _SKIP_TYPES
        ]
        texts = [self._node_text_fn(nid, nodes, self.graph)[:MAX_NODE_TEXT] for nid in embed_ids]

        all_embs: list[list[float]] = []
        for i in range(0, len(texts), EMBED_BATCH):
            all_embs.extend(self.embeddings.embed_documents(texts[i:i + EMBED_BATCH]))

        self._node_embeddings = {
            nid: np.array(emb, dtype=float)
            for nid, emb in zip(embed_ids, all_embs)
        }

    def _detect_communities(self) -> None:
        if self.graph.number_of_nodes() < 2:
            return
        undirected = self.graph.to_undirected()
        nodes_ud = dict(undirected.nodes(data=True))
        hub_nodes = [
            n for n in undirected.nodes()
            if str(n).endswith("_LAYER")
            or nodes_ud.get(n, {}).get("type", "") == "SEMANTIC_MARKER"
        ]
        undirected.remove_nodes_from(hub_nodes)
        communities = nx.community.louvain_communities(undirected, seed=42)
        nodes_data = dict(self.graph.nodes(data=True))
        for cid, members in enumerate(communities):
            for node_id in members:
                self.community_map[node_id] = cid
            self.community_summaries[cid] = _community_summary(cid, members, nodes_data, self.graph)

    def _upgrade_community_summaries(self) -> None:
        """Replace node-list summaries with LLM-generated descriptions (GraphRAG-inspired).

        Runs once at ingest. Communities with ≥3 members get a 2-3 sentence LLM
        summary instead of a raw member list, reducing retrieval noise for global queries.
        """
        from collections import defaultdict
        nodes_data = dict(self.graph.nodes(data=True))
        cid_to_members: dict[int, set] = defaultdict(set)
        for node_id, cid in self.community_map.items():
            cid_to_members[cid].add(node_id)

        for cid, members in cid_to_members.items():
            if len(members) < 3:
                continue
            member_lines = [
                f"  {n} [{nodes_data.get(n, {}).get('type', '')}]"
                for n in sorted(members)[:15]
            ]
            edge_lines = [
                f"  {src} --{data.get('relationType', '')}--> {tgt}"
                for src, tgt, data in self.graph.edges(data=True)
                if src in members and tgt in members and data.get("relationType")
            ]
            context = (
                f"Components ({len(members)} nodes):\n" + "\n".join(member_lines) +
                (f"\n  ... and {len(members) - 15} more" if len(members) > 15 else "") +
                "\nRelationships:\n" + "\n".join(edge_lines[:20]) +
                (f"\n  ... and {len(edge_lines) - 20} more" if len(edge_lines) > 20 else "")
            )
            prompt = (
                "You are a Java Spring Boot expert. In 2-3 sentences, describe what this "
                "architectural community collectively does and how its components interact. "
                "Be specific about responsibilities.\n\n"
                f"{context}\n\nSummary:"
            )
            summary = self.llm.invoke(prompt).strip()
            self.community_summaries[cid] = (
                f"Architectural Community {cid} ({len(members)} nodes):\n  {summary}"
            )

    def query(self, question: str) -> tuple[str, list[str]]:
        return self._query_graph_ppr(question)

    def _query_graph_ppr(
        self,
        question: str,
        edge_filter: set | None = None,
        include_community: bool = True,
    ) -> tuple[str, list[str]]:
        """PPR-based retrieval (HippoRAG-inspired).

        Personalized PageRank scores all nodes by proximity to seeds via random
        walk — soft distance decay vs hard hop cutoff, hub nodes naturally
        downweighted. Edge weights favour structural/DI relations over method-detail edges.
        """
        assert self._node_embeddings, "Call ingest() first"

        # stage 1: embed query; find top seeds via cosine similarity
        query_vec = np.array(self.embeddings.embed_query(question))

        seed_pool = (
            {nid: emb for nid, emb in self._node_embeddings.items()
             if self._node_type(nid) in self._ppr_seed_types}
            if self._ppr_seed_types else self._node_embeddings
        )
        seed_sims = {
            nid: float(np.dot(emb, query_vec) / (np.linalg.norm(emb) * np.linalg.norm(query_vec) + 1e-9))
            for nid, emb in seed_pool.items()
        }
        seeds = sorted(seed_sims, key=seed_sims.get, reverse=True)[:TOP_SEEDS]

        # stage 2: PPR on (optionally filtered) undirected graph
        if edge_filter is not None:
            work_graph = nx.MultiDiGraph()
            work_graph.add_nodes_from(self.graph.nodes(data=True))
            for u, v, k, d in self.graph.edges(keys=True, data=True):
                if d.get("relationType") in edge_filter:
                    work_graph.add_edge(u, v, key=k, **d)
        else:
            work_graph = self.graph

        valid_seeds = [s for s in seeds if s in work_graph]
        personalization = (
            {s: 1.0 / len(valid_seeds) for s in valid_seeds} if valid_seeds else None
        )
        ppr_scores = nx.pagerank(
            work_graph.to_undirected(),
            personalization=personalization,
            alpha=PPR_ALPHA,
            weight="weight",
        )

        # stage 3: top PPR_CANDIDATES nodes, skip noisy types, cosine rerank
        nodes_data = dict(self.graph.nodes(data=True))
        _RETRIEVAL_SKIP = {"ENDPOINT", "UNKNOWN", ""}
        ranked = [
            n for n, _ in sorted(ppr_scores.items(), key=lambda x: x[1], reverse=True)
            if not nodes_data.get(n, {}).get("type", "").endswith("_LAYER")
            and nodes_data.get(n, {}).get("type", "") not in _RETRIEVAL_SKIP
        ]
        candidates = ranked[:PPR_CANDIDATES]
        candidate_vecs = np.array([
            self._node_embeddings.get(n, np.zeros_like(query_vec)) for n in candidates
        ])
        scores = candidate_vecs @ query_vec / (
            np.linalg.norm(candidate_vecs, axis=1) * np.linalg.norm(query_vec) + 1e-9
        )
        top_indices = np.argsort(scores)[::-1][:TOP_K]
        reranked = [self._node_text_fn(candidates[i], nodes_data, self.graph) for i in top_indices]

        # stage 4: shortest-path context between seed nodes
        path_contexts = _find_paths(seeds, self.graph)

        # stage 5: community context (skip for local queries)
        community_contexts = []
        if include_community:
            community_ids = {self.community_map.get(s) for s in seeds} - {None}
            community_contexts = [
                self.community_summaries[cid] for cid in community_ids
                if "(1 nodes)" not in self.community_summaries.get(cid, "(1 nodes)")
            ]

        all_contexts = community_contexts + path_contexts + reranked
        prompt = _build_prompt(question, all_contexts)
        answer = self.llm.invoke(prompt)
        return answer, all_contexts

    def _node_type(self, node_id: str) -> str:
        data = self.graph.nodes.get(node_id, {})
        return data.get("type", "")


# ── node text ─────────────────────────────────────────────────────────────────

def _node_text(node_id: str, nodes: dict, graph: nx.MultiDiGraph) -> str:
    node = nodes.get(node_id, {})
    node_type = node.get("type", "UNKNOWN")
    meta = node.get("metadata") or {}

    lines = [f"{node_id} [{node_type}]"]

    if meta.get("packageName"):
        lines.append(f"  Package: {meta['packageName']}")
    if meta.get("annotations"):
        lines.append(f"  Annotations: {meta['annotations']}")
    if meta.get("methods"):
        lines.append(f"  Methods: {meta['methods']}")

    for src, tgt, data in graph.out_edges(node_id, data=True):
        lines.append(f"  {src} --{data.get('relationType')}--> {tgt}")
    for src, tgt, data in graph.in_edges(node_id, data=True):
        lines.append(f"  {src} --{data.get('relationType')}--> {tgt}")

    return "\n".join(lines)


# ── shortest-path serialization between seeds ─────────────────────────────────

def _find_paths(seeds: list[str], graph: nx.MultiDiGraph) -> list[str]:
    if len(seeds) < 2:
        return []
    ug = graph.to_undirected()
    path_lines = []
    for i, src in enumerate(seeds):
        for tgt in seeds[i + 1:]:
            try:
                path_nodes = nx.shortest_path(ug, src, tgt)
                if 2 <= len(path_nodes) <= MAX_PATH_LEN:
                    segments = []
                    for j in range(len(path_nodes) - 1):
                        a, b = path_nodes[j], path_nodes[j + 1]
                        edge_data = graph.get_edge_data(a, b) or graph.get_edge_data(b, a) or {}
                        rels = [v.get("relationType", "---")
                                for v in edge_data.values()] if edge_data else ["---"]
                        rel = "/".join(sorted(set(rels)))
                        segments.append(f"{a} --{rel}--> {b}")
                    path_lines.append("  " + " → ".join(segments))
            except nx.exception.NetworkXNoPath:
                pass
    if not path_lines:
        return []
    return ["Paths between seed nodes:\n" + "\n".join(path_lines)]


# ── community detection (Louvain) — fallback node-list summary ────────────────

def _community_summary(cid: int, members: set, nodes_data: dict, graph: nx.MultiDiGraph) -> str:
    member_strs = []
    for node_id in sorted(members):
        node_type = nodes_data.get(node_id, {}).get("type", "")
        member_strs.append(f"{node_id} [{node_type}]" if node_type else node_id)

    internal_rels = sorted({
        data.get("relationType", "")
        for src, tgt, data in graph.edges(data=True)
        if src in members and tgt in members
    } - {""})

    return (
        f"Architectural Community {cid} ({len(members)} nodes):\n"
        f"  Members: {', '.join(member_strs[:12])}\n"
        f"  Internal relations: {', '.join(internal_rels) or 'none'}"
    )


# ── shared helpers ────────────────────────────────────────────────────────────

def _build_prompt(question: str, contexts: list[str]) -> str:
    ctx_block = "\n\n---\n\n".join(contexts)
    return (
        f"You are a Java Spring Boot expert. Answer based only on the provided context.\n\n"
        f"Context:\n{ctx_block}\n\n"
        f"Question: {question}\n\n"
        f"Answer:"
    )
