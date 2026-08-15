package net.epicpunishments.testing;

import net.epicpunishments.common.domain.Actor;
import net.epicpunishments.common.domain.AuditEntry;
import net.epicpunishments.common.domain.Page;
import net.epicpunishments.common.domain.PageRequest;
import net.epicpunishments.common.persistence.PersistenceException;
import net.epicpunishments.common.persistence.PersistenceFailureKind;
import net.epicpunishments.report.domain.Report;
import net.epicpunishments.report.domain.ReportNotification;
import net.epicpunishments.report.domain.ReportResponse;
import net.epicpunishments.report.domain.ReportStatus;
import net.epicpunishments.report.port.ReportMutationResult;
import net.epicpunishments.report.port.ReportMutationStore;
import net.epicpunishments.report.port.ReportRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Predicate;

public final class InMemoryReportStore implements ReportRepository, ReportMutationStore, ReportStoreTestFixture {
    private final Map<UUID, Report> storedReports = new HashMap<>();
    private final List<ReportResponse> storedResponses = new ArrayList<>();
    private final Map<UUID, ReportNotification> storedNotifications = new HashMap<>();
    private final List<AuditEntry> storedAuditEntries = new ArrayList<>();
    private boolean failNextAuditWrite;

    @Override
    public ReportRepository reports() {
        return this;
    }

    @Override
    public ReportMutationStore mutations() {
        return this;
    }

    @Override
    public synchronized List<AuditEntry> auditEntries() {
        return List.copyOf(storedAuditEntries);
    }

    @Override
    public synchronized void failNextAuditWrite() {
        failNextAuditWrite = true;
    }

    @Override
    public synchronized CompletionStage<ReportMutationResult> createReport(Report report, AuditEntry auditEntry) {
        if (consumeAuditFailure()) {
            return failedAuditWrite();
        }
        if (storedReports.containsKey(report.id())) {
            return CompletableFuture.failedFuture(new PersistenceException(
                    PersistenceFailureKind.CONFLICT,
                    "Report already exists"
            ));
        }
        if (storedReports.values().stream().anyMatch(existing -> !existing.isClosed()
                && existing.reporter().playerId().equals(report.reporter().playerId())
                && existing.reported().playerId().equals(report.reported().playerId()))) {
            return CompletableFuture.failedFuture(new PersistenceException(
                    PersistenceFailureKind.CONFLICT,
                    "An open report already exists for these participants"
            ));
        }
        requireMatchingAudit(report.id(), auditEntry);
        storedReports.put(report.id(), report);
        storedAuditEntries.add(auditEntry);
        return CompletableFuture.completedFuture(ReportMutationResult.applied(report));
    }

    @Override
    public synchronized CompletionStage<ReportMutationResult> claimReport(
            UUID reportId,
            long expectedVersion,
            Actor assignee,
            Instant claimedAt,
            AuditEntry auditEntry
    ) {
        ReportMutationResult rejected = rejectMutation(reportId, expectedVersion, report -> report.status() == ReportStatus.OPEN);
        if (rejected != null) {
            return CompletableFuture.completedFuture(rejected);
        }
        if (consumeAuditFailure()) {
            return failedAuditWrite();
        }
        requireMatchingAudit(reportId, auditEntry);
        Report current = storedReports.get(reportId);
        Report updated = update(current, ReportStatus.IN_REVIEW, Optional.of(assignee), claimedAt);
        commit(updated, auditEntry);
        return CompletableFuture.completedFuture(ReportMutationResult.applied(updated));
    }

    @Override
    public synchronized CompletionStage<ReportMutationResult> respondToReport(
            UUID reportId,
            long expectedVersion,
            ReportResponse response,
            Optional<ReportNotification> notification,
            AuditEntry auditEntry
    ) {
        ReportMutationResult rejected = rejectMutation(reportId, expectedVersion, report -> !report.isClosed());
        if (rejected != null) {
            return CompletableFuture.completedFuture(rejected);
        }
        validateRelatedRecords(reportId, response, notification);
        if (consumeAuditFailure()) {
            return failedAuditWrite();
        }
        requireMatchingAudit(reportId, auditEntry);
        Report current = storedReports.get(reportId);
        Report updated = update(current, current.status(), current.assignee(), response.createdAt());
        commit(updated, auditEntry);
        storedResponses.add(response);
        notification.ifPresent(value -> storedNotifications.put(value.id(), value));
        return CompletableFuture.completedFuture(ReportMutationResult.applied(updated));
    }

    @Override
    public synchronized CompletionStage<ReportMutationResult> resolveReport(
            UUID reportId,
            long expectedVersion,
            Instant resolvedAt,
            Optional<ReportResponse> response,
            Optional<ReportNotification> notification,
            AuditEntry auditEntry
    ) {
        return closeReport(
                reportId,
                expectedVersion,
                ReportStatus.RESOLVED,
                resolvedAt,
                response,
                notification,
                auditEntry
        );
    }

    @Override
    public synchronized CompletionStage<ReportMutationResult> dismissReport(
            UUID reportId,
            long expectedVersion,
            Instant dismissedAt,
            ReportResponse reason,
            Optional<ReportNotification> notification,
            AuditEntry auditEntry
    ) {
        return closeReport(
                reportId,
                expectedVersion,
                ReportStatus.DISMISSED,
                dismissedAt,
                Optional.of(reason),
                notification,
                auditEntry
        );
    }

    @Override
    public synchronized CompletionStage<Integer> markNotificationsRead(UUID recipientId, Instant readAt) {
        List<ReportNotification> unread = storedNotifications.values().stream()
                .filter(notification -> notification.recipientId().equals(recipientId) && !notification.isRead())
                .toList();
        if (unread.stream().anyMatch(notification -> readAt.isBefore(notification.createdAt()))) {
            throw new IllegalArgumentException("readAt cannot be before a notification was created");
        }
        for (ReportNotification notification : unread) {
            storedNotifications.put(notification.id(), new ReportNotification(
                        notification.id(),
                        notification.recipientId(),
                        notification.reportId(),
                        notification.responseId(),
                        notification.createdAt(),
                        Optional.of(readAt)
                ));
        }
        return CompletableFuture.completedFuture(unread.size());
    }

    @Override
    public synchronized CompletionStage<Optional<Report>> findById(UUID reportId) {
        return CompletableFuture.completedFuture(Optional.ofNullable(storedReports.get(reportId)));
    }

    @Override
    public synchronized CompletionStage<Optional<Report>> findLatestByReporter(UUID reporterId) {
        return CompletableFuture.completedFuture(storedReports.values().stream()
                .filter(report -> report.reporter().playerId().equals(reporterId))
                .max(Comparator.comparing(Report::createdAt).thenComparing(report -> report.id().toString())));
    }

    @Override
    public synchronized CompletionStage<Optional<Report>> findOpenByParticipants(UUID reporterId, UUID reportedId) {
        return CompletableFuture.completedFuture(storedReports.values().stream()
                .filter(report -> report.reporter().playerId().equals(reporterId))
                .filter(report -> report.reported().playerId().equals(reportedId))
                .filter(report -> !report.isClosed())
                .max(Comparator.comparing(Report::createdAt).thenComparing(report -> report.id().toString())));
    }

    @Override
    public synchronized CompletionStage<Page<Report>> findByReporter(UUID reporterId, PageRequest pageRequest) {
        return CompletableFuture.completedFuture(page(
                report -> report.reporter().playerId().equals(reporterId),
                pageRequest
        ));
    }

    @Override
    public synchronized CompletionStage<Page<Report>> findByStatus(
            Optional<ReportStatus> status,
            PageRequest pageRequest
    ) {
        return CompletableFuture.completedFuture(page(
                report -> status.map(value -> report.status() == value).orElse(true),
                pageRequest
        ));
    }

    @Override
    public synchronized CompletionStage<List<ReportResponse>> findResponses(UUID reportId) {
        return CompletableFuture.completedFuture(storedResponses.stream()
                .filter(response -> response.reportId().equals(reportId))
                .sorted(Comparator.comparing(ReportResponse::createdAt).thenComparing(response -> response.id().toString()))
                .toList());
    }

    @Override
    public synchronized CompletionStage<List<ReportNotification>> findUnreadNotifications(UUID recipientId) {
        return CompletableFuture.completedFuture(storedNotifications.values().stream()
                .filter(notification -> notification.recipientId().equals(recipientId) && !notification.isRead())
                .sorted(Comparator.comparing(ReportNotification::createdAt))
                .toList());
    }

    private CompletionStage<ReportMutationResult> closeReport(
            UUID reportId,
            long expectedVersion,
            ReportStatus status,
            Instant closedAt,
            Optional<ReportResponse> response,
            Optional<ReportNotification> notification,
            AuditEntry auditEntry
    ) {
        ReportMutationResult rejected = rejectMutation(reportId, expectedVersion, report -> !report.isClosed());
        if (rejected != null) {
            return CompletableFuture.completedFuture(rejected);
        }
        response.ifPresent(value -> validateRelatedRecords(reportId, value, notification));
        notification.ifPresent(value -> {
            if (!value.reportId().equals(reportId)
                    || !value.responseId().equals(response.map(ReportResponse::id))) {
                throw new IllegalArgumentException("Notification does not refer to the report response");
            }
        });
        if (consumeAuditFailure()) {
            return failedAuditWrite();
        }
        requireMatchingAudit(reportId, auditEntry);
        Report current = storedReports.get(reportId);
        Report updated = update(current, status, current.assignee(), closedAt);
        commit(updated, auditEntry);
        response.ifPresent(storedResponses::add);
        notification.ifPresent(value -> storedNotifications.put(value.id(), value));
        return CompletableFuture.completedFuture(ReportMutationResult.applied(updated));
    }

    private ReportMutationResult rejectMutation(
            UUID reportId,
            long expectedVersion,
            Predicate<Report> validState
    ) {
        Report current = storedReports.get(reportId);
        if (current == null) {
            return ReportMutationResult.withoutReport(ReportMutationResult.Status.NOT_FOUND);
        }
        if (current.version() != expectedVersion) {
            return ReportMutationResult.withoutReport(ReportMutationResult.Status.VERSION_CONFLICT);
        }
        if (!validState.test(current)) {
            return ReportMutationResult.withoutReport(ReportMutationResult.Status.INVALID_STATE);
        }
        return null;
    }

    private Page<Report> page(Predicate<Report> filter, PageRequest pageRequest) {
        List<Report> matching = storedReports.values().stream()
                .filter(filter)
                .sorted(Comparator.comparing(Report::createdAt).reversed()
                        .thenComparing(report -> report.id().toString()))
                .toList();
        int from = (int) Math.min(pageRequest.offset(), matching.size());
        int to = Math.min(from + pageRequest.size(), matching.size());
        return new Page<>(matching.subList(from, to), pageRequest.page(), pageRequest.size(), matching.size());
    }

    private void commit(Report report, AuditEntry auditEntry) {
        storedReports.put(report.id(), report);
        storedAuditEntries.add(auditEntry);
    }

    private boolean consumeAuditFailure() {
        if (!failNextAuditWrite) {
            return false;
        }
        failNextAuditWrite = false;
        return true;
    }

    private static CompletionStage<ReportMutationResult> failedAuditWrite() {
        return CompletableFuture.failedFuture(new PersistenceException(
                PersistenceFailureKind.TRANSIENT,
                "Induced audit write failure"
        ));
    }

    private static Report update(
            Report current,
            ReportStatus status,
            Optional<Actor> assignee,
            Instant updatedAt
    ) {
        return new Report(
                current.id(),
                current.reporter(),
                current.reported(),
                current.reason(),
                status,
                assignee,
                current.version() + 1,
                current.createdAt(),
                updatedAt
        );
    }

    private static void validateRelatedRecords(
            UUID reportId,
            ReportResponse response,
            Optional<ReportNotification> notification
    ) {
        if (!response.reportId().equals(reportId)) {
            throw new IllegalArgumentException("Response does not refer to the mutated report");
        }
        notification.ifPresent(value -> {
            if (!value.reportId().equals(reportId) || !value.responseId().equals(Optional.of(response.id()))) {
                throw new IllegalArgumentException("Notification does not refer to the response");
            }
        });
    }

    private static void requireMatchingAudit(UUID reportId, AuditEntry auditEntry) {
        if (!auditEntry.entityId().equals(reportId)) {
            throw new IllegalArgumentException("Audit entry does not refer to the mutated report");
        }
    }
}
