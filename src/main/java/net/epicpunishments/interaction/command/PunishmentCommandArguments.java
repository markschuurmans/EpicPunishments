package net.epicpunishments.interaction.command;

import java.util.Objects;

public final class PunishmentCommandArguments {
    private PunishmentCommandArguments() {
    }

    public static TargetAndRemainder withRequiredRemainder(String input) {
        TargetAndRemainder parsed = withOptionalRemainder(input);
        if (parsed.remainder().isEmpty()) {
            throw new IllegalArgumentException("Additional punishment details are required");
        }
        return parsed;
    }

    public static TargetAndRemainder withOptionalRemainder(String input) {
        String normalized = normalize(input);
        int separator = firstWhitespace(normalized);
        if (separator < 0) {
            return new TargetAndRemainder(normalized, "");
        }
        return new TargetAndRemainder(
                normalized.substring(0, separator),
                normalized.substring(separator).strip()
        );
    }

    public static HistoryArguments history(String input) {
        TargetAndRemainder parsed = withOptionalRemainder(input);
        if (parsed.remainder().isEmpty()) {
            return new HistoryArguments(parsed.target(), 1);
        }
        if (firstWhitespace(parsed.remainder()) >= 0) {
            throw new IllegalArgumentException("History accepts only a target and optional page number");
        }
        try {
            int page = Integer.parseInt(parsed.remainder());
            if (page < 1) {
                throw new IllegalArgumentException("Page must be a positive integer");
            }
            return new HistoryArguments(parsed.target(), page);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Page must be a positive integer", exception);
        }
    }

    private static String normalize(String input) {
        Objects.requireNonNull(input, "input");
        String normalized = input.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("A typed player target is required");
        }
        return normalized;
    }

    private static int firstWhitespace(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isWhitespace(value.charAt(index))) {
                return index;
            }
        }
        return -1;
    }

    public record TargetAndRemainder(String target, String remainder) {
        public TargetAndRemainder {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(remainder, "remainder");
        }
    }

    public record HistoryArguments(String target, int page) {
        public HistoryArguments {
            Objects.requireNonNull(target, "target");
            if (page < 1) {
                throw new IllegalArgumentException("page must be positive");
            }
        }
    }
}
