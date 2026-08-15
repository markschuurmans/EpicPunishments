package net.epicpunishments.interaction.listener;

import net.epicpunishments.common.config.ConfigurationService;
import net.epicpunishments.identity.application.JoinOutcome;
import net.epicpunishments.identity.application.LoginAssessmentService;
import net.epicpunishments.identity.application.LoginAttempt;
import net.epicpunishments.identity.application.LoginDecision;
import net.epicpunishments.identity.application.SuccessfulJoinService;
import net.epicpunishments.identity.domain.PlayerAddress;
import net.epicpunishments.identity.domain.SuccessfulJoin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.net.InetSocketAddress;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

public final class PlayerConnectionListener implements Listener {
    private final LoginAssessmentService logins;
    private final SuccessfulJoinService joins;
    private final PaperPlayerNotifications notifications;
    private final PaperReportNotifications reportNotifications;
    private final ConfigurationService configurations;
    private final Clock clock;
    private final Logger logger;

    public PlayerConnectionListener(
            LoginAssessmentService logins,
            SuccessfulJoinService joins,
            PaperPlayerNotifications notifications,
            PaperReportNotifications reportNotifications,
            ConfigurationService configurations,
            Clock clock,
            Logger logger
    ) {
        this.logins = Objects.requireNonNull(logins, "logins");
        this.joins = Objects.requireNonNull(joins, "joins");
        this.notifications = Objects.requireNonNull(notifications, "notifications");
        this.reportNotifications = Objects.requireNonNull(reportNotifications, "reportNotifications");
        this.configurations = Objects.requireNonNull(configurations, "configurations");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        LoginDecision decision = logins.assess(new LoginAttempt(
                event.getUniqueId(),
                event.getName(),
                PlayerAddress.from(event.getAddress()),
                clock.instant()
        ));
        if (decision.degraded()) {
            logger.warning("Login assessment protection was degraded for player " + event.getUniqueId()
                    + "; applied configured failure policy.");
            notifications.notifyDegradedProtection(event.getUniqueId());
        }
        switch (decision.status()) {
            case ALLOWED -> {
                // Preserve the result selected by earlier login handlers.
            }
            case DENIED_BANNED -> event.disallow(
                    AsyncPlayerPreLoginEvent.Result.KICK_BANNED,
                    messages().message("login.banned", Map.of(
                            "reason", decision.ban().orElseThrow().reason()
                    ))
            );
            case DENIED_TEMPORARY_FAILURE -> event.disallow(
                    AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    messages().message("login.temporary-error")
            );
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        reportNotifications.deliverUnread(playerId);
        String playerName = event.getPlayer().getName();
        InetSocketAddress socketAddress = event.getPlayer().getAddress();
        if (socketAddress == null || socketAddress.getAddress() == null) {
            logger.warning("Could not capture the forwarded address for joined player " + playerId + '.');
            notifications.notifyDegradedProtection(playerId);
            notifications.apply(playerId, temporaryFailure());
            return;
        }

        SuccessfulJoin join = new SuccessfulJoin(
                playerId,
                playerName,
                PlayerAddress.from(socketAddress.getAddress()),
                clock.instant()
        );
        var processing = joins.process(join);
        processing.successfulJoinWrite().whenComplete((ignored, failure) -> {
            if (failure != null) {
                logger.warning("Could not record successful join for player " + playerId + '.');
            }
        });
        processing.assessment().whenComplete((outcome, failure) -> {
            if (failure != null) {
                logger.warning("Could not initialize punishment state for joined player " + playerId + '.');
                notifications.notifyDegradedProtection(playerId);
                notifications.apply(playerId, temporaryFailure());
                return;
            }
            if (outcome.degraded()) {
                logger.warning("Join fallback protection was degraded for player " + playerId
                        + "; applied configured failure policy.");
                notifications.notifyDegradedProtection(playerId);
            }
            notifications.apply(playerId, outcome);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        joins.endSession(event.getPlayer().getUniqueId());
    }

    private net.epicpunishments.common.message.MessageCatalog messages() {
        return configurations.current().orElseThrow(() -> new IllegalStateException(
                "Configuration is unavailable during login handling"
        )).messages();
    }

    private static JoinOutcome temporaryFailure() {
        return new JoinOutcome(
                JoinOutcome.DisconnectReason.TEMPORARY_FAILURE,
                Optional.empty(),
                List.of(),
                true
        );
    }
}
