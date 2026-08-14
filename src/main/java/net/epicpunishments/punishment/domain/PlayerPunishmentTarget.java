package net.epicpunishments.punishment.domain;

import java.util.Objects;
import java.util.UUID;

public record PlayerPunishmentTarget(UUID playerId) implements PunishmentTarget {
    public PlayerPunishmentTarget {
        Objects.requireNonNull(playerId, "playerId");
    }

    @Override
    public PunishmentTargetType type() {
        return PunishmentTargetType.PLAYER;
    }
}
