package net.epicpunishments.report.domain;

import net.epicpunishments.common.domain.Actor;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ReportResponse(
        UUID id,
        UUID reportId,
        Actor administrator,
        String message,
        ResponseVisibility visibility,
        Instant createdAt
) {
    public ReportResponse {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(reportId, "reportId");
        Objects.requireNonNull(administrator, "administrator");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(visibility, "visibility");
        Objects.requireNonNull(createdAt, "createdAt");
        if (message.isBlank() || message.length() > 4_096) {
            throw new IllegalArgumentException("message must contain between 1 and 4096 characters");
        }
    }
}
