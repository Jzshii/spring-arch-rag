from .plain_graph_rag import PlainGraphRAG, MAX_NODE_TEXT, BASIC_RELATIONS, RELATION_WEIGHTS
import json
import re
import networkx as nx

COLLECTION = "arch_graph"

# Local queries: single-entity property/annotation/role lookups.
# Route to PCST over BASIC_RELATIONS only + skip community context.
_LOCAL_RE = re.compile(
    r"\b(what annotations?|what fields?|what methods?|what (is the )?role|"
    r"what (is the )?package|what (bean|endpoint|interface)|"
    r"what does .+? (provide|configure|expose|create|return|implement)|"
    r"what role does|what (DAOs?|repositories?)|"
    r"which annotations?|which fields?|which methods?)\b",
    re.IGNORECASE,
)

# Global queries: cross-layer flows, dependencies, architectural behaviour.
_GLOBAL_RE = re.compile(
    r"\b(how does|how (is|are)|trace|flow|end.to.end|describe|"
    r"which layers?|what happens|dependency chain|"
    r"when .+? (logs?|creates?|sends?|requests?|processes?))\b",
    re.IGNORECASE,
)

# Arch-specific relation weights — extends RELATION_WEIGHTS from plain_graph.
# Structural/DI relations keep high weights; arch metadata mid; layer hubs penalised.
_ARCH_RELATION_WEIGHTS: dict[str, float] = {
    **RELATION_WEIGHTS,
    "EXPOSES_ENDPOINT": 2.0,    # controller → endpoint: strong architectural signal
    "MANAGES_ENTITY": 2.0,      # repository manages entity
    "CALLS_SERVICE": 2.0,       # cross-service call in microservices
    "CREATES_BEAN": 1.5,        # configuration → bean
    "CALLS": 1.0,               # method invocation
    "USES_CIRCUIT_BREAKER": 1.0,
    "HAS_SEMANTIC": 0.5,        # semantic marker link, low structural value
    "DEPENDS_ON_LAYER": 0.2,    # layer dependency, noisy
    "BELONGS_TO_LAYER": 0.1,    # very high cost: layer hubs distort PCST
}


def _classify_query(question: str) -> str:
    """Return 'local' for single-entity lookups, 'global' for cross-layer flows."""
    if _GLOBAL_RE.search(question):
        return "global"
    if _LOCAL_RE.search(question):
        return "local"
    return "global"


class ArchGraphRAG(PlainGraphRAG):
    """Full architectural graph — all relation types + enriched node text + PCST + LLM summaries.

    Inherits PCST retrieval, LLM community summaries, path serialization, and HyDE
    from PlainGraphRAG. Differs from PlainGraphRAG only in:
    - All relation types (not just BASIC_RELATIONS)
    - Enriched node text (_arch_node_text vs _node_text)
    - All node types embedded (not just class-level architectural types)
    - Query-type routing (local/global classifier)
    """

    def __init__(self):
        super().__init__()
        self._node_text_fn = _arch_node_text
        self._ppr_seed_types = None  # all embedded types are valid PPR seeds

    def ingest(self, data_path: str) -> None:
        with open(data_path, "r") as f:
            data = json.load(f)

        nodes = {n["id"]: n for n in data.get("nodes", [])}

        for node_id, node in nodes.items():
            self.graph.add_node(node_id, **node)

        for rel in data.get("relations", []):
            self.graph.add_edge(
                rel["source"], rel["target"],
                relationType=rel["relationType"],
                weight=_ARCH_RELATION_WEIGHTS.get(rel["relationType"], 1.0),
            )

        self._detect_communities()
        self._upgrade_community_summaries()
        self._embed_all_nodes(nodes)

    def query(self, question: str) -> tuple[str, list[str]]:
        if _classify_query(question) == "local":
            return self._query_graph_ppr(
                question,
                edge_filter=BASIC_RELATIONS,
                include_community=False,
            )
        return self._query_graph_ppr(question)


def _arch_node_text(node_id: str, nodes: dict, graph: nx.MultiDiGraph) -> str:
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

    if node_type == "ENDPOINT":
        lines.append(f"  HTTP: {meta.get('httpMethod', '')} {meta.get('path', '')}")
        lines.append(f"  Controller: {meta.get('controller', '')}, Handler: {meta.get('method', '')}")

    if node_type == "METHOD" and meta.get("toolDescription"):
        desc = meta["toolDescription"].strip().strip('"""').strip()
        lines.append(f"  Tool description: {desc}")

    if node_type == "SEMANTIC_MARKER":
        semantic_desc = {
            "TRANSACTION_BOUNDARY": "marks transactional boundary (@Transactional)",
            "CACHE": "marks cache operation (@Cacheable/@CachePut/@CacheEvict)",
            "ASYNC_EXECUTION": "marks async execution (@Async)",
            "SCHEDULED_TASK": "marks scheduled task (@Scheduled)",
            "AI_TOOL": "marks Spring AI tool method (@Tool) callable by LLM agents",
            "SERVICE_DISCOVERY": "enables service discovery via Eureka/Spring Cloud (@EnableDiscoveryClient)",
            "EUREKA_SERVER": "acts as Eureka service registry server (@EnableEurekaServer)",
            "CONFIG_SERVER": "acts as Spring Cloud Config Server (@EnableConfigServer)",
            "ADMIN_SERVER": "acts as Spring Boot Admin Server (@EnableAdminServer)",
            "CACHING_ENABLED": "enables Spring caching infrastructure (@EnableCaching)",
        }
        lines.append(f"  Meaning: {semantic_desc.get(node_id, node_id)}")

    for src, tgt, data in graph.out_edges(node_id, data=True):
        lines.append(f"  {src} --{data.get('relationType')}--> {tgt}")
    for src, tgt, data in graph.in_edges(node_id, data=True):
        lines.append(f"  {src} --{data.get('relationType')}--> {tgt}")

    return "\n".join(lines)
