package net.epicpunishments.punishment.domain;

import net.epicpunishments.common.domain.Actor;

import java.time.Instant;
import java.util.Objects;

public record PunishmentRevocation(Actor actor, Instant revokedAt, String reason) {
    public PunishmentRevocation {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(revokedAt, "revokedAt");
        Objects.requireNonNull(reason, "reason");
        if (reason.isBlank() || reason.length() > 1_024) {
            throw new IllegalArgumentException("reason must contain between 1 and 1024 characters");
        }
    }
}
