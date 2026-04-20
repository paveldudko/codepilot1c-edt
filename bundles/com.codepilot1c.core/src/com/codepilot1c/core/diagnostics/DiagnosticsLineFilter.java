/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.diagnostics;

/**
 * Utility for filtering diagnostics by a 1-based line range.
 *
 * <p>Range semantics:</p>
 * <ul>
 *   <li>{@code from <= 0} — no lower bound;</li>
 *   <li>{@code to <= 0} — no upper bound;</li>
 *   <li>both {@code <= 0} — filter is effectively disabled ({@code matches}
 *       returns {@code true} for every line);</li>
 *   <li>bounds are inclusive.</li>
 * </ul>
 *
 * <p>Diagnostics with {@code lineNumber <= 0} (file-level or unknown
 * location) pass the filter iff the filter is disabled — otherwise the
 * caller has asked for a specific range and file-level warnings are
 * out of scope.</p>
 */
public final class DiagnosticsLineFilter {

    private DiagnosticsLineFilter() {
    }

    /** True when no line bounds are set (accept everything). */
    public static boolean isDisabled(int from, int to) {
        return from <= 0 && to <= 0;
    }

    /**
     * Checks whether a diagnostic at {@code lineNumber} passes the filter.
     *
     * @param lineNumber 1-based line number from the diagnostic
     *                   ({@code <= 0} means unknown/file-level)
     * @param from       inclusive lower bound, {@code <= 0} means unbounded below
     * @param to         inclusive upper bound, {@code <= 0} means unbounded above
     */
    public static boolean matches(int lineNumber, int from, int to) {
        if (isDisabled(from, to)) {
            return true;
        }
        if (lineNumber <= 0) {
            return false;
        }
        if (from > 0 && lineNumber < from) {
            return false;
        }
        if (to > 0 && lineNumber > to) {
            return false;
        }
        return true;
    }

    /**
     * Normalizes a user-provided range so callers can reason about it
     * consistently. If the user passed {@code to < from} both positive,
     * swap them — tolerant of sloppy inputs.
     *
     * @return a 2-element int array {@code [from, to]} with the same
     *         semantics as the inputs, with {@code from <= to} whenever
     *         both are positive
     */
    public static int[] normalize(int from, int to) {
        int f = from < 0 ? 0 : from;
        int t = to < 0 ? 0 : to;
        if (f > 0 && t > 0 && t < f) {
            int swap = f;
            f = t;
            t = swap;
        }
        return new int[] { f, t };
    }
}
