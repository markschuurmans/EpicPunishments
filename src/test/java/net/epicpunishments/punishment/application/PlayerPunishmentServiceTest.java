package net.epicpunishments.punishment.application;

import net.epicpunishments.common.config.PunishmentConfiguration;
import net.epicpunishments.common.domain.Actor;
import net.epicpunishments.common.domain.PageRequest;
import net.epicpunishments.identity.domain.LoginAssessment;
import net.epicpunishments.identity.domain.PlayerAddress;
import net.epicpunishments.identity.domain.SuccessfulJoin;
import net.epicpunishments.punishment.domain.PunishmentType;
import net.epicpunishments.punishment.domain.SessionPunishments;
import net.epicpunishments.testing.InMemoryModerationStore;
import net.epicpunishments.testing.InMemoryPlayerIdentityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlayerPunishmentServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-15T12:00:00Z");
    private static final PlayerAddress ADDRESS = PlayerAddress.fromBytes(new byte[]{(byte) 192, 0, 2, 42});

    private InMemoryPlayerIdentityRepository identities;
    private InMemoryModerationStore store;
    private SessionPunishmentCache sessions;

    @BeforeEach
    void setUp() {
        identities = new InMemoryPlayerIdentityRepository();
        store = new InMemoryModerationStore();
        sessions = new SessionPunishmentCache();
    }

    @Test
    void resolvesOfflineHistoricalNamesAndPublishesCacheStateOnlyAfterAtomicCommit() {
        UUID playerId = record("OldName");
        record(playerId, "NewName", NOW.minusSeconds(10));
        sessions.put(new LoginAssessment(playerId, ADDRESS, NOW, SessionPunishments.empty()));
        var service = service(false);

        PlayerModerationResult result = service.create(new PlayerModerationRequest(
                "player:OldName",
                PunishmentType.BAN,
                "Repeated griefing",
                Optional.of(Duration.ofDays(7)),
                Actor.console(),
                false
        )).toCompletableFuture().join();

        assertThat(result.status()).isEqualTo(PlayerModerationResult.Status.APPLIED);
        assertThat(result.identity().orElseThrow().playerId()).isEqualTo(playerId);
        assertThat(result.punishments().getFirst().expiresAt()).contains(NOW.plus(Duration.ofDays(7)));
        assertThat(store.auditEntries()).singleElement().satisfies(audit -> {
            assertThat(audit.action()).isEqualTo("punishment.create");
            assertThat(audit.entityId()).isEqualTo(result.punishments().getFirst().id());
        });
        assertThat(sessions.activeMute(playerId, NOW)).isEmpty();
        assertThat(sessions.isBanned(playerId, ADDRESS, NOW)).isTrue();
    }

    @Test
    void auditFailureDoesNotCreateDatabaseOrCacheState() {
        UUID playerId = record("Target");
        sessions.put(new LoginAssessment(playerId, ADDRESS, NOW, SessionPunishments.empty()));
        store.failNextAuditWrite();

        assertThatThrownBy(() -> service(false).create(new PlayerModerationRequest(
                "player:Target",
                PunishmentType.MUTE,
                "Test mute",
                Optional.empty(),
                Actor.console(),
                false
        )).toCompletableFuture().join()).hasRootCauseMessage("Induced audit write failure");

        assertThat(store.auditEntries()).isEmpty();
        assertThat(sessions.activeMute(playerId, NOW)).isEmpty();
        assertThat(store.findActiveForPlayer(playerId, NOW).toCompletableFuture().join().mutes()).isEmpty();
    }

    @Test
    void appliesExemptionBeforeMutation() {
        record("Target");
        var service = service(true);

        PlayerModerationResult denied = service.create(new PlayerModerationRequest(
                "player:Target",
                PunishmentType.WARNING,
                "Test warning",
                Optional.empty(),
                Actor.player(UUID.randomUUID(), "Moderator"),
                false
        )).toCompletableFuture().join();

        assertThat(denied.status()).isEqualTo(PlayerModerationResult.Status.TARGET_EXEMPT);
        assertThat(store.auditEntries()).isEmpty();
    }

    @Test
    void ambiguousHistoricalNameRequiresUuid() {
        record("SharedName");
        record("SharedName");

        PlayerModerationResult result = service(false).create(new PlayerModerationRequest(
                "player:SharedName",
                PunishmentType.WARNING,
                "Test warning",
                Optional.empty(),
                Actor.console(),
                false
        )).toCompletableFuture().join();

        assertThat(result.status()).isEqualTo(PlayerModerationResult.Status.TARGET_AMBIGUOUS);
        assertThat(store.auditEntries()).isEmpty();
    }

    @Test
    void revokesEveryActivePunishmentOfTheRequestedTypeAndRetainsHistory() {
        UUID playerId = record("Target");
        sessions.put(new LoginAssessment(playerId, ADDRESS, NOW, SessionPunishments.empty()));
        var service = service(false);
        create(service, "player:Target", PunishmentType.MUTE, "First");
        create(service, "player:Target", PunishmentType.MUTE, "Second");

        PlayerModerationResult result = service.revoke(new PlayerRevocationRequest(
                "player:Target", PunishmentType.MUTE, "Appeal accepted", Actor.console()
        )).toCompletableFuture().join();

        assertThat(result.status()).isEqualTo(PlayerModerationResult.Status.APPLIED);
        assertThat(result.punishments()).hasSize(2).allSatisfy(punishment ->
                assertThat(punishment.revocation()).isPresent());
        assertThat(sessions.activeMute(playerId, NOW)).isEmpty();
        assertThat(store.findHistory(
                result.punishments().getFirst().target(),
                new PageRequest(0, 10)
        ).toCompletableFuture().join().items()).hasSize(2);
        assertThat(store.auditEntries()).hasSize(4);
    }

    @Test
    void warningHistoryUsesRepositorySideTypeFiltering() {
        record("Target");
        var service = service(false);
        create(service, "player:Target", PunishmentType.BAN, "Ban");
        create(service, "player:Target", PunishmentType.WARNING, "Warning");

        PlayerHistoryResult result = service.history(
                "player:Target",
                Optional.of(PunishmentType.WARNING),
                new PageRequest(0, 10)
        ).toCompletableFuture().join();

        assertThat(result.history().orElseThrow().items())
                .singleElement()
                .extracting(punishment -> punishment.type())
                .isEqualTo(PunishmentType.WARNING);
        assertThat(result.history().orElseThrow().totalItems()).isEqualTo(1);
    }

    private PlayerPunishmentService service(boolean exempt) {
        return new PlayerPunishmentService(
                new PlayerTargetResolver(identities, new PlayerTargetParser()),
                ignored -> CompletableFuture.completedFuture(exempt),
                new TargetAuthorizationService(false),
                store,
                store,
                sessions,
                () -> new PunishmentConfiguration(Duration.ofDays(30), 100, 10, false, java.util.Set.of()),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private UUID record(String name) {
        UUID playerId = UUID.randomUUID();
        record(playerId, name, NOW.minusSeconds(30));
        return playerId;
    }

    private void record(UUID playerId, String name, Instant at) {
        identities.recordSuccessfulJoin(new SuccessfulJoin(playerId, name, ADDRESS, at))
                .toCompletableFuture().join();
    }

    private static void create(
            PlayerPunishmentService service,
            String target,
            PunishmentType type,
            String reason
    ) {
        service.create(new PlayerModerationRequest(
                target, type, reason, Optional.empty(), Actor.console(), false
        )).toCompletableFuture().join();
    }
}
