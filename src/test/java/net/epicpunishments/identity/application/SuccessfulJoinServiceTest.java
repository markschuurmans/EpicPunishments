package net.epicpunishments.identity.application;

import net.epicpunishments.common.config.LoginFailurePolicy;
import net.epicpunishments.common.domain.Actor;
import net.epicpunishments.identity.domain.LoginAssessment;
import net.epicpunishments.identity.domain.PlayerAddress;
import net.epicpunishments.identity.domain.SuccessfulJoin;
import net.epicpunishments.punishment.application.SessionPunishmentCache;
import net.epicpunishments.punishment.domain.PlayerPunishmentTarget;
import net.epicpunishments.punishment.domain.Punishment;
import net.epicpunishments.punishment.domain.PunishmentType;
import net.epicpunishments.punishment.domain.SessionPunishments;
import net.epicpunishments.testing.InMemoryModerationStore;
import net.epicpunishments.testing.InMemoryPlayerIdentityRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SuccessfulJoinServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-15T12:00:00Z");
    private static final PlayerAddress ADDRESS = PlayerAddress.fromBytes(new byte[]{(byte) 198, 51, 100, 7});

    @Test
    void consumesPendingStateInitializesTheSessionAndRecordsOnlyTheSuccessfulJoin() {
        UUID playerId = UUID.randomUUID();
        var identities = new InMemoryPlayerIdentityRepository();
        var punishments = new InMemoryModerationStore();
        var pending = pending();
        var sessions = new SessionPunishmentCache();
        LoginAssessment assessment = new LoginAssessment(playerId, ADDRESS, NOW, SessionPunishments.empty());
        pending.put(assessment);
        var service = new SuccessfulJoinService(
                identities,
                (id, address, at) -> CompletableFuture.failedFuture(new AssertionError("fallback was not expected")),
                punishments,
                pending,
                sessions,
                LoginFailurePolicy.DENY,
                Duration.ofSeconds(1)
        );

        SuccessfulJoin join = new SuccessfulJoin(playerId, "JoinedPlayer", ADDRESS, NOW);
        JoinProcessing processing = service.process(join);

        assertThat(processing.assessment().toCompletableFuture().join().disconnectReason())
                .isEqualTo(JoinOutcome.DisconnectReason.NONE);
        processing.successfulJoinWrite().toCompletableFuture().join();
        assertThat(identities.findByPlayerId(playerId).toCompletableFuture().join()).isPresent();
        assertThat(identities.findAddressHistory(playerId).toCompletableFuture().join())
                .singleElement().extracting(history -> history.joinCount()).isEqualTo(1L);
        assertThat(sessions.find(playerId, ADDRESS)).contains(SessionPunishments.empty());
    }

    @Test
    void performsAnAsynchronousFallbackAndReturnsUndeliveredWarnings() {
        UUID playerId = UUID.randomUUID();
        Punishment warning = new Punishment(
                UUID.randomUUID(),
                PunishmentType.WARNING,
                new PlayerPunishmentTarget(playerId),
                "Please follow the rules",
                Actor.console(),
                NOW.minusSeconds(1),
                Optional.empty(),
                Optional.empty()
        );
        var identities = new InMemoryPlayerIdentityRepository();
        var punishments = new InMemoryModerationStore();
        var service = new SuccessfulJoinService(
                identities,
                (id, address, at) -> CompletableFuture.completedFuture(new LoginAssessment(
                        id,
                        address,
                        at,
                        new SessionPunishments(List.of(), List.of(), List.of(warning))
                )),
                punishments,
                pending(),
                new SessionPunishmentCache(),
                LoginFailurePolicy.DENY,
                Duration.ofSeconds(1)
        );

        JoinOutcome outcome = service.process(new SuccessfulJoin(playerId, "Fallback", ADDRESS, NOW))
                .assessment().toCompletableFuture().join();

        assertThat(outcome.disconnectReason()).isEqualTo(JoinOutcome.DisconnectReason.NONE);
        assertThat(outcome.warnings()).containsExactly(warning);
    }

    @Test
    void failClosedFallbackDisconnectsAfterItsBoundedTimeout() {
        UUID playerId = UUID.randomUUID();
        var service = new SuccessfulJoinService(
                new InMemoryPlayerIdentityRepository(),
                (id, address, at) -> new CompletableFuture<>(),
                new InMemoryModerationStore(),
                pending(),
                new SessionPunishmentCache(),
                LoginFailurePolicy.DENY,
                Duration.ofMillis(25)
        );

        JoinOutcome outcome = service.process(new SuccessfulJoin(playerId, "Fallback", ADDRESS, NOW))
                .assessment().toCompletableFuture().join();

        assertThat(outcome.disconnectReason()).isEqualTo(JoinOutcome.DisconnectReason.TEMPORARY_FAILURE);
        assertThat(outcome.degraded()).isTrue();
    }

    @Test
    void keepsWarningStateWhenTheDeliveryWriteDoesNotCommit() {
        UUID playerId = UUID.randomUUID();
        Punishment warning = new Punishment(
                UUID.randomUUID(),
                PunishmentType.WARNING,
                new PlayerPunishmentTarget(playerId),
                "Delivery must commit",
                Actor.console(),
                NOW.minusSeconds(1),
                Optional.empty(),
                Optional.empty()
        );
        var pending = pending();
        var sessions = new SessionPunishmentCache();
        var punishments = new InMemoryModerationStore();
        pending.put(new LoginAssessment(
                playerId,
                ADDRESS,
                NOW,
                new SessionPunishments(List.of(), List.of(), List.of(warning))
        ));
        var service = new SuccessfulJoinService(
                new InMemoryPlayerIdentityRepository(),
                punishments,
                punishments,
                pending,
                sessions,
                LoginFailurePolicy.DENY,
                Duration.ofSeconds(1)
        );
        service.process(new SuccessfulJoin(playerId, "Delivery", ADDRESS, NOW))
                .assessment().toCompletableFuture().join();

        assertThatThrownBy(() -> service.recordWarningDelivery(warning.id(), playerId, NOW)
                .toCompletableFuture().join()).hasRootCauseMessage("Warning punishment does not exist");

        assertThat(sessions.find(playerId, ADDRESS).orElseThrow().undeliveredWarnings())
                .containsExactly(warning);
    }

    private static PendingLoginAssessments pending() {
        return new PendingLoginAssessments(10, Duration.ofSeconds(30), Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
