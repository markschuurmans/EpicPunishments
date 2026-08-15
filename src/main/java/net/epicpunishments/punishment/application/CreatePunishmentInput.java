package net.epicpunishments.punishment.application;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

public record CreatePunishmentInput(Optional<Duration> duration, String reason) {
    public CreatePunishmentInput {
        duration = Objects.requireNonNull(duration, "duration");
        reason = Objects.requireNonNull(reason, "reason");
    }

    public static CreatePunishmentInput parse(String details, PunishmentDurationParser durations) {
        Objects.requireNonNull(details, "details");
        Objects.requireNonNull(durations, "durations");
        String normalized = details.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("A reason is required");
        }
        int separator = normalized.indexOf(' ');
        String first = separator < 0 ? normalized : normalized.substring(0, separator);
        if (!durations.looksLikeDuration(first)) {
            return new CreatePunishmentInput(Optional.empty(), normalized);
        }
        if (separator < 0 || normalized.substring(separator + 1).isBlank()) {
            throw new IllegalArgumentException("A reason is required after the duration");
        }
        return new CreatePunishmentInput(
                durations.parse(first),
                normalized.substring(separator + 1).strip()
        );
    }
}
