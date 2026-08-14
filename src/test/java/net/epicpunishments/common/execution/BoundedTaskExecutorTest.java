package net.epicpunishments.common.execution;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BoundedTaskExecutorTest {
    @Test
    void executesWorkOnOwnedNamedThreads() {
        try (var executor = new BoundedTaskExecutor(1, 2, Duration.ofSeconds(1), "test-io")) {
            String caller = Thread.currentThread().getName();

            String worker = executor.submit(() -> Thread.currentThread().getName())
                    .toCompletableFuture()
                    .join();

            assertThat(worker).startsWith("test-io-").isNotEqualTo(caller);
            assertThat(executor.pendingTaskCount()).isZero();
        }
    }

    @Test
    void boundedShutdownInterruptsRunningWorkAndCompletesQueuedResults() throws Exception {
        var executor = new BoundedTaskExecutor(1, 1, Duration.ofMillis(100), "shutdown-test");
        var started = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var running = executor.submit(() -> {
            started.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException exception) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
            return "finished";
        });
        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
        var queued = executor.submit(() -> "never runs");

        ShutdownResult shutdown = executor.shutdownGracefully();

        assertThat(shutdown.cancelledTasks()).isEqualTo(1);
        assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(running.toCompletableFuture().join()).isEqualTo("finished");
        assertThatThrownBy(() -> queued.toCompletableFuture().join())
                .hasRootCauseInstanceOf(RejectedExecutionException.class);
        assertThatThrownBy(() -> executor.submit(() -> "rejected").toCompletableFuture().join())
                .hasRootCauseInstanceOf(RejectedExecutionException.class);
    }
}
