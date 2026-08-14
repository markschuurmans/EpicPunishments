package net.epicpunishments.infrastructure.persistence;

import net.epicpunishments.common.config.DatabaseConfiguration;
import net.epicpunishments.common.config.SqliteConnectionConfiguration;
import net.epicpunishments.common.execution.TaskExecutor;
import net.epicpunishments.common.persistence.PersistenceException;
import net.epicpunishments.common.persistence.PersistenceFailureKind;
import net.epicpunishments.common.persistence.PersistenceProvider;
import net.epicpunishments.infrastructure.persistence.sqlite.SqlitePersistenceProvider;

import java.util.Objects;

public final class PersistenceProviderFactory {
    private PersistenceProviderFactory() {
    }

    public static PersistenceProvider create(DatabaseConfiguration configuration, TaskExecutor taskExecutor) {
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(taskExecutor, "taskExecutor");
        return switch (configuration.type()) {
            case SQLITE -> new SqlitePersistenceProvider(
                    (SqliteConnectionConfiguration) configuration.connection(),
                    configuration.queryTimeout(),
                    taskExecutor
            );
            case MYSQL, POSTGRES -> throw new PersistenceException(
                    PersistenceFailureKind.INVALID_DATA,
                    configuration.type().name() + " persistence is not available in this build"
            );
        };
    }
}
