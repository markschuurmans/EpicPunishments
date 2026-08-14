package net.epicpunishments.common.config;

import net.epicpunishments.common.message.MessageCatalog;

import java.util.Objects;

public record ConfigurationSnapshot(
        DatabaseConfiguration database,
        MessageCatalog messages
) {
    public ConfigurationSnapshot {
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(messages, "messages");
    }
}
