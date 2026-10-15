"""
Graph ladder (analysis_plan.md, Schritt 3): four variants on the same
graph JSON, differing only by node- and relation-type filters applied at
ingest. Retrieval pipeline is otherwise identical to ArchGraphRAG (PPR,
path block, community block, same weights) but WITHOUT the local/global
query-type routing — every question is treated as "global" (full edge
set for that tier, community context always included). This is why G4
is not identical to the existing arch_graph system, even though G4's
node/relation sets match it.
"""

import json
import networkx as nx

from .plain_graph_rag import PlainGraphRAG, RELATION_WEIGHTS
from .arch_graph_rag import _ARCH_RELATION_WEIGHTS, _arch_node_text

CLASS_ROLE_TYPES = {
    "CONTROLLER", "SERVICE", "REPOSITORY", "ENTITY", "COMPONENT",
    "SERVICE_CLIENT", "CONFIGURATION", "EXCEPTION_HANDLER",
    "EVENT_LISTENER", "SCHEDULER", "SECURITY_COMPONENT", "UNKNOWN",
}
LAYER_TYPES = {
    "CONTROLLER_LAYER", "SERVICE_LAYER", "REPOSITORY_LAYER",
    "ENTITY_LAYER", "CONFIGURATION_LAYER", "COMPONENT_LAYER",
}

G1_RELATIONS = {"EXTENDS", "IMPLEMENTS", "USES", "RETURNS", "ACCEPTS_PARAMETER"}
G2_RELATIONS = G1_RELATIONS | {
    "INJECTS", "ONE_TO_ONE", "ONE_TO_MANY", "MANY_TO_ONE", "MANY_TO_MANY",
    "EXPOSES_ENDPOINT", "CREATES_BEAN", "HAS_SEMANTIC",
    "USES_CIRCUIT_BREAKER", "CALLS_SERVICE",
}
G3_RELATIONS = G2_RELATIONS | {"BELONGS_TO_LAYER", "DEPENDS_ON_LAYER", "MANAGES_ENTITY"}
G4_RELATIONS = G3_RELATIONS | {"CALLS"}

G1_NODE_TYPES = set(CLASS_ROLE_TYPES)
G2_NODE_TYPES = G1_NODE_TYPES | {"ENDPOINT", "SEMANTIC_MARKER"}
G3_NODE_TYPES = G2_NODE_TYPES | LAYER_TYPES
G4_NODE_TYPES = G3_NODE_TYPES | {"METHOD"}

_WEIGHTS = {**RELATION_WEIGHTS, **_ARCH_RELATION_WEIGHTS}


class _LadderBase(PlainGraphRAG):
    NODE_TYPES: set[str] = set()
    RELATION_TYPES: set[str] = set()

    def __init__(self):
        super().__init__()
        self._node_text_fn = _arch_node_text
        self._ppr_seed_types = None  # all embedded (= tier-allowed) types are valid seeds
        self.stats: dict = {}

    def ingest(self, data_path: str) -> None:
        with open(data_path, "r") as f:
            data = json.load(f)

        all_nodes = {n["id"]: n for n in data.get("nodes", [])}
        all_relations = data.get("relations", [])

        # node filter: tier-allowed types only
        kept_node_ids = {
            nid for nid, n in all_nodes.items()
            if n.get("type", "") in self.NODE_TYPES
        }
        for nid, n in all_nodes.items():
            if nid in kept_node_ids:
                self.graph.add_node(nid, **n)

        # relation filter: tier-allowed relation type, AND neither endpoint is
        # a *declared* node whose type got filtered out by this tier. An
        # endpoint not declared in nodes[] at all (phantom/placeholder target,
        # e.g. an external framework class) is not subject to node-type
        # filtering — it is let through unconditionally, exactly as
        # ArchGraphRAG does (nx.add_edge auto-creates a bare node for it).
        type_matched = [r for r in all_relations if r["relationType"] in self.RELATION_TYPES]

        def _endpoint_ok(end_id: str) -> bool:
            return end_id in kept_node_ids or end_id not in all_nodes

        kept_relations = [
            r for r in type_matched
            if _endpoint_ok(r["source"]) and _endpoint_ok(r["target"])
        ]
        removed_by_node_filter = len(type_matched) - len(kept_relations)

        for rel in kept_relations:
            self.graph.add_edge(
                rel["source"], rel["target"],
                relationType=rel["relationType"],
                weight=_WEIGHTS.get(rel["relationType"], 1.0),
            )

        # placeholder nodes: edge endpoints outside the *original* declared
        # node set entirely (framework/external types) — independent of
        # tier filtering, same definition used for the main graph stats.
        placeholder_ids = {
            end for r in kept_relations for end in (r["source"], r["target"])
            if end not in all_nodes
        }

        # components: same convention as tab:graphstatistik — full graph
        # including placeholder targets as bare nodes.
        full_graph = nx.Graph()
        full_graph.add_nodes_from(kept_node_ids)
        for r in kept_relations:
            full_graph.add_edge(r["source"], r["target"])
        n_components = nx.number_connected_components(full_graph)

        self.stats = {
            "n_nodes": len(kept_node_ids),
            "n_edges": len(kept_relations),
            "n_components": n_components,
            "n_edges_removed_by_node_filter": removed_by_node_filter,
            "n_placeholder_nodes": len(placeholder_ids),
        }

        self._detect_communities()
        self._upgrade_community_summaries()
        self._embed_all_nodes({nid: all_nodes[nid] for nid in kept_node_ids})

    def query(self, question: str) -> tuple[str, list[str]]:
        # no local/global routing — always the "global" path: full tier
        # relation set, community context always included.
        return self._query_graph_ppr(question, edge_filter=None, include_community=True)


class G1RAG(_LadderBase):
    """Class nodes only; EXTENDS, IMPLEMENTS, USES, RETURNS, ACCEPTS_PARAMETER."""
    NODE_TYPES = G1_NODE_TYPES
    RELATION_TYPES = G1_RELATIONS


class G2RAG(_LadderBase):
    """G1 + INJECTS, JPA relations, EXPOSES_ENDPOINT, CREATES_BEAN, HAS_SEMANTIC,
    USES_CIRCUIT_BREAKER, CALLS_SERVICE + endpoint/semantic nodes."""
    NODE_TYPES = G2_NODE_TYPES
    RELATION_TYPES = G2_RELATIONS


class G3RAG(_LadderBase):
    """G2 + layer nodes, BELONGS_TO_LAYER, DEPENDS_ON_LAYER, MANAGES_ENTITY."""
    NODE_TYPES = G3_NODE_TYPES
    RELATION_TYPES = G3_RELATIONS


class G4RAG(_LadderBase):
    """G3 + method nodes, CALLS. Not identical to arch_graph (no local/global routing)."""
    NODE_TYPES = G4_NODE_TYPES
    RELATION_TYPES = G4_RELATIONS
