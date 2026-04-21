package com.codepilot1c.core.util;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Runs a blocking supplier with a hard wall-clock timeout.
 * <p>
 * When the supplier does not complete within {@code timeoutMillis}, the
 * call returns {@code onTimeout} — the background computation is not
 * guaranteed to be interrupted (depends on whether the supplier is
 * cancellation-aware), but the caller never blocks longer than the budget.
 */
public final class TimeBoundedCall {

    private TimeBoundedCall() {
    }

    public static <T> T callWithin(Supplier<T> action, long timeoutMillis, T onTimeout) {
        if (action == null) {
            return onTimeout;
        }
        CompletableFuture<T> future = CompletableFuture.supplyAsync(action);
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return onTimeout;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return onTimeout;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new RuntimeException(cause);
        }
    }
}
