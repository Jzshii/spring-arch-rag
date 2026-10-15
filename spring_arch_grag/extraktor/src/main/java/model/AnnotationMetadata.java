package model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AnnotationMetadata {

    private String name;

    private final Map<String, List<String>> attributes =
            new HashMap<>();

    public AnnotationMetadata() {
    }

    public AnnotationMetadata(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Map<String, List<String>> getAttributes() {
        return attributes;
    }

    public void addAttribute(String key, List<String> values) {
        attributes.put(key, new ArrayList<>(values));
    }

    @Override
    public String toString() {
        return "AnnotationMetadata{" +
                "name='" + name + '\'' +
                ", attributes=" + attributes +
                '}';
    }
}
