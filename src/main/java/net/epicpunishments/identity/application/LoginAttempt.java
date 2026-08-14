package net.epicpunishments.identity.application;

import net.epicpunishments.identity.domain.PlayerAddress;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record LoginAttempt(UUID playerId, String playerName, PlayerAddress address, Instant attemptedAt) {
    public LoginAttempt {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(playerName, "playerName");
        if (playerName.isBlank() || playerName.length() > 16) {
            throw new IllegalArgumentException("A player name must contain between 1 and 16 characters");
        }
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(attemptedAt, "attemptedAt");
    }
}
