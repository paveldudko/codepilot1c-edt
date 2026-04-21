/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * Tests for {@link TimeBoundedCall}.
 */
public class TimeBoundedCallTest {

    @Test
    public void returnsValueWhenSupplierCompletesInTime() {
        String result = TimeBoundedCall.callWithin(() -> "ok", 1_000L, "fallback"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("ok", result); //$NON-NLS-1$
    }

    @Test
    public void returnsFallbackWhenSupplierExceedsBudget() {
        long start = System.nanoTime();
        String result = TimeBoundedCall.callWithin(() -> {
            try {
                Thread.sleep(5_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "late"; //$NON-NLS-1$
        }, 100L, "fallback"); //$NON-NLS-1$
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertEquals("fallback", result); //$NON-NLS-1$
        assertTrue("wall-clock time must respect budget, was " + elapsedMs + "ms", //$NON-NLS-1$ //$NON-NLS-2$
                elapsedMs < 2_000L);
    }

    @Test
    public void propagatesRuntimeExceptionFromSupplier() {
        try {
            TimeBoundedCall.callWithin(() -> {
                throw new IllegalStateException("boom"); //$NON-NLS-1$
            }, 1_000L, "fallback"); //$NON-NLS-1$
            fail("expected IllegalStateException"); //$NON-NLS-1$
        } catch (IllegalStateException e) {
            assertEquals("boom", e.getMessage()); //$NON-NLS-1$
        }
    }

    @Test
    public void nullSupplierReturnsFallback() {
        String result = TimeBoundedCall.callWithin(null, 100L, "fallback"); //$NON-NLS-1$
        assertEquals("fallback", result); //$NON-NLS-1$
    }
}
