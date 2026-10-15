package model;

import java.util.ArrayList;
import java.util.List;

public class ParameterMetadata {

    private String parameterName;

    private String parameterType;

    private final List<String> annotations =
            new ArrayList<>();

    public String getParameterName() {
        return parameterName;
    }

    public void setParameterName(String parameterName) {
        this.parameterName = parameterName;
    }

    public String getParameterType() {
        return parameterType;
    }

    public void setParameterType(String parameterType) {
        this.parameterType = parameterType;
    }

    public List<String> getAnnotations() {
        return annotations;
    }

    @Override
    public String toString() {
        return "ParameterMetadata{" +
                "parameterName='" + parameterName + '\'' +
                ", parameterType='" + parameterType + '\'' +
                ", annotations=" + annotations +
                '}';
    }
}
