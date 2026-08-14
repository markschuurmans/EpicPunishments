package net.epicpunishments.contract;

import net.epicpunishments.identity.domain.PlayerAddress;
import net.epicpunishments.identity.domain.SuccessfulJoin;
import net.epicpunishments.identity.port.PlayerIdentityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public abstract class PlayerIdentityRepositoryContract {
    private PlayerIdentityRepository repository;

    protected abstract PlayerIdentityRepository createRepository();

    @BeforeEach
    final void setUpIdentityRepositoryContract() {
        repository = createRepository();
    }

    @Test
    final void upsertsIdentityAndRetainsHistoricalNames() {
        UUID playerId = UUID.randomUUID();
        Instant firstJoin = Instant.parse("2026-01-01T10:00:00Z");
        Instant secondJoin = firstJoin.plusSeconds(60);
        PlayerAddress address = PlayerAddress.fromBytes(new byte[]{(byte) 192, 0, 2, 10});

        join(new SuccessfulJoin(playerId, "FirstName", address, firstJoin));
        join(new SuccessfulJoin(playerId, "SecondName", address, secondJoin));

        var identity = repository.findByPlayerId(playerId).toCompletableFuture().join().orElseThrow();
        assertThat(identity.currentName()).isEqualTo("SecondName");
        assertThat(identity.firstSeenAt()).isEqualTo(firstJoin);
        assertThat(identity.lastSeenAt()).isEqualTo(secondJoin);
        assertThat(repository.findByCurrentOrHistoricalName("firstname").toCompletableFuture().join())
                .extracting(value -> value.playerId())
                .containsExactly(playerId);
    }

    @Test
    final void tracksOnlyRecordedSuccessfulJoinsByNormalizedAddress() {
        UUID playerId = UUID.randomUUID();
        Instant firstJoin = Instant.parse("2026-01-01T10:00:00Z");
        byte[] mappedAddress = {
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xff, (byte) 0xff,
                (byte) 203, 0, 113, 7
        };

        join(new SuccessfulJoin(playerId, "Player", PlayerAddress.fromBytes(mappedAddress), firstJoin));
        join(new SuccessfulJoin(
                playerId,
                "Player",
                PlayerAddress.fromBytes(new byte[]{(byte) 203, 0, 113, 7}),
                firstJoin.plusSeconds(10)
        ));

        assertThat(repository.findAddressHistory(playerId).toCompletableFuture().join())
                .singleElement()
                .satisfies(history -> {
                    assertThat(history.address().family().name()).isEqualTo("IPV4");
                    assertThat(history.joinCount()).isEqualTo(2);
                    assertThat(history.firstSuccessfulJoinAt()).isEqualTo(firstJoin);
                    assertThat(history.lastSuccessfulJoinAt()).isEqualTo(firstJoin.plusSeconds(10));
                });
    }

    @Test
    final void nameLookupSurfacesHistoricalAmbiguity() {
        PlayerAddress address = PlayerAddress.fromBytes(new byte[]{127, 0, 0, 1});
        Instant now = Instant.parse("2026-01-01T10:00:00Z");
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        join(new SuccessfulJoin(first, "Shared", address, now));
        join(new SuccessfulJoin(first, "Renamed", address, now.plusSeconds(1)));
        join(new SuccessfulJoin(second, "Shared", address, now.plusSeconds(2)));

        assertThat(repository.findByCurrentOrHistoricalName("SHARED").toCompletableFuture().join())
                .extracting(value -> value.playerId())
                .containsExactlyInAnyOrder(first, second);
    }

    private void join(SuccessfulJoin successfulJoin) {
        repository.recordSuccessfulJoin(successfulJoin).toCompletableFuture().join();
    }
}
