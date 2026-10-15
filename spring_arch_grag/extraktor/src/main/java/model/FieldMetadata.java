package model;

import java.util.ArrayList;
import java.util.List;

public class FieldMetadata {

    private String fieldName;

    private String fieldType;

    private final List<String> annotations =
            new ArrayList<>();

    public String getFieldName() {
        return fieldName;
    }

    public void setFieldName(String fieldName) {
        this.fieldName = fieldName;
    }

    public String getFieldType() {
        return fieldType;
    }

    public void setFieldType(String fieldType) {
        this.fieldType = fieldType;
    }

    public List<String> getAnnotations() {
        return annotations;
    }

    @Override
    public String toString() {
        return "FieldMetadata{" +
                "fieldName='" + fieldName + '\'' +
                ", fieldType='" + fieldType + '\'' +
                ", annotations=" + annotations +
                '}';
    }
}
