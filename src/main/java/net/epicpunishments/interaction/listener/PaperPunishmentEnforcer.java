package net.epicpunishments.interaction.listener;

import net.epicpunishments.common.config.ConfigurationService;
import net.epicpunishments.identity.application.SuccessfulJoinService;
import net.epicpunishments.interaction.PaperMainThreadExecutor;
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

    public PaperPunishmentEnforcer(
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

    public void apply(Punishment punishment) {
        Objects.requireNonNull(punishment, "punishment");
        PlayerPunishmentTarget target = (PlayerPunishmentTarget) punishment.target();
        var messages = configurations.current().map(snapshot -> snapshot.messages()).orElse(null);
        if (messages == null) {
            return;
        }
        mainThreadExecutor.execute(() -> {
            Player player = server.getPlayer(target.playerId());
            if (player == null) {
                return;
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
