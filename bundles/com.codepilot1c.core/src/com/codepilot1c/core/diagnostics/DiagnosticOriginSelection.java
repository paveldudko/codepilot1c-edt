/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.diagnostics;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * The two origin-aware decisions {@code get_diagnostics} makes over a collected
 * list: which entries the caller gets back, and which entries count toward the
 * error/warning/info totals.
 *
 * <p>Both used to be private helpers inside the UI bundle's collector, where no
 * test runtime can reach them — the review-marker leak was therefore only ever
 * observable live. They are extracted here as dependency-free generic functions
 * (the caller supplies origin/severity accessors) precisely so the decisions can
 * be asserted on a returned value in {@code com.codepilot1c.core.tests}. The
 * collector delegates; it holds no copy of the rules.</p>
 *
 * <p>Note that the two decisions are deliberately <em>not</em> the same
 * predicate. {@code origin=all} puts review overlays back into the returned
 * list, yet they must still never reach the counters: a review comment is not an
 * error, a warning or an info-level finding, and the counters are what a caller
 * reads to decide "is this file clean".</p>
 *
 * @see DiagnosticOrigin
 */
public final class DiagnosticOriginSelection {

    /** Index of the error count in the {@link #countBySeverity} result. */
    public static final int ERRORS = 0;
    /** Index of the warning count in the {@link #countBySeverity} result. */
    public static final int WARNINGS = 1;
    /** Index of the info count in the {@link #countBySeverity} result. */
    public static final int INFOS = 2;

    private static final int LEVEL_ERROR = 2;
    private static final int LEVEL_WARNING = 1;

    private DiagnosticOriginSelection() {
    }

    /**
     * Keeps only the entries whose provenance the caller asked for.
     *
     * @param <T> diagnostic entry type
     * @param items collected entries ({@code null}/empty tolerated)
     * @param originFilter filter expression — see
     *        {@link DiagnosticOrigin#accepts(String, String)}; blank means the
     *        default (everything but review overlays)
     * @param originOf reads the origin label off an entry; a {@code null} label
     *        degrades to {@link DiagnosticOrigin#UNKNOWN} and is kept
     * @return the retained entries in their original order; the very same list
     *         instance when nothing was dropped (callers rely on the list they
     *         passed staying untouched)
     */
    public static <T> List<T> retain(
            List<T> items, String originFilter, Function<? super T, String> originOf) {

        if (items == null || items.isEmpty() || originOf == null) {
            return items;
        }
        List<T> retained = new ArrayList<>(items.size());
        for (T item : items) {
            if (DiagnosticOrigin.accepts(originFilter, originOf.apply(item))) {
                retained.add(item);
            }
        }
        return retained.size() == items.size() ? items : retained;
    }

    /**
     * Tallies entries per severity, skipping review overlays whatever their
     * nominal severity is.
     *
     * <p>Severity is read as a level: {@code >= 2} counts as an error,
     * {@code == 1} as a warning, anything else (including a negative "severity
     * was never declared" value) as info — matching
     * {@code IMarker.SEVERITY_ERROR}/{@code _WARNING} and the diagnostic
     * severity enum's own levels.</p>
     *
     * @param <T> diagnostic entry type
     * @param items collected entries ({@code null}/empty tolerated)
     * @param originOf reads the origin label off an entry
     * @param severityLevelOf reads the severity level off an entry
     * @return a 3-element array indexed by {@link #ERRORS}, {@link #WARNINGS},
     *         {@link #INFOS}
     */
    public static <T> int[] countBySeverity(
            Collection<T> items,
            Function<? super T, String> originOf,
            ToIntFunction<? super T> severityLevelOf) {

        int errors = 0;
        int warnings = 0;
        int infos = 0;
        if (items != null) {
            for (T item : items) {
                if (originOf != null && DiagnosticOrigin.isReviewAnnotation(originOf.apply(item))) {
                    continue;
                }
                int level = severityLevelOf == null ? 0 : severityLevelOf.applyAsInt(item);
                if (level >= LEVEL_ERROR) {
                    errors++;
                } else if (level == LEVEL_WARNING) {
                    warnings++;
                } else {
                    infos++;
                }
            }
        }
        return new int[] {errors, warnings, infos};
    }
}
