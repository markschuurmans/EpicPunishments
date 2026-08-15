package net.epicpunishments.report.application;

import net.epicpunishments.report.domain.Report;
import net.epicpunishments.report.domain.ReportResponse;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record ReportResult(Status status, Optional<Report> report, List<ReportResponse> responses) {
    public enum Status {
        APPLIED,
        FOUND,
        NOT_FOUND,
        NOT_OWNER,
        TARGET_NOT_FOUND,
        TARGET_AMBIGUOUS,
        SELF_REPORT,
        COOLDOWN,
        DUPLICATE,
        VERSION_CONFLICT,
        INVALID_STATE
    }

    public ReportResult {
        Objects.requireNonNull(status, "status");
        report = Objects.requireNonNull(report, "report");
        responses = List.copyOf(Objects.requireNonNull(responses, "responses"));
        if ((status == Status.APPLIED || status == Status.FOUND) != report.isPresent()) {
            throw new IllegalArgumentException("Only successful report results carry a report");
        }
    }

    public static ReportResult success(Status status, Report report, List<ReportResponse> responses) {
        if (status != Status.APPLIED && status != Status.FOUND) {
            throw new IllegalArgumentException("Status is not successful");
        }
        return new ReportResult(status, Optional.of(report), responses);
    }

    public static ReportResult failure(Status status) {
        return new ReportResult(status, Optional.empty(), List.of());
    }
}
