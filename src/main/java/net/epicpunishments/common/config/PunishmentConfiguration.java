package net.epicpunishments.common.config;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;

public record PunishmentConfiguration(
        Duration maximumDuration,
        int maximumReasonLength,
        int historyPageSize,
        boolean consoleBypassesExempt,
        Set<PunishmentCommandAlias> commandAliases
) {
    public PunishmentConfiguration(
            Duration maximumDuration,
            int maximumReasonLength,
            int historyPageSize,
            boolean consoleBypassesExempt
    ) {
        this(
                maximumDuration,
                maximumReasonLength,
                historyPageSize,
                consoleBypassesExempt,
                Set.of(PunishmentCommandAlias.BAN, PunishmentCommandAlias.MUTE, PunishmentCommandAlias.WARN)
        );
    }

    public PunishmentConfiguration {
        Objects.requireNonNull(maximumDuration, "maximumDuration");
        commandAliases = Set.copyOf(Objects.requireNonNull(commandAliases, "commandAliases"));
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
