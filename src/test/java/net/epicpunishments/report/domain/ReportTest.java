package net.epicpunishments.report.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportTest {
    @Test
    void identifiesTerminalStatuses() {
        assertThat(report(ReportStatus.OPEN).isClosed()).isFalse();
        assertThat(report(ReportStatus.IN_REVIEW).isClosed()).isFalse();
        assertThat(report(ReportStatus.RESOLVED).isClosed()).isTrue();
        assertThat(report(ReportStatus.DISMISSED).isClosed()).isTrue();
    }

    @Test
    void rejectsSelfReportsAndInvalidVersion() {
        UUID playerId = UUID.randomUUID();
        Instant now = Instant.parse("2026-05-01T10:00:00Z");

        assertThatThrownBy(() -> new Report(
                UUID.randomUUID(),
                new ReportParticipant(playerId, "Player"),
                new ReportParticipant(playerId, "Player"),
                "Reason",
                ReportStatus.OPEN,
                Optional.empty(),
                0,
                now,
                now
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("themselves");
    }

    private static Report report(ReportStatus status) {
        Instant now = Instant.parse("2026-05-01T10:00:00Z");
        return new Report(
                UUID.randomUUID(),
                new ReportParticipant(UUID.randomUUID(), "Reporter"),
                new ReportParticipant(UUID.randomUUID(), "Reported"),
                "Reason",
                status,
                Optional.empty(),
                0,
                now,
                now
        );
    }
}
