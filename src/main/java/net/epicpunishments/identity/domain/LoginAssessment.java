package net.epicpunishments.identity.domain;

import net.epicpunishments.punishment.domain.SessionPunishments;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record LoginAssessment(
        UUID playerId,
        PlayerAddress address,
        Instant assessedAt,
        SessionPunishments punishments
) {
    public LoginAssessment {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(assessedAt, "assessedAt");
        Objects.requireNonNull(punishments, "punishments");
    }
}
