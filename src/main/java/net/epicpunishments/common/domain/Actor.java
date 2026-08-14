package net.epicpunishments.common.domain;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record Actor(ActorType type, Optional<UUID> playerId, String displayName) {
    public Actor {
        Objects.requireNonNull(type, "type");
        playerId = Objects.requireNonNull(playerId, "playerId");
        displayName = requireText(displayName, "displayName", 64);
        if (type == ActorType.PLAYER && playerId.isEmpty()) {
            throw new IllegalArgumentException("A player actor requires a player ID");
        }
        if (type == ActorType.CONSOLE && playerId.isPresent()) {
            throw new IllegalArgumentException("A console actor cannot have a player ID");
        }
    }

    public static Actor player(UUID playerId, String displayName) {
        return new Actor(ActorType.PLAYER, Optional.of(Objects.requireNonNull(playerId, "playerId")), displayName);
    }

    public static Actor console() {
        return new Actor(ActorType.CONSOLE, Optional.empty(), "Console");
    }

    private static String requireText(String value, String name, int maximumLength) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(name + " must contain between 1 and " + maximumLength + " characters");
        }
        return value;
    }
}
