package parser;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class TypeUtils {

    private static final Pattern GENERIC_INNER = Pattern.compile("<([^,>]+)>");


    static String resolveProjectType(String typeName, Set<String> projectClasses) {
        if (typeName == null || typeName.isBlank()) return null;
        String plain = simpleName(typeName);
        if (projectClasses.contains(plain)) return plain;
        Matcher m = GENERIC_INNER.matcher(typeName);
        if (m.find()) {
            String inner = simpleName(m.group(1).trim());
            if (projectClasses.contains(inner)) return inner;
        }
        return null;
    }

    static String simpleName(String typeName) {
        if (typeName == null) return null;
        int lt = typeName.indexOf('<');
        if (lt != -1) typeName = typeName.substring(0, lt);
        int dot = typeName.lastIndexOf('.');
        if (dot != -1) typeName = typeName.substring(dot + 1);
        return typeName.trim();
    }

    static String beanTypeName(String typeName) {
        if (typeName == null) return null;
        int lt = typeName.indexOf('<');
        if (lt != -1) typeName = typeName.substring(0, lt);
        String[] parts = typeName.trim().split("\\.");
        StringBuilder result = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (Character.isUpperCase(part.charAt(0)) || result.length() > 0) {
                if (result.length() > 0) result.append(".");
                result.append(part);
            }
        }
        return result.length() > 0 ? result.toString() : typeName.trim();
    }

    static String stripSuffix(String name, String suffix) {
        if (name != null && name.endsWith(suffix) && name.length() > suffix.length()) {
            return name.substring(0, name.length() - suffix.length());
        }
        return null;
    }

    private TypeUtils() {}
}
