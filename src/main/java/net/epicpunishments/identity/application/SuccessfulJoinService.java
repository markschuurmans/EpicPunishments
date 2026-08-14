package net.epicpunishments.identity.application;

import net.epicpunishments.common.config.LoginFailurePolicy;
import net.epicpunishments.identity.domain.LoginAssessment;
import net.epicpunishments.identity.domain.SuccessfulJoin;
import net.epicpunishments.identity.port.LoginAssessmentRepository;
import net.epicpunishments.identity.port.PlayerIdentityRepository;
import net.epicpunishments.punishment.application.SessionPunishmentCache;
import net.epicpunishments.punishment.domain.Punishment;
import net.epicpunishments.punishment.domain.SessionPunishments;
import net.epicpunishments.punishment.port.PunishmentRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SuccessfulJoinService {
    private final PlayerIdentityRepository identities;
    private final LoginAssessmentRepository loginAssessments;
    private final PunishmentRepository punishments;
    private final PendingLoginAssessments pendingAssessments;
    private final SessionPunishmentCache sessionCache;
    private final LoginFailurePolicy failurePolicy;
    private final Duration queryTimeout;
    private final ConcurrentHashMap<UUID, UUID> activeSessions = new ConcurrentHashMap<>();
    private final AtomicBoolean stopping = new AtomicBoolean();

    public SuccessfulJoinService(
            PlayerIdentityRepository identities,
            LoginAssessmentRepository loginAssessments,
            PunishmentRepository punishments,
            PendingLoginAssessments pendingAssessments,
            SessionPunishmentCache sessionCache,
            LoginFailurePolicy failurePolicy,
            Duration queryTimeout
    ) {
        this.identities = Objects.requireNonNull(identities, "identities");
        this.loginAssessments = Objects.requireNonNull(loginAssessments, "loginAssessments");
        this.punishments = Objects.requireNonNull(punishments, "punishments");
        this.pendingAssessments = Objects.requireNonNull(pendingAssessments, "pendingAssessments");
        this.sessionCache = Objects.requireNonNull(sessionCache, "sessionCache");
        this.failurePolicy = Objects.requireNonNull(failurePolicy, "failurePolicy");
        this.queryTimeout = Objects.requireNonNull(queryTimeout, "queryTimeout");
        if (queryTimeout.isNegative() || queryTimeout.isZero()) {
            throw new IllegalArgumentException("queryTimeout must be positive");
        }
    }

    public JoinProcessing process(SuccessfulJoin join) {
        Objects.requireNonNull(join, "join");
        if (stopping.get()) {
            var failure = new RejectedExecutionException("Successful join service is stopping");
            CompletableFuture<JoinOutcome> rejectedAssessment = CompletableFuture.failedFuture(failure);
            CompletableFuture<Void> rejectedWrite = CompletableFuture.failedFuture(failure);
            return new JoinProcessing(rejectedAssessment, rejectedWrite);
        }
        UUID sessionToken = UUID.randomUUID();
        activeSessions.put(join.playerId(), sessionToken);
        CompletionStage<Void> successfulJoinWrite = identities.recordSuccessfulJoin(join);
        Optional<LoginAssessment> pending = pendingAssessments.take(join.playerId())
                .filter(assessment -> assessment.address().equals(join.address()));
        CompletionStage<JoinOutcome> assessment = pending
                .<CompletionStage<JoinOutcome>>map(value -> CompletableFuture.completedFuture(
                        apply(value, false, sessionToken)
                ))
                .orElseGet(() -> fallbackAssessment(join, sessionToken));
        return new JoinProcessing(assessment, successfulJoinWrite);
    }

    public CompletionStage<Boolean> recordWarningDelivery(
            UUID punishmentId,
            UUID playerId,
            Instant deliveredAt
    ) {
        if (stopping.get()) {
            return CompletableFuture.failedFuture(new RejectedExecutionException(
                    "Successful join service is stopping"
            ));
        }
        return punishments.recordWarningDelivery(punishmentId, playerId, deliveredAt)
                .thenApply(inserted -> {
                    sessionCache.markWarningDelivered(playerId, punishmentId);
                    return inserted;
                });
    }

    public void endSession(UUID playerId) {
        activeSessions.remove(Objects.requireNonNull(playerId, "playerId"));
        sessionCache.remove(playerId);
    }

    public void stop() {
        stopping.set(true);
        activeSessions.clear();
        sessionCache.clear();
    }

    private CompletionStage<JoinOutcome> fallbackAssessment(SuccessfulJoin join, UUID sessionToken) {
        return loginAssessments.assessLogin(join.playerId(), join.address(), join.joinedAt())
                .toCompletableFuture()
                .orTimeout(queryTimeout.toNanos(), TimeUnit.NANOSECONDS)
                .handle((assessment, failure) -> {
                    if (failure != null) {
                        return afterFailure(join, sessionToken);
                    }
                    requireMatchingAssessment(join, assessment);
                    return apply(assessment, false, sessionToken);
                });
    }

    private JoinOutcome afterFailure(SuccessfulJoin join, UUID sessionToken) {
        if (failurePolicy == LoginFailurePolicy.DENY) {
            return new JoinOutcome(
                    JoinOutcome.DisconnectReason.TEMPORARY_FAILURE,
                    Optional.empty(),
                    List.of(),
                    true
            );
        }
        SessionPunishments cached = sessionCache.find(join.playerId(), join.address())
                .orElse(SessionPunishments.empty());
        return apply(
                new LoginAssessment(join.playerId(), join.address(), join.joinedAt(), cached),
                true,
                sessionToken
        );
    }

    private JoinOutcome apply(LoginAssessment assessment, boolean degraded, UUID sessionToken) {
        if (!stopping.get() && sessionToken.equals(activeSessions.get(assessment.playerId()))) {
            sessionCache.put(assessment);
        }
        Punishment ban = assessment.punishments().bans().stream()
                .filter(punishment -> punishment.isActiveAt(assessment.assessedAt()))
                .findFirst()
                .orElse(null);
        return new JoinOutcome(
                ban == null ? JoinOutcome.DisconnectReason.NONE : JoinOutcome.DisconnectReason.BANNED,
                Optional.ofNullable(ban),
                ban == null ? assessment.punishments().undeliveredWarnings() : List.of(),
                degraded
        );
    }

    private static void requireMatchingAssessment(SuccessfulJoin join, LoginAssessment assessment) {
        if (!assessment.playerId().equals(join.playerId()) || !assessment.address().equals(join.address())) {
            throw new CompletionException(new IllegalStateException(
                    "Login repository returned an assessment for a different subject"
            ));
        }
    }
}
