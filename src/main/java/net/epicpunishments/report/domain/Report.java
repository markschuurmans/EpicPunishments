package net.epicpunishments.report.domain;

import net.epicpunishments.common.domain.Actor;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record Report(
        UUID id,
        ReportParticipant reporter,
        ReportParticipant reported,
        String reason,
        ReportStatus status,
        Optional<Actor> assignee,
        long version,
        Instant createdAt,
        Instant updatedAt
) {
    public Report {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(reporter, "reporter");
        Objects.requireNonNull(reported, "reported");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(status, "status");
        assignee = Objects.requireNonNull(assignee, "assignee");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (reporter.playerId().equals(reported.playerId())) {
            throw new IllegalArgumentException("A player cannot report themselves");
        }
        if (reason.isBlank() || reason.length() > 1_024) {
            throw new IllegalArgumentException("reason must contain between 1 and 1024 characters");
        }
        if (version < 0) {
            throw new IllegalArgumentException("version cannot be negative");
        }
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt cannot be before createdAt");
        }
    }

    public boolean isClosed() {
        return status == ReportStatus.RESOLVED || status == ReportStatus.DISMISSED;
    }
}
