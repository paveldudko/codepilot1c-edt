/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.diagnostics;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests for {@link DiagnosticsLineFilter}.
 *
 * <p>Covers the line-range filtering used by {@code get_diagnostics} with
 * the new {@code line_from}/{@code line_to} parameters.</p>
 */
public class DiagnosticsLineFilterTest {

    @Test
    public void disabledWhenBothZero() {
        assertTrue(DiagnosticsLineFilter.isDisabled(0, 0));
        assertTrue(DiagnosticsLineFilter.matches(42, 0, 0));
        assertTrue(DiagnosticsLineFilter.matches(-1, 0, 0));
    }

    @Test
    public void disabledWhenBothNegative() {
        assertTrue(DiagnosticsLineFilter.isDisabled(-5, -1));
    }

    @Test
    public void lowerBoundOnly() {
        assertFalse(DiagnosticsLineFilter.isDisabled(10, 0));
        assertFalse(DiagnosticsLineFilter.matches(5, 10, 0));
        assertTrue(DiagnosticsLineFilter.matches(10, 10, 0));
        assertTrue(DiagnosticsLineFilter.matches(10_000, 10, 0));
    }

    @Test
    public void upperBoundOnly() {
        assertFalse(DiagnosticsLineFilter.isDisabled(0, 20));
        assertTrue(DiagnosticsLineFilter.matches(1, 0, 20));
        assertTrue(DiagnosticsLineFilter.matches(20, 0, 20));
        assertFalse(DiagnosticsLineFilter.matches(21, 0, 20));
    }

    @Test
    public void bothBoundsInclusive() {
        assertFalse(DiagnosticsLineFilter.matches(99, 100, 200));
        assertTrue(DiagnosticsLineFilter.matches(100, 100, 200));
        assertTrue(DiagnosticsLineFilter.matches(150, 100, 200));
        assertTrue(DiagnosticsLineFilter.matches(200, 100, 200));
        assertFalse(DiagnosticsLineFilter.matches(201, 100, 200));
    }

    @Test
    public void fileLevelDiagnosticsDroppedWhenRangeSet() {
        // lineNumber <= 0 means "unknown/file-level". When user asks for a
        // specific range, those should not leak through.
        assertFalse(DiagnosticsLineFilter.matches(0, 10, 20));
        assertFalse(DiagnosticsLineFilter.matches(-1, 10, 20));
    }

    @Test
    public void fileLevelDiagnosticsKeptWhenFilterDisabled() {
        assertTrue(DiagnosticsLineFilter.matches(0, 0, 0));
        assertTrue(DiagnosticsLineFilter.matches(-1, 0, 0));
    }

    @Test
    public void normalizeNegativeToZero() {
        assertArrayEquals(new int[] { 0, 10 }, DiagnosticsLineFilter.normalize(-5, 10));
        assertArrayEquals(new int[] { 5, 0 }, DiagnosticsLineFilter.normalize(5, -1));
        assertArrayEquals(new int[] { 0, 0 }, DiagnosticsLineFilter.normalize(-5, -1));
    }

    @Test
    public void normalizeSwapsReversedRange() {
        // Tolerate user passing from > to.
        assertArrayEquals(new int[] { 10, 20 }, DiagnosticsLineFilter.normalize(20, 10));
    }

    @Test
    public void normalizeKeepsValidRange() {
        assertArrayEquals(new int[] { 10, 20 }, DiagnosticsLineFilter.normalize(10, 20));
        assertArrayEquals(new int[] { 10, 10 }, DiagnosticsLineFilter.normalize(10, 10));
    }

    @Test
    public void singleLineRange() {
        assertTrue(DiagnosticsLineFilter.matches(42, 42, 42));
        assertFalse(DiagnosticsLineFilter.matches(41, 42, 42));
        assertFalse(DiagnosticsLineFilter.matches(43, 42, 42));
    }
}
