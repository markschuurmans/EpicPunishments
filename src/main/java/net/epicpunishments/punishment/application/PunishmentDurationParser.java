package net.epicpunishments.punishment.application;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PunishmentDurationParser {
    private static final Pattern DURATION = Pattern.compile("([1-9][0-9]*)(m|h|d)");

    private final Duration maximum;

    public PunishmentDurationParser(Duration maximum) {
        this.maximum = Objects.requireNonNull(maximum, "maximum");
        if (maximum.isNegative() || maximum.isZero()) {
            throw new IllegalArgumentException("maximum must be positive");
        }
    }

    public Optional<Duration> parse(String input) {
        Objects.requireNonNull(input, "input");
        if (input.equalsIgnoreCase("perm")) {
            return Optional.empty();
        }
        Matcher matcher = DURATION.matcher(input);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Duration must be perm or use m, h, or d");
        }
        try {
            long amount = Long.parseLong(matcher.group(1));
            Duration duration = switch (matcher.group(2)) {
                case "m" -> Duration.ofMinutes(amount);
                case "h" -> Duration.ofHours(amount);
                case "d" -> Duration.ofDays(amount);
                default -> throw new IllegalStateException("Unexpected duration unit");
            };
            if (duration.compareTo(maximum) > 0) {
                throw new IllegalArgumentException("Duration exceeds the configured maximum");
            }
            return Optional.of(duration);
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new IllegalArgumentException("Duration is outside the supported range", exception);
        }
    }

    public boolean looksLikeDuration(String input) {
        return input.equalsIgnoreCase("perm") || DURATION.matcher(input).matches();
    }
}
