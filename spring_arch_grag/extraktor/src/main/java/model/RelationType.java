package model;

public enum RelationType {

    // ArchitectureKnowledge
    HANDLES,
    MANAGES,

    // structural
    INJECTS,
    USES,
    EXTENDS,
    IMPLEMENTS,

    // JPA
    MANY_TO_ONE,
    ONE_TO_MANY,
    MANY_TO_MANY,
    ONE_TO_ONE,

    // Spring architecture
    MANAGES_ENTITY,
    CREATES_BEAN,
    CALLS_SERVICE,

    // method-level
    RETURNS,
    ACCEPTS_PARAMETER,
    EXPOSES_ENDPOINT,
    CALLS,

    // graph meta
    BELONGS_TO_LAYER,
    DEPENDS_ON_LAYER,
    HAS_SEMANTIC,
    USES_CIRCUIT_BREAKER
}
