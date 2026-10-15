package parser;

import model.ArchitecturalRole;

import java.util.List;

public class RoleDetector {

    private static final List<String> JPA_REPO_INTERFACES = List.of(
            "JpaRepository", "CrudRepository",
            "PagingAndSortingRepository", "JpaSpecificationExecutor",
            "ListCrudRepository", "ListPagingAndSortingRepository"
    );

    /**
     * Determines architectural role.

     */
    public static ArchitecturalRole determineRole(
            String className,
            List<String> classAnnotations,
            List<String> methodAnnotations,
            List<String> extendedTypeNames,
            boolean isMapStructMapper) {

        // annotation-based
        if (classAnnotations.contains("RestController")
                || classAnnotations.contains("Controller")) {
            return ArchitecturalRole.CONTROLLER;
        }
        if (classAnnotations.contains("ControllerAdvice")
                || classAnnotations.contains("RestControllerAdvice")
                || classAnnotations.contains("ResponseStatus")) {
            return ArchitecturalRole.EXCEPTION_HANDLER;
        }
        if (classAnnotations.contains("Service")) {
            return ArchitecturalRole.SERVICE;
        }
        if (classAnnotations.contains("Mapper") && isMapStructMapper) {
            return ArchitecturalRole.COMPONENT;
        }
        if (classAnnotations.contains("Repository")
                || classAnnotations.contains("Mapper")) {
            return ArchitecturalRole.REPOSITORY;
        }
        if (classAnnotations.contains("Entity")) {
            return ArchitecturalRole.ENTITY;
        }
        if (classAnnotations.contains("Configuration")
                || classAnnotations.contains("SpringBootApplication")) {
            return ArchitecturalRole.CONFIGURATION;
        }
        if (classAnnotations.contains("EnableWebSecurity")) {
            return ArchitecturalRole.SECURITY_COMPONENT;
        }
        // method annotation-based
        if (methodAnnotations.contains("EventListener")) {
            return ArchitecturalRole.EVENT_LISTENER;
        }
        if (methodAnnotations.contains("Scheduled")) {
            return ArchitecturalRole.SCHEDULER;
        }

        if (classAnnotations.contains("Component")) {
            if (className.endsWith("ServiceClient") || className.endsWith("ApiClient"))
                return ArchitecturalRole.SERVICE_CLIENT;
            return ArchitecturalRole.COMPONENT;
        }

        // extends JPA repository interface
        for (String extType : extendedTypeNames) {
            if (isJpaRepositoryInterface(extType)) {
                return ArchitecturalRole.REPOSITORY;
            }
        }

        // naming conventions
        if (className.endsWith("Repository") || className.endsWith("Dao")) return ArchitecturalRole.REPOSITORY;
        if (className.endsWith("Controller")) return ArchitecturalRole.CONTROLLER;
        if (className.endsWith("Service"))    return ArchitecturalRole.SERVICE;
        if (className.endsWith("Dto") || className.endsWith("DTO")
                || className.endsWith("Param") || className.endsWith("Result")) return ArchitecturalRole.DTO;

        return ArchitecturalRole.UNKNOWN;
    }

    public static ArchitecturalRole determineRole(
            String className,
            List<String> classAnnotations,
            List<String> methodAnnotations,
            List<String> extendedTypeNames) {
        return determineRole(className, classAnnotations, methodAnnotations, extendedTypeNames, false);
    }

    public static ArchitecturalRole determineRole(
            String className,
            List<String> classAnnotations) {
        return determineRole(className, classAnnotations, List.of(), List.of(), false);
    }

    static boolean isJpaRepositoryInterface(String simpleTypeName) {
        for (String repo : JPA_REPO_INTERFACES) {
            if (simpleTypeName.equals(repo) || simpleTypeName.startsWith(repo + "<")) {
                return true;
            }
        }
        return false;
    }
}
