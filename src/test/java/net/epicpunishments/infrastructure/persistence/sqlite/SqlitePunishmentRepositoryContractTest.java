package net.epicpunishments.infrastructure.persistence.sqlite;

import net.epicpunishments.contract.PunishmentRepositoryContract;
import net.epicpunishments.testing.ModerationStoreTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

class SqlitePunishmentRepositoryContractTest extends PunishmentRepositoryContract {
    @TempDir
    Path temporaryDirectory;

    private SqliteTestContext context;

    @Override
    protected ModerationStoreTestFixture createFixture() {
        context = new SqliteTestContext(temporaryDirectory.resolve("punishments.db"));
        return new ModerationStoreTestFixture() {
            @Override
            public net.epicpunishments.punishment.port.PunishmentRepository punishments() {
                return context.punishments();
            }

            @Override
            public net.epicpunishments.punishment.port.ModerationMutationStore mutations() {
                return context.moderationMutations();
            }

            @Override
            public net.epicpunishments.identity.port.LoginAssessmentRepository loginAssessments() {
                return context.loginAssessments();
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
