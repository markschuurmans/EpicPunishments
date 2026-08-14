package net.epicpunishments.infrastructure.persistence.sqlite;

import net.epicpunishments.common.domain.Actor;
import net.epicpunishments.common.domain.AuditEntry;
import net.epicpunishments.common.domain.Page;
import net.epicpunishments.common.domain.PageRequest;
import net.epicpunishments.report.domain.Report;
import net.epicpunishments.report.domain.ReportNotification;
import net.epicpunishments.report.domain.ReportResponse;
import net.epicpunishments.report.domain.ReportStatus;
import net.epicpunishments.report.domain.ResponseVisibility;
import net.epicpunishments.report.port.ReportMutationResult;
import net.epicpunishments.report.port.ReportMutationStore;
import net.epicpunishments.report.port.ReportRepository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Predicate;

final class SqliteReportStore implements ReportRepository, ReportMutationStore {
    private static final String REPORT_COLUMNS = """
            SELECT report_uuid, reporter_uuid, reporter_name, reported_uuid, reported_name,
                   reason, status, assignee_type, assignee_player_uuid, assignee_display_name,
                   version, created_at, updated_at
            FROM reports
            """;

    private final SqliteDatabase database;

    SqliteReportStore(SqliteDatabase database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    @Override
    public CompletionStage<ReportMutationResult> createReport(Report report, AuditEntry auditEntry) {
        Objects.requireNonNull(report, "report");
        requireMatchingAudit(report.id(), auditEntry);
        return database.transaction(connection -> {
            insertReport(connection, report);
            SqliteMappings.insertAudit(database, connection, auditEntry);
            return ReportMutationResult.applied(report);
        });
    }

    @Override
    public CompletionStage<ReportMutationResult> claimReport(
            UUID reportId,
            long expectedVersion,
            Actor assignee,
            Instant claimedAt,
            AuditEntry auditEntry
    ) {
        Objects.requireNonNull(assignee, "assignee");
        Objects.requireNonNull(claimedAt, "claimedAt");
        requireMatchingAudit(reportId, auditEntry);
        return database.transaction(connection -> mutate(
                connection,
                reportId,
                expectedVersion,
                report -> report.status() == ReportStatus.OPEN,
                ReportStatus.IN_REVIEW,
                Optional.of(assignee),
                claimedAt,
                Optional.empty(),
                Optional.empty(),
                auditEntry
        ));
    }

    @Override
    public CompletionStage<ReportMutationResult> respondToReport(
            UUID reportId,
            long expectedVersion,
            ReportResponse response,
            Optional<ReportNotification> notification,
            AuditEntry auditEntry
    ) {
        Objects.requireNonNull(response, "response");
        notification = Objects.requireNonNull(notification, "notification");
        validateRelatedRecords(reportId, response, notification);
        requireMatchingAudit(reportId, auditEntry);
        Optional<ReportNotification> capturedNotification = notification;
        return database.transaction(connection -> {
            Optional<Report> current = findById(connection, reportId);
            ReportMutationResult rejected = reject(current, expectedVersion, report -> !report.isClosed());
            if (rejected != null) {
                return rejected;
            }
            Report report = current.orElseThrow();
            return applyMutation(
                    connection,
                    report,
                    report.status(),
                    report.assignee(),
                    response.createdAt(),
                    Optional.of(response),
                    capturedNotification,
                    auditEntry
            );
        });
    }

    @Override
    public CompletionStage<ReportMutationResult> resolveReport(
            UUID reportId,
            long expectedVersion,
            Instant resolvedAt,
            Optional<ReportResponse> response,
            Optional<ReportNotification> notification,
            AuditEntry auditEntry
    ) {
        Objects.requireNonNull(resolvedAt, "resolvedAt");
        return closeReport(
                reportId,
                expectedVersion,
                ReportStatus.RESOLVED,
                resolvedAt,
                Objects.requireNonNull(response, "response"),
                Objects.requireNonNull(notification, "notification"),
                auditEntry
        );
    }

    @Override
    public CompletionStage<ReportMutationResult> dismissReport(
            UUID reportId,
            long expectedVersion,
            Instant dismissedAt,
            ReportResponse reason,
            Optional<ReportNotification> notification,
            AuditEntry auditEntry
    ) {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(dismissedAt, "dismissedAt");
        return closeReport(
                reportId,
                expectedVersion,
                ReportStatus.DISMISSED,
                dismissedAt,
                Optional.of(reason),
                Objects.requireNonNull(notification, "notification"),
                auditEntry
        );
    }

    @Override
    public CompletionStage<Integer> markNotificationsRead(UUID recipientId, Instant readAt) {
        Objects.requireNonNull(recipientId, "recipientId");
        Objects.requireNonNull(readAt, "readAt");
        return database.transaction(connection -> {
            try (PreparedStatement check = database.prepare(connection, """
                    SELECT 1 FROM report_notifications
                    WHERE recipient_uuid = ? AND read_at IS NULL AND created_at > ?
                    LIMIT 1
                    """)) {
                check.setString(1, SqliteMappings.uuid(recipientId));
                check.setString(2, SqliteMappings.instant(readAt));
                try (ResultSet results = check.executeQuery()) {
                    if (results.next()) {
                        throw new IllegalArgumentException("readAt cannot be before a notification was created");
                    }
                }
            }
            try (PreparedStatement update = database.prepare(connection, """
                    UPDATE report_notifications SET read_at = ?
                    WHERE recipient_uuid = ? AND read_at IS NULL
                    """)) {
                update.setString(1, SqliteMappings.instant(readAt));
                update.setString(2, SqliteMappings.uuid(recipientId));
                return update.executeUpdate();
            }
        });
    }

    @Override
    public CompletionStage<Optional<Report>> findById(UUID reportId) {
        Objects.requireNonNull(reportId, "reportId");
        return database.read(connection -> findById(connection, reportId));
    }

    @Override
    public CompletionStage<Page<Report>> findByReporter(UUID reporterId, PageRequest pageRequest) {
        Objects.requireNonNull(reporterId, "reporterId");
        Objects.requireNonNull(pageRequest, "pageRequest");
        return database.read(connection -> page(
                connection,
                "reporter_uuid = ?",
                statement -> statement.setString(1, SqliteMappings.uuid(reporterId)),
                1,
                pageRequest
        ));
    }

    @Override
    public CompletionStage<Page<Report>> findByStatus(Optional<ReportStatus> status, PageRequest pageRequest) {
        status = Objects.requireNonNull(status, "status");
        Objects.requireNonNull(pageRequest, "pageRequest");
        Optional<ReportStatus> capturedStatus = status;
        return database.read(connection -> capturedStatus.isPresent()
                ? page(
                        connection,
                        "status = ?",
                        statement -> statement.setString(1, capturedStatus.orElseThrow().name()),
                        1,
                        pageRequest
                )
                : page(connection, "1 = 1", statement -> { }, 0, pageRequest));
    }

    @Override
    public CompletionStage<List<ReportResponse>> findResponses(UUID reportId) {
        Objects.requireNonNull(reportId, "reportId");
        return database.read(connection -> {
            var responses = new ArrayList<ReportResponse>();
            try (PreparedStatement statement = database.prepare(connection, """
                    SELECT response_uuid, report_uuid, administrator_type, administrator_player_uuid,
                           administrator_display_name, message, visibility, created_at
                    FROM report_responses
                    WHERE report_uuid = ?
                    ORDER BY created_at, response_uuid
                    """)) {
                statement.setString(1, SqliteMappings.uuid(reportId));
                try (ResultSet results = statement.executeQuery()) {
                    while (results.next()) {
                        responses.add(readResponse(results));
                    }
                }
            }
            return List.copyOf(responses);
        });
    }

    @Override
    public CompletionStage<List<ReportNotification>> findUnreadNotifications(UUID recipientId) {
        Objects.requireNonNull(recipientId, "recipientId");
        return database.read(connection -> {
            var notifications = new ArrayList<ReportNotification>();
            try (PreparedStatement statement = database.prepare(connection, """
                    SELECT notification_uuid, recipient_uuid, report_uuid, response_uuid, created_at, read_at
                    FROM report_notifications
                    WHERE recipient_uuid = ? AND read_at IS NULL
                    ORDER BY created_at, notification_uuid
                    """)) {
                statement.setString(1, SqliteMappings.uuid(recipientId));
                try (ResultSet results = statement.executeQuery()) {
                    while (results.next()) {
                        notifications.add(readNotification(results));
                    }
                }
            }
            return List.copyOf(notifications);
        });
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
        Objects.requireNonNull(reportId, "reportId");
        if (response.isEmpty() && notification.isPresent()) {
            throw new IllegalArgumentException("A response notification requires a response");
        }
        response.ifPresent(value -> validateRelatedRecords(reportId, value, notification));
        requireMatchingAudit(reportId, auditEntry);
        return database.transaction(connection -> {
            Optional<Report> current = findById(connection, reportId);
            ReportMutationResult rejected = reject(current, expectedVersion, report -> !report.isClosed());
            if (rejected != null) {
                return rejected;
            }
            Report report = current.orElseThrow();
            return applyMutation(
                    connection,
                    report,
                    status,
                    report.assignee(),
                    closedAt,
                    response,
                    notification,
                    auditEntry
            );
        });
    }

    private ReportMutationResult mutate(
            Connection connection,
            UUID reportId,
            long expectedVersion,
            Predicate<Report> validState,
            ReportStatus status,
            Optional<Actor> assignee,
            Instant updatedAt,
            Optional<ReportResponse> response,
            Optional<ReportNotification> notification,
            AuditEntry auditEntry
    ) throws SQLException {
        Optional<Report> current = findById(connection, reportId);
        ReportMutationResult rejected = reject(current, expectedVersion, validState);
        if (rejected != null) {
            return rejected;
        }
        return applyMutation(
                connection,
                current.orElseThrow(),
                status,
                assignee,
                updatedAt,
                response,
                notification,
                auditEntry
        );
    }

    private ReportMutationResult applyMutation(
            Connection connection,
            Report current,
            ReportStatus status,
            Optional<Actor> assignee,
            Instant updatedAt,
            Optional<ReportResponse> response,
            Optional<ReportNotification> notification,
            AuditEntry auditEntry
    ) throws SQLException {
        Report updated = new Report(
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
        if (!updateReport(connection, updated, current.version())) {
            return ReportMutationResult.withoutReport(ReportMutationResult.Status.VERSION_CONFLICT);
        }
        if (response.isPresent()) {
            insertResponse(connection, response.orElseThrow());
        }
        if (notification.isPresent()) {
            insertNotification(connection, notification.orElseThrow());
        }
        SqliteMappings.insertAudit(database, connection, auditEntry);
        return ReportMutationResult.applied(updated);
    }

    private Optional<Report> findById(Connection connection, UUID reportId) throws SQLException {
        try (PreparedStatement statement = database.prepare(connection, REPORT_COLUMNS + " WHERE report_uuid = ?")) {
            statement.setString(1, SqliteMappings.uuid(reportId));
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? Optional.of(SqliteMappings.report(results)) : Optional.empty();
            }
        }
    }

    private Page<Report> page(
            Connection connection,
            String predicate,
            StatementBinder binder,
            int predicateParameterCount,
            PageRequest pageRequest
    ) throws SQLException {
        long total;
        try (PreparedStatement count = database.prepare(connection, "SELECT count(*) FROM reports WHERE " + predicate)) {
            binder.bind(count);
            try (ResultSet results = count.executeQuery()) {
                results.next();
                total = results.getLong(1);
            }
        }
        var reports = new ArrayList<Report>();
        try (PreparedStatement select = database.prepare(connection,
                REPORT_COLUMNS + " WHERE " + predicate + " ORDER BY created_at DESC, report_uuid LIMIT ? OFFSET ?")) {
            binder.bind(select);
            select.setInt(predicateParameterCount + 1, pageRequest.size());
            select.setLong(predicateParameterCount + 2, pageRequest.offset());
            try (ResultSet results = select.executeQuery()) {
                while (results.next()) {
                    reports.add(SqliteMappings.report(results));
                }
            }
        }
        return new Page<>(reports, pageRequest.page(), pageRequest.size(), total);
    }

    private void insertReport(Connection connection, Report report) throws SQLException {
        try (PreparedStatement statement = database.prepare(connection, """
                INSERT INTO reports (
                    report_uuid, reporter_uuid, reporter_name, reported_uuid, reported_name,
                    reason, status, assignee_type, assignee_player_uuid, assignee_display_name,
                    version, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, SqliteMappings.uuid(report.id()));
            statement.setString(2, SqliteMappings.uuid(report.reporter().playerId()));
            statement.setString(3, report.reporter().nameAtCreation());
            statement.setString(4, SqliteMappings.uuid(report.reported().playerId()));
            statement.setString(5, report.reported().nameAtCreation());
            statement.setString(6, report.reason());
            statement.setString(7, report.status().name());
            bindOptionalActor(statement, 8, report.assignee());
            statement.setLong(11, report.version());
            statement.setString(12, SqliteMappings.instant(report.createdAt()));
            statement.setString(13, SqliteMappings.instant(report.updatedAt()));
            statement.executeUpdate();
        }
    }

    private boolean updateReport(Connection connection, Report report, long expectedVersion) throws SQLException {
        try (PreparedStatement statement = database.prepare(connection, """
                UPDATE reports
                SET status = ?, assignee_type = ?, assignee_player_uuid = ?, assignee_display_name = ?,
                    version = ?, updated_at = ?
                WHERE report_uuid = ? AND version = ?
                """)) {
            statement.setString(1, report.status().name());
            bindOptionalActor(statement, 2, report.assignee());
            statement.setLong(5, report.version());
            statement.setString(6, SqliteMappings.instant(report.updatedAt()));
            statement.setString(7, SqliteMappings.uuid(report.id()));
            statement.setLong(8, expectedVersion);
            return statement.executeUpdate() == 1;
        }
    }

    private void insertResponse(Connection connection, ReportResponse response) throws SQLException {
        try (PreparedStatement statement = database.prepare(connection, """
                INSERT INTO report_responses (
                    response_uuid, report_uuid, administrator_type, administrator_player_uuid,
                    administrator_display_name, message, visibility, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, SqliteMappings.uuid(response.id()));
            statement.setString(2, SqliteMappings.uuid(response.reportId()));
            SqliteMappings.bindActor(statement, 3, response.administrator());
            statement.setString(6, response.message());
            statement.setString(7, response.visibility().name());
            statement.setString(8, SqliteMappings.instant(response.createdAt()));
            statement.executeUpdate();
        }
    }

    private void insertNotification(Connection connection, ReportNotification notification) throws SQLException {
        try (PreparedStatement statement = database.prepare(connection, """
                INSERT INTO report_notifications (
                    notification_uuid, recipient_uuid, report_uuid, response_uuid, created_at, read_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, SqliteMappings.uuid(notification.id()));
            statement.setString(2, SqliteMappings.uuid(notification.recipientId()));
            statement.setString(3, SqliteMappings.uuid(notification.reportId()));
            statement.setString(4, notification.responseId().map(SqliteMappings::uuid).orElse(null));
            statement.setString(5, SqliteMappings.instant(notification.createdAt()));
            statement.setString(6, notification.readAt().map(SqliteMappings::instant).orElse(null));
            statement.executeUpdate();
        }
    }

    private static ReportResponse readResponse(ResultSet results) throws SQLException {
        return new ReportResponse(
                SqliteMappings.uuid(results.getString("response_uuid")),
                SqliteMappings.uuid(results.getString("report_uuid")),
                SqliteMappings.actor(
                        results.getString("administrator_type"),
                        results.getString("administrator_player_uuid"),
                        results.getString("administrator_display_name")
                ),
                results.getString("message"),
                ResponseVisibility.valueOf(results.getString("visibility")),
                SqliteMappings.instant(results.getString("created_at"))
        );
    }

    private static ReportNotification readNotification(ResultSet results) throws SQLException {
        String responseId = results.getString("response_uuid");
        return new ReportNotification(
                SqliteMappings.uuid(results.getString("notification_uuid")),
                SqliteMappings.uuid(results.getString("recipient_uuid")),
                SqliteMappings.uuid(results.getString("report_uuid")),
                responseId == null ? Optional.empty() : Optional.of(SqliteMappings.uuid(responseId)),
                SqliteMappings.instant(results.getString("created_at")),
                SqliteMappings.optionalInstant(results, "read_at")
        );
    }

    private static ReportMutationResult reject(
            Optional<Report> current,
            long expectedVersion,
            Predicate<Report> validState
    ) {
        if (current.isEmpty()) {
            return ReportMutationResult.withoutReport(ReportMutationResult.Status.NOT_FOUND);
        }
        Report report = current.orElseThrow();
        if (report.version() != expectedVersion) {
            return ReportMutationResult.withoutReport(ReportMutationResult.Status.VERSION_CONFLICT);
        }
        if (!validState.test(report)) {
            return ReportMutationResult.withoutReport(ReportMutationResult.Status.INVALID_STATE);
        }
        return null;
    }

    private static void bindOptionalActor(PreparedStatement statement, int firstIndex, Optional<Actor> actor)
            throws SQLException {
        if (actor.isPresent()) {
            SqliteMappings.bindActor(statement, firstIndex, actor.orElseThrow());
        } else {
            statement.setString(firstIndex, null);
            statement.setString(firstIndex + 1, null);
            statement.setString(firstIndex + 2, null);
        }
    }

    private static void validateRelatedRecords(
            UUID reportId,
            ReportResponse response,
            Optional<ReportNotification> notification
    ) {
        Objects.requireNonNull(reportId, "reportId");
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
        Objects.requireNonNull(reportId, "reportId");
        Objects.requireNonNull(auditEntry, "auditEntry");
        if (!auditEntry.entityId().equals(reportId)) {
            throw new IllegalArgumentException("Audit entry does not refer to the mutated report");
        }
    }

    @FunctionalInterface
    private interface StatementBinder {
        void bind(PreparedStatement statement) throws SQLException;
    }
}
