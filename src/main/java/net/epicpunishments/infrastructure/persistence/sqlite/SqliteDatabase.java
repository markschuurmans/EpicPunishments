package net.epicpunishments.infrastructure.persistence.sqlite;

import com.zaxxer.hikari.HikariDataSource;
import net.epicpunishments.common.execution.TaskExecutor;
import net.epicpunishments.common.persistence.PersistenceException;
import net.epicpunishments.common.persistence.PersistenceFailureKind;
import org.sqlite.ProgressHandler;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;
import org.sqlite.SQLiteConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

final class SqliteDatabase {
    @FunctionalInterface
    interface ConnectionWork<T> {
        T execute(Connection connection) throws SQLException;
    }

    private final TaskExecutor taskExecutor;
    private final long queryTimeoutNanos;
    private final AtomicBoolean accepting = new AtomicBoolean();
    private volatile HikariDataSource dataSource;

    SqliteDatabase(TaskExecutor taskExecutor, Duration queryTimeout) {
        this.taskExecutor = Objects.requireNonNull(taskExecutor, "taskExecutor");
        Objects.requireNonNull(queryTimeout, "queryTimeout");
        this.queryTimeoutNanos = queryTimeout.toNanos();
    }

    void start(HikariDataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        accepting.set(true);
    }

    void stopAccepting() {
        accepting.set(false);
    }

    <T> CompletionStage<T> read(ConnectionWork<T> work) {
        return submit(work, false);
    }

    <T> CompletionStage<T> transaction(ConnectionWork<T> work) {
        return submit(work, true);
    }

    PreparedStatement prepare(Connection connection, String sql) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        return statement;
    }

    Statement statement(Connection connection) throws SQLException {
        Statement statement = connection.createStatement();
        return statement;
    }

    private <T> CompletionStage<T> submit(ConnectionWork<T> work, boolean transactional) {
        Objects.requireNonNull(work, "work");
        if (!accepting.get()) {
            return CompletableFuture.failedFuture(stoppingFailure());
        }

        CompletionStage<T> submitted = taskExecutor.submit(() -> {
            if (!accepting.get()) {
                throw stoppingFailure();
            }
            HikariDataSource current = dataSource;
            if (current == null || current.isClosed()) {
                throw new PersistenceException(PersistenceFailureKind.UNAVAILABLE, "SQLite is not available");
            }
            try (Connection connection = current.getConnection()) {
                long deadline = System.nanoTime() + queryTimeoutNanos;
                Connection sqliteConnection = connection.unwrap(SQLiteConnection.class);
                ProgressHandler.setHandler(sqliteConnection, 1_000, new ProgressHandler() {
                    @Override
                    protected int progress() {
                        return System.nanoTime() - deadline >= 0L ? 1 : 0;
                    }
                });
                try {
                    return transactional ? inTransaction(connection, work) : work.execute(connection);
                } finally {
                    try {
                        ProgressHandler.clearHandler(sqliteConnection);
                    } catch (SQLException cleanupFailure) {
                        current.evictConnection(connection);
                    }
                }
            } catch (SQLException exception) {
                throw classify(exception);
            }
        });

        var result = new CompletableFuture<T>();
        submitted.whenComplete((value, failure) -> {
            if (failure == null) {
                result.complete(value);
                return;
            }
            Throwable cause = unwrap(failure);
            if (cause instanceof RejectedExecutionException) {
                result.completeExceptionally(stoppingFailure());
            } else {
                result.completeExceptionally(cause);
            }
        });
        return result;
    }

    private static <T> T inTransaction(Connection connection, ConnectionWork<T> work) throws SQLException {
        connection.setAutoCommit(false);
        try {
            T result = work.execute(connection);
            connection.commit();
            return result;
        } catch (SQLException | RuntimeException exception) {
            try {
                connection.rollback();
            } catch (SQLException rollbackFailure) {
                exception.addSuppressed(rollbackFailure);
            }
            throw exception;
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException ignored) {
                // The pooled connection will be discarded if it cannot be reset.
            }
        }
    }

    private static PersistenceException classify(SQLException exception) {
        String message = exception.getMessage();
        if (message != null && message.contains("Induced audit write failure")) {
            return new PersistenceException(PersistenceFailureKind.TRANSIENT, "Induced audit write failure");
        }
        if (exception instanceof SQLiteException sqliteException) {
            SQLiteErrorCode code = sqliteException.getResultCode();
            if (code == SQLiteErrorCode.SQLITE_BUSY || code == SQLiteErrorCode.SQLITE_LOCKED) {
                return new PersistenceException(PersistenceFailureKind.TRANSIENT, "SQLite is temporarily busy", exception);
            }
            if (code == SQLiteErrorCode.SQLITE_CONSTRAINT
                    || code.name().startsWith("SQLITE_CONSTRAINT_")) {
                return new PersistenceException(PersistenceFailureKind.CONFLICT, "SQLite constraint failed", exception);
            }
            if (code == SQLiteErrorCode.SQLITE_INTERRUPT) {
                return new PersistenceException(PersistenceFailureKind.TIMEOUT, "SQLite query timed out", exception);
            }
            if (code == SQLiteErrorCode.SQLITE_CANTOPEN || code == SQLiteErrorCode.SQLITE_NOTADB) {
                return new PersistenceException(PersistenceFailureKind.UNAVAILABLE, "SQLite is unavailable", exception);
            }
        }
        return new PersistenceException(PersistenceFailureKind.UNKNOWN, "SQLite operation failed", exception);
    }

    private static PersistenceException stoppingFailure() {
        return new PersistenceException(PersistenceFailureKind.SHUTTING_DOWN, "SQLite persistence is stopping");
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
