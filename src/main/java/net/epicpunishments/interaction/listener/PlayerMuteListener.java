package net.epicpunishments.interaction.listener;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.epicpunishments.common.config.ConfigurationService;
import net.epicpunishments.interaction.PaperMainThreadExecutor;
import net.epicpunishments.punishment.application.SessionPunishmentCache;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class PlayerMuteListener implements Listener {
    private final Plugin plugin;
    private final Server server;
    private final SessionPunishmentCache sessions;
    private final ConfigurationService configurations;
    private final PaperMainThreadExecutor mainThreadExecutor;
    private final Clock clock;

    public PlayerMuteListener(
            Plugin plugin,
            SessionPunishmentCache sessions,
            ConfigurationService configurations,
            PaperMainThreadExecutor mainThreadExecutor,
            Clock clock
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.server = plugin.getServer();
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.configurations = Objects.requireNonNull(configurations, "configurations");
        this.mainThreadExecutor = Objects.requireNonNull(mainThreadExecutor, "mainThreadExecutor");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        sessions.activeMute(playerId, clock.instant()).ifPresent(mute -> {
            event.setCancelled(true);
            var message = configurations.current().map(snapshot -> snapshot.messages().message(
                    "punishment.mute-blocked",
                    Map.of("reason", mute.reason())
            )).orElse(null);
            if (message == null) {
                return;
            }
            mainThreadExecutor.execute(() -> {
                Player player = server.getPlayer(playerId);
                if (player != null) {
                    player.getScheduler().execute(plugin, () -> player.sendMessage(message), () -> { }, 1L);
                }
            });
        });
    }
}
