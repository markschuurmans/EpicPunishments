package net.epicpunishments.common.config;

import net.epicpunishments.common.message.MessageCatalog;

import java.util.Objects;

public record ConfigurationSnapshot(
        DatabaseConfiguration database,
        PunishmentConfiguration punishments,
        ReportConfiguration reports,
        MessageCatalog messages
) {
    public ConfigurationSnapshot {
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(punishments, "punishments");
        Objects.requireNonNull(reports, "reports");
        Objects.requireNonNull(messages, "messages");
    }
}
