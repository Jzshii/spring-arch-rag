package model;

import java.util.Collections;
import java.util.List;

public class ArchitectureGraph {

    private final List<ArchitectureNode> nodes;
    private final List<ArchitectureRelation> relations;

    public ArchitectureGraph(List<ArchitectureNode> nodes, List<ArchitectureRelation> relations) {
        this.nodes = nodes;
        this.relations = relations;
    }

    public List<ArchitectureNode> getNodes() {
        return Collections.unmodifiableList(nodes);
    }

    public List<ArchitectureRelation> getRelations() {
        return Collections.unmodifiableList(relations);
    }

    @Override
    public String toString() {
        return "ArchitectureGraph{nodes=" + nodes.size() + ", relations=" + relations.size() + "}";
    }
}
