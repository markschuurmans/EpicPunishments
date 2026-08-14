package net.epicpunishments.bootstrap;

import net.epicpunishments.common.config.ConfigurationException;
import net.epicpunishments.common.config.ConfigurationService;
import net.epicpunishments.common.config.ResourceProvider;
import net.epicpunishments.common.config.YamlConfigurationLoader;
import net.epicpunishments.common.execution.BoundedTaskExecutor;
import net.epicpunishments.interaction.PaperMainThreadExecutor;
import net.epicpunishments.interaction.command.CommandManager;
import net.epicpunishments.interaction.command.EpicPunishmentsCommand;
import net.epicpunishments.interaction.command.PaperMessageDispatcher;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PluginContainer implements AutoCloseable {
    private static final int EXECUTOR_THREADS = 4;
    private static final int EXECUTOR_QUEUE_CAPACITY = 128;
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);

    private final EpicPunishments plugin;
    private final BoundedTaskExecutor taskExecutor;
    private final ConfigurationService configurations;
    private final PaperMainThreadExecutor mainThreadExecutor;
    private final AtomicBoolean stopping = new AtomicBoolean();

    private PluginContainer(
            EpicPunishments plugin,
            BoundedTaskExecutor taskExecutor,
            ConfigurationService configurations,
            PaperMainThreadExecutor mainThreadExecutor
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.taskExecutor = Objects.requireNonNull(taskExecutor, "taskExecutor");
        this.configurations = Objects.requireNonNull(configurations, "configurations");
        this.mainThreadExecutor = Objects.requireNonNull(mainThreadExecutor, "mainThreadExecutor");
    }

    public static PluginContainer create(EpicPunishments plugin) {
        var taskExecutor = new BoundedTaskExecutor(
                EXECUTOR_THREADS,
                EXECUTOR_QUEUE_CAPACITY,
                SHUTDOWN_TIMEOUT,
                "EpicPunishments-io"
        );
        ClassLoader classLoader = plugin.getClass().getClassLoader();
        ResourceProvider resources = classLoader::getResourceAsStream;
        var loader = new YamlConfigurationLoader(
                plugin.getDataFolder().toPath(),
                resources,
                System.getenv()
        );
        var configurations = new ConfigurationService(loader, taskExecutor);
        return new PluginContainer(
                plugin,
                taskExecutor,
                configurations,
                new PaperMainThreadExecutor(plugin)
        );
    }

    public void enable() {
        var dispatcher = new PaperMessageDispatcher(plugin.getServer(), mainThreadExecutor);
        CommandManager.register(plugin, new EpicPunishmentsCommand(
                configurations,
                plugin.getPluginMeta().getVersion(),
                dispatcher,
                plugin.getLogger()
        ));

        configurations.start().whenComplete((snapshot, failure) -> {
            if (stopping.get()) {
                return;
            }
            mainThreadExecutor.execute(() -> {
                if (stopping.get() || !plugin.isEnabled()) {
                    return;
                }
                if (failure != null) {
                    plugin.getLogger().severe("EpicPunishments could not load a safe configuration and will disable. "
                            + safeFailureMessage(failure));
                    plugin.getServer().getPluginManager().disablePlugin(plugin);
                    return;
                }
                plugin.getLogger().info("Configuration and messages loaded; database provider: "
                        + snapshot.database().type().name().toLowerCase(java.util.Locale.ROOT) + '.');
            });
        });
    }

    @Override
    public void close() {
        if (!stopping.compareAndSet(false, true)) {
            return;
        }

        configurations.stop();
        mainThreadExecutor.close();
        plugin.getServer().getGlobalRegionScheduler().cancelTasks(plugin);
        var shutdown = taskExecutor.shutdownGracefully();
        if (!shutdown.terminated()) {
            plugin.getLogger().warning("The EpicPunishments executor did not terminate within "
                    + SHUTDOWN_TIMEOUT.toSeconds() + " seconds.");
        }
        if (shutdown.cancelledTasks() > 0) {
            plugin.getLogger().warning("Cancelled " + shutdown.cancelledTasks()
                    + " queued EpicPunishments task(s) during shutdown.");
        }
    }

    private static String safeFailureMessage(Throwable failure) {
        Throwable cause = failure;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof ConfigurationException || cause instanceof IllegalStateException) {
            return cause.getMessage();
        }
        return "Unexpected " + cause.getClass().getSimpleName() + '.';
    }
}
