package parser;

import model.*;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ArchitectureGraphBuilder {

    private static final Set<String> INJECT_ANNOTATIONS = Set.of(
            "Autowired", "Resource", "Inject"
    );

    private static final Set<String> HTTP_ANNOTATIONS = Set.of(
            "GetMapping", "PostMapping", "PutMapping",
            "DeleteMapping", "PatchMapping", "RequestMapping"
    );

    private static final Set<String> SEMANTIC_ANNOTATIONS = Set.of(
            "Transactional", "Cacheable", "CachePut", "CacheEvict",
            "Async", "Scheduled"
    );

    private static final Set<String> CIRCUIT_BREAKER_TYPES = Set.of(
            "ReactiveCircuitBreakerFactory", "CircuitBreakerFactory",
            "CircuitBreaker", "CircuitBreakerRegistry",
            "Resilience4JCircuitBreakerFactory"
    );

    private static final Pattern GENERIC_INNER = Pattern.compile("<([^,>]+)>");


    private Map<String, List<ClassMetadata>> classesBySimpleName;
    private Map<ClassMetadata, String> nodeIdOf;


    public ArchitectureGraph build(ProjectMetadata project) {
        Set<String> projectClasses = project.getProjectClassNames();

        buildNodeIdentity(project);

        Map<String, ArchitectureNode> nodeMap = new LinkedHashMap<>();
        Set<ArchitectureRelation> relations = new LinkedHashSet<>();

        Map<String, NodeType> classNodeTypes = buildClassNodeTypeMap(project);

        addLayerNodes(nodeMap);

        Set<String> observedLayerPairs = new LinkedHashSet<>();

        for (ClassMetadata cls : project.getClasses()) {
            String source = nodeIdOf.get(cls);
            NodeType nodeType = toNodeType(cls.getRole());
            Map<String, String> classMeta = new LinkedHashMap<>();
            if (cls.getPackageName() != null && !cls.getPackageName().isBlank())
                classMeta.put("packageName", cls.getPackageName());
            if (!cls.getAnnotations().isEmpty())
                classMeta.put("annotations", cls.getAnnotations().stream()
                        .map(a -> "@" + a)
                        .collect(java.util.stream.Collectors.joining(", ")));
            if (!cls.getMethods().isEmpty())
                classMeta.put("methods", cls.getMethods().stream()
                        .map(model.MethodMetadata::getMethodName)
                        .collect(java.util.stream.Collectors.joining(", ")));
            nodeMap.put(source, new ArchitectureNode(source, nodeType, classMeta));

            addBelongsToLayer(relations, source, nodeType);
            addClassLevelSemantics(relations, nodeMap, cls, source);
            addInheritance(relations, cls, source);
            addJpaRelations(relations, cls, source);
            addManagesEntity(relations, cls, source, projectClasses);
            addConstructorInjections(relations, cls, source, nodeType, classNodeTypes, observedLayerPairs);
            addFieldUsages(relations, cls, source, nodeType, classNodeTypes, observedLayerPairs);
            addMethodRelations(relations, nodeMap, cls, source);
        }


        Map<String, String> endpointIndex = buildEndpointIndex(nodeMap);
        for (ClassMetadata cls : project.getClasses()) {
            addCalledServices(relations, nodeMap, cls, nodeIdOf.get(cls), endpointIndex);
        }

        addLayerDependencies(relations, observedLayerPairs);

        return new ArchitectureGraph(
                new ArrayList<>(nodeMap.values()),
                new ArrayList<>(relations)
        );
    }

    // node identity

    private void buildNodeIdentity(ProjectMetadata project) {
        classesBySimpleName = new HashMap<>();
        for (ClassMetadata cls : project.getClasses()) {
            classesBySimpleName
                    .computeIfAbsent(cls.getClassName(), k -> new ArrayList<>())
                    .add(cls);
        }

        nodeIdOf = new IdentityHashMap<>();
        for (ClassMetadata cls : project.getClasses()) {
            List<ClassMetadata> siblings = classesBySimpleName.get(cls.getClassName());
            String id = (siblings.size() > 1) ? qualifiedId(cls) : cls.getClassName();
            nodeIdOf.put(cls, id);
        }
    }

    private String qualifiedId(ClassMetadata cls) {
        String pkg = cls.getPackageName();
        return (pkg != null && !pkg.isBlank()) ? pkg + "." + cls.getClassName() : cls.getClassName();
    }


    private List<String> resolveNodeIds(String typeName) {
        if (typeName == null || typeName.isBlank()) return List.of();

        List<ClassMetadata> hit = classesBySimpleName.get(TypeUtils.simpleName(typeName));
        if (hit != null) return idsOf(hit);

        Matcher m = GENERIC_INNER.matcher(typeName);
        if (m.find()) {
            List<ClassMetadata> innerHit = classesBySimpleName.get(TypeUtils.simpleName(m.group(1).trim()));
            if (innerHit != null) return idsOf(innerHit);
        }
        return List.of();
    }

    private List<String> idsOf(List<ClassMetadata> classes) {
        List<String> ids = new ArrayList<>(classes.size());
        for (ClassMetadata c : classes) ids.add(nodeIdOf.get(c));
        return ids;
    }


    private void addLayerNodes(Map<String, ArchitectureNode> nodeMap) {
        for (NodeType layerType : List.of(
                NodeType.CONTROLLER_LAYER,
                NodeType.SERVICE_LAYER,
                NodeType.REPOSITORY_LAYER,
                NodeType.ENTITY_LAYER,
                NodeType.CONFIGURATION_LAYER,
                NodeType.COMPONENT_LAYER)) {
            nodeMap.put(layerType.name(), new ArchitectureNode(layerType.name(), layerType));
        }
    }

    private Map<String, NodeType> buildClassNodeTypeMap(ProjectMetadata project) {
        Map<String, NodeType> map = new HashMap<>();
        for (ClassMetadata cls : project.getClasses()) {
            map.put(nodeIdOf.get(cls), toNodeType(cls.getRole()));
        }
        return map;
    }

    private void addSemanticNode(Map<String, ArchitectureNode> nodeMap, String semanticId) {
        nodeMap.putIfAbsent(semanticId, new ArchitectureNode(semanticId, NodeType.SEMANTIC_MARKER));
    }

    private void ensureMethodNode(Map<String, ArchitectureNode> nodeMap, String methodId) {
        nodeMap.putIfAbsent(methodId, new ArchitectureNode(methodId, NodeType.METHOD));
    }

    // layer membership

    private void addBelongsToLayer(
            Set<ArchitectureRelation> relations,
            String source,
            NodeType type) {

        String layerId = layerNodeId(type);
        if (layerId != null) {
            relations.add(new ArchitectureRelation(source, layerId, RelationType.BELONGS_TO_LAYER));
        }
    }

    private void addLayerDependencies(
            Set<ArchitectureRelation> relations,
            Set<String> observedLayerPairs) {

        for (String pair : observedLayerPairs) {
            String[] parts = pair.split("\\|", 2);
            relations.add(new ArchitectureRelation(parts[0], parts[1], RelationType.DEPENDS_ON_LAYER));
        }
    }

    // class-level semantics (@Transactional on class)

    private void addClassLevelSemantics(
            Set<ArchitectureRelation> relations,
            Map<String, ArchitectureNode> nodeMap,
            ClassMetadata cls,
            String source) {

        if (cls.getAnnotations().contains("Transactional")) {
            addSemanticNode(nodeMap, "TRANSACTION_BOUNDARY");
            relations.add(new ArchitectureRelation(source, "TRANSACTION_BOUNDARY", RelationType.HAS_SEMANTIC));
        }
        if (cls.getAnnotations().contains("EnableDiscoveryClient")
                || cls.getAnnotations().contains("EnableEurekaClient")) {
            addSemanticNode(nodeMap, "SERVICE_DISCOVERY");
            relations.add(new ArchitectureRelation(source, "SERVICE_DISCOVERY", RelationType.HAS_SEMANTIC));
        }
        if (cls.getAnnotations().contains("EnableEurekaServer")) {
            addSemanticNode(nodeMap, "EUREKA_SERVER");
            relations.add(new ArchitectureRelation(source, "EUREKA_SERVER", RelationType.HAS_SEMANTIC));
        }
        if (cls.getAnnotations().contains("EnableConfigServer")) {
            addSemanticNode(nodeMap, "CONFIG_SERVER");
            relations.add(new ArchitectureRelation(source, "CONFIG_SERVER", RelationType.HAS_SEMANTIC));
        }
        if (cls.getAnnotations().contains("EnableAdminServer")) {
            addSemanticNode(nodeMap, "ADMIN_SERVER");
            relations.add(new ArchitectureRelation(source, "ADMIN_SERVER", RelationType.HAS_SEMANTIC));
        }
        if (cls.getAnnotations().contains("EnableCaching")) {
            addSemanticNode(nodeMap, "CACHING_ENABLED");
            relations.add(new ArchitectureRelation(source, "CACHING_ENABLED", RelationType.HAS_SEMANTIC));
        }
    }

    // inheritance

    private void addInheritance(
            Set<ArchitectureRelation> relations,
            ClassMetadata cls,
            String source) {

        for (String parent : cls.getExtendedTypes()) {
            for (String target : resolveNodeIds(parent)) {
                relations.add(new ArchitectureRelation(source, target, RelationType.EXTENDS));
            }
        }
        for (String iface : cls.getImplementedTypes()) {
            for (String target : resolveNodeIds(iface)) {
                relations.add(new ArchitectureRelation(source, target, RelationType.IMPLEMENTS));
            }
        }
    }

    // inter-service calls (WebClient/microservices)

    private Map<String, String> buildEndpointIndex(Map<String, ArchitectureNode> nodeMap) {
        Map<String, String> index = new HashMap<>();
        for (ArchitectureNode node : nodeMap.values()) {
            if (node.getType() != NodeType.ENDPOINT || node.getMetadata() == null) continue;
            String key = normalizeForMatch(node.getMetadata().get("httpMethod"), node.getMetadata().get("path"));
            if (key != null) index.putIfAbsent(key, node.getId());
        }
        return index;
    }

    private String normalizeForMatch(String httpMethod, String path) {
        if (httpMethod == null || path == null) return null;
        String p = path;
        int queryStart = p.indexOf('?');
        if (queryStart >= 0) p = p.substring(0, queryStart);
        while (p.startsWith("/")) p = p.substring(1);
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        p = p.replaceAll("\\{[^}]*}", "*");
        if (p.isBlank()) return null;
        return httpMethod.toUpperCase(Locale.ROOT) + " " + p;
    }

    private void addCalledServices(
            Set<ArchitectureRelation> relations,
            Map<String, ArchitectureNode> nodeMap,
            ClassMetadata cls,
            String source,
            Map<String, String> endpointIndex) {

        for (ServiceCallMetadata call : cls.getCalledServices()) {
            String matchKey = normalizeForMatch(call.getHttpMethod(), call.getPath());
            String target = (matchKey != null) ? endpointIndex.get(matchKey) : null;

            if (target == null) {
                target = call.getServiceName();
                nodeMap.putIfAbsent(target, new ArchitectureNode(target, NodeType.COMPONENT));
            }
            relations.add(new ArchitectureRelation(source, target, RelationType.CALLS_SERVICE));
        }
    }

    // JPA field relations

    private void addJpaRelations(
            Set<ArchitectureRelation> relations,
            ClassMetadata cls,
            String source) {

        for (FieldMetadata field : cls.getFields()) {
            RelationType jpaType = jpaRelationType(field.getAnnotations());
            if (jpaType == null) continue;
            for (String target : resolveNodeIds(field.getFieldType())) {
                relations.add(new ArchitectureRelation(source, target, jpaType));
            }
        }
    }

    // MANAGES_ENTITY

    private void addManagesEntity(
            Set<ArchitectureRelation> relations,
            ClassMetadata cls,
            String source,
            Set<String> projectClasses) {

        if (cls.getRole() != ArchitecturalRole.REPOSITORY) return;

        Set<String> managed = new LinkedHashSet<>();

        // 1. JpaRepository<Entity, ID> generic arg
        for (String extType : cls.getExtendedTypes()) {
            if (RoleDetector.isJpaRepositoryInterface(TypeUtils.simpleName(extType))) {
                managed.addAll(resolveNodeIds(extType));
            }
        }

        // 2. Naming convention: VetRepository = Vet, UmsAdminMapper = UmsAdmin, UserDao = User
        for (String suffix : List.of("Repository", "Mapper", "Dao")) {
            String entityByName = TypeUtils.stripSuffix(cls.getClassName(), suffix);
            if (entityByName != null && projectClasses.contains(entityByName)) {
                managed.addAll(resolveNodeIds(entityByName));
            }
        }

        //  Method return types
        for (MethodMetadata method : cls.getMethods()) {
            managed.addAll(resolveNodeIds(method.getReturnType()));
        }

        for (String entity : managed) {
            relations.add(new ArchitectureRelation(source, entity, RelationType.MANAGES_ENTITY));
        }
    }

    // constructor injection

    private void addConstructorInjections(
            Set<ArchitectureRelation> relations,
            ClassMetadata cls,
            String source,
            NodeType sourceNodeType,
            Map<String, NodeType> classNodeTypes,
            Set<String> observedLayerPairs) {

        for (ConstructorMetadata ctor : cls.getConstructors()) {
            for (ParameterMetadata param : ctor.getParameters()) {
                for (String target : resolveNodeIds(param.getParameterType())) {
                    if (target.equals(source)) continue;
                    relations.add(new ArchitectureRelation(source, target, RelationType.INJECTS));
                    trackLayerPair(sourceNodeType, classNodeTypes.get(target), observedLayerPairs);
                }
            }
        }
    }

    // field usages (non-JPA)

    private void addFieldUsages(
            Set<ArchitectureRelation> relations,
            ClassMetadata cls,
            String source,
            NodeType sourceNodeType,
            Map<String, NodeType> classNodeTypes,
            Set<String> observedLayerPairs) {

        for (FieldMetadata field : cls.getFields()) {
            if (jpaRelationType(field.getAnnotations()) != null) continue;

            String simpleType = TypeUtils.simpleName(field.getFieldType());
            if (CIRCUIT_BREAKER_TYPES.contains(simpleType)) {
                relations.add(new ArchitectureRelation(source, simpleType, RelationType.USES_CIRCUIT_BREAKER));
                continue;
            }

            boolean isInjected = field.getAnnotations().stream()
                    .anyMatch(INJECT_ANNOTATIONS::contains);
            RelationType relType = isInjected ? RelationType.INJECTS : RelationType.USES;

            for (String target : resolveNodeIds(field.getFieldType())) {
                if (target.equals(source)) continue;
                relations.add(new ArchitectureRelation(source, target, relType));
                trackLayerPair(sourceNodeType, classNodeTypes.get(target), observedLayerPairs);
            }
        }
    }

    // method-level relations

    private void addMethodRelations(
            Set<ArchitectureRelation> relations,
            Map<String, ArchitectureNode> nodeMap,
            ClassMetadata cls,
            String source) {

        String classBasePath = classBasePath(cls);

        for (MethodMetadata method : cls.getMethods()) {
            String methodId = source + "." + method.getMethodName();

            for (String returnTarget : resolveNodeIds(method.getReturnType())) {
                if (!returnTarget.equals(source)) {
                    relations.add(new ArchitectureRelation(source, returnTarget, RelationType.RETURNS));
                }
            }

            for (ParameterMetadata param : method.getParameters()) {
                for (String paramTarget : resolveNodeIds(param.getParameterType())) {
                    if (!paramTarget.equals(source)) {
                        relations.add(new ArchitectureRelation(source, paramTarget, RelationType.ACCEPTS_PARAMETER));
                    }
                }
            }

            // EXPOSES_ENDPOINT: structured node with HTTP method + full path
            for (AnnotationMetadata ann : method.getAnnotationDetails()) {
                if (!isHttpMappingAnnotation(ann.getName())) continue;

                String httpMethod = annotationToHttpMethod(ann.getName());
                String path = combinePaths(classBasePath, extractPath(ann));
                String endpointId = httpMethod + " " + path;

                if (!nodeMap.containsKey(endpointId)) {
                    Map<String, String> meta = new LinkedHashMap<>();
                    meta.put("httpMethod", httpMethod);
                    meta.put("path", path);
                    meta.put("controller", source);
                    meta.put("method", method.getMethodName());
                    nodeMap.put(endpointId, new ArchitectureNode(endpointId, NodeType.ENDPOINT, meta));
                }
                relations.add(new ArchitectureRelation(source, endpointId, RelationType.EXPOSES_ENDPOINT));
            }

            // CREATES_BEAN
            if (method.getAnnotations().contains("Bean")) {
                String returnType = TypeUtils.beanTypeName(method.getReturnType());
                if (returnType != null && !returnType.equals("void")) {
                    relations.add(new ArchitectureRelation(source, returnType, RelationType.CREATES_BEAN));
                }
            }

            // HAS_SEMANTIC: method-level Spring annotations (incl. @Tool)
            for (AnnotationMetadata ann : method.getAnnotationDetails()) {
                String semanticTarget = toSemanticTarget(ann.getName());
                if (semanticTarget == null) continue;

                // @Tool: enrich method node with tool description before ensuring it exists
                if ("Tool".equals(ann.getName())) {
                    List<String> desc = ann.getAttributes().get("description");
                    if (desc != null && !desc.isEmpty()) {
                        Map<String, String> methodMeta = new LinkedHashMap<>();
                        methodMeta.put("toolDescription", desc.get(0));
                        nodeMap.put(methodId, new ArchitectureNode(methodId, NodeType.METHOD, methodMeta));
                    }
                }

                ensureMethodNode(nodeMap, methodId);
                addSemanticNode(nodeMap, semanticTarget);
                relations.add(new ArchitectureRelation(methodId, semanticTarget, RelationType.HAS_SEMANTIC));
            }

            for (String call : method.getCalls()) {
                ensureMethodNode(nodeMap, methodId);
                relations.add(new ArchitectureRelation(methodId, call, RelationType.CALLS));
            }
        }
    }

    // helpers

    private void trackLayerPair(
            NodeType sourceType,
            NodeType targetType,
            Set<String> observedLayerPairs) {

        if (sourceType == null || targetType == null) return;
        String sourceLayer = layerNodeId(sourceType);
        String targetLayer = layerNodeId(targetType);
        if (sourceLayer != null && targetLayer != null && !sourceLayer.equals(targetLayer)) {
            observedLayerPairs.add(sourceLayer + "|" + targetLayer);
        }
    }

    // matches @GetMapping etc., plus composed wrappers like @AnonymousGetMapping
    private boolean isHttpMappingAnnotation(String annotationName) {
        return HTTP_ANNOTATIONS.contains(annotationName) || annotationName.endsWith("Mapping");
    }

    private String annotationToHttpMethod(String annotation) {
        if (annotation.contains("Get"))    return "GET";
        if (annotation.contains("Post"))   return "POST";
        if (annotation.contains("Put"))    return "PUT";
        if (annotation.contains("Delete")) return "DELETE";
        if (annotation.contains("Patch"))  return "PATCH";
        return "HTTP";
    }

    /** Class-level @RequestMapping base path, or "" if the class has none. */
    private String classBasePath(ClassMetadata cls) {
        for (AnnotationMetadata ann : cls.getAnnotationDetails()) {
            if ("RequestMapping".equals(ann.getName())) {
                String path = extractPath(ann);
                return "/".equals(path) ? "" : path;
            }
        }
        return "";
    }

    /** Joins a class-level base path with a method-level path without duplicating slashes. */
    private String combinePaths(String base, String method) {
        if (base == null || base.isBlank()) return method;
        if (method == null || method.isBlank() || method.equals("/")) return base;
        String b = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        String m = method.startsWith("/") ? method : "/" + method;
        return b + m;
    }

    private String extractPath(AnnotationMetadata ann) {
        List<String> values = ann.getAttributes().get("value");
        if (values != null && !values.isEmpty()) return values.get(0);
        List<String> paths = ann.getAttributes().get("path");
        if (paths != null && !paths.isEmpty()) return paths.get(0);
        return "/";
    }

    private String toSemanticTarget(String annotation) {
        return switch (annotation) {
            case "Transactional"            -> "TRANSACTION_BOUNDARY";
            case "Cacheable", "CachePut",
                 "CacheEvict"               -> "CACHE";
            case "Async"                    -> "ASYNC_EXECUTION";
            case "Scheduled"                -> "SCHEDULED_TASK";
            case "Tool"                     -> "AI_TOOL";
            default                         -> null;
        };
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

    private NodeType toNodeType(ArchitecturalRole role) {
        return switch (role) {
            case CONTROLLER        -> NodeType.CONTROLLER;
            case SERVICE           -> NodeType.SERVICE;
            case REPOSITORY        -> NodeType.REPOSITORY;
            case ENTITY            -> NodeType.ENTITY;
            case COMPONENT         -> NodeType.COMPONENT;
            case SERVICE_CLIENT    -> NodeType.SERVICE_CLIENT;
            case CONFIGURATION     -> NodeType.CONFIGURATION;
            case EXCEPTION_HANDLER -> NodeType.EXCEPTION_HANDLER;
            case EVENT_LISTENER    -> NodeType.EVENT_LISTENER;
            case SCHEDULER         -> NodeType.SCHEDULER;
            case SECURITY_COMPONENT -> NodeType.SECURITY_COMPONENT;
            case DTO, UNKNOWN      -> NodeType.UNKNOWN;
        };
    }

    private String layerNodeId(NodeType type) {
        return switch (type) {
            case CONTROLLER   -> NodeType.CONTROLLER_LAYER.name();
            case SERVICE      -> NodeType.SERVICE_LAYER.name();
            case REPOSITORY   -> NodeType.REPOSITORY_LAYER.name();
            case ENTITY       -> NodeType.ENTITY_LAYER.name();
            case COMPONENT    -> NodeType.COMPONENT_LAYER.name();
            case CONFIGURATION -> NodeType.CONFIGURATION_LAYER.name();
            default           -> null;
        };
    }
}
