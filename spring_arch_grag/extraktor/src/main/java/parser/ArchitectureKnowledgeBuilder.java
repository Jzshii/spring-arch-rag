package parser;

import model.*;

import java.util.*;

public class ArchitectureKnowledgeBuilder {

    public ArchitectureKnowledge build(ProjectMetadata project) {
        ArchitectureKnowledge knowledge = new ArchitectureKnowledge();
        Set<String> projectClasses = project.getProjectClassNames();

        
        Map<String, Set<String>> coveredTargets = new HashMap<>();

        for (ClassMetadata cls : project.getClasses()) {
            String source = cls.getClassName();
            extractInheritance(knowledge, coveredTargets, cls, source, projectClasses);
            extractJpaRelations(knowledge, coveredTargets, cls, source, projectClasses);
            extractRoleConventions(knowledge, coveredTargets, cls, source, projectClasses);
            extractConstructorInjections(knowledge, coveredTargets, cls, source, projectClasses);
            extractUsages(knowledge, coveredTargets, cls, source, projectClasses);
        }

        return knowledge;
    }

    private void extractInheritance(
            ArchitectureKnowledge knowledge,
            Map<String, Set<String>> covered,
            ClassMetadata cls,
            String source,
            Set<String> projectClasses) {

        for (String parent : cls.getExtendedTypes()) {
            String target = TypeUtils.simpleName(parent);
            if (projectClasses.contains(target)) {
                knowledge.addRelation(new ArchitectureRelation(source, target, RelationType.EXTENDS));
                markCovered(covered, source, target);
            }
        }
        for (String iface : cls.getImplementedTypes()) {
            String target = TypeUtils.simpleName(iface);
            if (projectClasses.contains(target)) {
                knowledge.addRelation(new ArchitectureRelation(source, target, RelationType.IMPLEMENTS));
                markCovered(covered, source, target);
            }
        }
    }

    private void extractJpaRelations(
            ArchitectureKnowledge knowledge,
            Map<String, Set<String>> covered,
            ClassMetadata cls,
            String source,
            Set<String> projectClasses) {

        for (FieldMetadata field : cls.getFields()) {
            RelationType jpaType = jpaRelationType(field.getAnnotations());
            if (jpaType == null) continue;
            String target = TypeUtils.resolveProjectType(field.getFieldType(), projectClasses);
            if (target != null) {
                knowledge.addRelation(new ArchitectureRelation(source, target, jpaType));
                markCovered(covered, source, target);
            }
        }
    }

    private void extractRoleConventions(
            ArchitectureKnowledge knowledge,
            Map<String, Set<String>> covered,
            ClassMetadata cls,
            String source,
            Set<String> projectClasses) {

        if (cls.getRole() == ArchitecturalRole.REPOSITORY) {
            String entity = TypeUtils.stripSuffix(source, "Repository");
            if (entity != null && projectClasses.contains(entity)) {
                knowledge.addRelation(new ArchitectureRelation(source, entity, RelationType.MANAGES));
                markCovered(covered, source, entity);
            }
        }
        if (cls.getRole() == ArchitecturalRole.CONTROLLER) {
            String domain = TypeUtils.stripSuffix(source, "Controller");
            if (domain != null && projectClasses.contains(domain)) {
                knowledge.addRelation(new ArchitectureRelation(source, domain, RelationType.HANDLES));
                markCovered(covered, source, domain);
            }
        }
    }

    private void extractConstructorInjections(
            ArchitectureKnowledge knowledge,
            Map<String, Set<String>> covered,
            ClassMetadata cls,
            String source,
            Set<String> projectClasses) {

        for (ConstructorMetadata ctor : cls.getConstructors()) {
            for (ParameterMetadata param : ctor.getParameters()) {
                String target = TypeUtils.simpleName(param.getParameterType());
                if (projectClasses.contains(target) && !target.equals(source)) {
                    knowledge.addRelation(new ArchitectureRelation(source, target, RelationType.INJECTS));
                    markCovered(covered, source, target);
                }
            }
        }
    }

    private void extractUsages(
            ArchitectureKnowledge knowledge,
            Map<String, Set<String>> covered,
            ClassMetadata cls,
            String source,
            Set<String> projectClasses) {

        for (String dep : cls.getDependencies()) {
            String target = TypeUtils.simpleName(dep);
            if (projectClasses.contains(target)
                    && !target.equals(source)
                    && !isCovered(covered, source, target)) {
                knowledge.addRelation(new ArchitectureRelation(source, target, RelationType.USES));
            }
        }
    }

    private RelationType jpaRelationType(List<String> annotations) {
        for (String ann : annotations) {
            switch (ann) {
                case "ManyToOne"  -> { return RelationType.MANY_TO_ONE; }
                case "OneToMany"  -> { return RelationType.ONE_TO_MANY; }
                case "ManyToMany" -> { return RelationType.MANY_TO_MANY; }
                case "OneToOne"   -> { return RelationType.ONE_TO_ONE; }
            }
        }
        return null;
    }

    private void markCovered(Map<String, Set<String>> covered, String source, String target) {
        covered.computeIfAbsent(source, k -> new HashSet<>()).add(target);
    }

    private boolean isCovered(Map<String, Set<String>> covered, String source, String target) {
        Set<String> targets = covered.get(source);
        return targets != null && targets.contains(target);
    }
}
