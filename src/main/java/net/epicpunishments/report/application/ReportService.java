package net.epicpunishments.report.application;

import net.epicpunishments.common.config.ReportConfiguration;
import net.epicpunishments.common.domain.Actor;
import net.epicpunishments.common.domain.AuditEntry;
import net.epicpunishments.common.domain.Page;
import net.epicpunishments.common.domain.PageRequest;
import net.epicpunishments.common.persistence.PersistenceException;
import net.epicpunishments.common.persistence.PersistenceFailureKind;
import net.epicpunishments.identity.domain.PlayerIdentity;
import net.epicpunishments.identity.port.PlayerIdentityRepository;
import net.epicpunishments.report.domain.Report;
import net.epicpunishments.report.domain.ReportNotification;
import net.epicpunishments.report.domain.ReportParticipant;
import net.epicpunishments.report.domain.ReportResponse;
import net.epicpunishments.report.domain.ReportStatus;
import net.epicpunishments.report.domain.ResponseVisibility;
import net.epicpunishments.report.port.ReportMutationResult;
import net.epicpunishments.report.port.ReportMutationStore;
import net.epicpunishments.report.port.ReportRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

public final class ReportService {
    private static final String ENTITY_TYPE = "report";

    private final PlayerIdentityRepository identities;
    private final ReportRepository reports;
    private final ReportMutationStore mutations;
    private final Supplier<ReportConfiguration> configuration;
    private final Clock clock;
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final Object creationMonitor = new Object();
    private final Map<UUID, CompletableFuture<Void>> creationTails = new HashMap<>();

    public ReportService(
            PlayerIdentityRepository identities,
            ReportRepository reports,
            ReportMutationStore mutations,
            Supplier<ReportConfiguration> configuration,
            Clock clock
    ) {
        this.identities = Objects.requireNonNull(identities, "identities");
        this.reports = Objects.requireNonNull(reports, "reports");
        this.mutations = Objects.requireNonNull(mutations, "mutations");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public CompletionStage<ReportResult> create(
            UUID reporterId,
            String reporterName,
            String reportedName,
            String reason
    ) {
        requireAccepting();
        Objects.requireNonNull(reporterId, "reporterId");
        ReportConfiguration policy = configuration.get();
        String capturedReporterName = requireText(reporterName, "reporterName", 16);
        String capturedReportedName = requireText(reportedName, "reportedName", 16);
        String capturedReason = requireText(reason, "reason", policy.maximumReasonLength());
        return identities.findByCurrentOrHistoricalName(capturedReportedName).thenCompose(matches -> {
            if (matches.isEmpty()) {
                return completed(ReportResult.Status.TARGET_NOT_FOUND);
            }
            if (matches.size() > 1) {
                return completed(ReportResult.Status.TARGET_AMBIGUOUS);
            }
            PlayerIdentity reported = matches.getFirst();
            if (reported.playerId().equals(reporterId)) {
                return completed(ReportResult.Status.SELF_REPORT);
            }
            return serializeCreation(reporterId, () ->
                    createResolved(reporterId, capturedReporterName, reported, capturedReason, policy.cooldown()));
        });
    }

    public CompletionStage<Page<Report>> listOwn(UUID reporterId, PageRequest pageRequest) {
        requireAccepting();
        return reports.findByReporter(Objects.requireNonNull(reporterId, "reporterId"), pageRequest);
    }

    public CompletionStage<Page<Report>> listStaff(Optional<ReportStatus> status, PageRequest pageRequest) {
        requireAccepting();
        return reports.findByStatus(status, pageRequest);
    }

    public CompletionStage<ReportResult> viewOwn(UUID reporterId, UUID reportId) {
        requireAccepting();
        Objects.requireNonNull(reporterId, "reporterId");
        return findDetails(reportId).thenApply(result -> {
            if (result.status() != ReportResult.Status.FOUND) {
                return result;
            }
            if (!result.report().orElseThrow().reporter().playerId().equals(reporterId)) {
                return ReportResult.failure(ReportResult.Status.NOT_OWNER);
            }
            List<ReportResponse> visible = result.responses().stream()
                    .filter(response -> response.visibility() == ResponseVisibility.REPORTER)
                    .toList();
            return ReportResult.success(ReportResult.Status.FOUND, result.report().orElseThrow(), visible);
        });
    }

    public CompletionStage<ReportResult> viewStaff(UUID reportId) {
        requireAccepting();
        return findDetails(reportId);
    }

    public CompletionStage<ReportResult> claim(UUID reportId, Actor actor) {
        requireAccepting();
        return mutateExisting(reportId, (report, now) -> mutations.claimReport(
                report.id(), report.version(), actor, now, audit(actor, "report.claim", report.id(), now)
        ));
    }

    public CompletionStage<ReportResult> respond(UUID reportId, Actor actor, String message) {
        requireAccepting();
        String capturedMessage = requireText(message, "message", configuration.get().maximumResponseLength());
        return mutateExisting(reportId, (report, now) -> {
            ReportResponse response = response(report, actor, capturedMessage, now);
            return mutations.respondToReport(
                    report.id(), report.version(), response,
                    Optional.of(notification(report, Optional.of(response.id()), now)),
                    audit(actor, "report.respond", report.id(), now)
            );
        });
    }

    public CompletionStage<ReportResult> resolve(UUID reportId, Actor actor, Optional<String> resolution) {
        requireAccepting();
        Objects.requireNonNull(resolution, "resolution");
        Optional<String> captured = resolution.map(value ->
                requireText(value, "resolution", configuration.get().maximumResponseLength()));
        return mutateExisting(reportId, (report, now) -> {
            Optional<ReportResponse> response = captured.map(value -> response(report, actor, value, now));
            return mutations.resolveReport(
                    report.id(), report.version(), now, response,
                    Optional.of(notification(report, response.map(ReportResponse::id), now)),
                    audit(actor, "report.resolve", report.id(), now)
            );
        });
    }

    public CompletionStage<ReportResult> dismiss(UUID reportId, Actor actor, String reason) {
        requireAccepting();
        String capturedReason = requireText(reason, "reason", configuration.get().maximumResponseLength());
        return mutateExisting(reportId, (report, now) -> {
            ReportResponse response = response(report, actor, capturedReason, now);
            return mutations.dismissReport(
                    report.id(), report.version(), now, response,
                    Optional.of(notification(report, Optional.of(response.id()), now)),
                    audit(actor, "report.dismiss", report.id(), now)
            );
        });
    }

    public CompletionStage<List<ReportNotification>> unreadNotifications(UUID recipientId) {
        requireAccepting();
        return reports.findUnreadNotifications(recipientId);
    }

    public CompletionStage<Integer> markNotificationsRead(UUID recipientId, Instant readAt) {
        requireAccepting();
        return mutations.markNotificationsRead(recipientId, readAt);
    }

    public void stop() {
        accepting.set(false);
    }

    private CompletionStage<ReportResult> createResolved(
            UUID reporterId,
            String reporterName,
            PlayerIdentity reported,
            String reason,
            java.time.Duration cooldown
    ) {
        Instant now = clock.instant();
        CompletionStage<Optional<Report>> duplicate = reports.findOpenByParticipants(reporterId, reported.playerId());
        CompletionStage<Optional<Report>> latest = reports.findLatestByReporter(reporterId);
        return duplicate.thenCombine(latest, CreationCheck::new).thenCompose(check -> {
            if (check.duplicate().isPresent()) {
                return completed(ReportResult.Status.DUPLICATE);
            }
            if (check.latest().filter(value -> value.createdAt().plus(cooldown).isAfter(now))
                    .isPresent()) {
                return completed(ReportResult.Status.COOLDOWN);
            }
            Report report = new Report(
                    UUID.randomUUID(),
                    new ReportParticipant(reporterId, reporterName),
                    new ReportParticipant(reported.playerId(), reported.currentName()),
                    reason,
                    ReportStatus.OPEN,
                    Optional.empty(),
                    0,
                    now,
                    now
            );
            CompletionStage<ReportMutationResult> created = mutations.createReport(
                    report,
                    audit(Actor.player(reporterId, reporterName), "report.create", report.id(), now)
            );
            return mapCreationConflict(created.thenApply(this::mapMutation));
        });
    }

    private CompletionStage<ReportResult> serializeCreation(
            UUID reporterId,
            Supplier<CompletionStage<ReportResult>> work
    ) {
        var output = new CompletableFuture<ReportResult>();
        CompletableFuture<Void> next;
        synchronized (creationMonitor) {
            CompletableFuture<Void> previous = creationTails.getOrDefault(
                    reporterId, CompletableFuture.completedFuture(null));
            next = previous.handle((ignored, failure) -> null).thenCompose(ignored -> {
                final CompletionStage<ReportResult> submitted;
                try {
                    requireAccepting();
                    submitted = work.get();
                } catch (RuntimeException exception) {
                    output.completeExceptionally(exception);
                    return CompletableFuture.<Void>completedFuture(null);
                }
                return submitted.handle((result, failure) -> {
                    if (failure == null) {
                        output.complete(result);
                    } else {
                        output.completeExceptionally(unwrap(failure));
                    }
                    return (Void) null;
                });
            }).toCompletableFuture();
            creationTails.put(reporterId, next);
        }
        CompletableFuture<Void> captured = next;
        next.whenComplete((ignored, failure) -> {
            synchronized (creationMonitor) {
                creationTails.remove(reporterId, captured);
            }
        });
        return output;
    }

    private CompletionStage<ReportResult> mapCreationConflict(CompletionStage<ReportResult> stage) {
        var result = new CompletableFuture<ReportResult>();
        stage.whenComplete((value, failure) -> {
            Throwable cause = unwrap(failure);
            if (cause instanceof PersistenceException persistence
                    && persistence.kind() == PersistenceFailureKind.CONFLICT) {
                result.complete(ReportResult.failure(ReportResult.Status.DUPLICATE));
            } else if (failure != null) {
                result.completeExceptionally(cause);
            } else {
                result.complete(value);
            }
        });
        return result;
    }

    private CompletionStage<ReportResult> findDetails(UUID reportId) {
        Objects.requireNonNull(reportId, "reportId");
        return reports.findById(reportId).thenCompose(found -> found.<CompletionStage<ReportResult>>map(report ->
                reports.findResponses(report.id()).thenApply(responses ->
                        ReportResult.success(ReportResult.Status.FOUND, report, responses)))
                .orElseGet(() -> completed(ReportResult.Status.NOT_FOUND)));
    }

    private CompletionStage<ReportResult> mutateExisting(UUID reportId, Mutation mutation) {
        Objects.requireNonNull(reportId, "reportId");
        Objects.requireNonNull(mutation, "mutation");
        return reports.findById(reportId).thenCompose(found -> {
            if (found.isEmpty()) {
                return completed(ReportResult.Status.NOT_FOUND);
            }
            return mutation.apply(found.orElseThrow(), clock.instant()).thenApply(this::mapMutation);
        });
    }

    private ReportResult mapMutation(ReportMutationResult result) {
        return switch (result.status()) {
            case APPLIED -> ReportResult.success(ReportResult.Status.APPLIED, result.report().orElseThrow(), List.of());
            case NOT_FOUND -> ReportResult.failure(ReportResult.Status.NOT_FOUND);
            case VERSION_CONFLICT -> ReportResult.failure(ReportResult.Status.VERSION_CONFLICT);
            case INVALID_STATE -> ReportResult.failure(ReportResult.Status.INVALID_STATE);
        };
    }

    private static ReportResponse response(Report report, Actor actor, String message, Instant now) {
        return new ReportResponse(UUID.randomUUID(), report.id(), actor, message, ResponseVisibility.REPORTER, now);
    }

    private static ReportNotification notification(Report report, Optional<UUID> responseId, Instant now) {
        return new ReportNotification(
                UUID.randomUUID(), report.reporter().playerId(), report.id(), responseId, now, Optional.empty()
        );
    }

    private static AuditEntry audit(Actor actor, String action, UUID reportId, Instant now) {
        return new AuditEntry(UUID.randomUUID(), actor, action, ENTITY_TYPE, reportId, now, "{}");
    }

    private static String requireText(String value, String name, int maximumLength) {
        Objects.requireNonNull(value, name);
        String stripped = value.strip();
        if (stripped.isEmpty() || stripped.length() > maximumLength) {
            throw new IllegalArgumentException(name + " must contain between 1 and " + maximumLength + " characters");
        }
        return stripped;
    }

    private void requireAccepting() {
        if (!accepting.get()) {
            throw new IllegalStateException("Report service is stopping");
        }
    }

    private static CompletionStage<ReportResult> completed(ReportResult.Status status) {
        return CompletableFuture.completedFuture(ReportResult.failure(status));
    }

    private static Throwable unwrap(Throwable failure) {
        if (failure == null) {
            return null;
        }
        Throwable current = failure;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    @FunctionalInterface
    private interface Mutation {
        CompletionStage<ReportMutationResult> apply(Report report, Instant now);
    }

    private record CreationCheck(Optional<Report> duplicate, Optional<Report> latest) {
    }
}
