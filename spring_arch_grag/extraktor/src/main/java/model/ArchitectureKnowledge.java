package model;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public class ArchitectureKnowledge {

    private final Set<ArchitectureRelation> relations = new LinkedHashSet<>();

    public void addRelation(ArchitectureRelation relation) {
        relations.add(relation);
    }

    public Set<ArchitectureRelation> getRelations() {
        return Collections.unmodifiableSet(relations);
    }

    public String toReadableText() {
        StringBuilder sb = new StringBuilder();
        for (ArchitectureRelation r : relations) {
            sb.append(describe(r)).append("\n");
        }
        return sb.toString().trim();
    }

    private String describe(ArchitectureRelation r) {
        String s = r.getSource(), t = r.getTarget();
        return switch (r.getRelationType()) {
            case HANDLES          -> s + " handles " + t + " entities.";
            case MANAGES          -> s + " manages " + t + " entities.";
            case MANAGES_ENTITY   -> s + " manages " + t + " entities.";
            case INJECTS          -> s + " injects " + t + ".";
            case USES             -> s + " uses " + t + ".";
            case EXTENDS          -> s + " extends " + t + ".";
            case IMPLEMENTS       -> s + " implements " + t + ".";
            case MANY_TO_ONE      -> s + " has a many-to-one relationship with " + t + ".";
            case ONE_TO_MANY      -> s + " has a one-to-many relationship with " + t + ".";
            case MANY_TO_MANY     -> s + " has a many-to-many relationship with " + t + ".";
            case ONE_TO_ONE       -> s + " has a one-to-one relationship with " + t + ".";
            case RETURNS          -> s + " returns " + t + ".";
            case ACCEPTS_PARAMETER -> s + " accepts " + t + " as a parameter.";
            case EXPOSES_ENDPOINT -> s + " exposes endpoint " + t + ".";
            case CREATES_BEAN     -> s + " creates bean " + t + ".";
            case BELONGS_TO_LAYER -> s + " belongs to " + t + ".";
            case DEPENDS_ON_LAYER -> s + " depends on " + t + ".";
            case HAS_SEMANTIC     -> s + " has semantic behaviour: " + t + ".";
            case CALLS            -> s + " calls " + t + ".";
            case CALLS_SERVICE         -> s + " calls remote service " + t + ".";
            case USES_CIRCUIT_BREAKER  -> s + " uses circuit breaker " + t + ".";
        };
    }

    @Override
    public String toString() {
        return "ArchitectureKnowledge{relations=" + relations + "}";
    }
}
