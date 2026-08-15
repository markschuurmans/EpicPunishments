package net.epicpunishments.bootstrap;

import net.epicpunishments.common.config.ConfigurationException;
import net.epicpunishments.common.config.ConfigurationService;
import net.epicpunishments.common.config.ConfigurationSnapshot;
import net.epicpunishments.common.config.ResourceProvider;
import net.epicpunishments.common.config.YamlConfigurationLoader;
import net.epicpunishments.common.execution.BoundedTaskExecutor;
import net.epicpunishments.common.persistence.PersistenceException;
import net.epicpunishments.common.persistence.PersistenceFailureKind;
import net.epicpunishments.common.persistence.PersistenceHealth;
import net.epicpunishments.common.persistence.PersistenceProvider;
import net.epicpunishments.infrastructure.persistence.PersistenceProviderFactory;
import net.epicpunishments.identity.application.LoginAssessmentService;
import net.epicpunishments.identity.application.PendingLoginAssessments;
import net.epicpunishments.identity.application.SuccessfulJoinService;
import net.epicpunishments.interaction.PaperMainThreadExecutor;
import net.epicpunishments.interaction.PaperPlayerExemptionLookup;
import net.epicpunishments.interaction.command.CommandManager;
import net.epicpunishments.interaction.command.EpicPunishmentsCommand;
import net.epicpunishments.interaction.command.PaperMessageDispatcher;
import net.epicpunishments.interaction.command.PunishmentCommandRuntime;
import net.epicpunishments.interaction.listener.PaperPlayerNotifications;
import net.epicpunishments.interaction.listener.PaperPunishmentEnforcer;
import net.epicpunishments.interaction.listener.PlayerConnectionListener;
import net.epicpunishments.interaction.listener.PlayerMuteListener;
import net.epicpunishments.punishment.application.AddressPunishmentService;
import net.epicpunishments.punishment.application.AddressTargetParser;
import net.epicpunishments.punishment.application.PlayerPunishmentService;
import net.epicpunishments.punishment.application.PlayerTargetParser;
import net.epicpunishments.punishment.application.PlayerTargetResolver;
import net.epicpunishments.punishment.application.SessionPunishmentCache;
import net.epicpunishments.punishment.application.TargetAuthorizationService;

import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PluginContainer implements AutoCloseable {
    private static final int EXECUTOR_THREADS = 4;
    private static final int EXECUTOR_QUEUE_CAPACITY = 128;
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration PENDING_LOGIN_TTL = Duration.ofSeconds(30);
    private static final int MAXIMUM_PENDING_LOGINS = 2_048;

    private final EpicPunishments plugin;
    private final BoundedTaskExecutor taskExecutor;
    private final ConfigurationService configurations;
    private final PaperMainThreadExecutor mainThreadExecutor;
    private final Clock clock = Clock.systemUTC();
    private final AtomicBoolean stopping = new AtomicBoolean();
    private volatile PersistenceProvider persistenceProvider;
    private volatile PendingLoginAssessments pendingLogins;
    private volatile SessionPunishmentCache sessionPunishments;
    private volatile LoginAssessmentService loginAssessmentService;
    private volatile SuccessfulJoinService successfulJoinService;
    private volatile PlayerPunishmentService playerPunishmentService;
    private volatile AddressPunishmentService addressPunishmentService;
    private volatile PunishmentCommandRuntime punishmentCommandRuntime;

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
                () -> Optional.ofNullable(punishmentCommandRuntime),
                plugin.getServer(),
                clock,
                plugin.getLogger()
        ));

        configurations.start().thenCompose(snapshot -> {
            if (stopping.get()) {
                return CompletableFuture.<ConfigurationSnapshot>failedFuture(
                        new IllegalStateException("Plugin is stopping.")
                );
            }
            PersistenceProvider provider = PersistenceProviderFactory.create(snapshot.database(), taskExecutor);
            persistenceProvider = provider;
            return provider.initialize()
                    .thenCompose(ignored -> provider.health())
                    .thenCompose(health -> health == PersistenceHealth.HEALTHY
                            ? CompletableFuture.completedFuture(snapshot)
                            : CompletableFuture.failedFuture(new PersistenceException(
                                    PersistenceFailureKind.UNAVAILABLE,
                                    "Persistence health check reported " + health.name().toLowerCase(Locale.ROOT)
                            )));
        }).whenComplete((snapshot, failure) -> {
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
                PersistenceProvider provider = persistenceProvider;
                String schemaVersion = provider.schemaVersion().toCompletableFuture().join();
                initializePlayerConnections(snapshot, provider);
                plugin.getLogger().info("Configuration, messages, and database schema loaded; provider: "
                        + provider.providerName() + ", schema version: " + schemaVersion + '.');
            });
        });
    }

    @Override
    public void close() {
        if (!stopping.compareAndSet(false, true)) {
            return;
        }

        LoginAssessmentService logins = loginAssessmentService;
        if (logins != null) {
            logins.stop();
        }
        SuccessfulJoinService joins = successfulJoinService;
        if (joins != null) {
            joins.stop();
        }
        PlayerPunishmentService playerPunishments = playerPunishmentService;
        if (playerPunishments != null) {
            playerPunishments.stop();
        }
        AddressPunishmentService addressPunishments = addressPunishmentService;
        if (addressPunishments != null) {
            addressPunishments.stop();
        }
        punishmentCommandRuntime = null;
        configurations.stop();
        mainThreadExecutor.close();
        plugin.getServer().getGlobalRegionScheduler().cancelTasks(plugin);
        closePersistence();
        var shutdown = taskExecutor.shutdownGracefully();
        if (!shutdown.terminated()) {
            plugin.getLogger().warning("The EpicPunishments executor did not terminate within "
                    + SHUTDOWN_TIMEOUT.toSeconds() + " seconds.");
        }
        if (shutdown.cancelledTasks() > 0) {
            plugin.getLogger().warning("Cancelled " + shutdown.cancelledTasks()
                    + " queued EpicPunishments task(s) during shutdown.");
        }
        PendingLoginAssessments pending = pendingLogins;
        if (pending != null) {
            pending.clear();
        }
        SessionPunishmentCache sessions = sessionPunishments;
        if (sessions != null) {
            sessions.clear();
        }
    }

    private void initializePlayerConnections(ConfigurationSnapshot snapshot, PersistenceProvider provider) {
        var pending = new PendingLoginAssessments(MAXIMUM_PENDING_LOGINS, PENDING_LOGIN_TTL, clock);
        var sessions = new SessionPunishmentCache();
        var loginService = new LoginAssessmentService(
                provider.loginAssessments(),
                pending,
                sessions,
                snapshot.database().queryTimeout(),
                snapshot.database().loginFailurePolicy()
        );
        var joinService = new SuccessfulJoinService(
                provider.playerIdentities(),
                provider.loginAssessments(),
                provider.punishments(),
                pending,
                sessions,
                snapshot.database().loginFailurePolicy(),
                snapshot.database().queryTimeout()
        );
        var notifications = new PaperPlayerNotifications(
                plugin,
                mainThreadExecutor,
                configurations,
                joinService,
                clock,
                plugin.getLogger()
        );
        var playerPunishments = new PlayerPunishmentService(
                new PlayerTargetResolver(provider.playerIdentities(), new PlayerTargetParser()),
                new PaperPlayerExemptionLookup(plugin, mainThreadExecutor),
                new TargetAuthorizationService(() -> configurations.current()
                        .map(current -> current.punishments().consoleBypassesExempt())
                        .orElse(snapshot.punishments().consoleBypassesExempt())),
                provider.punishments(),
                provider.moderationMutations(),
                sessions,
                () -> configurations.current()
                        .map(ConfigurationSnapshot::punishments)
                        .orElse(snapshot.punishments()),
                clock
        );
        var punishmentEnforcer = new PaperPunishmentEnforcer(
                plugin,
                mainThreadExecutor,
                configurations,
                joinService,
                sessions,
                clock,
                plugin.getLogger()
        );
        loginAssessmentService = loginService;
        successfulJoinService = joinService;
        playerPunishmentService = playerPunishments;
        var addressPunishments = new AddressPunishmentService(new AddressTargetParser(), provider.punishments(),
                provider.moderationMutations(), sessions, () -> configurations.current()
                .map(ConfigurationSnapshot::punishments).orElse(snapshot.punishments()), clock);
        addressPunishmentService = addressPunishments;
        punishmentCommandRuntime = new PunishmentCommandRuntime(
                playerPunishments,
                addressPunishments,
                punishmentEnforcer
        );
        pendingLogins = pending;
        sessionPunishments = sessions;
        plugin.getServer().getPluginManager().registerEvents(new PlayerConnectionListener(
                loginService,
                joinService,
                notifications,
                configurations,
                clock,
                plugin.getLogger()
        ), plugin);
        plugin.getServer().getPluginManager().registerEvents(new PlayerMuteListener(
                plugin,
                sessions,
                configurations,
                mainThreadExecutor,
                clock
        ), plugin);
        plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(
                plugin,
                task -> pending.purgeExpired(),
                20L,
                200L
        );
    }

    private void closePersistence() {
        PersistenceProvider provider = persistenceProvider;
        if (provider == null) {
            return;
        }
        try {
            provider.closeAsync().toCompletableFuture().get(SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            plugin.getLogger().warning("Interrupted while closing the persistence provider.");
        } catch (TimeoutException exception) {
            plugin.getLogger().warning("The persistence provider did not close within "
                    + SHUTDOWN_TIMEOUT.toSeconds() + " seconds.");
        } catch (ExecutionException exception) {
            plugin.getLogger().warning("The persistence provider could not close cleanly. "
                    + safeFailureMessage(exception));
        }
    }

    private static String safeFailureMessage(Throwable failure) {
        Throwable cause = failure;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof ConfigurationException
                || cause instanceof PersistenceException
                || cause instanceof IllegalStateException) {
            return cause.getMessage();
        }
        return "Unexpected " + cause.getClass().getSimpleName() + '.';
    }
}
