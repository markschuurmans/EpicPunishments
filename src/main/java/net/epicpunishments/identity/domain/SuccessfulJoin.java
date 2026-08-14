package net.epicpunishments.identity.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record SuccessfulJoin(UUID playerId, String playerName, PlayerAddress address, Instant joinedAt) {
    public SuccessfulJoin {
        Objects.requireNonNull(playerId, "playerId");
        playerName = PlayerIdentity.requirePlayerName(playerName);
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(joinedAt, "joinedAt");
    }
}
