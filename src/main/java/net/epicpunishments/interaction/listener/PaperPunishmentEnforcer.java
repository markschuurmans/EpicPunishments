package net.epicpunishments.interaction.listener;

import net.epicpunishments.common.config.ConfigurationService;
import net.epicpunishments.identity.application.SuccessfulJoinService;
import net.epicpunishments.interaction.PaperMainThreadExecutor;
import net.epicpunishments.punishment.application.SessionPunishmentCache;
import net.epicpunishments.punishment.domain.AddressPunishmentTarget;
import net.epicpunishments.punishment.domain.PlayerPunishmentTarget;
import net.epicpunishments.punishment.domain.Punishment;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.plugin.Plugin;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Logger;

public final class PaperPunishmentEnforcer {
    private final Plugin plugin;
    private final Server server;
    private final PaperMainThreadExecutor mainThreadExecutor;
    private final ConfigurationService configurations;
    private final SuccessfulJoinService joins;
    private final Clock clock;
    private final Logger logger;
    private final SessionPunishmentCache sessions;

    public PaperPunishmentEnforcer(
            Plugin plugin,
            PaperMainThreadExecutor mainThreadExecutor,
            ConfigurationService configurations,
            SuccessfulJoinService joins,
            SessionPunishmentCache sessions,
            Clock clock,
            Logger logger
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.server = plugin.getServer();
        this.mainThreadExecutor = Objects.requireNonNull(mainThreadExecutor, "mainThreadExecutor");
        this.configurations = Objects.requireNonNull(configurations, "configurations");
        this.joins = Objects.requireNonNull(joins, "joins");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public void apply(Punishment punishment) {
        Objects.requireNonNull(punishment, "punishment");
        var messages = configurations.current().map(snapshot -> snapshot.messages()).orElse(null);
        if (messages == null) {
            return;
        }
        mainThreadExecutor.execute(() -> {
            java.util.Set<UUID> affected = punishment.target() instanceof PlayerPunishmentTarget target
                    ? java.util.Set.of(target.playerId())
                    : sessions.playersAt(((AddressPunishmentTarget) punishment.target()).address());
            for (UUID playerId : affected) {
                Player player = server.getPlayer(playerId);
                if (player == null) {
                    continue;
                }
                player.getScheduler().execute(plugin, () -> {
                    switch (punishment.type()) {
                        case BAN -> player.kick(messages.message("login.banned", Map.of(
                                "reason", punishment.reason()
                        )), PlayerKickEvent.Cause.PLUGIN);
                        case MUTE -> player.sendMessage(messages.message("punishment.muted", Map.of(
                                "reason", punishment.reason()
                        )));
                        case WARNING -> {
                            player.sendMessage(messages.message("warning.received", Map.of(
                                    "reason", punishment.reason()
                            )));
                            recordWarningDelivery(punishment.id(), player.getUniqueId());
                        }
                    }
                }, () -> { }, 1L);
            }
        });
    }

    private void recordWarningDelivery(UUID punishmentId, UUID playerId) {
        joins.recordWarningDelivery(punishmentId, playerId, clock.instant())
                .whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        logger.warning("Could not record warning delivery " + punishmentId
                                + " for player " + playerId + '.');
                    }
                });
    }
}
