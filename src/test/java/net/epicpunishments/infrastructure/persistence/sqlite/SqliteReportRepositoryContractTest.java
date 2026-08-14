package net.epicpunishments.infrastructure.persistence.sqlite;

import net.epicpunishments.contract.ReportRepositoryContract;
import net.epicpunishments.testing.ReportStoreTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

class SqliteReportRepositoryContractTest extends ReportRepositoryContract {
    @TempDir
    Path temporaryDirectory;

    private SqliteTestContext context;

    @Override
    protected ReportStoreTestFixture createFixture() {
        context = new SqliteTestContext(temporaryDirectory.resolve("reports.db"));
        return new ReportStoreTestFixture() {
            @Override
            public net.epicpunishments.report.port.ReportRepository reports() {
                return context.reports();
            }

            @Override
            public net.epicpunishments.report.port.ReportMutationStore mutations() {
                return context.reportMutations();
            }

            @Override
            public java.util.List<net.epicpunishments.common.domain.AuditEntry> auditEntries() {
                return context.auditEntries();
            }

            @Override
            public void failNextAuditWrite() {
                context.failNextAuditWrite();
            }
        };
    }

    @AfterEach
    void closeContext() {
        if (context != null) {
            context.close();
        }
    }
}
