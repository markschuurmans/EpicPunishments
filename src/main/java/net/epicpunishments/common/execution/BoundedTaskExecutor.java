package net.epicpunishments.common.execution;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class BoundedTaskExecutor implements TaskExecutor, AutoCloseable {
    private final ThreadPoolExecutor executor;
    private final Duration shutdownTimeout;
    private final AtomicBoolean stopping = new AtomicBoolean();

    public BoundedTaskExecutor(int threadCount, int queueCapacity, Duration shutdownTimeout, String threadNamePrefix) {
        if (threadCount < 1) {
            throw new IllegalArgumentException("threadCount must be positive");
        }
        if (queueCapacity < 1) {
            throw new IllegalArgumentException("queueCapacity must be positive");
        }
        if (shutdownTimeout.isNegative() || shutdownTimeout.isZero()) {
            throw new IllegalArgumentException("shutdownTimeout must be positive");
        }
        if (threadNamePrefix.isBlank()) {
            throw new IllegalArgumentException("threadNamePrefix must not be blank");
        }

        this.shutdownTimeout = shutdownTimeout;
        this.executor = new ThreadPoolExecutor(
                threadCount,
                threadCount,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                Thread.ofPlatform().name(threadNamePrefix + '-', 1).factory(),
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    @Override
    public <T> CompletionStage<T> submit(Callable<T> task) {
        Objects.requireNonNull(task, "task");
        var result = new CompletableFuture<T>();
        if (stopping.get()) {
            return CompletableFuture.failedFuture(new RejectedExecutionException("Executor is stopping."));
        }

        try {
            executor.execute(new QueuedTask<>(task, result, stopping));
        } catch (RejectedExecutionException exception) {
            result.completeExceptionally(exception);
        }
        return result;
    }

    @Override
    public int pendingTaskCount() {
        return executor.getActiveCount() + executor.getQueue().size();
    }

    public ShutdownResult shutdownGracefully() {
        if (!stopping.compareAndSet(false, true)) {
            return new ShutdownResult(executor.isTerminated(), 0);
        }

        executor.shutdown();
        long deadline = System.nanoTime() + shutdownTimeout.toNanos();
        boolean interrupted = false;
        try {
            if (awaitUntil(deadline)) {
                return new ShutdownResult(true, 0);
            }
        } catch (InterruptedException exception) {
            interrupted = true;
        }

        var cancelled = executor.shutdownNow();
        cancelled.forEach(task -> {
            if (task instanceof QueuedTask<?> queuedTask) {
                queuedTask.cancel();
            }
        });
        int cancelledTasks = cancelled.size();
        boolean terminated = executor.isTerminated();
        if (!terminated && !interrupted) {
            try {
                terminated = awaitUntil(deadline);
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        return new ShutdownResult(terminated, cancelledTasks);
    }

    private boolean awaitUntil(long deadline) throws InterruptedException {
        long remaining = deadline - System.nanoTime();
        return remaining <= 0L ? executor.isTerminated() : executor.awaitTermination(remaining, TimeUnit.NANOSECONDS);
    }

    @Override
    public void close() {
        shutdownGracefully();
    }

    private static final class QueuedTask<T> implements Runnable {
        private final Callable<T> task;
        private final CompletableFuture<T> result;
        private final AtomicBoolean stopping;

        private QueuedTask(Callable<T> task, CompletableFuture<T> result, AtomicBoolean stopping) {
            this.task = task;
            this.result = result;
            this.stopping = stopping;
        }

        @Override
        public void run() {
            if (stopping.get()) {
                cancel();
                return;
            }
            try {
                result.complete(task.call());
            } catch (Throwable throwable) {
                result.completeExceptionally(throwable);
            }
        }

        private void cancel() {
            result.completeExceptionally(new RejectedExecutionException("Executor is stopping."));
        }
    }
}
