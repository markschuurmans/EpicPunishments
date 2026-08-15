package net.epicpunishments.punishment.application;

import net.epicpunishments.common.domain.Actor;
import net.epicpunishments.identity.domain.LoginAssessment;
import net.epicpunishments.identity.domain.PlayerAddress;
import net.epicpunishments.punishment.domain.PlayerPunishmentTarget;
import net.epicpunishments.punishment.domain.AddressPunishmentTarget;
import net.epicpunishments.punishment.domain.Punishment;
import net.epicpunishments.punishment.domain.PunishmentType;
import net.epicpunishments.punishment.domain.SessionPunishments;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SessionPunishmentCacheTest {
    private static final Instant NOW = Instant.parse("2026-08-15T12:00:00Z");

    @Test
    void requiresBothThePlayerAndCurrentAddressAndChecksExpiryOnAccess() {
        UUID playerId = UUID.randomUUID();
        PlayerAddress address = PlayerAddress.fromBytes(new byte[]{10, 0, 0, 1});
        Punishment ban = new Punishment(
                UUID.randomUUID(),
                PunishmentType.BAN,
                new PlayerPunishmentTarget(playerId),
                "Test ban",
                Actor.console(),
                NOW,
                Optional.of(NOW.plusSeconds(10)),
                Optional.empty()
        );
        var cache = new SessionPunishmentCache();
        cache.put(new LoginAssessment(
                playerId,
                address,
                NOW,
                new SessionPunishments(List.of(ban), List.of(), List.of())
        ));

        assertThat(cache.isBanned(playerId, address, NOW.plusSeconds(9))).isTrue();
        assertThat(cache.isBanned(playerId, address, NOW.plusSeconds(10))).isFalse();
        assertThat(cache.find(playerId, PlayerAddress.fromBytes(new byte[]{10, 0, 0, 2}))).isEmpty();
    }

    @Test
    void removesAWarningFromLocalStateOnlyWhenMarkedDelivered() {
        UUID playerId = UUID.randomUUID();
        PlayerAddress address = PlayerAddress.fromBytes(new byte[]{10, 0, 0, 1});
        Punishment warning = new Punishment(
                UUID.randomUUID(),
                PunishmentType.WARNING,
                new PlayerPunishmentTarget(playerId),
                "Test warning",
                Actor.console(),
                NOW,
                Optional.empty(),
                Optional.empty()
        );
        var cache = new SessionPunishmentCache();
        cache.put(new LoginAssessment(
                playerId,
                address,
                NOW,
                new SessionPunishments(List.of(), List.of(), List.of(warning))
        ));

        cache.markWarningDelivered(playerId, warning.id());

        assertThat(cache.find(playerId, address).orElseThrow().undeliveredWarnings()).isEmpty();
    }

    @Test
    void appliesAndRevokesCommittedPlayerMutesForTheCurrentSession() {
        UUID playerId = UUID.randomUUID();
        PlayerAddress address = PlayerAddress.fromBytes(new byte[]{10, 0, 0, 1});
        var cache = new SessionPunishmentCache();
        cache.put(new LoginAssessment(playerId, address, NOW, SessionPunishments.empty()));
        Punishment mute = new Punishment(
                UUID.randomUUID(),
                PunishmentType.MUTE,
                new PlayerPunishmentTarget(playerId),
                "Test mute",
                Actor.console(),
                NOW,
                Optional.of(NOW.plusSeconds(10)),
                Optional.empty()
        );

        cache.apply(mute);

        assertThat(cache.activeMute(playerId, NOW.plusSeconds(9))).contains(mute);
        assertThat(cache.activeMute(playerId, NOW.plusSeconds(10))).isEmpty();

        cache.revoke(mute.id());

        assertThat(cache.activeMute(playerId, NOW.plusSeconds(1))).isEmpty();
    }

    @Test
    void appliesAddressPunishmentsOnlyToSessionsUsingTheNormalizedAddress() {
        UUID matching = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        PlayerAddress address = PlayerAddress.fromBytes(new byte[]{10, 0, 0, 1});
        var cache = new SessionPunishmentCache();
        cache.put(new LoginAssessment(matching, address, NOW, SessionPunishments.empty()));
        cache.put(new LoginAssessment(other, PlayerAddress.fromBytes(new byte[]{10, 0, 0, 2}), NOW,
                SessionPunishments.empty()));
        Punishment warning = new Punishment(UUID.randomUUID(), PunishmentType.WARNING,
                new AddressPunishmentTarget(address), "Test warning", Actor.console(), NOW,
                Optional.empty(), Optional.empty());

        cache.apply(warning);

        assertThat(cache.find(matching, address).orElseThrow().undeliveredWarnings()).containsExactly(warning);
        assertThat(cache.find(other, PlayerAddress.fromBytes(new byte[]{10, 0, 0, 2})).orElseThrow()
                .undeliveredWarnings()).isEmpty();
        assertThat(cache.playersAt(address)).containsExactly(matching);
    }
}
