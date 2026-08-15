package net.epicpunishments.common.message;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class MessageCatalog {
    public static final Set<String> REQUIRED_KEYS = Set.of(
            "command.usage",
            "command.version",
            "command.reload-started",
            "command.reload-success",
            "command.reload-failed",
            "command.not-ready",
            "login.banned",
            "login.temporary-error",
            "login.degraded-warning",
            "warning.received",
            "punishment.usage",
            "punishment.invalid-input",
            "punishment.invalid-target",
            "punishment.target-not-found",
            "punishment.target-ambiguous",
            "punishment.target-exempt",
            "punishment.applied",
            "punishment.revoked",
            "punishment.no-active",
            "punishment.history-header",
            "punishment.history-entry",
            "punishment.history-empty",
            "punishment.command-failed",
            "punishment.unsupported-sender",
            "punishment.muted",
            "punishment.mute-blocked"
    );

    private final Map<String, MessageTemplate> templates;

    private MessageCatalog(Map<String, MessageTemplate> templates) {
        this.templates = Map.copyOf(templates);
    }

    public static MessageCatalog parse(Map<String, String> sources) {
        Objects.requireNonNull(sources, "sources");
        var missing = new java.util.TreeSet<>(REQUIRED_KEYS);
        missing.removeAll(sources.keySet());
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("Missing message keys: " + String.join(", ", missing));
        }

        MiniMessage miniMessage = MiniMessage.builder().strict(true).build();
        var parsed = new LinkedHashMap<String, MessageTemplate>();
        for (var entry : sources.entrySet()) {
            String key = entry.getKey();
            String source = entry.getValue();
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("Message keys must not be blank.");
            }
            if (source == null || source.isBlank()) {
                throw new IllegalArgumentException("Message " + key + " must not be blank.");
            }
            try {
                parsed.put(key, new MessageTemplate(miniMessage.deserialize(source), source));
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("Message " + key + " is not valid MiniMessage.", exception);
            }
        }
        return new MessageCatalog(parsed);
    }

    public Component message(String key) {
        return template(key).render(Map.of());
    }

    public Component message(String key, Map<String, String> placeholders) {
        return template(key).render(placeholders);
    }

    private MessageTemplate template(String key) {
        MessageTemplate template = templates.get(key);
        if (template == null) {
            throw new IllegalArgumentException("Unknown message key: " + key);
        }
        return template;
    }
}
