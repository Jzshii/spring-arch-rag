package parser;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.IntersectionType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.UnionType;
import com.github.javaparser.ast.type.WildcardType;
import model.ClassMetadata;
import model.MethodMetadata;
import model.ProjectMetadata;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class ProjectAnalyzer {

    public static ProjectMetadata analyze(Path projectRoot) throws Exception {

        System.out.println("ANALYZER STARTED");

        ProjectMetadata project = new ProjectMetadata();
        List<Path> javaFiles = ProjectScanner.findJavaFiles(projectRoot);

        System.out.println("JAVA FILES: " + javaFiles.size());

        // extract all metadata
        for (Path file : javaFiles) {
            List<ClassMetadata> classes = MetadataExtractor.extract(file.toFile());
            project.getClasses().addAll(classes);
            classes.forEach(c -> project.getProjectClassNames().add(c.getClassName()));
        }

        // resolve cross-class relationships
        for (Path file : javaFiles) {
            CompilationUnit cu = StaticJavaParser.parse(file.toFile());
            String packageName = cu.getPackageDeclaration()
                    .map(pd -> pd.getNameAsString())
                    .orElse("");

            for (ClassOrInterfaceDeclaration clazz : cu.findAll(ClassOrInterfaceDeclaration.class)) {


                Optional<ClassMetadata> metadataOpt = project.getClasses().stream()
                        .filter(c -> c.getClassName().equals(clazz.getNameAsString())
                                && (c.getPackageName() == null ? "" : c.getPackageName())
                                        .equals(packageName))
                        .findFirst();

                if (metadataOpt.isEmpty()) continue;
                ClassMetadata metadata = metadataOpt.get();

                for (ClassOrInterfaceType extendedType : clazz.getExtendedTypes()) {
                    String typStr = extendedType.asString();
                    if (!metadata.getExtendedTypes().contains(typStr)) {
                        metadata.getExtendedTypes().add(typStr);
                    }
                    addDependenciesFromType(extendedType, project, metadata);
                }

                for (ClassOrInterfaceType implementedType : clazz.getImplementedTypes()) {
                    String typStr = implementedType.asString();
                    if (!metadata.getImplementedTypes().contains(typStr)) {
                        metadata.getImplementedTypes().add(typStr);
                    }
                    addDependenciesFromType(implementedType, project, metadata);
                }

                // field dependencies
                for (FieldDeclaration field : clazz.getFields()) {
                    field.getVariables().forEach(variable ->
                            addDependenciesFromType(variable.getType(), project, metadata));
                }

                // constructor dependencies
                for (ConstructorDeclaration constructor : clazz.getConstructors()) {
                    constructor.getParameters().forEach(parameter ->
                            addDependenciesFromType(parameter.getType(), project, metadata));
                }

                // method dependencies + call graph
                Map<String, String> fieldTypes = buildFieldTypeMap(metadata);

                for (MethodDeclaration method : clazz.getMethods()) {
                    addDependenciesFromType(method.getType(), project, metadata);
                    method.getParameters().forEach(parameter ->
                            addDependenciesFromType(parameter.getType(), project, metadata));

                    findMethodMetadata(metadata, method.getNameAsString())
                            .ifPresent(methodMeta ->
                                    method.getBody().ifPresent(body ->
                                            body.findAll(MethodCallExpr.class).forEach(call ->
                                                    call.getScope().ifPresent(scope -> {
                                                        if (!scope.isNameExpr()) return;
                                                        String scopeName = scope.asNameExpr().getNameAsString();
                                                        String fieldType = fieldTypes.get(scopeName);
                                                        if (fieldType != null
                                                                && project.getProjectClassNames().contains(fieldType)) {
                                                            String target = fieldType + "." + call.getNameAsString();
                                                            if (!methodMeta.getCalls().contains(target)) {
                                                                methodMeta.getCalls().add(target);
                                                            }
                                                        }
                                                    }))
                                    ));
                }
            }
        }

        return project;
    }

    private static Optional<MethodMetadata> findMethodMetadata(ClassMetadata cls, String methodName) {
        return cls.getMethods().stream()
                .filter(m -> m.getMethodName().equals(methodName))
                .findFirst();
    }

    
    private static Map<String, String> buildFieldTypeMap(ClassMetadata cls) {
        Map<String, String> map = new HashMap<>();
        for (var field : cls.getFields()) {
            String simpleName = TypeUtils.simpleName(field.getFieldType());
            if (simpleName != null) {
                map.put(field.getFieldName(), simpleName);
            }
        }
        return map;
    }

    private static void addDependenciesFromType(Type type, ProjectMetadata project, ClassMetadata metadata) {
        for (String typeName : collectTypeNames(type)) {
            addDependency(project, metadata, typeName);
        }
    }

    private static void addDependency(ProjectMetadata project, ClassMetadata metadata, String typeName) {
        if (project.getProjectClassNames().contains(typeName)
                && !metadata.getDependencies().contains(typeName)) {
            metadata.getDependencies().add(typeName);
        }
    }

    private static List<String> collectTypeNames(Type type) {
        List<String> names = new ArrayList<>();

        if (type == null
                || type.isPrimitiveType()
                || type.isVoidType()
                || type.isVarType()
                || type.isUnknownType()) {
            return names;
        }

        if (type.isArrayType()) {
            names.addAll(collectTypeNames(type.asArrayType().getComponentType()));
            return names;
        }

        if (type.isClassOrInterfaceType()) {
            ClassOrInterfaceType classType = type.asClassOrInterfaceType();
            names.add(classType.getNameAsString());
            classType.getTypeArguments().ifPresent(arguments ->
                    arguments.forEach(argument -> names.addAll(collectTypeNames(argument))));
            return names;
        }

        if (type.isWildcardType()) {
            WildcardType wildcardType = type.asWildcardType();
            wildcardType.getExtendedType().ifPresent(extended ->
                    names.addAll(collectTypeNames(extended)));
            wildcardType.getSuperType().ifPresent(bound ->
                    names.addAll(collectTypeNames(bound)));
            return names;
        }

        if (type.isIntersectionType()) {
            type.asIntersectionType().getElements().forEach(element ->
                    names.addAll(collectTypeNames(element)));
            return names;
        }

        if (type.isUnionType()) {
            type.asUnionType().getElements().forEach(element ->
                    names.addAll(collectTypeNames(element)));
            return names;
        }

        return names;
    }
}
