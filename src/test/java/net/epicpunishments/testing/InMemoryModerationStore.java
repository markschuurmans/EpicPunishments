package net.epicpunishments.testing;

import net.epicpunishments.common.domain.AuditEntry;
import net.epicpunishments.common.domain.Page;
import net.epicpunishments.common.domain.PageRequest;
import net.epicpunishments.common.persistence.PersistenceException;
import net.epicpunishments.common.persistence.PersistenceFailureKind;
import net.epicpunishments.identity.domain.LoginAssessment;
import net.epicpunishments.identity.domain.PlayerAddress;
import net.epicpunishments.identity.port.LoginAssessmentRepository;
import net.epicpunishments.punishment.domain.AddressPunishmentTarget;
import net.epicpunishments.punishment.domain.PlayerPunishmentTarget;
import net.epicpunishments.punishment.domain.Punishment;
import net.epicpunishments.punishment.domain.PunishmentRevocation;
import net.epicpunishments.punishment.domain.PunishmentTarget;
import net.epicpunishments.punishment.domain.PunishmentType;
import net.epicpunishments.punishment.domain.SessionPunishments;
import net.epicpunishments.punishment.port.ModerationMutationResult;
import net.epicpunishments.punishment.port.ModerationMutationStore;
import net.epicpunishments.punishment.port.PunishmentRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Predicate;

public final class InMemoryModerationStore implements
        PunishmentRepository,
        ModerationMutationStore,
        LoginAssessmentRepository,
        ModerationStoreTestFixture {
    private final Map<UUID, Punishment> storedPunishments = new HashMap<>();
    private final List<AuditEntry> storedAuditEntries = new ArrayList<>();
    private final Set<Delivery> deliveries = new HashSet<>();
    private boolean failNextAuditWrite;

    @Override
    public PunishmentRepository punishments() {
        return this;
    }

    @Override
    public ModerationMutationStore mutations() {
        return this;
    }

    @Override
    public LoginAssessmentRepository loginAssessments() {
        return this;
    }

    @Override
    public synchronized List<AuditEntry> auditEntries() {
        return List.copyOf(storedAuditEntries);
    }

    @Override
    public synchronized void failNextAuditWrite() {
        failNextAuditWrite = true;
    }

    @Override
    public synchronized CompletionStage<ModerationMutationResult> createPunishment(
            Punishment punishment,
            AuditEntry auditEntry
    ) {
        if (failAuditWrite()) {
            return failedAuditWrite();
        }
        if (storedPunishments.containsKey(punishment.id())) {
            return CompletableFuture.failedFuture(new PersistenceException(
                    PersistenceFailureKind.CONFLICT,
                    "Punishment already exists"
            ));
        }
        requireMatchingAudit(punishment.id(), auditEntry);
        storedPunishments.put(punishment.id(), punishment);
        storedAuditEntries.add(auditEntry);
        return CompletableFuture.completedFuture(ModerationMutationResult.applied(punishment));
    }

    @Override
    public synchronized CompletionStage<ModerationMutationResult> revokePunishment(
            UUID punishmentId,
            PunishmentRevocation revocation,
            AuditEntry auditEntry
    ) {
        Punishment punishment = storedPunishments.get(punishmentId);
        if (punishment == null) {
            return CompletableFuture.completedFuture(ModerationMutationResult.notFound());
        }
        if (punishment.revocation().isPresent()) {
            return CompletableFuture.completedFuture(ModerationMutationResult.alreadyRevoked());
        }
        if (failAuditWrite()) {
            return failedAuditWrite();
        }
        requireMatchingAudit(punishmentId, auditEntry);
        Punishment revoked = punishment.revoke(revocation);
        storedPunishments.put(punishmentId, revoked);
        storedAuditEntries.add(auditEntry);
        return CompletableFuture.completedFuture(ModerationMutationResult.applied(revoked));
    }

    @Override
    public synchronized CompletionStage<Optional<Punishment>> findById(UUID punishmentId) {
        return CompletableFuture.completedFuture(Optional.ofNullable(storedPunishments.get(punishmentId)));
    }

    @Override
    public synchronized CompletionStage<SessionPunishments> findActiveForPlayer(UUID playerId, Instant at) {
        return CompletableFuture.completedFuture(sessionFor(
                punishment -> punishment.target() instanceof PlayerPunishmentTarget target
                        && target.playerId().equals(playerId),
                playerId,
                at
        ));
    }

    @Override
    public synchronized CompletionStage<SessionPunishments> findActiveForAddress(
            PlayerAddress address,
            UUID affectedPlayerId,
            Instant at
    ) {
        return CompletableFuture.completedFuture(sessionFor(
                punishment -> punishment.target() instanceof AddressPunishmentTarget target
                        && target.address().equals(address),
                affectedPlayerId,
                at
        ));
    }

    @Override
    public synchronized CompletionStage<Page<Punishment>> findHistory(
            PunishmentTarget target,
            PageRequest pageRequest
    ) {
        List<Punishment> matching = storedPunishments.values().stream()
                .filter(punishment -> punishment.target().equals(target))
                .sorted(Comparator.comparing(Punishment::createdAt).reversed()
                        .thenComparing(punishment -> punishment.id().toString()))
                .toList();
        int from = (int) Math.min(pageRequest.offset(), matching.size());
        int to = Math.min(from + pageRequest.size(), matching.size());
        return CompletableFuture.completedFuture(new Page<>(
                matching.subList(from, to),
                pageRequest.page(),
                pageRequest.size(),
                matching.size()
        ));
    }

    @Override
    public synchronized CompletionStage<Boolean> recordWarningDelivery(
            UUID punishmentId,
            UUID affectedPlayerId,
            Instant deliveredAt
    ) {
        Punishment punishment = storedPunishments.get(punishmentId);
        if (punishment == null || punishment.type() != PunishmentType.WARNING) {
            return CompletableFuture.failedFuture(new PersistenceException(
                    PersistenceFailureKind.INVALID_DATA,
                    "Warning punishment does not exist"
            ));
        }
        return CompletableFuture.completedFuture(deliveries.add(new Delivery(punishmentId, affectedPlayerId)));
    }

    @Override
    public synchronized CompletionStage<LoginAssessment> assessLogin(
            UUID playerId,
            PlayerAddress address,
            Instant assessedAt
    ) {
        SessionPunishments player = sessionFor(
                punishment -> punishment.target() instanceof PlayerPunishmentTarget target
                        && target.playerId().equals(playerId),
                playerId,
                assessedAt
        );
        SessionPunishments ip = sessionFor(
                punishment -> punishment.target() instanceof AddressPunishmentTarget target
                        && target.address().equals(address),
                playerId,
                assessedAt
        );
        return CompletableFuture.completedFuture(new LoginAssessment(
                playerId,
                address,
                assessedAt,
                new SessionPunishments(
                        combine(player.bans(), ip.bans()),
                        combine(player.mutes(), ip.mutes()),
                        combine(player.undeliveredWarnings(), ip.undeliveredWarnings())
                )
        ));
    }

    private SessionPunishments sessionFor(Predicate<Punishment> targetMatches, UUID affectedPlayerId, Instant at) {
        List<Punishment> active = storedPunishments.values().stream()
                .filter(targetMatches)
                .filter(punishment -> punishment.isActiveAt(at))
                .toList();
        return new SessionPunishments(
                ofType(active, PunishmentType.BAN),
                ofType(active, PunishmentType.MUTE),
                ofType(active, PunishmentType.WARNING).stream()
                        .filter(warning -> !deliveries.contains(new Delivery(warning.id(), affectedPlayerId)))
                        .toList()
        );
    }

    private boolean failAuditWrite() {
        if (!failNextAuditWrite) {
            return false;
        }
        failNextAuditWrite = false;
        return true;
    }

    private static CompletionStage<ModerationMutationResult> failedAuditWrite() {
        return CompletableFuture.failedFuture(new PersistenceException(
                PersistenceFailureKind.TRANSIENT,
                "Induced audit write failure"
        ));
    }

    private static void requireMatchingAudit(UUID entityId, AuditEntry auditEntry) {
        if (!auditEntry.entityId().equals(entityId)) {
            throw new IllegalArgumentException("Audit entry does not refer to the mutated punishment");
        }
    }

    private static List<Punishment> ofType(List<Punishment> punishments, PunishmentType type) {
        return punishments.stream().filter(punishment -> punishment.type() == type).toList();
    }

    private static List<Punishment> combine(List<Punishment> first, List<Punishment> second) {
        var combined = new ArrayList<>(first);
        combined.addAll(second);
        return List.copyOf(combined);
    }

    private record Delivery(UUID punishmentId, UUID affectedPlayerId) {
    }
}
