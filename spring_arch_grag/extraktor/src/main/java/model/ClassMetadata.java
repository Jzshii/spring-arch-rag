package model;

import java.util.ArrayList;
import java.util.List;

public class ClassMetadata {

    private String packageName;

    private String className;

    private ArchitecturalRole role =
            ArchitecturalRole.UNKNOWN;

    private final List<String> annotations =
            new ArrayList<>();

    private final List<AnnotationMetadata> annotationDetails =
            new ArrayList<>();

    private final List<String> dependencies =
            new ArrayList<>();

    private final List<FieldMetadata> fields =
            new ArrayList<>();

    private final List<MethodMetadata> methods =
            new ArrayList<>();

    private final List<ConstructorMetadata> constructors =
            new ArrayList<>();

    private final List<String> extendedTypes =
            new ArrayList<>();

    private final List<String> implementedTypes =
            new ArrayList<>();

    private final List<ServiceCallMetadata> calledServices =
            new ArrayList<>();

    public String getPackageName() {
        return packageName;
    }

    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }

    public String getClassName() {
        return className;
    }

    public void setClassName(String className) {
        this.className = className;
    }

    public List<AnnotationMetadata> getAnnotationDetails() {
        return annotationDetails;
    }

    public ArchitecturalRole getRole() {
        return role;
    }

    public void setRole(ArchitecturalRole role) {
        this.role = role;
    }

    public List<String> getAnnotations() {
        return annotations;
    }

    public List<String> getDependencies() {
        return dependencies;
    }

    public List<FieldMetadata> getFields() {
        return fields;
    }

    public List<ConstructorMetadata> getConstructors() {
        return constructors;
    }

    public List<String> getExtendedTypes() {
        return extendedTypes;
    }

    public List<String> getImplementedTypes() {
        return implementedTypes;
    }

    public List<ServiceCallMetadata> getCalledServices() {
        return calledServices;
    }

    public List<MethodMetadata> getMethods() {
        return methods;
    }

    @Override
    public String toString() {
        return "ClassMetadata{" +
                "packageName='" + packageName + '\'' +
                ", className='" + className + '\'' +
                ", role=" + role +
                ", annotations=" + annotations +
                ", annotationDetails=" + annotationDetails +
                ", dependencies=" + dependencies +
                ", fields=" + fields +
                ", methods=" + methods +
                ", constructors=" + constructors +
                ", extendedTypes=" + extendedTypes +
                ", implementedTypes=" + implementedTypes +
                '}';
    }
}