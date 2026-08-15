package net.epicpunishments.common.config;

import java.time.Duration;
import java.util.Objects;

public record PunishmentConfiguration(
        Duration maximumDuration,
        int maximumReasonLength,
        int historyPageSize,
        boolean consoleBypassesExempt
) {
    public PunishmentConfiguration {
        Objects.requireNonNull(maximumDuration, "maximumDuration");
        if (maximumDuration.isNegative() || maximumDuration.isZero()) {
            throw new IllegalArgumentException("maximumDuration must be positive");
        }
        if (maximumReasonLength < 1 || maximumReasonLength > 1_024) {
            throw new IllegalArgumentException("maximumReasonLength must be between 1 and 1024");
        }
        if (historyPageSize < 1 || historyPageSize > 100) {
            throw new IllegalArgumentException("historyPageSize must be between 1 and 100");
        }
    }
}
