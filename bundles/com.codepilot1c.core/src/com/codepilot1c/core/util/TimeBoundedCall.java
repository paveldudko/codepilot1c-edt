package com.codepilot1c.core.util;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Runs a blocking supplier with a hard wall-clock timeout.
 * <p>
 * When the supplier does not complete within {@code timeoutMillis}, the call
 * returns {@code onTimeout} — the background computation is not guaranteed
 * to be interrupted (depends on whether the supplier is cancellation-aware),
 * but the caller never blocks longer than the budget.
 * <p>
 * Implementation uses a dedicated daemon {@link Thread} and a
 * {@link CountDownLatch}: the caller waits on the latch for
 * {@code timeoutMillis}, and on timeout we abandon the worker thread to
 * finish in the background (daemon, so it will not block JVM shutdown).
 * An earlier revision used {@link java.util.concurrent.CompletableFuture}
 * on the common ForkJoinPool, but observed in the EDT runtime that
 * {@code Future.get(timeout)} did not wake reliably when the supplier
 * contended with internal Eclipse jobs — the pure-JDK primitive
 * {@code CountDownLatch.await(timeout)} avoids scheduler assumptions.
 * <p>
 * Dependency-free (pure JDK) so unit tests can execute without the
 * Eclipse runtime on the classpath.
 */
public final class TimeBoundedCall {

    /** No-op logger for call sites that do not need diagnostics. */
    public static final Consumer<String> NO_LOG = msg -> { };

    private TimeBoundedCall() {
    }

    public static <T> T callWithin(Supplier<T> action, long timeoutMillis, T onTimeout) {
        return callWithin(action, timeoutMillis, onTimeout, "call", NO_LOG); //$NON-NLS-1$
    }

    /**
     * Same as the simpler overload, but accepts a label and a logger so the
     * caller can trace every phase of the call (entry, worker start, wait
     * outcome, exit). Useful when wall-clock time reported at the call site
     * does not match the configured budget and we need to see which step
     * actually took the time.
     *
     * <p>All log lines are prefixed with {@code [label]} so multiple
     * concurrent call sites can be told apart.</p>
     */
    public static <T> T callWithin(
            Supplier<T> action,
            long timeoutMillis,
            T onTimeout,
            String label,
            Consumer<String> logger) {
        Consumer<String> log = logger != null ? logger : NO_LOG;
        String tag = label != null ? label : "call"; //$NON-NLS-1$

        long tEntry = System.nanoTime();
        log.accept(String.format("TimeBoundedCall[%s]: entry, budget=%d ms", tag, timeoutMillis)); //$NON-NLS-1$

        if (action == null) {
            log.accept(String.format("TimeBoundedCall[%s]: null action, returning onTimeout immediately", tag)); //$NON-NLS-1$
            return onTimeout;
        }

        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<RuntimeException> error = new AtomicReference<>();
        AtomicReference<Long> workerStartedNs = new AtomicReference<>();
        AtomicReference<Long> workerFinishedNs = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        Thread worker = new Thread(() -> {
            workerStartedNs.set(System.nanoTime());
            try {
                result.set(action.get());
            } catch (RuntimeException e) {
                error.set(e);
            } finally {
                workerFinishedNs.set(System.nanoTime());
                done.countDown();
            }
        }, "codepilot1c-TimeBoundedCall-" + tag); //$NON-NLS-1$
        worker.setDaemon(true);
        long tBeforeStart = System.nanoTime();
        worker.start();
        log.accept(String.format("TimeBoundedCall[%s]: worker thread started in %d ms (daemon=%s, tid=%d)", //$NON-NLS-1$
                tag, (System.nanoTime() - tBeforeStart) / 1_000_000, worker.isDaemon(), worker.getId()));

        boolean finishedInTime;
        long tAwait = System.nanoTime();
        try {
            finishedInTime = done.await(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            long awaitMs = (System.nanoTime() - tAwait) / 1_000_000;
            Thread.currentThread().interrupt();
            worker.interrupt();
            log.accept(String.format(
                    "TimeBoundedCall[%s]: INTERRUPTED while waiting after %d ms, returning onTimeout", tag, awaitMs)); //$NON-NLS-1$
            return onTimeout;
        }
        long awaitMs = (System.nanoTime() - tAwait) / 1_000_000;

        if (!finishedInTime) {
            // Best-effort interrupt of the supplier; if it ignores the flag
            // it keeps running in the background daemon thread.
            worker.interrupt();
            log.accept(String.format(
                    "TimeBoundedCall[%s]: TIMEOUT after %d ms of await (budget=%d ms); " //$NON-NLS-1$
                            + "worker state=%s, returning onTimeout", //$NON-NLS-1$
                    tag, awaitMs, timeoutMillis, worker.getState()));
            return onTimeout;
        }

        long workerElapsedMs = workerFinishedNs.get() != null && workerStartedNs.get() != null
                ? (workerFinishedNs.get() - workerStartedNs.get()) / 1_000_000
                : -1;
        long totalMs = (System.nanoTime() - tEntry) / 1_000_000;
        log.accept(String.format(
                "TimeBoundedCall[%s]: completed in %d ms (await=%d, worker=%d, error=%s)", //$NON-NLS-1$
                tag, totalMs, awaitMs, workerElapsedMs, error.get() != null ? error.get().getClass().getSimpleName() : "none")); //$NON-NLS-1$

        RuntimeException caught = error.get();
        if (caught != null) {
            throw caught;
        }
        return result.get();
    }
}
