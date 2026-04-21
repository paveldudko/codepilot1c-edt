package com.codepilot1c.core.util;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Runs a blocking supplier with a hard wall-clock timeout.
 * <p>
 * When the supplier does not complete within {@code timeoutMillis}, the call
 * returns {@code onTimeout} — the background computation is not guaranteed
 * to be interrupted (depends on whether the supplier is cancellation-aware),
 * but the caller never blocks longer than the budget.
 * <p>
 * Earlier revision used {@link java.util.concurrent.CompletableFuture#supplyAsync}
 * on the common ForkJoinPool, but observed on AM that the caller still
 * blocked for 30+ seconds despite the 5-second budget — we suspect the
 * EDT runtime's platform-doc path contends with ForkJoinPool scheduling
 * (possibly deadlocking on an internal job). Current implementation uses
 * a dedicated daemon {@link Thread} and a {@link CountDownLatch}: the
 * caller waits on the latch for {@code timeoutMillis}, and on timeout we
 * abandon the worker thread to finish in the background (daemon, so it
 * will not block JVM shutdown).
 * <p>
 * Dependency-free (pure JDK) so unit tests can execute without the
 * Eclipse runtime on the classpath.
 */
public final class TimeBoundedCall {

    private TimeBoundedCall() {
    }

    public static <T> T callWithin(Supplier<T> action, long timeoutMillis, T onTimeout) {
        if (action == null) {
            return onTimeout;
        }
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<RuntimeException> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        Thread worker = new Thread(() -> {
            try {
                result.set(action.get());
            } catch (RuntimeException e) {
                error.set(e);
            } finally {
                done.countDown();
            }
        }, "codepilot1c-TimeBoundedCall"); //$NON-NLS-1$
        worker.setDaemon(true);
        worker.start();

        boolean finishedInTime;
        try {
            finishedInTime = done.await(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            worker.interrupt();
            return onTimeout;
        }

        if (!finishedInTime) {
            // Best-effort interrupt of the supplier; if it ignores the flag
            // it keeps running in the background daemon thread.
            worker.interrupt();
            return onTimeout;
        }

        RuntimeException caught = error.get();
        if (caught != null) {
            throw caught;
        }
        return result.get();
    }
}
