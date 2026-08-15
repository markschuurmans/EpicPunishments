package net.epicpunishments.punishment.application;

import net.epicpunishments.common.config.PunishmentConfiguration;
import net.epicpunishments.common.domain.Actor;
import net.epicpunishments.common.domain.AuditEntry;
import net.epicpunishments.common.domain.PageRequest;
import net.epicpunishments.identity.domain.PlayerIdentity;
import net.epicpunishments.punishment.domain.PlayerPunishmentTarget;
import net.epicpunishments.punishment.domain.Punishment;
import net.epicpunishments.punishment.domain.PunishmentRevocation;
import net.epicpunishments.punishment.domain.PunishmentType;
import net.epicpunishments.punishment.port.ModerationMutationResult;
import net.epicpunishments.punishment.port.ModerationMutationStore;
import net.epicpunishments.punishment.port.PlayerExemptionLookup;
import net.epicpunishments.punishment.port.PunishmentRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

public final class PlayerPunishmentService {
    private static final String ENTITY_TYPE = "punishment";

    private final PlayerTargetResolver targets;
    private final PlayerExemptionLookup exemptions;
    private final TargetAuthorizationService authorization;
    private final PunishmentRepository punishments;
    private final ModerationMutationStore mutations;
    private final SessionPunishmentCache sessions;
    private final Supplier<PunishmentConfiguration> policy;
    private final Clock clock;
    private final AtomicBoolean stopping = new AtomicBoolean();

    public PlayerPunishmentService(
            PlayerTargetResolver targets,
            PlayerExemptionLookup exemptions,
            TargetAuthorizationService authorization,
            PunishmentRepository punishments,
            ModerationMutationStore mutations,
            SessionPunishmentCache sessions,
            Supplier<PunishmentConfiguration> policy,
            Clock clock
    ) {
        this.targets = Objects.requireNonNull(targets, "targets");
        this.exemptions = Objects.requireNonNull(exemptions, "exemptions");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.punishments = Objects.requireNonNull(punishments, "punishments");
        this.mutations = Objects.requireNonNull(mutations, "mutations");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public CompletionStage<PlayerModerationResult> create(PlayerModerationRequest request) {
        Objects.requireNonNull(request, "request");
        CompletionStage<PlayerModerationResult> rejected = rejectIfStopping();
        if (rejected != null) {
            return rejected;
        }
        validateReason(request.reason());
        request.duration().ifPresent(this::validateDuration);
        return targets.resolve(request.target()).thenCompose(resolution -> {
            PlayerModerationResult unresolved = unresolved(resolution);
            if (unresolved != null) {
                return CompletableFuture.completedFuture(unresolved);
            }
            PlayerIdentity identity = resolution.identity().orElseThrow();
            return exemptions.isExempt(identity.playerId()).thenCompose(exempt -> {
                if (!authorization.mayPunish(request.actor(), request.overrideExempt(), exempt)) {
                    return CompletableFuture.completedFuture(PlayerModerationResult.notApplied(
                            PlayerModerationResult.Status.TARGET_EXEMPT,
                            Optional.of(identity)
                    ));
                }
                Instant now = clock.instant();
                Punishment punishment = new Punishment(
                        UUID.randomUUID(),
                        request.type(),
                        new PlayerPunishmentTarget(identity.playerId()),
                        request.reason(),
                        request.actor(),
                        now,
                        request.duration().map(now::plus),
                        Optional.empty()
                );
                AuditEntry audit = audit(request.actor(), "punishment.create", punishment.id(), now,
                        details(punishment));
                return mutations.createPunishment(punishment, audit).thenApply(result -> {
                    Punishment committed = requireApplied(result);
                    sessions.apply(committed);
                    return PlayerModerationResult.applied(identity, List.of(committed));
                });
            });
        });
    }

    public CompletionStage<PlayerModerationResult> revoke(PlayerRevocationRequest request) {
        Objects.requireNonNull(request, "request");
        CompletionStage<PlayerModerationResult> rejected = rejectIfStopping();
        if (rejected != null) {
            return rejected;
        }
        validateReason(request.reason());
        return targets.resolve(request.target()).thenCompose(resolution -> {
            PlayerModerationResult unresolved = unresolved(resolution);
            if (unresolved != null) {
                return CompletableFuture.completedFuture(unresolved);
            }
            PlayerIdentity identity = resolution.identity().orElseThrow();
            Instant now = clock.instant();
            return punishments.findActiveForPlayer(identity.playerId(), now).thenCompose(active -> {
                List<Punishment> matching = switch (request.type()) {
                    case BAN -> active.bans();
                    case MUTE -> active.mutes();
                    case WARNING -> throw new IllegalStateException("Warnings cannot be revoked here");
                };
                if (matching.isEmpty()) {
                    return CompletableFuture.completedFuture(PlayerModerationResult.notApplied(
                            PlayerModerationResult.Status.NO_ACTIVE_PUNISHMENT,
                            Optional.of(identity)
                    ));
                }
                PunishmentRevocation revocation = new PunishmentRevocation(request.actor(), now, request.reason());
                return revokeAll(matching, revocation, request.actor()).thenApply(revoked -> revoked.isEmpty()
                        ? PlayerModerationResult.notApplied(
                                PlayerModerationResult.Status.NO_ACTIVE_PUNISHMENT,
                                Optional.of(identity))
                        : PlayerModerationResult.applied(identity, revoked));
            });
        });
    }

    public CompletionStage<PlayerHistoryResult> history(
            String target,
            Optional<PunishmentType> type,
            PageRequest pageRequest
    ) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(pageRequest, "pageRequest");
        if (stopping.get()) {
            return CompletableFuture.failedFuture(new RejectedExecutionException("Punishment service is stopping"));
        }
        return targets.resolve(target).thenCompose(resolution -> {
            PlayerModerationResult unresolved = unresolved(resolution);
            if (unresolved != null) {
                return CompletableFuture.completedFuture(new PlayerHistoryResult(
                        unresolved.status(), unresolved.identity(), Optional.empty()));
            }
            PlayerIdentity identity = resolution.identity().orElseThrow();
            return punishments.findHistory(new PlayerPunishmentTarget(identity.playerId()), type, pageRequest)
                    .thenApply(page -> new PlayerHistoryResult(
                            PlayerModerationResult.Status.APPLIED,
                            Optional.of(identity),
                            Optional.of(page)
                    ));
        });
    }

    public void stop() {
        stopping.set(true);
    }

    private CompletionStage<List<Punishment>> revokeAll(
            List<Punishment> matching,
            PunishmentRevocation revocation,
            Actor actor
    ) {
        CompletionStage<List<Punishment>> stage = CompletableFuture.completedFuture(new ArrayList<>());
        for (Punishment punishment : matching) {
            stage = stage.thenCompose(revoked -> {
                AuditEntry audit = audit(actor, "punishment.revoke", punishment.id(), revocation.revokedAt(),
                        details(punishment));
                return mutations.revokePunishment(punishment.id(), revocation, audit).thenApply(result -> {
                    if (result.status() == ModerationMutationResult.Status.APPLIED) {
                        Punishment committed = result.punishment().orElseThrow();
                        sessions.revoke(committed.id());
                        revoked.add(committed);
                    }
                    return revoked;
                });
            });
        }
        return stage.thenApply(List::copyOf);
    }

    private PlayerModerationResult unresolved(PlayerTargetResolution resolution) {
        return switch (resolution.status()) {
            case RESOLVED -> null;
            case INVALID -> PlayerModerationResult.notApplied(
                    PlayerModerationResult.Status.INVALID_TARGET, Optional.empty());
            case NOT_FOUND -> PlayerModerationResult.notApplied(
                    PlayerModerationResult.Status.TARGET_NOT_FOUND, Optional.empty());
            case AMBIGUOUS -> PlayerModerationResult.notApplied(
                    PlayerModerationResult.Status.TARGET_AMBIGUOUS, Optional.empty());
        };
    }

    private CompletionStage<PlayerModerationResult> rejectIfStopping() {
        return stopping.get()
                ? CompletableFuture.failedFuture(new RejectedExecutionException("Punishment service is stopping"))
                : null;
    }

    private void validateReason(String reason) {
        PunishmentConfiguration currentPolicy = policy.get();
        if (reason.isBlank() || reason.length() > currentPolicy.maximumReasonLength()) {
            throw new IllegalArgumentException("Reason must contain between 1 and "
                    + currentPolicy.maximumReasonLength() + " characters");
        }
    }

    private void validateDuration(Duration duration) {
        if (duration.isNegative() || duration.isZero()
                || duration.compareTo(policy.get().maximumDuration()) > 0) {
            throw new IllegalArgumentException("Duration exceeds the configured bounds");
        }
    }

    private static Punishment requireApplied(ModerationMutationResult result) {
        if (result.status() != ModerationMutationResult.Status.APPLIED) {
            throw new IllegalStateException("Create punishment did not apply");
        }
        return result.punishment().orElseThrow();
    }

    private static AuditEntry audit(Actor actor, String action, UUID entityId, Instant at, String details) {
        return new AuditEntry(UUID.randomUUID(), actor, action, ENTITY_TYPE, entityId, at, details);
    }

    private static String details(Punishment punishment) {
        PlayerPunishmentTarget target = (PlayerPunishmentTarget) punishment.target();
        return "{\"type\":\"" + punishment.type().name() + "\",\"targetPlayerId\":\""
                + target.playerId() + "\"}";
    }
}
