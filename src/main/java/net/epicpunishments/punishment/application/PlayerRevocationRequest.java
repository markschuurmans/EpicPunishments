package net.epicpunishments.punishment.application;

import net.epicpunishments.common.domain.Actor;
import net.epicpunishments.punishment.domain.PunishmentType;

import java.util.Objects;

public record PlayerRevocationRequest(String target, PunishmentType type, String reason, Actor actor) {
    public PlayerRevocationRequest {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(actor, "actor");
        if (type == PunishmentType.WARNING) {
            throw new IllegalArgumentException("Warnings are not revoked through this use case");
        }
    }
}
