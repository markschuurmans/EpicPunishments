package net.epicpunishments.punishment.application;

import net.epicpunishments.common.config.PunishmentConfiguration;
import net.epicpunishments.common.domain.Actor;
import net.epicpunishments.common.domain.PageRequest;
import net.epicpunishments.identity.domain.LoginAssessment;
import net.epicpunishments.identity.domain.PlayerAddress;
import net.epicpunishments.punishment.domain.PunishmentType;
import net.epicpunishments.punishment.domain.SessionPunishments;
import net.epicpunishments.testing.InMemoryModerationStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AddressPunishmentServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-15T12:00:00Z");
    private static final PlayerAddress ADDRESS = PlayerAddress.fromBytes(new byte[]{(byte) 192, 0, 2, 42});
    private InMemoryModerationStore store;
    private SessionPunishmentCache sessions;

    @BeforeEach void setUp() { store = new InMemoryModerationStore(); sessions = new SessionPunishmentCache(); }

    @Test
    void commitsAndAuditsBeforeApplyingAnIpMuteToEveryMatchingLocalSession() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        sessions.put(new LoginAssessment(first, ADDRESS, NOW, SessionPunishments.empty()));
        sessions.put(new LoginAssessment(second, ADDRESS, NOW, SessionPunishments.empty()));
        sessions.put(new LoginAssessment(other, PlayerAddress.fromBytes(new byte[]{(byte) 192, 0, 2, 43}), NOW,
                SessionPunishments.empty()));

        AddressModerationResult result = service().create(new PlayerModerationRequest("ip:192.0.2.42",
                PunishmentType.MUTE, "Shared address", Optional.empty(), Actor.console(), false))
                .toCompletableFuture().join();

        assertThat(result.status()).isEqualTo(AddressModerationResult.Status.APPLIED);
        assertThat(sessions.activeMute(first, NOW)).isPresent();
        assertThat(sessions.activeMute(second, NOW)).isPresent();
        assertThat(sessions.activeMute(other, NOW)).isEmpty();
        assertThat(store.auditEntries()).singleElement().satisfies(audit -> {
            assertThat(audit.details()).contains("192.0.2.x");
            assertThat(audit.details()).doesNotContain("192.0.2.42");
        });
    }

    @Test
    void failedAuditLeavesPersistenceAndAllLocalSessionsUnchanged() {
        UUID player = UUID.randomUUID();
        sessions.put(new LoginAssessment(player, ADDRESS, NOW, SessionPunishments.empty()));
        store.failNextAuditWrite();

        assertThatThrownBy(() -> service().create(new PlayerModerationRequest("ip:192.0.2.42",
                PunishmentType.BAN, "Test", Optional.empty(), Actor.console(), false))
                .toCompletableFuture().join()).hasRootCauseMessage("Induced audit write failure");
        assertThat(sessions.isBanned(player, ADDRESS, NOW)).isFalse();
        assertThat(store.auditEntries()).isEmpty();
    }

    @Test
    void revokesMatchingIpPunishmentsAndRetainsHistory() {
        AddressPunishmentService service = service();
        service.create(new PlayerModerationRequest("ip:192.0.2.42", PunishmentType.BAN, "Test",
                Optional.empty(), Actor.console(), false)).toCompletableFuture().join();

        AddressModerationResult result = service.revoke(new PlayerRevocationRequest("ip:192.0.2.42",
                PunishmentType.BAN, "Appeal", Actor.console())).toCompletableFuture().join();

        assertThat(result.punishments()).singleElement().satisfies(value -> assertThat(value.revocation()).isPresent());
        assertThat(service.history("ip:192.0.2.42", Optional.empty(), new PageRequest(0, 10))
                .toCompletableFuture().join().items()).hasSize(1);
        assertThat(store.auditEntries()).hasSize(2);
    }

    private AddressPunishmentService service() {
        return new AddressPunishmentService(new AddressTargetParser(), store, store, sessions,
                () -> new PunishmentConfiguration(Duration.ofDays(30), 100, 10, false, java.util.Set.of()),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
