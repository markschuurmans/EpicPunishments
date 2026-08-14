package net.epicpunishments.punishment.domain;

import net.epicpunishments.common.domain.Actor;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record Punishment(
        UUID id,
        PunishmentType type,
        PunishmentTarget target,
        String reason,
        Actor issuer,
        Instant createdAt,
        Optional<Instant> expiresAt,
        Optional<PunishmentRevocation> revocation
) {
    public Punishment {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(issuer, "issuer");
        Objects.requireNonNull(createdAt, "createdAt");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        revocation = Objects.requireNonNull(revocation, "revocation");
        if (reason.isBlank() || reason.length() > 1_024) {
            throw new IllegalArgumentException("reason must contain between 1 and 1024 characters");
        }
        expiresAt.ifPresent(expiry -> {
            if (!expiry.isAfter(createdAt)) {
                throw new IllegalArgumentException("expiresAt must be after createdAt");
            }
        });
        revocation.ifPresent(value -> {
            if (value.revokedAt().isBefore(createdAt)) {
                throw new IllegalArgumentException("A punishment cannot be revoked before it was created");
            }
        });
    }

    public boolean isActiveAt(Instant instant) {
        Objects.requireNonNull(instant, "instant");
        return !instant.isBefore(createdAt)
                && revocation.isEmpty()
                && expiresAt.map(expiry -> instant.isBefore(expiry)).orElse(true);
    }

    public Punishment revoke(PunishmentRevocation value) {
        Objects.requireNonNull(value, "value");
        if (revocation.isPresent()) {
            throw new IllegalStateException("Punishment is already revoked");
        }
        return new Punishment(id, type, target, reason, issuer, createdAt, expiresAt, Optional.of(value));
    }
}
