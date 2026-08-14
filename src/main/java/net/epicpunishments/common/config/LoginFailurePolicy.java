package net.epicpunishments.common.config;

import java.util.Locale;

public enum LoginFailurePolicy {
    DENY,
    ALLOW_WITH_CACHE;

    static LoginFailurePolicy parse(String value) throws ConfigurationException {
        try {
            return valueOf(value.replace('-', '_').toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new ConfigurationException(
                    "database.login-failure-policy must be deny or allow-with-cache.",
                    exception
            );
        }
    }
}
