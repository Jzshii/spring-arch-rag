package model;

import java.util.ArrayList;
import java.util.List;

public class MethodMetadata {

    private String methodName;
    private String signature;
    private String returnType;

    private final List<String> annotations = new ArrayList<>();
    private final List<AnnotationMetadata> annotationDetails = new ArrayList<>();
    private final List<ParameterMetadata> parameters = new ArrayList<>();

    private final List<String> calls = new ArrayList<>();

    private String methodBody;

    public String getMethodName() {
        return methodName;
    }

    public void setMethodName(String methodName) {
        this.methodName = methodName;
    }

    public String getSignature() {
        return signature;
    }

    public void setSignature(String signature) {
        this.signature = signature;
    }

    public String getReturnType() {
        return returnType;
    }

    public void setReturnType(String returnType) {
        this.returnType = returnType;
    }

    public List<String> getAnnotations() {
        return annotations;
    }

    public List<AnnotationMetadata> getAnnotationDetails() {
        return annotationDetails;
    }

    public List<ParameterMetadata> getParameters() {
        return parameters;
    }

    public List<String> getCalls() {
        return calls;
    }

    public String getMethodBody() {
        return methodBody;
    }

    public void setMethodBody(String methodBody) {
        this.methodBody = methodBody;
    }

    @Override
    public String toString() {
        return "MethodMetadata{" +
                "methodName='" + methodName + '\'' +
                ", signature='" + signature + '\'' +
                ", returnType='" + returnType + '\'' +
                ", annotations=" + annotations +
                ", parameters=" + parameters +
                ", calls=" + calls +
                '}';
    }
}
