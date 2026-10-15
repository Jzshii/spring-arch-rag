package model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ProjectMetadata {

    private final List<ClassMetadata> classes =
            new ArrayList<>();

    private final Set<String> projectClassNames =
            new HashSet<>();

    private ArchitectureKnowledge architectureKnowledge;

    public List<ClassMetadata> getClasses() {
        return classes;
    }

    public Set<String> getProjectClassNames() {
        return projectClassNames;
    }

    public ArchitectureKnowledge getArchitectureKnowledge() {
        return architectureKnowledge;
    }

    public void setArchitectureKnowledge(ArchitectureKnowledge architectureKnowledge) {
        this.architectureKnowledge = architectureKnowledge;
    }
}