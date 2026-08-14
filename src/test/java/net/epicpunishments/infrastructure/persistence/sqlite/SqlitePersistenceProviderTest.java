package net.epicpunishments.infrastructure.persistence.sqlite;

import net.epicpunishments.common.persistence.PersistenceException;
import net.epicpunishments.common.persistence.PersistenceFailureKind;
import net.epicpunishments.common.persistence.PersistenceHealth;
import net.epicpunishments.identity.domain.PlayerAddress;
import net.epicpunishments.identity.domain.SuccessfulJoin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlitePersistenceProviderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void migratesReportsHealthAndPreservesDataAcrossRestart() throws Exception {
        Path databaseFile = temporaryDirectory.resolve("persistent.db");
        UUID playerId = UUID.randomUUID();
        Instant joinedAt = Instant.parse("2026-04-01T12:00:00.123456789Z");

        try (var first = new SqliteTestContext(databaseFile)) {
            assertThat(first.provider().schemaVersion().toCompletableFuture().join()).isEqualTo("1");
            assertThat(first.provider().health().toCompletableFuture().join()).isEqualTo(PersistenceHealth.HEALTHY);
            first.provider().playerIdentities().recordSuccessfulJoin(new SuccessfulJoin(
                    playerId,
                    "Persistent",
                    PlayerAddress.fromBytes(new byte[]{127, 0, 0, 1}),
                    joinedAt
            )).toCompletableFuture().join();
        }

        try (var reopened = new SqliteTestContext(databaseFile)) {
            assertThat(reopened.provider().playerIdentities().findByPlayerId(playerId)
                    .toCompletableFuture().join()).hasValueSatisfying(identity -> {
                        assertThat(identity.currentName()).isEqualTo("Persistent");
                        assertThat(identity.firstSeenAt()).isEqualTo(joinedAt);
                    });
        }

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
             var statement = connection.createStatement();
             var results = statement.executeQuery("PRAGMA journal_mode")) {
            assertThat(results.next()).isTrue();
            assertThat(results.getString(1)).isEqualToIgnoringCase("wal");
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
             var statement = connection.createStatement();
             var results = statement.executeQuery("SELECT typeof(address_bytes), length(address_bytes) FROM addresses")) {
            assertThat(results.next()).isTrue();
            assertThat(results.getString(1)).isEqualTo("blob");
            assertThat(results.getInt(2)).isEqualTo(4);
        }
    }

    @Test
    void rejectsRepositoryWorkAfterShutdownWithClassifiedFailure() {
        var context = new SqliteTestContext(temporaryDirectory.resolve("closed.db"));
        context.close();

        assertThatThrownBy(() -> context.provider().playerIdentities().findByPlayerId(UUID.randomUUID())
                .toCompletableFuture().join())
                .hasRootCauseInstanceOf(PersistenceException.class)
                .rootCause()
                .extracting(cause -> ((PersistenceException) cause).kind())
                .isEqualTo(PersistenceFailureKind.SHUTTING_DOWN);
    }
}
