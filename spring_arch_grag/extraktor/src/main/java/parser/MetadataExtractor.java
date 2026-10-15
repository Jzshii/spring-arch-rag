package parser;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.CharLiteralExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.DoubleLiteralExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithName;
import model.AnnotationMetadata;
import model.ClassMetadata;
import model.ConstructorMetadata;
import model.FieldMetadata;
import model.MethodMetadata;
import model.ParameterMetadata;
import model.ServiceCallMetadata;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class MetadataExtractor {

    public static List<ClassMetadata> extract(File file) throws IOException {

        CompilationUnit cu = StaticJavaParser.parse(file);

        String packageName = cu.getPackageDeclaration()
                .map(NodeWithName::getNameAsString)
                .orElse("");

        List<ClassMetadata> result = new ArrayList<>();

        for (ClassOrInterfaceDeclaration clazz : cu.findAll(ClassOrInterfaceDeclaration.class)) {

            ClassMetadata metadata = new ClassMetadata();
            metadata.setPackageName(packageName);
            metadata.setClassName(clazz.getNameAsString());

            // class annotations
            clazz.getAnnotations().forEach(annotation -> {
                metadata.getAnnotations().add(annotation.getNameAsString());
                metadata.getAnnotationDetails().add(toAnnotationMetadata(annotation));
            });

            // fields
            for (FieldDeclaration field : clazz.getFields()) {
                field.getVariables().forEach(variable -> {
                    FieldMetadata fieldMetadata = new FieldMetadata();
                    fieldMetadata.setFieldName(variable.getNameAsString());
                    fieldMetadata.setFieldType(variable.getType().asString());
                    field.getAnnotations().forEach(annotation ->
                            fieldMetadata.getAnnotations().add(annotation.getNameAsString()));
                    metadata.getFields().add(fieldMetadata);
                });
            }

            // constructors
            for (ConstructorDeclaration constructor : clazz.getConstructors()) {
                ConstructorMetadata constructorMetadata = new ConstructorMetadata();
                constructorMetadata.setSignature(constructor.getDeclarationAsString());
                constructor.getAnnotations().forEach(annotation ->
                        constructorMetadata.getAnnotations().add(annotation.getNameAsString()));
                for (Parameter parameter : constructor.getParameters()) {
                    ParameterMetadata parameterMetadata = new ParameterMetadata();
                    parameterMetadata.setParameterName(parameter.getNameAsString());
                    parameterMetadata.setParameterType(parameter.getType().asString());
                    parameter.getAnnotations().forEach(annotation ->
                            parameterMetadata.getAnnotations().add(annotation.getNameAsString()));
                    constructorMetadata.getParameters().add(parameterMetadata);
                }
                metadata.getConstructors().add(constructorMetadata);
            }

            // methods
            for (MethodDeclaration method : clazz.getMethods()) {
                MethodMetadata methodMetadata = new MethodMetadata();
                methodMetadata.setMethodName(method.getNameAsString());
                methodMetadata.setSignature(method.getDeclarationAsString());
                methodMetadata.setReturnType(method.getType().asString());
                method.getAnnotations().forEach(annotation -> {
                    methodMetadata.getAnnotations().add(annotation.getNameAsString());
                    methodMetadata.getAnnotationDetails().add(toAnnotationMetadata(annotation));
                });
                for (Parameter parameter : method.getParameters()) {
                    ParameterMetadata parameterMetadata = new ParameterMetadata();
                    parameterMetadata.setParameterName(parameter.getNameAsString());
                    parameterMetadata.setParameterType(parameter.getType().asString());
                    parameter.getAnnotations().forEach(annotation ->
                            parameterMetadata.getAnnotations().add(annotation.getNameAsString()));
                    methodMetadata.getParameters().add(parameterMetadata);
                }
                method.getBody().ifPresent(body ->
                        methodMetadata.setMethodBody(body.toString()));
                metadata.getMethods().add(methodMetadata);
            }

            // role detection
            List<String> allMethodAnnotations = metadata.getMethods().stream()
                    .flatMap(m -> m.getAnnotations().stream())
                    .collect(Collectors.toList());

            List<String> extendedTypeNames = clazz.getExtendedTypes().stream()
                    .map(t -> t.getNameAsString())
                    .collect(Collectors.toList());


            boolean isMapStructMapper = cu.getImports().stream()
                    .anyMatch(imp -> "org.mapstruct.Mapper".equals(imp.getNameAsString()));

            metadata.setRole(RoleDetector.determineRole(
                    metadata.getClassName(),
                    metadata.getAnnotations(),
                    allMethodAnnotations,
                    extendedTypeNames,
                    isMapStructMapper));

            metadata.getCalledServices().addAll(extractCalledServices(clazz));

            result.add(metadata);
        }

        return result;
    }

    private static AnnotationMetadata toAnnotationMetadata(AnnotationExpr annotation) {
        AnnotationMetadata metadata = new AnnotationMetadata(annotation.getNameAsString());

        if (annotation.isSingleMemberAnnotationExpr()) {
            SingleMemberAnnotationExpr singleMember = annotation.asSingleMemberAnnotationExpr();
            metadata.addAttribute("value", extractValues(singleMember.getMemberValue()));
        }

        if (annotation.isNormalAnnotationExpr()) {
            NormalAnnotationExpr normalAnnotation = annotation.asNormalAnnotationExpr();
            for (MemberValuePair pair : normalAnnotation.getPairs()) {
                metadata.addAttribute(pair.getNameAsString(), extractValues(pair.getValue()));
            }
        }

        return metadata;
    }

    private static List<String> extractValues(Expression expression) {
        if (expression.isArrayInitializerExpr()) {
            ArrayInitializerExpr initializer = expression.asArrayInitializerExpr();
            List<String> values = new ArrayList<>();
            for (Expression value : initializer.getValues()) {
                values.add(extractValue(value));
            }
            return values;
        }
        return List.of(extractValue(expression));
    }

    private static String extractValue(Expression expression) {
        if (expression.isStringLiteralExpr())
            return expression.asStringLiteralExpr().getValue();
        if (expression.isCharLiteralExpr())
            return expression.asCharLiteralExpr().getValue();
        if (expression.isBooleanLiteralExpr())
            return Boolean.toString(expression.asBooleanLiteralExpr().getValue());
        if (expression.isIntegerLiteralExpr())
            return expression.asIntegerLiteralExpr().getValue();
        if (expression.isLongLiteralExpr())
            return expression.asLongLiteralExpr().getValue();
        if (expression.isDoubleLiteralExpr())
            return expression.asDoubleLiteralExpr().getValue();
        if (expression.isFieldAccessExpr())
            return expression.asFieldAccessExpr().getNameAsString();
        if (expression.isNameExpr())
            return expression.asNameExpr().getNameAsString();
        if (expression.isClassExpr())
            return expression.asClassExpr().getType().asString();
        return expression.toString();
    }

    private static final Pattern SERVICE_URL =
            Pattern.compile("http://([a-z][a-z0-9-]+-service)/");
    private static final Pattern DISCOVERY_SERVICE =
            Pattern.compile("getInstances\\(\\s*\"([a-z][a-z0-9-]+-service)\"\\s*\\)");
    private static final Set<String> HTTP_VERB_CALLS =
            Set.of("get", "post", "put", "delete", "patch", "head");
    private static final Pattern HOST_PREFIX =
            Pattern.compile("^https?://[a-z][a-z0-9-]+-service/");

    private static String findServiceName(String text) {
        Matcher literal = SERVICE_URL.matcher(text);
        if (literal.find()) return literal.group(1);
        Matcher discovery = DISCOVERY_SERVICE.matcher(text);
        if (discovery.find()) return discovery.group(1);
        return null;
    }

    private static List<ServiceCallMetadata> extractCalledServices(ClassOrInterfaceDeclaration clazz) {
        LinkedHashMap<String, ServiceCallMetadata> calls = new LinkedHashMap<>();


        LinkedHashSet<String> classServiceNames = new LinkedHashSet<>();
        for (FieldDeclaration field : clazz.getFields()) {
            field.getVariables().forEach(var ->
                var.getInitializer().ifPresent(init -> {
                    String name = findServiceName(init.toString());
                    if (name != null) classServiceNames.add(name);
                })
            );
        }
        for (MethodDeclaration method : clazz.getMethods()) {
            method.getBody().ifPresent(body -> {
                String name = findServiceName(body.toString());
                if (name != null) classServiceNames.add(name);
            });
        }
        if (classServiceNames.isEmpty()) return List.of();


        if (classServiceNames.size() > 1) {
            for (String name : classServiceNames) addCall(calls, name, null, null);
            return new ArrayList<>(calls.values());
        }
        String serviceName = classServiceNames.iterator().next();

        boolean anyUriCall = false;
        for (MethodDeclaration method : clazz.getMethods()) {
            if (method.getBody().isEmpty()) continue;
            List<MethodCallExpr> uriCalls = method.getBody().get().findAll(MethodCallExpr.class).stream()
                    .filter(call -> "uri".equals(call.getNameAsString()))
                    .toList();
            for (MethodCallExpr uriCall : uriCalls) {
                anyUriCall = true;
                String httpMethod = findHttpMethodFromChain(uriCall);
                String path = null;
                if (!uriCall.getArguments().isEmpty()) {
                    path = extractPathTemplate(templatizeUri(uriCall.getArguments().get(0)));
                }
                addCall(calls, serviceName, httpMethod, path);
            }
        }
        if (!anyUriCall) addCall(calls, serviceName, null, null);

        return new ArrayList<>(calls.values());
    }

    private static void addCall(LinkedHashMap<String, ServiceCallMetadata> calls,
                                 String serviceName, String httpMethod, String path) {
        String key = serviceName + "|" + httpMethod + "|" + path;
        if (calls.containsKey(key)) return;
        ServiceCallMetadata call = new ServiceCallMetadata();
        call.setServiceName(serviceName);
        call.setHttpMethod(httpMethod);
        call.setPath(path);
        calls.put(key, call);
    }

    private static String templatizeUri(Expression expr) {
        if (expr.isStringLiteralExpr()) {
            return expr.asStringLiteralExpr().getValue();
        }
        if (expr.isBinaryExpr()) {
            BinaryExpr binary = expr.asBinaryExpr();
            if (binary.getOperator() == BinaryExpr.Operator.PLUS) {
                return templatizeUri(binary.getLeft()) + templatizeUri(binary.getRight());
            }
        }
        return "{}";
    }

    private static String extractPathTemplate(String uriTemplate) {
        if (uriTemplate == null) return null;
        String path;
        Matcher hostMatch = HOST_PREFIX.matcher(uriTemplate);
        if (hostMatch.find() && hostMatch.start() == 0) {
            path = uriTemplate.substring(hostMatch.end());
        } else if (uriTemplate.startsWith("{}")) {
            path = uriTemplate.substring(2);
        } else {
            return null;
        }
        int queryStart = path.indexOf('?');
        if (queryStart >= 0) path = path.substring(0, queryStart);
        return path.isBlank() ? null : path;
    }

    private static String findHttpMethodFromChain(MethodCallExpr uriCall) {
        Optional<Expression> scope = uriCall.getScope();
        while (scope.isPresent() && scope.get().isMethodCallExpr()) {
            MethodCallExpr call = scope.get().asMethodCallExpr();
            String name = call.getNameAsString().toLowerCase(Locale.ROOT);
            if (HTTP_VERB_CALLS.contains(name)) {
                return name.toUpperCase(Locale.ROOT);
            }
            scope = call.getScope();
        }
        return null;
    }
}
