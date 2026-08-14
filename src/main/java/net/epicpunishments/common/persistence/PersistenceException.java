package net.epicpunishments.common.persistence;

import java.util.Objects;

public final class PersistenceException extends RuntimeException {
    private final PersistenceFailureKind kind;

    public PersistenceException(PersistenceFailureKind kind, String message) {
        super(message);
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    public PersistenceException(PersistenceFailureKind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    public PersistenceFailureKind kind() {
        return kind;
    }
}
