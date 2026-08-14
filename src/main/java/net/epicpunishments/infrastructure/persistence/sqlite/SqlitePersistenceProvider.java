package net.epicpunishments.infrastructure.persistence.sqlite;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import net.epicpunishments.common.config.SqliteConnectionConfiguration;
import net.epicpunishments.common.execution.TaskExecutor;
import net.epicpunishments.common.persistence.PersistenceException;
import net.epicpunishments.common.persistence.PersistenceFailureKind;
import net.epicpunishments.common.persistence.PersistenceHealth;
import net.epicpunishments.common.persistence.PersistenceProvider;
import net.epicpunishments.identity.port.LoginAssessmentRepository;
import net.epicpunishments.identity.port.PlayerIdentityRepository;
import net.epicpunishments.punishment.port.ModerationMutationStore;
import net.epicpunishments.punishment.port.PunishmentRepository;
import net.epicpunishments.report.port.ReportMutationStore;
import net.epicpunishments.report.port.ReportRepository;
import org.flywaydb.core.Flyway;
import org.sqlite.SQLiteConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

public final class SqlitePersistenceProvider implements PersistenceProvider {
    private enum State {
        NEW,
        INITIALIZING,
        READY,
        CLOSING,
        CLOSED,
        FAILED
    }

    private final Path databaseFile;
    private final Duration queryTimeout;
    private final TaskExecutor taskExecutor;
    private final SqliteDatabase database;
    private final SqlitePlayerIdentityRepository playerIdentities;
    private final SqlitePunishmentStore punishmentStore;
    private final SqliteReportStore reportStore;

    private State state = State.NEW;
    private CompletableFuture<Void> initialization;
    private CompletableFuture<Void> closing;
    private HikariDataSource dataSource;
    private String currentSchemaVersion = "none";

    public SqlitePersistenceProvider(
            SqliteConnectionConfiguration configuration,
            Duration queryTimeout,
            TaskExecutor taskExecutor
    ) {
        Objects.requireNonNull(configuration, "configuration");
        this.databaseFile = configuration.file();
        this.queryTimeout = Objects.requireNonNull(queryTimeout, "queryTimeout");
        this.taskExecutor = Objects.requireNonNull(taskExecutor, "taskExecutor");
        this.database = new SqliteDatabase(taskExecutor, queryTimeout);
        this.playerIdentities = new SqlitePlayerIdentityRepository(database);
        this.punishmentStore = new SqlitePunishmentStore(database);
        this.reportStore = new SqliteReportStore(database);
    }

    @Override
    public synchronized CompletionStage<Void> initialize() {
        if (initialization != null) {
            return initialization;
        }
        if (state != State.NEW) {
            return CompletableFuture.failedFuture(new PersistenceException(
                    PersistenceFailureKind.SHUTTING_DOWN,
                    "SQLite persistence cannot be initialized after shutdown"
            ));
        }

        state = State.INITIALIZING;
        initialization = new CompletableFuture<>();
        taskExecutor.submit(this::initializeBlocking).whenComplete(this::completeInitialization);
        return initialization;
    }

    @Override
    public String providerName() {
        return "sqlite";
    }

    @Override
    public CompletionStage<PersistenceHealth> health() {
        return database.read(connection -> {
            try (var statement = database.statement(connection);
                 var results = statement.executeQuery("SELECT 1")) {
                if (!results.next() || results.getInt(1) != 1) {
                    return PersistenceHealth.DEGRADED;
                }
            }
            boolean foreignKeys;
            try (var statement = database.statement(connection);
                 var results = statement.executeQuery("PRAGMA foreign_keys")) {
                foreignKeys = results.next() && results.getInt(1) == 1;
            }
            boolean writeAheadLog;
            try (var statement = database.statement(connection);
                 var results = statement.executeQuery("PRAGMA journal_mode")) {
                writeAheadLog = results.next() && "wal".equalsIgnoreCase(results.getString(1));
            }
            boolean boundedBusyWait;
            try (var statement = database.statement(connection);
                 var results = statement.executeQuery("PRAGMA busy_timeout")) {
                boundedBusyWait = results.next() && results.getLong(1) == queryTimeout.toMillis();
            }
            return foreignKeys && writeAheadLog && boundedBusyWait
                    ? PersistenceHealth.HEALTHY
                    : PersistenceHealth.DEGRADED;
        });
    }

    @Override
    public synchronized CompletionStage<String> schemaVersion() {
        if (state != State.READY) {
            return CompletableFuture.failedFuture(new PersistenceException(
                    state == State.CLOSING || state == State.CLOSED
                            ? PersistenceFailureKind.SHUTTING_DOWN
                            : PersistenceFailureKind.UNAVAILABLE,
                    "SQLite schema version is unavailable"
            ));
        }
        return CompletableFuture.completedFuture(currentSchemaVersion);
    }

    @Override
    public PlayerIdentityRepository playerIdentities() {
        return playerIdentities;
    }

    @Override
    public LoginAssessmentRepository loginAssessments() {
        return punishmentStore;
    }

    @Override
    public PunishmentRepository punishments() {
        return punishmentStore;
    }

    @Override
    public ModerationMutationStore moderationMutations() {
        return punishmentStore;
    }

    @Override
    public ReportRepository reports() {
        return reportStore;
    }

    @Override
    public ReportMutationStore reportMutations() {
        return reportStore;
    }

    @Override
    public synchronized CompletionStage<Void> closeAsync() {
        if (closing != null) {
            return closing;
        }
        closing = new CompletableFuture<>();
        database.stopAccepting();

        if (state == State.NEW || state == State.FAILED || state == State.CLOSED) {
            state = State.CLOSED;
            closing.complete(null);
            return closing;
        }
        if (state == State.INITIALIZING) {
            state = State.CLOSING;
            initialization.whenComplete((ignored, failure) -> completeCloseAfterInitialization());
            return closing;
        }

        state = State.CLOSING;
        submitClose();
        return closing;
    }

    private InitializedDatabase initializeBlocking() {
        HikariDataSource opened = null;
        try {
            Path parent = databaseFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            opened = new HikariDataSource(hikariConfiguration());
            Flyway flyway = Flyway.configure(getClass().getClassLoader())
                    .dataSource(opened)
                    .locations("classpath:db/migration/sqlite")
                    .load();
            flyway.migrate();
            var current = flyway.info().current();
            return new InitializedDatabase(opened, current == null ? "none" : current.getVersion().getVersion());
        } catch (IOException | RuntimeException exception) {
            if (opened != null) {
                opened.close();
            }
            throw new PersistenceException(
                    PersistenceFailureKind.UNAVAILABLE,
                    "Could not initialize SQLite persistence",
                    exception
            );
        }
    }

    private synchronized void completeInitialization(InitializedDatabase initialized, Throwable failure) {
        if (failure != null) {
            state = state == State.CLOSING ? State.CLOSED : State.FAILED;
            initialization.completeExceptionally(unwrap(failure));
            if (closing != null) {
                closing.complete(null);
            }
            return;
        }
        dataSource = initialized.dataSource();
        currentSchemaVersion = initialized.schemaVersion();
        if (state == State.CLOSING) {
            dataSource.close();
            dataSource = null;
            state = State.CLOSED;
            initialization.completeExceptionally(new PersistenceException(
                    PersistenceFailureKind.SHUTTING_DOWN,
                    "SQLite persistence stopped during initialization"
            ));
            closing.complete(null);
            return;
        }
        database.start(dataSource);
        state = State.READY;
        initialization.complete(null);
    }

    private synchronized void completeCloseAfterInitialization() {
        if (!closing.isDone() && state != State.CLOSED) {
            submitClose();
        }
    }

    private void submitClose() {
        taskExecutor.submit(() -> {
            HikariDataSource current;
            synchronized (this) {
                current = dataSource;
                dataSource = null;
            }
            if (current != null) {
                current.close();
            }
            return null;
        }).whenComplete((ignored, failure) -> {
            synchronized (this) {
                state = State.CLOSED;
                if (failure == null) {
                    closing.complete(null);
                } else {
                    closing.completeExceptionally(unwrap(failure));
                }
            }
        });
    }

    private HikariConfig hikariConfiguration() {
        int busyTimeoutMillis = Math.toIntExact(queryTimeout.toMillis());
        var sqlite = new SQLiteConfig();
        sqlite.enforceForeignKeys(true);
        sqlite.setJournalMode(SQLiteConfig.JournalMode.WAL);
        sqlite.setBusyTimeout(busyTimeoutMillis);

        var hikari = new HikariConfig();
        hikari.setPoolName("EpicPunishments-SQLite");
        hikari.setJdbcUrl("jdbc:sqlite:" + databaseFile);
        hikari.setDriverClassName("org.sqlite.JDBC");
        hikari.setDataSourceProperties(sqlite.toProperties());
        hikari.setMaximumPoolSize(1);
        hikari.setMinimumIdle(1);
        hikari.setAutoCommit(true);
        hikari.setConnectionTimeout(Math.max(250L, queryTimeout.toMillis()));
        hikari.setValidationTimeout(Math.max(250L, Math.min(5_000L, queryTimeout.toMillis())));
        hikari.setMaxLifetime(0L);
        return hikari;
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private record InitializedDatabase(HikariDataSource dataSource, String schemaVersion) {
    }
}
