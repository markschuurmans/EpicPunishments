package net.epicpunishments.infrastructure.persistence.sqlite;

import net.epicpunishments.common.config.SqliteConnectionConfiguration;
import net.epicpunishments.common.domain.Actor;
import net.epicpunishments.common.domain.AuditEntry;
import net.epicpunishments.common.execution.BoundedTaskExecutor;
import net.epicpunishments.identity.port.LoginAssessmentRepository;
import net.epicpunishments.punishment.port.ModerationMutationStore;
import net.epicpunishments.punishment.port.PunishmentRepository;
import net.epicpunishments.report.port.ReportMutationStore;
import net.epicpunishments.report.port.ReportRepository;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class SqliteTestContext implements AutoCloseable {
    private final Path databaseFile;
    private final BoundedTaskExecutor executor;
    private final SqlitePersistenceProvider provider;

    SqliteTestContext(Path databaseFile) {
        this.databaseFile = databaseFile.toAbsolutePath().normalize();
        this.executor = new BoundedTaskExecutor(4, 64, Duration.ofSeconds(5), "sqlite-test");
        this.provider = new SqlitePersistenceProvider(
                new SqliteConnectionConfiguration(this.databaseFile),
                Duration.ofMillis(1_500),
                executor
        );
        provider.initialize().toCompletableFuture().join();
    }

    SqlitePersistenceProvider provider() {
        return provider;
    }

    public PunishmentRepository punishments() {
        return provider.punishments();
    }

    public ModerationMutationStore moderationMutations() {
        return provider.moderationMutations();
    }

    public LoginAssessmentRepository loginAssessments() {
        return provider.loginAssessments();
    }

    public ReportRepository reports() {
        return provider.reports();
    }

    public ReportMutationStore reportMutations() {
        return provider.reportMutations();
    }

    public List<AuditEntry> auditEntries() {
        var entries = new ArrayList<AuditEntry>();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
             var statement = connection.prepareStatement("""
                     SELECT audit_uuid, actor_type, actor_player_uuid, actor_display_name,
                            action, entity_type, entity_uuid, occurred_at, details
                     FROM audit_log ORDER BY rowid
                     """);
             var results = statement.executeQuery()) {
            while (results.next()) {
                Actor actor = SqliteMappings.actor(
                        results.getString("actor_type"),
                        results.getString("actor_player_uuid"),
                        results.getString("actor_display_name")
                );
                entries.add(new AuditEntry(
                        SqliteMappings.uuid(results.getString("audit_uuid")),
                        actor,
                        results.getString("action"),
                        results.getString("entity_type"),
                        SqliteMappings.uuid(results.getString("entity_uuid")),
                        SqliteMappings.instant(results.getString("occurred_at")),
                        results.getString("details")
                ));
            }
        } catch (java.sql.SQLException exception) {
            throw new AssertionError("Could not inspect SQLite audit entries", exception);
        }
        return List.copyOf(entries);
    }

    public void failNextAuditWrite() {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TRIGGER fail_next_audit
                    BEFORE INSERT ON audit_log
                    BEGIN
                        SELECT RAISE(ABORT, 'Induced audit write failure');
                    END
                    """);
        } catch (java.sql.SQLException exception) {
            throw new AssertionError("Could not install SQLite audit failure trigger", exception);
        }
    }

    @Override
    public void close() {
        provider.closeAsync().toCompletableFuture().join();
        executor.close();
    }
}
