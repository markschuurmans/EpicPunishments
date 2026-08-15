package net.epicpunishments.punishment.application;

import net.epicpunishments.identity.domain.PlayerIdentity;
import net.epicpunishments.punishment.domain.Punishment;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record PlayerModerationResult(
        Status status,
        Optional<PlayerIdentity> identity,
        List<Punishment> punishments
) {
    public enum Status {
        APPLIED,
        INVALID_TARGET,
        TARGET_NOT_FOUND,
        TARGET_AMBIGUOUS,
        TARGET_EXEMPT,
        NO_ACTIVE_PUNISHMENT
    }

    public PlayerModerationResult {
        Objects.requireNonNull(status, "status");
        identity = Objects.requireNonNull(identity, "identity");
        punishments = List.copyOf(Objects.requireNonNull(punishments, "punishments"));
        if (status == Status.APPLIED && (identity.isEmpty() || punishments.isEmpty())) {
            throw new IllegalArgumentException("An applied result requires a target and punishment");
        }
    }

    static PlayerModerationResult applied(PlayerIdentity identity, List<Punishment> punishments) {
        return new PlayerModerationResult(Status.APPLIED, Optional.of(identity), punishments);
    }

    static PlayerModerationResult notApplied(Status status, Optional<PlayerIdentity> identity) {
        return new PlayerModerationResult(status, identity, List.of());
    }
}
