package net.epicpunishments.common.observability;

import net.epicpunishments.common.persistence.PersistenceHealth;
import net.epicpunishments.common.persistence.PersistenceStatus;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.assertj.core.api.Assertions.assertThat;

class PluginStatusServiceTest {
    @Test
    void reportsProviderSchemaHealthAndPendingWork() {
        var service = new PluginStatusService(
                () -> Optional.of(status(
                        CompletableFuture.completedFuture("12"),
                        CompletableFuture.completedFuture(PersistenceHealth.HEALTHY)
                )),
                () -> 7
        );

        assertThat(service.status().toCompletableFuture().join()).isEqualTo(new PluginStatus(
                "test-provider", "12", PersistenceHealth.HEALTHY, 7
        ));
    }

    @Test
    void reportsUnavailableWithoutExposingProbeFailures() {
        var service = new PluginStatusService(
                () -> Optional.of(status(
                        CompletableFuture.failedFuture(new IllegalStateException("jdbc:secret")),
                        CompletableFuture.failedFuture(new IllegalStateException("password=secret"))
                )),
                () -> 2
        );

        PluginStatus result = service.status().toCompletableFuture().join();

        assertThat(result).isEqualTo(new PluginStatus(
                "test-provider", "unavailable", PersistenceHealth.UNAVAILABLE, 2
        ));
        assertThat(result.toString()).doesNotContain("secret", "password", "jdbc:");
    }

    @Test
    void reportsPendingStateBeforeAProviderIsSelected() {
        var service = new PluginStatusService(Optional::empty, () -> 1);

        assertThat(service.status().toCompletableFuture().join()).isEqualTo(new PluginStatus(
                "pending", "pending", PersistenceHealth.UNAVAILABLE, 1
        ));
    }

    private static PersistenceStatus status(
            CompletionStage<String> schema,
            CompletionStage<PersistenceHealth> health
    ) {
        return new PersistenceStatus() {
            @Override
            public String providerName() {
                return "test-provider";
            }

            @Override
            public CompletionStage<PersistenceHealth> health() {
                return health;
            }

            @Override
            public CompletionStage<String> schemaVersion() {
                return schema;
            }
        };
    }
}
