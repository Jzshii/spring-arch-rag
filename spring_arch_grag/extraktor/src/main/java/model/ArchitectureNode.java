package model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public class ArchitectureNode {

    private final String id;
    private final NodeType type;
    private final Map<String, String> metadata;

    public ArchitectureNode(String id, NodeType type) {
        this(id, type, null);
    }

    public ArchitectureNode(String id, NodeType type, Map<String, String> metadata) {
        this.id = id;
        this.type = type;
        this.metadata = (metadata != null && !metadata.isEmpty())
                ? Collections.unmodifiableMap(new LinkedHashMap<>(metadata))
                : null;
    }

    public String getId() {
        return id;
    }

    public NodeType getType() {
        return type;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ArchitectureNode that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return id + " [" + type + "]";
    }
}
