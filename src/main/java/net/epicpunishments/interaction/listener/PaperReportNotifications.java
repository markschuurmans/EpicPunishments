package net.epicpunishments.interaction.listener;

import net.epicpunishments.common.config.ConfigurationService;
import net.epicpunishments.common.message.MessageCatalog;
import net.epicpunishments.interaction.PaperMainThreadExecutor;
import net.epicpunishments.report.application.ReportService;
import net.epicpunishments.report.domain.Report;
import net.kyori.adventure.text.Component;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Logger;

public final class PaperReportNotifications {
    private static final String STAFF_PERMISSION = "epicpunishments.notify.report";

    private final Plugin plugin;
    private final Server server;
    private final PaperMainThreadExecutor mainThreadExecutor;
    private final ConfigurationService configurations;
    private final ReportService reports;
    private final Clock clock;
    private final Logger logger;

    public PaperReportNotifications(
            Plugin plugin,
            PaperMainThreadExecutor mainThreadExecutor,
            ConfigurationService configurations,
            ReportService reports,
            Clock clock,
            Logger logger
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.server = plugin.getServer();
        this.mainThreadExecutor = Objects.requireNonNull(mainThreadExecutor, "mainThreadExecutor");
        this.configurations = Objects.requireNonNull(configurations, "configurations");
        this.reports = Objects.requireNonNull(reports, "reports");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public void notifyStaff(Report report) {
        MessageCatalog messages = messages();
        if (messages == null) {
            return;
        }
        Component message = messages.message("report.staff-notification", Map.of(
                "id", report.id().toString(),
                "reporter", report.reporter().nameAtCreation(),
                "reported", report.reported().nameAtCreation()
        ));
        mainThreadExecutor.execute(() -> {
            server.getConsoleSender().sendMessage(message);
            for (Player staff : server.getOnlinePlayers()) {
                if (staff.hasPermission(STAFF_PERMISSION)) {
                    staff.getScheduler().execute(plugin, () -> staff.sendMessage(message), () -> { }, 1L);
                }
            }
        });
    }

    public void notifyReporter(Report report) {
        deliverUnread(report.reporter().playerId());
    }

    public void deliverUnread(UUID playerId) {
        reports.unreadNotifications(playerId).whenComplete((notifications, failure) -> {
            if (failure != null) {
                logger.warning("Could not load report notifications for player " + playerId + '.');
            } else if (!notifications.isEmpty()) {
                deliver(playerId, notifications.stream().map(notification -> notification.reportId()).toList());
            }
        });
    }

    private void deliver(UUID playerId, List<UUID> reportIds) {
        MessageCatalog messages = messages();
        if (messages == null) {
            return;
        }
        List<Component> rendered = reportIds.stream().map(reportId -> messages.message(
                "report.notification", Map.of("id", reportId.toString())
        )).toList();
        mainThreadExecutor.execute(() -> {
            Player player = server.getPlayer(playerId);
            if (player == null) {
                return;
            }
            player.getScheduler().execute(plugin, () -> {
                rendered.forEach(player::sendMessage);
                Instant deliveredAt = clock.instant();
                reports.markNotificationsRead(playerId, deliveredAt).whenComplete((count, failure) -> {
                    if (failure != null) {
                        logger.warning("Could not mark report notifications read for player " + playerId + '.');
                    }
                });
            }, () -> { }, 1L);
        });
    }

    private MessageCatalog messages() {
        return configurations.current().map(snapshot -> snapshot.messages()).orElse(null);
    }
}
