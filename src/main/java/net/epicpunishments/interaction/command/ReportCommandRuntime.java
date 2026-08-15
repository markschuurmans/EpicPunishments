package net.epicpunishments.interaction.command;

import net.epicpunishments.interaction.listener.PaperReportNotifications;
import net.epicpunishments.report.application.ReportService;

import java.util.Objects;

public record ReportCommandRuntime(ReportService service, PaperReportNotifications notifications) {
    public ReportCommandRuntime {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(notifications, "notifications");
    }
}
