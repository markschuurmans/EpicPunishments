package net.epicpunishments.interaction.listener;

import net.epicpunishments.common.config.ConfigurationService;
import net.epicpunishments.common.message.MessageCatalog;
import net.epicpunishments.identity.application.JoinOutcome;
import net.epicpunishments.identity.application.SuccessfulJoinService;
import net.epicpunishments.interaction.PaperMainThreadExecutor;
import net.kyori.adventure.text.Component;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.plugin.Plugin;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Logger;

public final class PaperPlayerNotifications {
    private final Plugin plugin;
    private final Server server;
    private final PaperMainThreadExecutor mainThreadExecutor;
    private final ConfigurationService configurations;
    private final SuccessfulJoinService joins;
    private final Clock clock;
    private final Logger logger;

    public PaperPlayerNotifications(
            Plugin plugin,
            PaperMainThreadExecutor mainThreadExecutor,
            ConfigurationService configurations,
            SuccessfulJoinService joins,
            Clock clock,
            Logger logger
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.server = plugin.getServer();
        this.mainThreadExecutor = Objects.requireNonNull(mainThreadExecutor, "mainThreadExecutor");
        this.configurations = Objects.requireNonNull(configurations, "configurations");
        this.joins = Objects.requireNonNull(joins, "joins");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public void apply(UUID playerId, JoinOutcome outcome) {
        MessageCatalog messages = configurations.current().map(snapshot -> snapshot.messages()).orElse(null);
        if (messages == null) {
            return;
        }
        Component disconnect = switch (outcome.disconnectReason()) {
            case NONE -> null;
            case BANNED -> messages.message("login.banned", Map.of(
                    "reason", outcome.ban().orElseThrow().reason()
            ));
            case TEMPORARY_FAILURE -> messages.message("login.temporary-error");
        };
        List<WarningMessage> warnings = outcome.warnings().stream()
                .map(warning -> new WarningMessage(
                        warning.id(),
                        messages.message("warning.received", Map.of("reason", warning.reason()))
                ))
                .toList();

        mainThreadExecutor.execute(() -> {
            Player player = server.getPlayer(playerId);
            if (player == null) {
                return;
            }
            player.getScheduler().execute(plugin, () -> {
                if (disconnect != null) {
                    player.kick(disconnect, PlayerKickEvent.Cause.PLUGIN);
                    return;
                }
                for (WarningMessage warning : warnings) {
                    player.sendMessage(warning.message());
                    joins.recordWarningDelivery(warning.punishmentId(), playerId, clock.instant())
                            .whenComplete((inserted, failure) -> {
                                if (failure != null) {
                                    logger.warning("Could not record warning delivery " + warning.punishmentId()
                                            + " for player " + playerId + '.');
                                }
                            });
                }
            }, () -> { }, 1L);
        });
    }

    public void notifyDegradedProtection(UUID assessedPlayerId) {
        MessageCatalog messages = configurations.current().map(snapshot -> snapshot.messages()).orElse(null);
        if (messages == null) {
            return;
        }
        Component message = messages.message(
                "login.degraded-warning",
                Map.of("player-id", assessedPlayerId.toString())
        );
        mainThreadExecutor.execute(() -> {
            server.getConsoleSender().sendMessage(message);
            for (Player staff : server.getOnlinePlayers()) {
                if (staff.hasPermission("epicpunishments.notify.degraded")) {
                    staff.getScheduler().execute(plugin, () -> staff.sendMessage(message), () -> { }, 1L);
                }
            }
        });
    }

    private record WarningMessage(UUID punishmentId, Component message) {
    }
}
