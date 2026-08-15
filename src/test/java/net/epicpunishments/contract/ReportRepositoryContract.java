package net.epicpunishments.contract;

import net.epicpunishments.common.domain.Actor;
import net.epicpunishments.common.domain.AuditEntry;
import net.epicpunishments.common.domain.PageRequest;
import net.epicpunishments.report.domain.Report;
import net.epicpunishments.report.domain.ReportNotification;
import net.epicpunishments.report.domain.ReportParticipant;
import net.epicpunishments.report.domain.ReportResponse;
import net.epicpunishments.report.domain.ReportStatus;
import net.epicpunishments.report.domain.ResponseVisibility;
import net.epicpunishments.report.port.ReportMutationResult;
import net.epicpunishments.testing.ReportStoreTestFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public abstract class ReportRepositoryContract {
    private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");
    private static final Actor CONSOLE = Actor.console();

    private ReportStoreTestFixture fixture;

    protected abstract ReportStoreTestFixture createFixture();

    @BeforeEach
    final void setUpReportRepositoryContract() {
        fixture = createFixture();
    }

    @Test
    final void createsReportAndAuditAtomically() {
        Report report = report(UUID.randomUUID(), UUID.randomUUID(), NOW);

        fixture.mutations().createReport(report, audit(report.id(), "report.create"))
                .toCompletableFuture().join();

        assertThat(fixture.reports().findById(report.id()).toCompletableFuture().join()).contains(report);
        assertThat(fixture.auditEntries()).singleElement()
                .extracting(AuditEntry::action)
                .isEqualTo("report.create");
    }

    @Test
    final void preventsConcurrentOpenDuplicatesAtTheStorageBoundary() {
        UUID reporterId = UUID.randomUUID();
        UUID reportedId = UUID.randomUUID();
        Report first = report(reporterId, reportedId, NOW);
        Report duplicate = report(reporterId, reportedId, NOW.plusSeconds(1));
        create(first);

        assertThatThrownBy(() -> fixture.mutations().createReport(
                duplicate, audit(duplicate.id(), "report.create")
        ).toCompletableFuture().join()).hasCauseInstanceOf(
                net.epicpunishments.common.persistence.PersistenceException.class
        );
        assertThat(fixture.auditEntries()).hasSize(1);
    }

    @Test
    final void optimisticClaimDoesNotSilentlyOverwrite() {
        Report report = report(UUID.randomUUID(), UUID.randomUUID(), NOW);
        create(report);
        Actor firstStaff = Actor.player(UUID.randomUUID(), "FirstStaff");
        Actor secondStaff = Actor.player(UUID.randomUUID(), "SecondStaff");

        var results = concurrently(
                () -> fixture.mutations().claimReport(
                        report.id(), 0, firstStaff, NOW.plusSeconds(1), audit(report.id(), "report.claim")
                ).toCompletableFuture().join(),
                () -> fixture.mutations().claimReport(
                        report.id(), 0, secondStaff, NOW.plusSeconds(1), audit(report.id(), "report.claim")
                ).toCompletableFuture().join()
        );

        assertThat(results).extracting(ReportMutationResult::status)
                .containsExactlyInAnyOrder(
                        ReportMutationResult.Status.APPLIED,
                        ReportMutationResult.Status.VERSION_CONFLICT
                );
        Report stored = fixture.reports().findById(report.id()).toCompletableFuture().join().orElseThrow();
        assertThat(stored.assignee()).hasValueSatisfying(assignee -> assertThat(assignee)
                .isIn(firstStaff, secondStaff));
        assertThat(stored.version()).isEqualTo(1);
        assertThat(fixture.auditEntries()).hasSize(2);
    }

    @Test
    final void responseAuditAndNotificationRollBackTogether() {
        Report report = report(UUID.randomUUID(), UUID.randomUUID(), NOW);
        create(report);
        ReportResponse response = response(report.id(), NOW.plusSeconds(1));
        ReportNotification notification = notification(report, response);
        fixture.failNextAuditWrite();

        assertThatThrownBy(() -> fixture.mutations().respondToReport(
                report.id(),
                report.version(),
                response,
                Optional.of(notification),
                audit(report.id(), "report.respond")
        ).toCompletableFuture().join()).hasRootCauseMessage("Induced audit write failure");

        assertThat(fixture.reports().findResponses(report.id()).toCompletableFuture().join()).isEmpty();
        assertThat(fixture.reports().findUnreadNotifications(report.reporter().playerId())
                .toCompletableFuture().join()).isEmpty();
        assertThat(fixture.reports().findById(report.id()).toCompletableFuture().join().orElseThrow().version())
                .isZero();
        assertThat(fixture.auditEntries()).hasSize(1);
    }

    @Test
    final void resolvesWithImmutableResponseAndUnreadNotification() {
        Report report = report(UUID.randomUUID(), UUID.randomUUID(), NOW);
        create(report);
        ReportResponse response = response(report.id(), NOW.plusSeconds(2));
        ReportNotification notification = notification(report, response);

        var result = fixture.mutations().resolveReport(
                report.id(),
                0,
                NOW.plusSeconds(2),
                Optional.of(response),
                Optional.of(notification),
                audit(report.id(), "report.resolve")
        ).toCompletableFuture().join();

        assertThat(result.report().orElseThrow().status()).isEqualTo(ReportStatus.RESOLVED);
        assertThat(fixture.reports().findResponses(report.id()).toCompletableFuture().join())
                .containsExactly(response);
        assertThat(fixture.reports().findUnreadNotifications(report.reporter().playerId())
                .toCompletableFuture().join()).containsExactly(notification);
        assertThat(fixture.mutations().markNotificationsRead(
                report.reporter().playerId(), NOW.plusSeconds(3)
        ).toCompletableFuture().join()).isEqualTo(1);
        assertThat(fixture.reports().findUnreadNotifications(report.reporter().playerId())
                .toCompletableFuture().join()).isEmpty();
    }

    @Test
    final void listsByReporterAndStatusInNewestFirstOrder() {
        UUID reporterId = UUID.randomUUID();
        Report oldest = report(reporterId, UUID.randomUUID(), NOW);
        Report newest = report(reporterId, UUID.randomUUID(), NOW.plusSeconds(5));
        Report otherReporter = report(UUID.randomUUID(), UUID.randomUUID(), NOW.plusSeconds(10));
        create(oldest);
        create(newest);
        create(otherReporter);

        var ownReports = fixture.reports().findByReporter(reporterId, new PageRequest(0, 10))
                .toCompletableFuture().join();
        var openReports = fixture.reports().findByStatus(Optional.of(ReportStatus.OPEN), new PageRequest(0, 2))
                .toCompletableFuture().join();
        var allReportsSecondPage = fixture.reports().findByStatus(Optional.empty(), new PageRequest(1, 2))
                .toCompletableFuture().join();

        assertThat(ownReports.items()).containsExactly(newest, oldest);
        assertThat(openReports.items()).containsExactly(otherReporter, newest);
        assertThat(openReports.totalItems()).isEqualTo(3);
        assertThat(allReportsSecondPage.items()).containsExactly(oldest);
        assertThat(allReportsSecondPage.totalItems()).isEqualTo(3);
        assertThat(fixture.reports().findLatestByReporter(reporterId).toCompletableFuture().join())
                .contains(newest);
        assertThat(fixture.reports().findOpenByParticipants(reporterId, newest.reported().playerId())
                .toCompletableFuture().join()).contains(newest);
    }

    @Test
    final void recordsStatusOnlyResolutionNotification() {
        Report report = report(UUID.randomUUID(), UUID.randomUUID(), NOW);
        create(report);
        ReportNotification notification = new ReportNotification(
                UUID.randomUUID(), report.reporter().playerId(), report.id(), Optional.empty(),
                NOW.plusSeconds(1), Optional.empty()
        );

        fixture.mutations().resolveReport(
                report.id(), 0, NOW.plusSeconds(1), Optional.empty(), Optional.of(notification),
                audit(report.id(), "report.resolve")
        ).toCompletableFuture().join();

        assertThat(fixture.reports().findUnreadNotifications(report.reporter().playerId())
                .toCompletableFuture().join()).containsExactly(notification);
        assertThat(fixture.reports().findResponses(report.id()).toCompletableFuture().join()).isEmpty();
    }

    @Test
    final void concurrentClaimAndCloseCommitOnlyOneExpectedVersion() {
        Report report = report(UUID.randomUUID(), UUID.randomUUID(), NOW);
        create(report);

        var results = concurrently(
                () -> fixture.mutations().claimReport(
                        report.id(),
                        0,
                        Actor.player(UUID.randomUUID(), "Staff"),
                        NOW.plusSeconds(1),
                        audit(report.id(), "report.claim")
                ).toCompletableFuture().join(),
                () -> fixture.mutations().resolveReport(
                        report.id(),
                        0,
                        NOW.plusSeconds(1),
                        Optional.empty(),
                        Optional.empty(),
                        audit(report.id(), "report.resolve")
                ).toCompletableFuture().join()
        );

        assertThat(results).extracting(ReportMutationResult::status)
                .containsExactlyInAnyOrder(
                        ReportMutationResult.Status.APPLIED,
                        ReportMutationResult.Status.VERSION_CONFLICT
                );
        assertThat(fixture.reports().findById(report.id()).toCompletableFuture().join().orElseThrow().version())
                .isEqualTo(1);
        assertThat(fixture.auditEntries()).hasSize(2);
    }

    private void create(Report report) {
        fixture.mutations().createReport(report, audit(report.id(), "report.create"))
                .toCompletableFuture().join();
    }

    private static Report report(UUID reporterId, UUID reportedId, Instant createdAt) {
        return new Report(
                UUID.randomUUID(),
                new ReportParticipant(reporterId, "Reporter"),
                new ReportParticipant(reportedId, "Reported"),
                "Contract test report",
                ReportStatus.OPEN,
                Optional.empty(),
                0,
                createdAt,
                createdAt
        );
    }

    private static ReportResponse response(UUID reportId, Instant createdAt) {
        return new ReportResponse(
                UUID.randomUUID(),
                reportId,
                CONSOLE,
                "The report was reviewed",
                ResponseVisibility.REPORTER,
                createdAt
        );
    }

    private static ReportNotification notification(Report report, ReportResponse response) {
        return new ReportNotification(
                UUID.randomUUID(),
                report.reporter().playerId(),
                report.id(),
                Optional.of(response.id()),
                response.createdAt(),
                Optional.empty()
        );
    }

    private static AuditEntry audit(UUID reportId, String action) {
        return new AuditEntry(UUID.randomUUID(), CONSOLE, action, "report", reportId, NOW, "{}");
    }

    private static <T> java.util.List<T> concurrently(Supplier<T> first, Supplier<T> second) {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var firstFuture = java.util.concurrent.CompletableFuture.supplyAsync(
                    () -> runAfter(start, first),
                    executor
            );
            var secondFuture = java.util.concurrent.CompletableFuture.supplyAsync(
                    () -> runAfter(start, second),
                    executor
            );
            start.countDown();
            return java.util.List.of(firstFuture.join(), secondFuture.join());
        }
    }

    private static <T> T runAfter(CountDownLatch start, Supplier<T> supplier) {
        try {
            start.await();
            return supplier.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Contract test was interrupted", exception);
        }
    }
}
