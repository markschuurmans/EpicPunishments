package net.epicpunishments.report.port;

import net.epicpunishments.report.domain.Report;

import java.util.Objects;
import java.util.Optional;

public record ReportMutationResult(Status status, Optional<Report> report) {
    public enum Status {
        APPLIED,
        NOT_FOUND,
        VERSION_CONFLICT,
        INVALID_STATE
    }

    public ReportMutationResult {
        Objects.requireNonNull(status, "status");
        report = Objects.requireNonNull(report, "report");
        if ((status == Status.APPLIED) != report.isPresent()) {
            throw new IllegalArgumentException("Only an applied mutation carries its report");
        }
    }

    public static ReportMutationResult applied(Report report) {
        return new ReportMutationResult(Status.APPLIED, Optional.of(report));
    }

    public static ReportMutationResult withoutReport(Status status) {
        if (status == Status.APPLIED) {
            throw new IllegalArgumentException("An applied result requires a report");
        }
        return new ReportMutationResult(status, Optional.empty());
    }
}
