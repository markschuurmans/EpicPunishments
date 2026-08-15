package net.epicpunishments.common.observability;

import net.epicpunishments.common.persistence.PersistenceHealth;
import net.epicpunishments.common.persistence.PersistenceStatus;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

public final class PluginStatusService {
    private static final String PENDING = "pending";
    private static final String UNAVAILABLE = "unavailable";

    private final Supplier<Optional<PersistenceStatus>> persistence;
    private final IntSupplier pendingTaskCount;

    public PluginStatusService(
            Supplier<Optional<PersistenceStatus>> persistence,
            IntSupplier pendingTaskCount
    ) {
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.pendingTaskCount = Objects.requireNonNull(pendingTaskCount, "pendingTaskCount");
    }

    public CompletionStage<PluginStatus> status() {
        Optional<PersistenceStatus> current = persistence.get();
        if (current.isEmpty()) {
            return CompletableFuture.completedFuture(new PluginStatus(
                    PENDING,
                    PENDING,
                    PersistenceHealth.UNAVAILABLE,
                    pendingTaskCount.getAsInt()
            ));
        }

        PersistenceStatus provider = current.orElseThrow();
        CompletionStage<String> schema = provider.schemaVersion()
                .exceptionally(ignored -> UNAVAILABLE);
        CompletionStage<PersistenceHealth> health = provider.health()
                .exceptionally(ignored -> PersistenceHealth.UNAVAILABLE);
        return schema.thenCombine(health, (schemaVersion, healthState) -> new PluginStatus(
                provider.providerName(),
                schemaVersion,
                healthState,
                pendingTaskCount.getAsInt()
        ));
    }
}
