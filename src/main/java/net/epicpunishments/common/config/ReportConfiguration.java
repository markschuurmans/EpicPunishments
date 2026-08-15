package net.epicpunishments.common.config;

import java.time.Duration;
import java.util.Objects;

public record ReportConfiguration(
        Duration cooldown,
        int maximumReasonLength,
        int maximumResponseLength,
        int pageSize
) {
    public ReportConfiguration {
        Objects.requireNonNull(cooldown, "cooldown");
        if (cooldown.isNegative() || cooldown.compareTo(Duration.ofDays(30)) > 0) {
            throw new IllegalArgumentException("cooldown must be between zero and 30 days");
        }
        if (maximumReasonLength < 1 || maximumReasonLength > 1_024) {
            throw new IllegalArgumentException("maximumReasonLength must be between 1 and 1024");
        }
        if (maximumResponseLength < 1 || maximumResponseLength > 4_096) {
            throw new IllegalArgumentException("maximumResponseLength must be between 1 and 4096");
        }
        if (pageSize < 1 || pageSize > 100) {
            throw new IllegalArgumentException("pageSize must be between 1 and 100");
        }
    }
}
