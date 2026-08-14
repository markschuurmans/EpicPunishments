package net.epicpunishments.identity.application;

import net.epicpunishments.common.config.LoginFailurePolicy;
import net.epicpunishments.common.domain.Actor;
import net.epicpunishments.identity.domain.LoginAssessment;
import net.epicpunishments.identity.domain.PlayerAddress;
import net.epicpunishments.punishment.application.SessionPunishmentCache;
import net.epicpunishments.punishment.domain.PlayerPunishmentTarget;
import net.epicpunishments.punishment.domain.Punishment;
import net.epicpunishments.punishment.domain.PunishmentType;
import net.epicpunishments.punishment.domain.SessionPunishments;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class LoginAssessmentServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-15T12:00:00Z");
    private static final PlayerAddress ADDRESS = PlayerAddress.fromBytes(new byte[]{(byte) 192, 0, 2, 10});

    @Test
    void allowsAnUnbannedLoginAndPublishesItsPendingAssessment() {
        UUID playerId = UUID.randomUUID();
        LoginAssessment assessment = assessment(playerId, SessionPunishments.empty());
        var pending = pending();
        var service = service(
                (id, address, at) -> CompletableFuture.completedFuture(assessment),
                pending,
                new SessionPunishmentCache(),
                LoginFailurePolicy.DENY,
                Duration.ofSeconds(1)
        );

        LoginDecision decision = service.assess(attempt(playerId));

        assertThat(decision.status()).isEqualTo(LoginDecision.Status.ALLOWED);
        assertThat(decision.degraded()).isFalse();
        assertThat(pending.take(playerId)).contains(assessment);
    }

    @Test
    void deniesAnActiveBanWithoutPublishingPendingState() {
        UUID playerId = UUID.randomUUID();
        Punishment ban = ban(playerId, Optional.empty());
        var pending = pending();
        var service = service(
                (id, address, at) -> CompletableFuture.completedFuture(assessment(
                        playerId,
                        new SessionPunishments(List.of(ban), List.of(), List.of())
                )),
                pending,
                new SessionPunishmentCache(),
                LoginFailurePolicy.DENY,
                Duration.ofSeconds(1)
        );

        LoginDecision decision = service.assess(attempt(playerId));

        assertThat(decision.status()).isEqualTo(LoginDecision.Status.DENIED_BANNED);
        assertThat(decision.ban()).contains(ban);
        assertThat(pending.take(playerId)).isEmpty();
    }

    @Test
    void boundsAStalledAssessmentAndAppliesTheDenyPolicy() {
        UUID playerId = UUID.randomUUID();
        var service = service(
                (id, address, at) -> new CompletableFuture<>(),
                pending(),
                new SessionPunishmentCache(),
                LoginFailurePolicy.DENY,
                Duration.ofMillis(25)
        );

        long started = System.nanoTime();
        LoginDecision decision = service.assess(attempt(playerId));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(decision.status()).isEqualTo(LoginDecision.Status.DENIED_TEMPORARY_FAILURE);
        assertThat(elapsed).isLessThan(Duration.ofSeconds(1));
    }

    @Test
    void allowWithCacheStillDeniesAKnownBanAndAllowsAnUnknownPlayer() {
        UUID knownPlayer = UUID.randomUUID();
        Punishment ban = ban(knownPlayer, Optional.empty());
        var sessions = new SessionPunishmentCache();
        sessions.put(assessment(
                knownPlayer,
                new SessionPunishments(List.of(ban), List.of(), List.of())
        ));
        var service = service(
                (id, address, at) -> CompletableFuture.failedFuture(new IllegalStateException("offline")),
                pending(),
                sessions,
                LoginFailurePolicy.ALLOW_WITH_CACHE,
                Duration.ofSeconds(1)
        );

        LoginDecision known = service.assess(attempt(knownPlayer));
        LoginDecision unknown = service.assess(attempt(UUID.randomUUID()));

        assertThat(known.status()).isEqualTo(LoginDecision.Status.DENIED_BANNED);
        assertThat(known.degraded()).isTrue();
        assertThat(unknown.status()).isEqualTo(LoginDecision.Status.ALLOWED);
        assertThat(unknown.degraded()).isTrue();
    }

    private static LoginAssessmentService service(
            net.epicpunishments.identity.port.LoginAssessmentRepository repository,
            PendingLoginAssessments pending,
            SessionPunishmentCache sessions,
            LoginFailurePolicy policy,
            Duration timeout
    ) {
        return new LoginAssessmentService(repository, pending, sessions, timeout, policy);
    }

    private static PendingLoginAssessments pending() {
        return new PendingLoginAssessments(10, Duration.ofSeconds(30), java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC));
    }

    private static LoginAttempt attempt(UUID playerId) {
        return new LoginAttempt(playerId, "TestPlayer", ADDRESS, NOW);
    }

    private static LoginAssessment assessment(UUID playerId, SessionPunishments punishments) {
        return new LoginAssessment(playerId, ADDRESS, NOW, punishments);
    }

    private static Punishment ban(UUID playerId, Optional<Instant> expiresAt) {
        return new Punishment(
                UUID.randomUUID(),
                PunishmentType.BAN,
                new PlayerPunishmentTarget(playerId),
                "Test ban",
                Actor.console(),
                NOW.minusSeconds(1),
                expiresAt,
                Optional.empty()
        );
    }
}
