/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.diagnostics;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

/**
 * Behavioural test for the two origin-aware decisions {@code get_diagnostics}
 * makes over a collected list — what comes back, and what is counted.
 *
 * <p>What was broken: {@code get_diagnostics scope=file} reads markers with
 * {@code findMarkers(null, true, …)}, i.e. every marker type on the resource.
 * The sibling commit-review plugin contributes
 * {@code com.dudko.edt.review.commentMarker} (an
 * {@code org.eclipse.core.resources.textmarker} subtype) and never sets
 * {@code IMarker.SEVERITY}, so the missing attribute degraded to {@code INFO},
 * passed the default severity threshold, landed among the EDT diagnostics and —
 * the part that actually misleads a caller — was tallied into {@code infoCount}.
 * A file with no code problems reported problems.</p>
 *
 * <p>Why the assertions look like this: the filtering and the tallying used to be
 * private methods of {@code com.codepilot1c.ui.diagnostics.EdtDiagnosticsCollector},
 * and no test bundle in this build can load the UI bundle
 * ({@code com.codepilot1c.core.tests} is a plain JAR module depending on core
 * only, and {@code com.codepilot1c.ui.tests} has no sources and is not in the
 * reactor). The rules are therefore in {@link DiagnosticOriginSelection}, the
 * collector delegates, and these tests assert on the returned list and the
 * returned counters instead of grepping the collector's source — a source-grep
 * assertion cannot tell whether a review entry ends up in {@code infoCount}.</p>
 *
 * <p>The last test walks the whole pure chain the live collector walks
 * (classify marker traits &rarr; retain &rarr; count), which is as close to the
 * live path as is reachable without an Eclipse workspace.</p>
 */
public class DiagnosticOriginSelectionTest {

    /** The foreign marker type that leaked in. */
    private static final String REVIEW_MARKER = "com.dudko.edt.review.commentMarker"; //$NON-NLS-1$

    /** Severity levels as the diagnostic severity enum / {@code IMarker} report them. */
    private static final int ERROR = 2;
    private static final int WARNING = 1;
    private static final int INFO = 0;

    /** Stand-in for an {@code EdtDiagnostic}: the two fields both decisions read. */
    private record Entry(String label, String origin, int severityLevel) {
    }

    private static Entry diagnostic(String label, int severityLevel) {
        return new Entry(label, DiagnosticOrigin.ANALYZER, severityLevel);
    }

    private static Entry review(String label, int severityLevel) {
        return new Entry(label, DiagnosticOrigin.REVIEW_ANNOTATION, severityLevel);
    }

    private static List<Entry> retain(List<Entry> items, String filter) {
        return DiagnosticOriginSelection.retain(items, filter, Entry::origin);
    }

    private static int[] count(List<Entry> items) {
        return DiagnosticOriginSelection.countBySeverity(items, Entry::origin, Entry::severityLevel);
    }

    private static List<String> labels(List<Entry> items) {
        List<String> out = new ArrayList<>();
        for (Entry e : items) {
            out.add(e.label());
        }
        return out;
    }

    // --- what comes back -----------------------------------------------------

    @Test
    public void aPlainCallDoesNotReturnReviewOverlays() {
        List<Entry> collected = List.of(
                diagnostic("real-error", ERROR), //$NON-NLS-1$
                review("comment-1", INFO), //$NON-NLS-1$
                diagnostic("real-warning", WARNING), //$NON-NLS-1$
                review("comment-2", INFO)); //$NON-NLS-1$

        for (String defaultish : new String[] {null, "", "  ", DiagnosticOrigin.FILTER_DIAGNOSTICS}) { //$NON-NLS-1$ //$NON-NLS-2$
            List<Entry> kept = retain(collected, defaultish);
            assertEquals("filter=" + defaultish, //$NON-NLS-1$
                    Arrays.asList("real-error", "real-warning"), labels(kept)); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    @Test
    public void retainedEntriesKeepTheirCollectionOrder() {
        List<Entry> collected = List.of(
                review("comment-1", INFO), //$NON-NLS-1$
                diagnostic("first", INFO), //$NON-NLS-1$
                review("comment-2", INFO), //$NON-NLS-1$
                diagnostic("second", ERROR), //$NON-NLS-1$
                diagnostic("third", WARNING)); //$NON-NLS-1$

        assertEquals(Arrays.asList("first", "second", "third"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                labels(retain(collected, null)));
    }

    @Test
    public void originAllBringsReviewOverlaysBack() {
        List<Entry> collected = List.of(
                diagnostic("real-error", ERROR), review("comment-1", INFO)); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(Arrays.asList("real-error", "comment-1"), //$NON-NLS-1$ //$NON-NLS-2$
                labels(retain(collected, DiagnosticOrigin.FILTER_ALL)));
    }

    @Test
    public void anExplicitOriginNarrowsToThatProvenanceOnly() {
        List<Entry> collected = List.of(
                new Entry("compiler-problem", DiagnosticOrigin.COMPILER, ERROR), //$NON-NLS-1$
                diagnostic("check-warning", WARNING), //$NON-NLS-1$
                review("comment-1", INFO)); //$NON-NLS-1$

        assertEquals(Collections.singletonList("comment-1"), //$NON-NLS-1$
                labels(retain(collected, DiagnosticOrigin.REVIEW_ANNOTATION)));
        assertEquals(Collections.singletonList("compiler-problem"), //$NON-NLS-1$
                labels(retain(collected, DiagnosticOrigin.COMPILER)));
        assertEquals(Arrays.asList("compiler-problem", "check-warning"), //$NON-NLS-1$ //$NON-NLS-2$
                labels(retain(collected, "compiler,analyzer"))); //$NON-NLS-1$
    }

    @Test
    public void aCleanListIsHandedBackUntouched() {
        // The collector passes its own list along; retaining everything must not
        // silently swap in a copy (nor drop anything).
        List<Entry> collected = List.of(diagnostic("only", INFO)); //$NON-NLS-1$
        assertSame(collected, retain(collected, null));
        assertSame(collected, retain(collected, DiagnosticOrigin.FILTER_ALL));
    }

    @Test
    public void anUnrecognizedFilterBehavesLikeTheDefaultRatherThanEmptyingTheAnswer() {
        List<Entry> collected = List.of(
                diagnostic("real-warning", WARNING), review("comment-1", INFO)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Collections.singletonList("real-warning"), //$NON-NLS-1$
                labels(retain(collected, "no-such-origin"))); //$NON-NLS-1$
    }

    @Test
    public void nullAndEmptyInputsAreTolerated() {
        assertEquals(Collections.emptyList(), retain(List.of(), null));
        assertNull(DiagnosticOriginSelection.retain(null, null, Entry::origin));
    }

    // --- what is counted -----------------------------------------------------

    @Test
    public void reviewOverlaysNeverReachTheCounters() {
        // The reported bug: two review comments on an otherwise clean file were
        // counted as info, so the file did not read as clean.
        int[] counts = count(List.of(review("comment-1", INFO), review("comment-2", INFO))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("errorCount", 0, counts[DiagnosticOriginSelection.ERRORS]); //$NON-NLS-1$
        assertEquals("warningCount", 0, counts[DiagnosticOriginSelection.WARNINGS]); //$NON-NLS-1$
        assertEquals("infoCount must not count review comments", //$NON-NLS-1$
                0, counts[DiagnosticOriginSelection.INFOS]);
    }

    @Test
    public void countersStayExactWhenTheCallerAskedForReviewOverlays() {
        // origin=all keeps review entries in the returned list — the counters
        // still describe diagnostics only. This is the case a "filter it out
        // early" fix would have missed.
        List<Entry> collected = List.of(
                diagnostic("real-error", ERROR), //$NON-NLS-1$
                review("comment-1", INFO), //$NON-NLS-1$
                diagnostic("real-warning", WARNING), //$NON-NLS-1$
                review("comment-2", INFO), //$NON-NLS-1$
                diagnostic("real-info", INFO)); //$NON-NLS-1$

        int[] counts = count(retain(collected, DiagnosticOrigin.FILTER_ALL));
        assertEquals(5, retain(collected, DiagnosticOrigin.FILTER_ALL).size());
        assertEquals(1, counts[DiagnosticOriginSelection.ERRORS]);
        assertEquals(1, counts[DiagnosticOriginSelection.WARNINGS]);
        assertEquals(1, counts[DiagnosticOriginSelection.INFOS]);
    }

    @Test
    public void aReviewOverlayIsSkippedEvenIfItClaimsAnErrorSeverity() {
        // Provenance decides, not severity: should the review plugin start
        // setting IMarker.SEVERITY, its comments must still not become errors.
        int[] counts = count(List.of(
                review("comment-as-error", ERROR), //$NON-NLS-1$
                diagnostic("real-error", ERROR))); //$NON-NLS-1$
        assertEquals(1, counts[DiagnosticOriginSelection.ERRORS]);
        assertEquals(0, counts[DiagnosticOriginSelection.WARNINGS]);
        assertEquals(0, counts[DiagnosticOriginSelection.INFOS]);
    }

    @Test
    public void everyOtherProvenanceIsCounted() {
        // The fix must not quietly shrink the counters: compiler, analyzer,
        // custom-check and unknown entries are all real findings.
        int[] counts = count(List.of(
                new Entry("compiler", DiagnosticOrigin.COMPILER, ERROR), //$NON-NLS-1$
                new Entry("analyzer", DiagnosticOrigin.ANALYZER, WARNING), //$NON-NLS-1$
                new Entry("third-party", DiagnosticOrigin.CUSTOM_CHECK, WARNING), //$NON-NLS-1$
                new Entry("unlabelled", DiagnosticOrigin.UNKNOWN, INFO), //$NON-NLS-1$
                new Entry("no-origin-at-all", null, INFO))); //$NON-NLS-1$
        assertEquals(1, counts[DiagnosticOriginSelection.ERRORS]);
        assertEquals(2, counts[DiagnosticOriginSelection.WARNINGS]);
        assertEquals(2, counts[DiagnosticOriginSelection.INFOS]);
    }

    @Test
    public void anUndeclaredSeverityCountsAsInfoNotAsAnError() {
        // getAttribute(IMarker.SEVERITY, -1) yields -1 when the attribute is
        // absent; that must land in info, not above ERROR.
        int[] counts = count(List.of(diagnostic("no-severity-attribute", -1))); //$NON-NLS-1$
        assertEquals(0, counts[DiagnosticOriginSelection.ERRORS]);
        assertEquals(0, counts[DiagnosticOriginSelection.WARNINGS]);
        assertEquals(1, counts[DiagnosticOriginSelection.INFOS]);
    }

    @Test
    public void emptyAndNullListsCountAsAllZero() {
        assertArrayEquals(new int[] {0, 0, 0}, count(List.of()));
        assertArrayEquals(new int[] {0, 0, 0},
                DiagnosticOriginSelection.countBySeverity(null, Entry::origin, Entry::severityLevel));
    }

    // --- the whole pure chain, as the collector walks it ---------------------

    @Test
    public void theLiveMarkerScanEndsUpWithACleanAnswerAndCleanCounters() {
        // Exactly the marker traits the collector reads off each marker:
        // (type, collection path, severity-declared, textmarker-subtype).
        List<Entry> collected = List.of(
                fromMarker("bsl-error", "com._1c.g5.v8.dt.bsl.ui.bslProblemMarker", true, true, ERROR), //$NON-NLS-1$ //$NON-NLS-2$
                fromMarker("edt-check", "com._1c.g5.v8.dt.check.marker", true, false, WARNING), //$NON-NLS-1$ //$NON-NLS-2$
                // The leak: no severity attribute at all, so INFO by default.
                fromMarker("review-comment", REVIEW_MARKER, false, true, INFO)); //$NON-NLS-1$

        List<Entry> answered = retain(collected, DiagnosticOrigin.defaultFilter());
        int[] counts = count(answered);

        assertFalse("a review comment must not appear in the default answer", //$NON-NLS-1$
                labels(answered).contains("review-comment")); //$NON-NLS-1$
        assertEquals(Arrays.asList("bsl-error", "edt-check"), labels(answered)); //$NON-NLS-1$ //$NON-NLS-2$
        assertArrayEquals("1 error, 1 warning, 0 info — the review comment is not an info finding", //$NON-NLS-1$
                new int[] {1, 1, 0}, counts);
    }

    @Test
    public void aFileWhoseOnlyMarkersAreReviewCommentsReadsAsClean() {
        List<Entry> collected = List.of(
                fromMarker("comment-1", REVIEW_MARKER, false, true, INFO), //$NON-NLS-1$
                fromMarker("comment-2", REVIEW_MARKER, false, true, INFO)); //$NON-NLS-1$

        List<Entry> answered = retain(collected, DiagnosticOrigin.defaultFilter());
        assertTrue("nothing should be reported for a file with no code problems", answered.isEmpty()); //$NON-NLS-1$
        assertArrayEquals(new int[] {0, 0, 0}, count(answered));
    }

    /** Builds an entry the way the collector does: origin from marker traits. */
    private static Entry fromMarker(
            String label, String markerType, boolean severityDeclared,
            boolean textMarkerSubtype, int severityLevel) {

        String origin = DiagnosticOrigin.classify(
                markerType, DiagnosticOrigin.SOURCE_MARKER, severityDeclared, textMarkerSubtype);
        return new Entry(label, origin, severityLevel);
    }
}
