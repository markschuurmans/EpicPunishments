package net.epicpunishments.contract;

import net.epicpunishments.common.domain.Actor;
import net.epicpunishments.common.domain.AuditEntry;
import net.epicpunishments.common.domain.PageRequest;
import net.epicpunishments.identity.domain.PlayerAddress;
import net.epicpunishments.punishment.domain.AddressPunishmentTarget;
import net.epicpunishments.punishment.domain.PlayerPunishmentTarget;
import net.epicpunishments.punishment.domain.Punishment;
import net.epicpunishments.punishment.domain.PunishmentRevocation;
import net.epicpunishments.punishment.domain.PunishmentTarget;
import net.epicpunishments.punishment.domain.PunishmentType;
import net.epicpunishments.punishment.port.ModerationMutationResult;
import net.epicpunishments.testing.ModerationStoreTestFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public abstract class PunishmentRepositoryContract {
    private static final Instant NOW = Instant.parse("2026-02-01T12:00:00Z");
    private static final Actor CONSOLE = Actor.console();

    private ModerationStoreTestFixture fixture;

    protected abstract ModerationStoreTestFixture createFixture();

    @BeforeEach
    final void setUpPunishmentRepositoryContract() {
        fixture = createFixture();
    }

    @Test
    final void returnsOnlyActivePunishmentsForTheRequestedTarget() {
        UUID playerId = UUID.randomUUID();
        PlayerAddress address = PlayerAddress.fromBytes(new byte[]{(byte) 198, 51, 100, 9});
        Punishment playerBan = punishment(PunishmentType.BAN, new PlayerPunishmentTarget(playerId), NOW, Optional.empty());
        Punishment ipMute = punishment(PunishmentType.MUTE, new AddressPunishmentTarget(address), NOW, Optional.empty());
        Punishment expired = punishment(
                PunishmentType.BAN,
                new PlayerPunishmentTarget(playerId),
                NOW.minusSeconds(120),
                Optional.of(NOW.minusSeconds(1))
        );
        create(playerBan);
        create(ipMute);
        create(expired);

        var assessment = fixture.loginAssessments().assessLogin(playerId, address, NOW.plusSeconds(1))
                .toCompletableFuture().join();

        assertThat(assessment.punishments().bans()).containsExactly(playerBan);
        assertThat(assessment.punishments().mutes()).containsExactly(ipMute);
        assertThat(fixture.punishments().findHistory(new PlayerPunishmentTarget(playerId), new PageRequest(0, 10))
                .toCompletableFuture().join().items()).contains(playerBan, expired);
    }

    @Test
    final void createAndRevokeEachPersistOneAuditEntryAtomically() {
        Punishment punishment = punishment(
                PunishmentType.MUTE,
                new PlayerPunishmentTarget(UUID.randomUUID()),
                NOW,
                Optional.empty()
        );
        create(punishment);
        PunishmentRevocation revocation = new PunishmentRevocation(CONSOLE, NOW.plusSeconds(20), "Appeal accepted");

        var result = fixture.mutations().revokePunishment(
                punishment.id(),
                revocation,
                audit(punishment.id(), "punishment.revoke")
        ).toCompletableFuture().join();

        assertThat(result.status()).isEqualTo(ModerationMutationResult.Status.APPLIED);
        assertThat(result.punishment().orElseThrow().revocation()).contains(revocation);
        assertThat(fixture.auditEntries()).extracting(AuditEntry::action)
                .containsExactly("punishment.create", "punishment.revoke");
        assertThat(fixture.punishments().findActiveForPlayer(
                ((PlayerPunishmentTarget) punishment.target()).playerId(),
                NOW.plusSeconds(21)
        ).toCompletableFuture().join().mutes()).isEmpty();
    }

    @Test
    final void rollsBackPunishmentWhenAuditWriteFails() {
        Punishment punishment = punishment(
                PunishmentType.BAN,
                new PlayerPunishmentTarget(UUID.randomUUID()),
                NOW,
                Optional.empty()
        );
        fixture.failNextAuditWrite();

        assertThatThrownBy(() -> fixture.mutations().createPunishment(
                punishment,
                audit(punishment.id(), "punishment.create")
        ).toCompletableFuture().join()).hasRootCauseMessage("Induced audit write failure");

        assertThat(fixture.punishments().findById(punishment.id()).toCompletableFuture().join()).isEmpty();
        assertThat(fixture.auditEntries()).isEmpty();
    }

    @Test
    final void warningDeliveryIsIdempotentPerPunishmentAndPlayer() {
        UUID playerId = UUID.randomUUID();
        Punishment warning = punishment(
                PunishmentType.WARNING,
                new AddressPunishmentTarget(PlayerAddress.fromBytes(new byte[]{127, 0, 0, 1})),
                NOW,
                Optional.empty()
        );
        create(warning);

        assertThat(fixture.punishments().recordWarningDelivery(warning.id(), playerId, NOW.plusSeconds(1))
                .toCompletableFuture().join()).isTrue();
        assertThat(fixture.punishments().recordWarningDelivery(warning.id(), playerId, NOW.plusSeconds(2))
                .toCompletableFuture().join()).isFalse();
    }

    @Test
    final void historyIsOrderedNewestFirstAndPaginates() {
        PunishmentTarget target = new PlayerPunishmentTarget(UUID.randomUUID());
        Punishment oldest = punishment(PunishmentType.WARNING, target, NOW, Optional.empty());
        Punishment middle = punishment(PunishmentType.WARNING, target, NOW.plusSeconds(1), Optional.empty());
        Punishment newest = punishment(PunishmentType.WARNING, target, NOW.plusSeconds(2), Optional.empty());
        create(oldest);
        create(middle);
        create(newest);

        var page = fixture.punishments().findHistory(target, new PageRequest(1, 2)).toCompletableFuture().join();

        assertThat(page.totalItems()).isEqualTo(3);
        assertThat(page.items()).containsExactly(oldest);
    }

    @Test
    final void historyCanBeFilteredByPunishmentTypeBeforePagination() {
        PunishmentTarget target = new PlayerPunishmentTarget(UUID.randomUUID());
        create(punishment(PunishmentType.BAN, target, NOW, Optional.empty()));
        Punishment warning = punishment(PunishmentType.WARNING, target, NOW.plusSeconds(1), Optional.empty());
        create(warning);

        var page = fixture.punishments().findHistory(
                target,
                Optional.of(PunishmentType.WARNING),
                new PageRequest(0, 1)
        ).toCompletableFuture().join();

        assertThat(page.totalItems()).isEqualTo(1);
        assertThat(page.items()).containsExactly(warning);
    }

    @Test
    final void competingRevocationsCannotBothApply() {
        Punishment punishment = punishment(
                PunishmentType.BAN,
                new PlayerPunishmentTarget(UUID.randomUUID()),
                NOW,
                Optional.empty()
        );
        create(punishment);

        var results = concurrently(
                () -> fixture.mutations().revokePunishment(
                        punishment.id(),
                        new PunishmentRevocation(CONSOLE, NOW.plusSeconds(1), "First"),
                        audit(punishment.id(), "punishment.revoke")
                ).toCompletableFuture().join(),
                () -> fixture.mutations().revokePunishment(
                        punishment.id(),
                        new PunishmentRevocation(CONSOLE, NOW.plusSeconds(2), "Second"),
                        audit(punishment.id(), "punishment.revoke")
                ).toCompletableFuture().join()
        );

        assertThat(results).extracting(ModerationMutationResult::status)
                .containsExactlyInAnyOrder(
                        ModerationMutationResult.Status.APPLIED,
                        ModerationMutationResult.Status.ALREADY_REVOKED
                );
        assertThat(fixture.auditEntries()).hasSize(2);
    }

    @Test
    final void concurrentDuplicateCreationCommitsExactlyOnce() {
        Punishment punishment = punishment(
                PunishmentType.BAN,
                new PlayerPunishmentTarget(UUID.randomUUID()),
                NOW,
                Optional.empty()
        );

        var outcomes = concurrentlyCapturingFailure(
                () -> fixture.mutations().createPunishment(
                        punishment,
                        audit(punishment.id(), "punishment.create")
                ).toCompletableFuture().join(),
                () -> fixture.mutations().createPunishment(
                        punishment,
                        audit(punishment.id(), "punishment.create")
                ).toCompletableFuture().join()
        );

        assertThat(outcomes).filteredOn(outcome -> outcome.value() != null).hasSize(1);
        assertThat(outcomes).filteredOn(outcome -> outcome.failure() != null).hasSize(1);
        assertThat(fixture.auditEntries()).hasSize(1);
        assertThat(fixture.punishments().findById(punishment.id()).toCompletableFuture().join())
                .contains(punishment);
    }

    private void create(Punishment punishment) {
        fixture.mutations().createPunishment(punishment, audit(punishment.id(), "punishment.create"))
                .toCompletableFuture().join();
    }

    private static Punishment punishment(
            PunishmentType type,
            PunishmentTarget target,
            Instant createdAt,
            Optional<Instant> expiresAt
    ) {
        return new Punishment(
                UUID.randomUUID(),
                type,
                target,
                "Contract test",
                CONSOLE,
                createdAt,
                expiresAt,
                Optional.empty()
        );
    }

    private static AuditEntry audit(UUID entityId, String action) {
        return new AuditEntry(UUID.randomUUID(), CONSOLE, action, "punishment", entityId, NOW, "{}");
    }

    private static <T> java.util.List<T> concurrently(Supplier<T> first, Supplier<T> second) {
        return concurrentlyCapturingFailure(first, second).stream().map(Outcome::value).toList();
    }

    private static <T> java.util.List<Outcome<T>> concurrentlyCapturingFailure(
            Supplier<T> first,
            Supplier<T> second
    ) {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var firstFuture = java.util.concurrent.CompletableFuture.supplyAsync(
                    () -> runAfter(start, first),
                    executor
            );
            var secondFuture = java.util.concurrent.CompletableFuture.supplyAsync(
                    () -> runAfter(start, second),
                    executor
            );
            start.countDown();
            return java.util.List.of(firstFuture.join(), secondFuture.join());
        }
    }

    private static <T> Outcome<T> runAfter(CountDownLatch start, Supplier<T> supplier) {
        try {
            start.await();
            return new Outcome<>(supplier.get(), null);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new Outcome<>(null, exception);
        } catch (RuntimeException exception) {
            return new Outcome<>(null, exception);
        }
    }

    private record Outcome<T>(T value, Throwable failure) {
    }
}
