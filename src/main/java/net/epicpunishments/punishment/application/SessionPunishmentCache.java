package net.epicpunishments.punishment.application;

import net.epicpunishments.identity.domain.LoginAssessment;
import net.epicpunishments.identity.domain.PlayerAddress;
import net.epicpunishments.punishment.domain.SessionPunishments;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SessionPunishmentCache {
    private final ConcurrentHashMap<UUID, LoginAssessment> sessions = new ConcurrentHashMap<>();

    public void put(LoginAssessment assessment) {
        Objects.requireNonNull(assessment, "assessment");
        sessions.put(assessment.playerId(), assessment);
    }

    public Optional<SessionPunishments> find(UUID playerId, PlayerAddress address) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(address, "address");
        LoginAssessment assessment = sessions.get(playerId);
        return assessment != null && assessment.address().equals(address)
                ? Optional.of(assessment.punishments())
                : Optional.empty();
    }

    public boolean isBanned(UUID playerId, PlayerAddress address, Instant at) {
        Objects.requireNonNull(at, "at");
        return find(playerId, address).map(punishments -> punishments.isBannedAt(at)).orElse(false);
    }

    public void markWarningDelivered(UUID playerId, UUID punishmentId) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(punishmentId, "punishmentId");
        sessions.computeIfPresent(playerId, (ignored, assessment) -> new LoginAssessment(
                assessment.playerId(),
                assessment.address(),
                assessment.assessedAt(),
                new SessionPunishments(
                        assessment.punishments().bans(),
                        assessment.punishments().mutes(),
                        assessment.punishments().undeliveredWarnings().stream()
                                .filter(warning -> !warning.id().equals(punishmentId))
                                .toList()
                )
        ));
    }

    public void remove(UUID playerId) {
        sessions.remove(Objects.requireNonNull(playerId, "playerId"));
    }

    public int size() {
        return sessions.size();
    }

    public void clear() {
        sessions.clear();
    }
}
