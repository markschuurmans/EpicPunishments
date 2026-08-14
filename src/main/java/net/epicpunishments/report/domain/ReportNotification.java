package net.epicpunishments.report.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record ReportNotification(
        UUID id,
        UUID recipientId,
        UUID reportId,
        Optional<UUID> responseId,
        Instant createdAt,
        Optional<Instant> readAt
) {
    public ReportNotification {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(recipientId, "recipientId");
        Objects.requireNonNull(reportId, "reportId");
        responseId = Objects.requireNonNull(responseId, "responseId");
        Objects.requireNonNull(createdAt, "createdAt");
        readAt = Objects.requireNonNull(readAt, "readAt");
        readAt.ifPresent(value -> {
            if (value.isBefore(createdAt)) {
                throw new IllegalArgumentException("readAt cannot be before createdAt");
            }
        });
    }

    public boolean isRead() {
        return readAt.isPresent();
    }
}
