package net.epicpunishments.common.config;

import java.util.Locale;

public enum DatabaseType {
    SQLITE,
    MYSQL,
    POSTGRES;

    static DatabaseType parse(String value) throws ConfigurationException {
        try {
            return valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new ConfigurationException(
                    "database.type must be one of: sqlite, mysql, postgres.",
                    exception
            );
        }
    }
}
