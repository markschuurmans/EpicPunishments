package net.epicpunishments.report.port;

import net.epicpunishments.common.domain.Actor;
import net.epicpunishments.common.domain.AuditEntry;
import net.epicpunishments.report.domain.Report;
import net.epicpunishments.report.domain.ReportNotification;
import net.epicpunishments.report.domain.ReportResponse;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public interface ReportMutationStore {
    CompletionStage<ReportMutationResult> createReport(Report report, AuditEntry auditEntry);

    CompletionStage<ReportMutationResult> claimReport(
            UUID reportId,
            long expectedVersion,
            Actor assignee,
            Instant claimedAt,
            AuditEntry auditEntry
    );

    CompletionStage<ReportMutationResult> respondToReport(
            UUID reportId,
            long expectedVersion,
            ReportResponse response,
            Optional<ReportNotification> notification,
            AuditEntry auditEntry
    );

    CompletionStage<ReportMutationResult> resolveReport(
            UUID reportId,
            long expectedVersion,
            Instant resolvedAt,
            Optional<ReportResponse> response,
            Optional<ReportNotification> notification,
            AuditEntry auditEntry
    );

    CompletionStage<ReportMutationResult> dismissReport(
            UUID reportId,
            long expectedVersion,
            Instant dismissedAt,
            ReportResponse reason,
            Optional<ReportNotification> notification,
            AuditEntry auditEntry
    );

    CompletionStage<Integer> markNotificationsRead(UUID recipientId, Instant readAt);
}
