package net.epicpunishments.interaction.listener;

import net.epicpunishments.common.config.ConfigurationService;
import net.epicpunishments.interaction.PaperMainThreadExecutor;
import net.epicpunishments.punishment.domain.Punishment;
import net.kyori.adventure.text.Component;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class PaperPunishmentNotifications {
    private static final String NOTIFY_PERMISSION = "epicpunishments.notify.punishment";
    private static final String FULL_IP_PERMISSION = "epicpunishments.punishment.history.ip";

    private final Plugin plugin;
    private final Server server;
    private final PaperMainThreadExecutor mainThreadExecutor;
    private final ConfigurationService configurations;

    public PaperPunishmentNotifications(
            Plugin plugin,
            PaperMainThreadExecutor mainThreadExecutor,
            ConfigurationService configurations
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.server = plugin.getServer();
        this.mainThreadExecutor = Objects.requireNonNull(mainThreadExecutor, "mainThreadExecutor");
        this.configurations = Objects.requireNonNull(configurations, "configurations");
    }

    public void notifyCommitted(Punishment punishment, String fullTarget, String redactedTarget) {
        Objects.requireNonNull(punishment, "punishment");
        Objects.requireNonNull(fullTarget, "fullTarget");
        Objects.requireNonNull(redactedTarget, "redactedTarget");
        var messages = configurations.current().map(snapshot -> snapshot.messages()).orElse(null);
        if (messages == null) {
            return;
        }
        var actor = punishment.revocation().map(value -> value.actor()).orElse(punishment.issuer());
        String action = punishment.revocation().isPresent() ? "revoked" : "created";
        String reason = punishment.revocation().map(value -> value.reason()).orElse(punishment.reason());
        Component fullMessage = messages.message("punishment.staff-notification", Map.of(
                "action", action,
                "type", punishment.type().name().toLowerCase(Locale.ROOT),
                "target", fullTarget,
                "id", punishment.id().toString(),
                "actor", actor.displayName(),
                "reason", reason
        ));
        Component redactedMessage = fullTarget.equals(redactedTarget) ? fullMessage
                : messages.message("punishment.staff-notification", Map.of(
                        "action", action,
                        "type", punishment.type().name().toLowerCase(Locale.ROOT),
                        "target", redactedTarget,
                        "id", punishment.id().toString(),
                        "actor", actor.displayName(),
                        "reason", reason
                ));
        mainThreadExecutor.execute(() -> {
            server.getConsoleSender().sendMessage(fullMessage);
            for (Player staff : server.getOnlinePlayers()) {
                if (!staff.hasPermission(NOTIFY_PERMISSION)) {
                    continue;
                }
                Component selected = PunishmentNotificationPrivacy.select(fullMessage, redactedMessage,
                        staff.hasPermission(FULL_IP_PERMISSION));
                staff.getScheduler().execute(plugin, () -> staff.sendMessage(selected), () -> { }, 1L);
            }
        });
    }

}
