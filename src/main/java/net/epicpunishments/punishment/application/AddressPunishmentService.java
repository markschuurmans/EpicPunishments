package net.epicpunishments.punishment.application;

import net.epicpunishments.common.config.PunishmentConfiguration;
import net.epicpunishments.common.domain.Actor;
import net.epicpunishments.common.domain.AuditEntry;
import net.epicpunishments.common.domain.Page;
import net.epicpunishments.common.domain.PageRequest;
import net.epicpunishments.identity.domain.PlayerAddress;
import net.epicpunishments.punishment.domain.AddressPunishmentTarget;
import net.epicpunishments.punishment.domain.Punishment;
import net.epicpunishments.punishment.domain.PunishmentRevocation;
import net.epicpunishments.punishment.domain.PunishmentType;
import net.epicpunishments.punishment.port.ModerationMutationResult;
import net.epicpunishments.punishment.port.ModerationMutationStore;
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

public final class AddressPunishmentService {
    private final AddressTargetParser targets;
    private final PunishmentRepository punishments;
    private final ModerationMutationStore mutations;
    private final SessionPunishmentCache sessions;
    private final Supplier<PunishmentConfiguration> policy;
    private final Clock clock;
    private final AtomicBoolean stopping = new AtomicBoolean();

    public AddressPunishmentService(AddressTargetParser targets, PunishmentRepository punishments,
            ModerationMutationStore mutations, SessionPunishmentCache sessions,
            Supplier<PunishmentConfiguration> policy, Clock clock) {
        this.targets = Objects.requireNonNull(targets, "targets");
        this.punishments = Objects.requireNonNull(punishments, "punishments");
        this.mutations = Objects.requireNonNull(mutations, "mutations");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public CompletionStage<AddressModerationResult> create(PlayerModerationRequest request) {
        Objects.requireNonNull(request, "request");
        rejectIfStopping();
        validateReason(request.reason());
        request.duration().ifPresent(this::validateDuration);
        PlayerAddress address = targets.parse(request.target());
        Instant now = clock.instant();
        Punishment punishment = new Punishment(UUID.randomUUID(), request.type(),
                new AddressPunishmentTarget(address), request.reason(), request.actor(), now,
                request.duration().map(now::plus), Optional.empty());
        return mutations.createPunishment(punishment, audit(request.actor(), "punishment.create", punishment, now))
                .thenApply(result -> {
                    if (result.status() != ModerationMutationResult.Status.APPLIED) {
                        throw new IllegalStateException("Create punishment did not apply");
                    }
                    Punishment committed = result.punishment().orElseThrow();
                    sessions.apply(committed);
                    return new AddressModerationResult(
                            AddressModerationResult.Status.APPLIED,
                            address,
                            List.of(committed)
                    );
                });
    }

    public CompletionStage<AddressModerationResult> revoke(PlayerRevocationRequest request) {
        Objects.requireNonNull(request, "request");
        rejectIfStopping();
        validateReason(request.reason());
        PlayerAddress address = targets.parse(request.target());
        Instant now = clock.instant();
        return punishments.findActiveForAddress(address, UUID.randomUUID(), now).thenCompose(active -> {
            List<Punishment> matching = request.type() == PunishmentType.BAN ? active.bans() : active.mutes();
            if (matching.isEmpty()) {
                return CompletableFuture.completedFuture(new AddressModerationResult(
                        AddressModerationResult.Status.NO_ACTIVE_PUNISHMENT, address, List.of()));
            }
            CompletionStage<List<Punishment>> stage = CompletableFuture.completedFuture(new ArrayList<>());
            for (Punishment punishment : matching) {
                stage = stage.thenCompose(revoked -> {
                    PunishmentRevocation revocation = new PunishmentRevocation(request.actor(), now, request.reason());
                    return mutations.revokePunishment(punishment.id(), revocation,
                            audit(request.actor(), "punishment.revoke", punishment, now)).thenApply(result -> {
                        if (result.status() == ModerationMutationResult.Status.APPLIED) {
                            Punishment committed = result.punishment().orElseThrow();
                            sessions.revoke(committed.id());
                            revoked.add(committed);
                        }
                        return revoked;
                    });
                });
            }
            return stage.thenApply(values -> new AddressModerationResult(values.isEmpty()
                    ? AddressModerationResult.Status.NO_ACTIVE_PUNISHMENT
                    : AddressModerationResult.Status.APPLIED, address, List.copyOf(values)));
        });
    }

    public CompletionStage<Page<Punishment>> history(String target, Optional<PunishmentType> type, PageRequest page) {
        rejectIfStopping();
        return punishments.findHistory(new AddressPunishmentTarget(targets.parse(target)), type, page);
    }

    public void stop() {
        stopping.set(true);
    }

    private void rejectIfStopping() {
        if (stopping.get()) {
            throw new RejectedExecutionException("Punishment service is stopping");
        }
    }

    private void validateReason(String reason) {
        int maximumLength = policy.get().maximumReasonLength();
        if (reason.isBlank() || reason.length() > maximumLength) {
            throw new IllegalArgumentException(
                    "Reason must contain between 1 and " + maximumLength + " characters"
            );
        }
    }

    private void validateDuration(Duration duration) {
        if (duration.isZero() || duration.isNegative() || duration.compareTo(policy.get().maximumDuration()) > 0) {
            throw new IllegalArgumentException("Duration exceeds the configured bounds");
        }
    }

    private static AuditEntry audit(Actor actor, String action, Punishment punishment, Instant at) {
        PlayerAddress address = ((AddressPunishmentTarget) punishment.target()).address();
        return new AuditEntry(UUID.randomUUID(), actor, action, "punishment", punishment.id(), at,
                "{\"type\":\"" + punishment.type().name() + "\",\"targetAddress\":\"" + address.redacted() + "\"}");
    }
}
