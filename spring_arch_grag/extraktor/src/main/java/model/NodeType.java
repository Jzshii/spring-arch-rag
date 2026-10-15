package model;

public enum NodeType {

    // class roles
    CONTROLLER,
    SERVICE,
    REPOSITORY,
    ENTITY,
    COMPONENT,
    SERVICE_CLIENT,
    CONFIGURATION,
    EXCEPTION_HANDLER,
    EVENT_LISTENER,
    SCHEDULER,
    SECURITY_COMPONENT,
    UNKNOWN,

    //layer nodes
    CONTROLLER_LAYER,
    SERVICE_LAYER,
    REPOSITORY_LAYER,
    ENTITY_LAYER,
    CONFIGURATION_LAYER,
    COMPONENT_LAYER,

    // endpoint, method, and semantic marker nodes
    ENDPOINT,
    METHOD,
    SEMANTIC_MARKER
}
