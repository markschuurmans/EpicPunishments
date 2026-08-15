package net.epicpunishments.punishment.application;

import net.epicpunishments.common.domain.Actor;
import net.epicpunishments.punishment.domain.PunishmentType;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

public record PlayerModerationRequest(
        String target,
        PunishmentType type,
        String reason,
        Optional<Duration> duration,
        Actor actor,
        boolean overrideExempt
) {
    public PlayerModerationRequest {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(reason, "reason");
        duration = Objects.requireNonNull(duration, "duration");
        Objects.requireNonNull(actor, "actor");
        if (type == PunishmentType.WARNING && duration.isPresent()) {
            throw new IllegalArgumentException("Warnings cannot have a duration");
        }
    }
}
