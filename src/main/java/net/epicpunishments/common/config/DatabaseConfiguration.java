package net.epicpunishments.common.config;

import java.time.Duration;
import java.util.Objects;

public record DatabaseConfiguration(
        DatabaseType type,
        Duration queryTimeout,
        LoginFailurePolicy loginFailurePolicy,
        DatabaseConnectionConfiguration connection
) {
    public DatabaseConfiguration {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(queryTimeout, "queryTimeout");
        Objects.requireNonNull(loginFailurePolicy, "loginFailurePolicy");
        Objects.requireNonNull(connection, "connection");
        if (queryTimeout.compareTo(Duration.ofMillis(100)) < 0
                || queryTimeout.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("queryTimeout must be between 100ms and 30s");
        }

        boolean validConnectionType = switch (type) {
            case SQLITE -> connection instanceof SqliteConnectionConfiguration;
            case MYSQL, POSTGRES -> connection instanceof NetworkConnectionConfiguration;
        };
        if (!validConnectionType) {
            throw new IllegalArgumentException("Database type and connection settings do not match.");
        }
    }
}
