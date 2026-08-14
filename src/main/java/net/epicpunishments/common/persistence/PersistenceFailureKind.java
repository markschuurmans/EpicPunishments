package net.epicpunishments.common.persistence;

public enum PersistenceFailureKind {
    CONFLICT,
    INVALID_DATA,
    SHUTTING_DOWN,
    TIMEOUT,
    TRANSIENT,
    UNAVAILABLE,
    UNKNOWN
}
