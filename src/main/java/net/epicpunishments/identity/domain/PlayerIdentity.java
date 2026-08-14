package net.epicpunishments.identity.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record PlayerIdentity(UUID playerId, String currentName, Instant firstSeenAt, Instant lastSeenAt) {
    public PlayerIdentity {
        Objects.requireNonNull(playerId, "playerId");
        currentName = requirePlayerName(currentName);
        Objects.requireNonNull(firstSeenAt, "firstSeenAt");
        Objects.requireNonNull(lastSeenAt, "lastSeenAt");
        if (lastSeenAt.isBefore(firstSeenAt)) {
            throw new IllegalArgumentException("lastSeenAt cannot be before firstSeenAt");
        }
    }

    static String requirePlayerName(String name) {
        Objects.requireNonNull(name, "name");
        if (name.isBlank() || name.length() > 16) {
            throw new IllegalArgumentException("A player name must contain between 1 and 16 characters");
        }
        return name;
    }
}
