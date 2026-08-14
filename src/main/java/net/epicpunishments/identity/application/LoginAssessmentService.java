package net.epicpunishments.identity.application;

import net.epicpunishments.common.config.LoginFailurePolicy;
import net.epicpunishments.identity.domain.LoginAssessment;
import net.epicpunishments.identity.port.LoginAssessmentRepository;
import net.epicpunishments.punishment.application.SessionPunishmentCache;
import net.epicpunishments.punishment.domain.Punishment;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class LoginAssessmentService {
    private final LoginAssessmentRepository repository;
    private final PendingLoginAssessments pendingAssessments;
    private final SessionPunishmentCache sessionCache;
    private final Duration queryTimeout;
    private final LoginFailurePolicy failurePolicy;
    private final AtomicBoolean stopping = new AtomicBoolean();

    public LoginAssessmentService(
            LoginAssessmentRepository repository,
            PendingLoginAssessments pendingAssessments,
            SessionPunishmentCache sessionCache,
            Duration queryTimeout,
            LoginFailurePolicy failurePolicy
    ) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.pendingAssessments = Objects.requireNonNull(pendingAssessments, "pendingAssessments");
        this.sessionCache = Objects.requireNonNull(sessionCache, "sessionCache");
        this.queryTimeout = Objects.requireNonNull(queryTimeout, "queryTimeout");
        this.failurePolicy = Objects.requireNonNull(failurePolicy, "failurePolicy");
        if (queryTimeout.isNegative() || queryTimeout.isZero()) {
            throw new IllegalArgumentException("queryTimeout must be positive");
        }
    }

    public LoginDecision assess(LoginAttempt attempt) {
        Objects.requireNonNull(attempt, "attempt");
        if (stopping.get()) {
            return LoginDecision.temporaryFailure();
        }
        try {
            LoginAssessment assessment = repository.assessLogin(
                    attempt.playerId(),
                    attempt.address(),
                    attempt.attemptedAt()
            ).toCompletableFuture().get(queryTimeout.toNanos(), TimeUnit.NANOSECONDS);
            requireMatchingAssessment(attempt, assessment);
            return decide(assessment, false);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return decideAfterFailure(attempt);
        } catch (ExecutionException | TimeoutException | RuntimeException exception) {
            return decideAfterFailure(attempt);
        }
    }

    public void stop() {
        stopping.set(true);
        pendingAssessments.clear();
    }

    private LoginDecision decide(LoginAssessment assessment, boolean degraded) {
        Punishment ban = assessment.punishments().bans().stream()
                .filter(punishment -> punishment.isActiveAt(assessment.assessedAt()))
                .findFirst()
                .orElse(null);
        if (ban != null) {
            return LoginDecision.banned(ban, degraded);
        }
        if (stopping.get()) {
            return LoginDecision.temporaryFailure();
        }
        pendingAssessments.put(assessment);
        return LoginDecision.allowed(degraded);
    }

    private LoginDecision decideAfterFailure(LoginAttempt attempt) {
        if (failurePolicy == LoginFailurePolicy.DENY) {
            return LoginDecision.temporaryFailure();
        }
        return sessionCache.find(attempt.playerId(), attempt.address())
                .map(cached -> decide(new LoginAssessment(
                        attempt.playerId(),
                        attempt.address(),
                        attempt.attemptedAt(),
                        cached
                ), true))
                .orElseGet(() -> LoginDecision.allowed(true));
    }

    private static void requireMatchingAssessment(LoginAttempt attempt, LoginAssessment assessment) {
        if (!assessment.playerId().equals(attempt.playerId()) || !assessment.address().equals(attempt.address())) {
            throw new IllegalStateException("Login repository returned an assessment for a different subject");
        }
    }
}
