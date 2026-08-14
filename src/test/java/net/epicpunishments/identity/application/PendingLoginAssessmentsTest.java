package net.epicpunishments.identity.application;

import net.epicpunishments.identity.domain.LoginAssessment;
import net.epicpunishments.identity.domain.PlayerAddress;
import net.epicpunishments.punishment.domain.SessionPunishments;
import net.epicpunishments.testing.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PendingLoginAssessmentsTest {
    private static final Instant NOW = Instant.parse("2026-08-15T12:00:00Z");
    private static final PlayerAddress ADDRESS = PlayerAddress.fromBytes(new byte[]{127, 0, 0, 1});

    @Test
    void consumesAnAssessmentOnlyOnceAndExpiresItAtTheBoundedDeadline() {
        var clock = new MutableClock(NOW);
        var pending = new PendingLoginAssessments(2, Duration.ofSeconds(30), clock);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        pending.put(assessment(first));
        assertThat(pending.take(first)).contains(assessment(first));
        assertThat(pending.take(first)).isEmpty();

        pending.put(assessment(second));
        clock.advance(Duration.ofSeconds(30));

        assertThat(pending.take(second)).isEmpty();
        assertThat(pending.size()).isZero();
    }

    @Test
    void evictsTheOldestAssessmentWhenCapacityIsReached() {
        var pending = new PendingLoginAssessments(2, Duration.ofSeconds(30), new MutableClock(NOW));
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();

        pending.put(assessment(first));
        pending.put(assessment(second));
        pending.put(assessment(third));

        assertThat(pending.take(first)).isEmpty();
        assertThat(pending.take(second)).isPresent();
        assertThat(pending.take(third)).isPresent();
    }

    private static LoginAssessment assessment(UUID playerId) {
        return new LoginAssessment(playerId, ADDRESS, NOW, SessionPunishments.empty());
    }
}
