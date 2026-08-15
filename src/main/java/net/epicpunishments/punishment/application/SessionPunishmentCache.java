package net.epicpunishments.punishment.application;

import net.epicpunishments.identity.domain.LoginAssessment;
import net.epicpunishments.identity.domain.PlayerAddress;
import net.epicpunishments.punishment.domain.SessionPunishments;
import net.epicpunishments.punishment.domain.PlayerPunishmentTarget;
import net.epicpunishments.punishment.domain.Punishment;

import java.time.Instant;
import java.util.ArrayList;
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

    public Optional<Punishment> activeMute(UUID playerId, Instant at) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(at, "at");
        LoginAssessment assessment = sessions.get(playerId);
        return assessment == null ? Optional.empty() : assessment.punishments().mutes().stream()
                .filter(punishment -> punishment.isActiveAt(at))
                .findFirst();
    }

    public void apply(Punishment punishment) {
        Objects.requireNonNull(punishment, "punishment");
        if (!(punishment.target() instanceof PlayerPunishmentTarget target)) {
            throw new IllegalArgumentException("Only player punishments can be applied by this milestone");
        }
        sessions.computeIfPresent(target.playerId(), (ignored, assessment) -> {
            SessionPunishments current = assessment.punishments();
            var bans = new ArrayList<>(current.bans());
            var mutes = new ArrayList<>(current.mutes());
            var warnings = new ArrayList<>(current.undeliveredWarnings());
            switch (punishment.type()) {
                case BAN -> bans.add(punishment);
                case MUTE -> mutes.add(punishment);
                case WARNING -> warnings.add(punishment);
            }
            return withPunishments(assessment, new SessionPunishments(bans, mutes, warnings));
        });
    }

    public void revoke(UUID punishmentId) {
        Objects.requireNonNull(punishmentId, "punishmentId");
        sessions.replaceAll((ignored, assessment) -> {
            SessionPunishments current = assessment.punishments();
            return withPunishments(assessment, new SessionPunishments(
                    without(current.bans(), punishmentId),
                    without(current.mutes(), punishmentId),
                    without(current.undeliveredWarnings(), punishmentId)
            ));
        });
    }

    public void markWarningDelivered(UUID playerId, UUID punishmentId) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(punishmentId, "punishmentId");
        sessions.computeIfPresent(playerId, (ignored, assessment) -> withPunishments(
                assessment,
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

    private static LoginAssessment withPunishments(LoginAssessment assessment, SessionPunishments punishments) {
        return new LoginAssessment(
                assessment.playerId(),
                assessment.address(),
                assessment.assessedAt(),
                punishments
        );
    }

    private static java.util.List<Punishment> without(java.util.List<Punishment> values, UUID punishmentId) {
        return values.stream().filter(value -> !value.id().equals(punishmentId)).toList();
    }
}
