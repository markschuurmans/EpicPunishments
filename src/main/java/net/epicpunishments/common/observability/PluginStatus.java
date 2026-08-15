package net.epicpunishments.common.observability;

import net.epicpunishments.common.persistence.PersistenceHealth;

import java.util.Objects;

public record PluginStatus(
        String providerType,
        String schemaVersion,
        PersistenceHealth health,
        int pendingTaskCount
) {
    public PluginStatus {
        Objects.requireNonNull(providerType, "providerType");
        Objects.requireNonNull(schemaVersion, "schemaVersion");
        Objects.requireNonNull(health, "health");
        if (providerType.isBlank()) {
            throw new IllegalArgumentException("providerType must not be blank");
        }
        if (schemaVersion.isBlank()) {
            throw new IllegalArgumentException("schemaVersion must not be blank");
        }
        if (pendingTaskCount < 0) {
            throw new IllegalArgumentException("pendingTaskCount must not be negative");
        }
    }
}
