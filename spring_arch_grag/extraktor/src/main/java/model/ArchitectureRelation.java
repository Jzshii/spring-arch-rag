package model;

import java.util.Objects;

public class ArchitectureRelation {

    private final String source;
    private final String target;
    private final RelationType relationType;

    public ArchitectureRelation(String source, String target, RelationType relationType) {
        this.source = source;
        this.target = target;
        this.relationType = relationType;
    }

    public String getSource() {
        return source;
    }

    public String getTarget() {
        return target;
    }

    public RelationType getRelationType() {
        return relationType;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ArchitectureRelation that)) return false;
        return Objects.equals(source, that.source)
                && Objects.equals(target, that.target)
                && relationType == that.relationType;
    }

    @Override
    public int hashCode() {
        return Objects.hash(source, target, relationType);
    }

    @Override
    public String toString() {
        return source + " --" + relationType + "--> " + target;
    }
}