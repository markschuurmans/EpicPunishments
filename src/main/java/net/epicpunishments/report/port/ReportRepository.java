package net.epicpunishments.report.port;

import net.epicpunishments.common.domain.Page;
import net.epicpunishments.common.domain.PageRequest;
import net.epicpunishments.report.domain.Report;
import net.epicpunishments.report.domain.ReportNotification;
import net.epicpunishments.report.domain.ReportResponse;
import net.epicpunishments.report.domain.ReportStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public interface ReportRepository {
    CompletionStage<Optional<Report>> findById(UUID reportId);

    CompletionStage<Optional<Report>> findLatestByReporter(UUID reporterId);

    CompletionStage<Optional<Report>> findOpenByParticipants(UUID reporterId, UUID reportedId);

    CompletionStage<Page<Report>> findByReporter(UUID reporterId, PageRequest pageRequest);

    CompletionStage<Page<Report>> findByStatus(Optional<ReportStatus> status, PageRequest pageRequest);

    CompletionStage<List<ReportResponse>> findResponses(UUID reportId);

    CompletionStage<List<ReportNotification>> findUnreadNotifications(UUID recipientId);
}
