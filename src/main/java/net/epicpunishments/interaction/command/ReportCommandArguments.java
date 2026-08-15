package net.epicpunishments.interaction.command;

import net.epicpunishments.report.domain.ReportStatus;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class ReportCommandArguments {
    private ReportCommandArguments() {
    }

    public static TargetAndMessage targetAndMessage(String input) {
        Parts parts = splitRequired(input, "A player and reason are required");
        return new TargetAndMessage(parts.first(), parts.remainder());
    }

    public static IdAndMessage idAndRequiredMessage(String input) {
        Parts parts = splitRequired(input, "A report ID and message are required");
        return new IdAndMessage(reportId(parts.first()), parts.remainder());
    }

    public static IdAndOptionalMessage idAndOptionalMessage(String input) {
        String normalized = normalize(input);
        int separator = whitespace(normalized);
        if (separator < 0) {
            return new IdAndOptionalMessage(reportId(normalized), Optional.empty());
        }
        return new IdAndOptionalMessage(
                reportId(normalized.substring(0, separator)),
                Optional.of(normalized.substring(separator).strip()).filter(value -> !value.isEmpty())
        );
    }

    public static UUID reportId(String input) {
        try {
            return UUID.fromString(Objects.requireNonNull(input, "input"));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Report ID must be a UUID", exception);
        }
    }

    public static int page(String input) {
        try {
            int page = Integer.parseInt(Objects.requireNonNull(input, "input"));
            if (page < 1) {
                throw new IllegalArgumentException("Page must be a positive integer");
            }
            return page;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Page must be a positive integer", exception);
        }
    }

    public static StaffList staffList(String input) {
        String normalized = input == null ? "" : input.strip();
        if (normalized.isEmpty()) {
            return new StaffList(Optional.empty(), 1);
        }
        String[] parts = normalized.split("\\s+");
        if (parts.length > 2) {
            throw new IllegalArgumentException("List accepts an optional status and page");
        }
        Optional<ReportStatus> status;
        int page;
        if (isInteger(parts[0])) {
            if (parts.length > 1) {
                throw new IllegalArgumentException("A page number must be the final argument");
            }
            status = Optional.empty();
            page = page(parts[0]);
        } else {
            status = Optional.of(status(parts[0]));
            page = parts.length == 2 ? page(parts[1]) : 1;
        }
        return new StaffList(status, page);
    }

    private static ReportStatus status(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "open" -> ReportStatus.OPEN;
            case "in-review" -> ReportStatus.IN_REVIEW;
            case "resolved" -> ReportStatus.RESOLVED;
            case "dismissed" -> ReportStatus.DISMISSED;
            default -> throw new IllegalArgumentException(
                    "Status must be open, in-review, resolved, or dismissed");
        };
    }

    private static Parts splitRequired(String input, String message) {
        String normalized = normalize(input);
        int separator = whitespace(normalized);
        if (separator < 0 || normalized.substring(separator).isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return new Parts(normalized.substring(0, separator), normalized.substring(separator).strip());
    }

    private static String normalize(String input) {
        Objects.requireNonNull(input, "input");
        String normalized = input.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Report arguments are required");
        }
        return normalized;
    }

    private static int whitespace(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isWhitespace(value.charAt(index))) {
                return index;
            }
        }
        return -1;
    }

    private static boolean isInteger(String value) {
        return value.chars().allMatch(Character::isDigit);
    }

    public record TargetAndMessage(String target, String message) {
    }

    public record IdAndMessage(UUID reportId, String message) {
    }

    public record IdAndOptionalMessage(UUID reportId, Optional<String> message) {
    }

    public record StaffList(Optional<ReportStatus> status, int page) {
    }

    private record Parts(String first, String remainder) {
    }
}
