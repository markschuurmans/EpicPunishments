package net.epicpunishments.common.config;

import net.epicpunishments.common.execution.TaskExecutor;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class ConfigurationService {
    private final ConfigurationLoader loader;
    private final TaskExecutor executor;
    private final AtomicReference<ConfigurationSnapshot> current = new AtomicReference<>();
    private final AtomicReference<DatabaseConfiguration> startupDatabase = new AtomicReference<>();
    private final AtomicBoolean loadInProgress = new AtomicBoolean();
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final Object publicationLock = new Object();

    public ConfigurationService(ConfigurationLoader loader, TaskExecutor executor) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    public CompletionStage<ConfigurationSnapshot> start() {
        if (current.get() != null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Configuration is already loaded."));
        }
        return load(false);
    }

    public CompletionStage<ConfigurationSnapshot> reload() {
        if (current.get() == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Configuration is not loaded yet."));
        }
        return load(true);
    }

    public Optional<ConfigurationSnapshot> current() {
        return Optional.ofNullable(current.get());
    }

    public void stop() {
        synchronized (publicationLock) {
            stopping.set(true);
            current.set(null);
            startupDatabase.set(null);
        }
    }

    private CompletionStage<ConfigurationSnapshot> load(boolean reload) {
        if (stopping.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Configuration service is stopping."));
        }
        if (!loadInProgress.compareAndSet(false, true)) {
            return CompletableFuture.failedFuture(new IllegalStateException("A configuration load is already running."));
        }

        CompletionStage<ConfigurationSnapshot> load = executor.submit(loader::load).thenApply(snapshot -> {
            synchronized (publicationLock) {
                if (stopping.get()) {
                    throw new IllegalStateException("Configuration service is stopping.");
                }
                if (reload && !Objects.equals(startupDatabase.get(), snapshot.database())) {
                    throw new IllegalStateException("Database settings changed and require a server restart.");
                }
                if (!reload) {
                    startupDatabase.set(snapshot.database());
                }
                current.set(snapshot);
            }
            return snapshot;
        });
        return load.whenComplete((ignored, failure) -> loadInProgress.set(false));
    }
}
