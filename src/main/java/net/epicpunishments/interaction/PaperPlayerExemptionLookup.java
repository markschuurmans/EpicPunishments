package net.epicpunishments.interaction;

import net.epicpunishments.punishment.port.PlayerExemptionLookup;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;

public final class PaperPlayerExemptionLookup implements PlayerExemptionLookup {
    private static final String EXEMPT_PERMISSION = "epicpunishments.exempt";

    private final Plugin plugin;
    private final Server server;
    private final PaperMainThreadExecutor mainThreadExecutor;

    public PaperPlayerExemptionLookup(Plugin plugin, PaperMainThreadExecutor mainThreadExecutor) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.server = plugin.getServer();
        this.mainThreadExecutor = Objects.requireNonNull(mainThreadExecutor, "mainThreadExecutor");
    }

    @Override
    public CompletionStage<Boolean> isExempt(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        var result = new CompletableFuture<Boolean>();
        boolean scheduled = mainThreadExecutor.tryExecute(() -> {
            Player player = server.getPlayer(playerId);
            if (player == null) {
                result.complete(false);
                return;
            }
            player.getScheduler().execute(
                    plugin,
                    () -> result.complete(player.hasPermission(EXEMPT_PERMISSION)),
                    () -> result.complete(false),
                    1L
            );
        });
        if (!scheduled) {
            result.completeExceptionally(new RejectedExecutionException("Plugin is stopping"));
        }
        return result;
    }
}
