package net.epicpunishments.interaction;

import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.concurrent.Executor;

public final class PaperMainThreadExecutor implements Executor, AutoCloseable {
    private final Plugin plugin;
    private boolean closed;

    public PaperMainThreadExecutor(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public synchronized void execute(Runnable command) {
        tryExecute(command);
    }

    public synchronized boolean tryExecute(Runnable command) {
        Objects.requireNonNull(command, "command");
        if (closed) {
            return false;
        }
        plugin.getServer().getGlobalRegionScheduler().execute(plugin, command);
        return true;
    }

    @Override
    public synchronized void close() {
        closed = true;
    }
}
