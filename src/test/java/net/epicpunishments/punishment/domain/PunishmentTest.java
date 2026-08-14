package net.epicpunishments.punishment.domain;

import net.epicpunishments.common.domain.Actor;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PunishmentTest {
    private static final Instant CREATED = Instant.parse("2026-04-01T10:00:00Z");

    @Test
    void temporaryPunishmentExpiresAtItsExclusiveBoundary() {
        Punishment punishment = punishment(Optional.of(CREATED.plusSeconds(60)));

        assertThat(punishment.isActiveAt(CREATED)).isTrue();
        assertThat(punishment.isActiveAt(CREATED.plusSeconds(59))).isTrue();
        assertThat(punishment.isActiveAt(CREATED.plusSeconds(60))).isFalse();
    }

    @Test
    void revocationProducesAReplacementAndPreservesIssuerData() {
        Punishment original = punishment(Optional.empty());
        PunishmentRevocation revocation = new PunishmentRevocation(
                Actor.player(UUID.randomUUID(), "Moderator"),
                CREATED.plusSeconds(30),
                "Mistake"
        );

        Punishment revoked = original.revoke(revocation);

        assertThat(original.revocation()).isEmpty();
        assertThat(revoked.revocation()).contains(revocation);
        assertThat(revoked.issuer()).isEqualTo(original.issuer());
        assertThat(revoked.isActiveAt(CREATED.plusSeconds(31))).isFalse();
        assertThatThrownBy(() -> revoked.revoke(revocation)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsExpiryAtOrBeforeCreation() {
        assertThatThrownBy(() -> punishment(Optional.of(CREATED)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("after createdAt");
    }

    private static Punishment punishment(Optional<Instant> expiry) {
        return new Punishment(
                UUID.randomUUID(),
                PunishmentType.BAN,
                new PlayerPunishmentTarget(UUID.randomUUID()),
                "Test reason",
                Actor.console(),
                CREATED,
                expiry,
                Optional.empty()
        );
    }
}
