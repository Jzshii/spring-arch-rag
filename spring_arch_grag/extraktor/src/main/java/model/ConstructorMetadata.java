package model;

import java.util.ArrayList;
import java.util.List;

public class ConstructorMetadata {

    private String signature;

    private final List<String> annotations =
            new ArrayList<>();

    private final List<ParameterMetadata> parameters =
            new ArrayList<>();

    public String getSignature() {
        return signature;
    }

    public void setSignature(String signature) {
        this.signature = signature;
    }

    public List<String> getAnnotations() {
        return annotations;
    }

    public List<ParameterMetadata> getParameters() {
        return parameters;
    }

    @Override
    public String toString() {
        return "ConstructorMetadata{" +
                "signature='" + signature + '\'' +
                ", annotations=" + annotations +
                ", parameters=" + parameters +
                '}';
    }
}
