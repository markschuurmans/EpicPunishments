package net.epicpunishments.common.config;

import net.epicpunishments.common.execution.TaskExecutor;
import net.epicpunishments.common.message.MessageCatalog;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigurationServiceTest {
    @Test
    void reloadPublishesAtomicallyAndKeepsThePreviousSnapshotOnFailure() {
        ConfigurationSnapshot initial = snapshot("Initial", Duration.ofSeconds(3));
        var nextLoad = new AtomicReference<LoadResult>(new LoadResult(initial, null));
        ConfigurationLoader loader = () -> {
            LoadResult result = nextLoad.get();
            if (result.failure() != null) {
                throw result.failure();
            }
            return result.snapshot();
        };
        var service = new ConfigurationService(loader, new DirectTaskExecutor());

        assertThat(service.start().toCompletableFuture().join()).isSameAs(initial);
        assertThat(service.current()).containsSame(initial);

        ConfigurationSnapshot databaseChanged = snapshot("Changed database", Duration.ofSeconds(4));
        nextLoad.set(new LoadResult(databaseChanged, null));
        assertThatThrownBy(() -> service.reload().toCompletableFuture().join())
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("Database settings changed and require a server restart.");
        assertThat(service.current()).containsSame(initial);

        ConfigurationSnapshot messagesChanged = snapshot("Reloaded messages", Duration.ofSeconds(3));
        nextLoad.set(new LoadResult(messagesChanged, null));
        assertThat(service.reload().toCompletableFuture().join()).isSameAs(messagesChanged);
        assertThat(service.current()).containsSame(messagesChanged);

        nextLoad.set(new LoadResult(null, new ConfigurationException("Invalid messages.")));
        assertThatThrownBy(() -> service.reload().toCompletableFuture().join())
                .hasRootCauseInstanceOf(ConfigurationException.class);
        assertThat(service.current()).containsSame(messagesChanged);
    }

    @Test
    void stoppingClearsPublishedConfigurationAndRejectsFurtherWork() {
        ConfigurationSnapshot initial = snapshot("Initial", Duration.ofSeconds(3));
        var service = new ConfigurationService(() -> initial, new DirectTaskExecutor());
        service.start().toCompletableFuture().join();

        service.stop();

        assertThat(service.current()).isEmpty();
        assertThatThrownBy(() -> service.reload().toCompletableFuture().join())
                .hasRootCauseInstanceOf(IllegalStateException.class);
    }

    private ConfigurationSnapshot snapshot(String usage, Duration timeout) {
        var messages = new LinkedHashMap<String, String>();
        for (String key : MessageCatalog.REQUIRED_KEYS) {
            messages.put(key, "Message");
        }
        messages.put("command.usage", usage);
        return new ConfigurationSnapshot(
                new DatabaseConfiguration(
                        DatabaseType.SQLITE,
                        timeout,
                        LoginFailurePolicy.DENY,
                        new SqliteConnectionConfiguration(Path.of("database.db"))
                ),
                MessageCatalog.parse(messages)
        );
    }

    private record LoadResult(ConfigurationSnapshot snapshot, ConfigurationException failure) {
    }

    private static final class DirectTaskExecutor implements TaskExecutor {
        @Override
        public <T> CompletionStage<T> submit(Callable<T> task) {
            try {
                return CompletableFuture.completedFuture(task.call());
            } catch (Exception exception) {
                return CompletableFuture.failedFuture(exception);
            }
        }

        @Override
        public int pendingTaskCount() {
            return 0;
        }
    }
}
