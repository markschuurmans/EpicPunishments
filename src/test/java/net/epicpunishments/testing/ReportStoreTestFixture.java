package net.epicpunishments.testing;

import net.epicpunishments.common.domain.AuditEntry;
import net.epicpunishments.report.port.ReportMutationStore;
import net.epicpunishments.report.port.ReportRepository;

import java.util.List;

public interface ReportStoreTestFixture {
    ReportRepository reports();

    ReportMutationStore mutations();

    List<AuditEntry> auditEntries();

    void failNextAuditWrite();
}
