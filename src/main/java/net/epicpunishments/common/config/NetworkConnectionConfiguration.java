package net.epicpunishments.common.config;

import java.util.Objects;

public record NetworkConnectionConfiguration(
        String host,
        int port,
        String database,
        String username,
        String password,
        int poolSize
) implements DatabaseConnectionConfiguration {
    public NetworkConnectionConfiguration {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(password, "password");
        if (host.isBlank()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
        if (database.isBlank()) {
            throw new IllegalArgumentException("database must not be blank");
        }
        if (username.isBlank()) {
            throw new IllegalArgumentException("username must not be blank");
        }
        if (poolSize < 1 || poolSize > 64) {
            throw new IllegalArgumentException("poolSize must be between 1 and 64");
        }
    }

    @Override
    public String toString() {
        return "NetworkConnectionConfiguration[host=" + host
                + ", port=" + port
                + ", database=" + database
                + ", username=" + username
                + ", password=<redacted>"
                + ", poolSize=" + poolSize + ']';
    }
}
