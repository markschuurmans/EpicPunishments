package net.epicpunishments.common.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record AuditEntry(
        UUID id,
        Actor actor,
        String action,
        String entityType,
        UUID entityId,
        Instant occurredAt,
        String details
) {
    public AuditEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(actor, "actor");
        action = requireText(action, "action", 64);
        entityType = requireText(entityType, "entityType", 64);
        Objects.requireNonNull(entityId, "entityId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        details = requireText(details, "details", 4_096);
    }

    private static String requireText(String value, String name, int maximumLength) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(name + " must contain between 1 and " + maximumLength + " characters");
        }
        return value;
    }
}
