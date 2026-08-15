package net.epicpunishments.report.application;

import net.epicpunishments.common.config.ReportConfiguration;
import net.epicpunishments.common.domain.Actor;
import net.epicpunishments.identity.domain.PlayerAddress;
import net.epicpunishments.identity.domain.SuccessfulJoin;
import net.epicpunishments.report.domain.ReportStatus;
import net.epicpunishments.testing.InMemoryPlayerIdentityRepository;
import net.epicpunishments.testing.InMemoryReportStore;
import net.epicpunishments.testing.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-15T10:00:00Z");

    private final InMemoryPlayerIdentityRepository identities = new InMemoryPlayerIdentityRepository();
    private final InMemoryReportStore store = new InMemoryReportStore();
    private final MutableClock clock = new MutableClock(NOW);
    private final ReportConfiguration configuration = new ReportConfiguration(
            Duration.ofMinutes(5), 64, 128, 10
    );
    private ReportService service;
    private UUID reporterId;
    private UUID reportedId;

    @BeforeEach
    void setUp() throws Exception {
        reporterId = remember("Reporter", "192.0.2.10");
        reportedId = remember("Reported", "192.0.2.11");
        service = new ReportService(identities, store, store, () -> configuration, clock);
    }

    @Test
    void createsStoredReportWithAtomicAuditAndRejectsDuplicate() {
        ReportResult created = service.create(reporterId, "Reporter", "Reported", "Chat abuse")
                .toCompletableFuture().join();

        assertThat(created.status()).isEqualTo(ReportResult.Status.APPLIED);
        assertThat(created.report().orElseThrow().reported().playerId()).isEqualTo(reportedId);
        assertThat(store.auditEntries()).singleElement().satisfies(audit -> {
            assertThat(audit.action()).isEqualTo("report.create");
            assertThat(audit.actor()).isEqualTo(Actor.player(reporterId, "Reporter"));
        });

        assertThat(service.create(reporterId, "Reporter", "Reported", "More abuse")
                .toCompletableFuture().join().status()).isEqualTo(ReportResult.Status.DUPLICATE);
    }

    @Test
    void appliesCooldownAfterClosedReportAndAllowsCreationWhenItExpires() {
        ReportResult first = service.create(reporterId, "Reporter", "Reported", "First")
                .toCompletableFuture().join();
        service.dismiss(first.report().orElseThrow().id(), Actor.console(), "Reviewed")
                .toCompletableFuture().join();

        assertThat(service.create(reporterId, "Reporter", "Reported", "Second")
                .toCompletableFuture().join().status()).isEqualTo(ReportResult.Status.COOLDOWN);

        clock.advance(Duration.ofMinutes(5));
        assertThat(service.create(reporterId, "Reporter", "Reported", "Second")
                .toCompletableFuture().join().status()).isEqualTo(ReportResult.Status.APPLIED);
    }

    @Test
    void serializesConcurrentCreationsSoCooldownCannotBeBypassed() throws Exception {
        remember("OtherTarget", "192.0.2.14");
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                await(start);
                return service.create(reporterId, "Reporter", "Reported", "First")
                        .toCompletableFuture().join().status();
            }, executor);
            var second = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                await(start);
                return service.create(reporterId, "Reporter", "OtherTarget", "Second")
                        .toCompletableFuture().join().status();
            }, executor);
            start.countDown();

            assertThat(java.util.List.of(first.join(), second.join())).containsExactlyInAnyOrder(
                    ReportResult.Status.APPLIED, ReportResult.Status.COOLDOWN
            );
        }
    }

    @Test
    void rejectsSelfUnknownAndAmbiguousHistoricalTargets() throws Exception {
        assertThat(service.create(reporterId, "Reporter", "Reporter", "Self")
                .toCompletableFuture().join().status()).isEqualTo(ReportResult.Status.SELF_REPORT);
        assertThat(service.create(reporterId, "Reporter", "Missing", "Missing")
                .toCompletableFuture().join().status()).isEqualTo(ReportResult.Status.TARGET_NOT_FOUND);

        UUID renamed = remember("Shared", "192.0.2.12");
        remember("Shared", "192.0.2.13");
        assertThat(renamed).isNotNull();
        assertThat(service.create(reporterId, "Reporter", "Shared", "Ambiguous")
                .toCompletableFuture().join().status()).isEqualTo(ReportResult.Status.TARGET_AMBIGUOUS);
    }

    @Test
    void enforcesOwnerAccessAndCompletesStaffWorkflowWithNotifications() {
        var report = service.create(reporterId, "Reporter", "Reported", "Reason")
                .toCompletableFuture().join().report().orElseThrow();
        Actor staff = Actor.player(UUID.randomUUID(), "Staff");

        assertThat(service.viewOwn(reportedId, report.id()).toCompletableFuture().join().status())
                .isEqualTo(ReportResult.Status.NOT_OWNER);
        assertThat(service.claim(report.id(), staff).toCompletableFuture().join().report().orElseThrow().status())
                .isEqualTo(ReportStatus.IN_REVIEW);
        assertThat(service.respond(report.id(), staff, "We are reviewing this")
                .toCompletableFuture().join().status()).isEqualTo(ReportResult.Status.APPLIED);
        assertThat(service.resolve(report.id(), staff, Optional.empty())
                .toCompletableFuture().join().report().orElseThrow().status()).isEqualTo(ReportStatus.RESOLVED);

        ReportResult ownView = service.viewOwn(reporterId, report.id()).toCompletableFuture().join();
        assertThat(ownView.responses()).singleElement()
                .extracting(response -> response.message()).isEqualTo("We are reviewing this");
        assertThat(service.unreadNotifications(reporterId).toCompletableFuture().join()).hasSize(2);
        assertThat(store.auditEntries()).extracting(audit -> audit.action()).containsExactly(
                "report.create", "report.claim", "report.respond", "report.resolve"
        );
    }

    @Test
    void validatesConfiguredLengthsAndRejectsWorkAfterStop() {
        assertThatThrownBy(() -> service.create(reporterId, "Reporter", "Reported", "x".repeat(65)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("64");

        service.stop();

        assertThatThrownBy(() -> service.listOwn(reporterId, new net.epicpunishments.common.domain.PageRequest(0, 10)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("stopping");
    }

    private UUID remember(String name, String address) throws Exception {
        UUID playerId = UUID.randomUUID();
        identities.recordSuccessfulJoin(new SuccessfulJoin(
                playerId,
                name,
                PlayerAddress.from(InetAddress.getByName(address)),
                NOW
        )).toCompletableFuture().join();
        return playerId;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
