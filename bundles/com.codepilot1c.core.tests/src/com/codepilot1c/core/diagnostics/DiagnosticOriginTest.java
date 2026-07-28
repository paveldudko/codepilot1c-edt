/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for the marker-provenance classifier behind
 * {@code get_diagnostics}'s {@code origin} field.
 *
 * <p>Regression context: {@code get_diagnostics scope=file} reads markers with
 * {@code findMarkers(null, true, …)} — every type, subtypes included. The
 * sibling commit-review plugin contributes
 * {@code com.dudko.edt.review.commentMarker} (a
 * {@code org.eclipse.core.resources.textmarker} subtype) and declares no
 * {@code IMarker.SEVERITY}, so it degraded to {@code INFO} and blended into the
 * diagnostics list with no way to tell it apart. These tests pin the rules that
 * give every entry a provenance label and keep review overlays out of the
 * default answer.</p>
 */
public class DiagnosticOriginTest {

    private static final String REVIEW_MARKER = "com.dudko.edt.review.commentMarker"; //$NON-NLS-1$

    // --- review overlays -----------------------------------------------------

    @Test
    public void reviewCommentMarkerIsReviewAnnotationFromTypeAlone() {
        assertEquals(DiagnosticOrigin.REVIEW_ANNOTATION,
                DiagnosticOrigin.classify(REVIEW_MARKER, DiagnosticOrigin.SOURCE_MARKER));
    }

    @Test
    public void reviewCommentMarkerIsReviewAnnotationWithFullTraits() {
        // How the collector actually sees it: no severity attribute, textmarker subtype.
        assertEquals(DiagnosticOrigin.REVIEW_ANNOTATION,
                DiagnosticOrigin.classify(REVIEW_MARKER, DiagnosticOrigin.SOURCE_MARKER, false, true));
        // ...and it stays review even if the plugin later starts setting severity.
        assertEquals(DiagnosticOrigin.REVIEW_ANNOTATION,
                DiagnosticOrigin.classify(REVIEW_MARKER, DiagnosticOrigin.SOURCE_MARKER, true, true));
    }

    @Test
    public void severitylessTextMarkerSubtypeIsReviewAnnotation() {
        // Generic rule: an unknown textmarker subtype with no severity is an
        // annotation overlay, not a diagnostic.
        assertEquals(DiagnosticOrigin.REVIEW_ANNOTATION,
                DiagnosticOrigin.classify("com.acme.notes.noteMarker", //$NON-NLS-1$
                        DiagnosticOrigin.SOURCE_MARKER, false, true));
    }

    @Test
    public void textMarkerSubtypeThatDeclaresSeverityIsNotReview() {
        assertEquals(DiagnosticOrigin.CUSTOM_CHECK,
                DiagnosticOrigin.classify("com.acme.lint.lintMarker", //$NON-NLS-1$
                        DiagnosticOrigin.SOURCE_MARKER, true, true));
    }

    @Test
    public void platformTaskAndBookmarkMarkersAreNotSweptIntoReview() {
        // TODO/task and bookmark markers also carry no severity, but they
        // predate this rule and callers still expect them in the answer.
        assertEquals(DiagnosticOrigin.UNKNOWN,
                DiagnosticOrigin.classify("org.eclipse.core.resources.taskmarker", //$NON-NLS-1$
                        DiagnosticOrigin.SOURCE_MARKER, false, true));
        assertEquals(DiagnosticOrigin.UNKNOWN,
                DiagnosticOrigin.classify("org.eclipse.core.resources.bookmark", //$NON-NLS-1$
                        DiagnosticOrigin.SOURCE_MARKER, false, false));
    }

    // --- compiler ------------------------------------------------------------

    @Test
    public void jdtAndPlatformProblemMarkersAreCompiler() {
        assertEquals(DiagnosticOrigin.COMPILER,
                DiagnosticOrigin.classify("org.eclipse.jdt.core.problem", //$NON-NLS-1$
                        DiagnosticOrigin.SOURCE_MARKER, true, false));
        assertEquals(DiagnosticOrigin.COMPILER,
                DiagnosticOrigin.classify("org.eclipse.core.resources.problemmarker", //$NON-NLS-1$
                        DiagnosticOrigin.SOURCE_MARKER, true, true));
    }

    @Test
    public void bslProblemMarkerAndSyntaxErrorsAreCompiler() {
        assertEquals(DiagnosticOrigin.COMPILER,
                DiagnosticOrigin.classify("com._1c.g5.v8.dt.bsl.ui.bslProblemMarker", //$NON-NLS-1$
                        DiagnosticOrigin.SOURCE_MARKER, true, true));
        assertEquals(DiagnosticOrigin.COMPILER,
                DiagnosticOrigin.classify("org.eclipse.xtext.ui.editor.error", //$NON-NLS-1$
                        DiagnosticOrigin.SOURCE_ANNOTATION));
    }

    // --- analyzer ------------------------------------------------------------

    @Test
    public void markerManagerSourceIsAnalyzer() {
        assertEquals(DiagnosticOrigin.ANALYZER,
                DiagnosticOrigin.classify(null, DiagnosticOrigin.SOURCE_MARKER_MANAGER));
        assertEquals(DiagnosticOrigin.ANALYZER,
                DiagnosticOrigin.classify("BslModule", DiagnosticOrigin.SOURCE_MARKER_MANAGER)); //$NON-NLS-1$
    }

    @Test
    public void edtCheckAndValidatorTypesAreAnalyzer() {
        assertEquals(DiagnosticOrigin.ANALYZER,
                DiagnosticOrigin.classify("com._1c.g5.v8.dt.check.marker", //$NON-NLS-1$
                        DiagnosticOrigin.SOURCE_MARKER, true, false));
        // Our own synthetic labels from the live BSL/DCS validators.
        assertEquals(DiagnosticOrigin.ANALYZER,
                DiagnosticOrigin.classify("xtext:SU4", DiagnosticOrigin.SOURCE_ANNOTATION)); //$NON-NLS-1$
        assertEquals(DiagnosticOrigin.ANALYZER,
                DiagnosticOrigin.classify("dcs-schema", DiagnosticOrigin.SOURCE_ANNOTATION)); //$NON-NLS-1$
    }

    // --- leftovers -----------------------------------------------------------

    @Test
    public void unknownTypeWithSeverityIsCustomCheck() {
        assertEquals(DiagnosticOrigin.CUSTOM_CHECK,
                DiagnosticOrigin.classify("com.acme.thing.someMarker", //$NON-NLS-1$
                        DiagnosticOrigin.SOURCE_MARKER, true, false));
    }

    @Test
    public void unknownTypeWithoutSeverityIsUnknown() {
        assertEquals(DiagnosticOrigin.UNKNOWN,
                DiagnosticOrigin.classify("com.acme.thing.someMarker", //$NON-NLS-1$
                        DiagnosticOrigin.SOURCE_MARKER, false, false));
        // The collector's own "type unreadable" placeholder must not read as a check.
        assertEquals(DiagnosticOrigin.UNKNOWN,
                DiagnosticOrigin.classify("unknown", DiagnosticOrigin.SOURCE_MARKER, true, false)); //$NON-NLS-1$
    }

    @Test
    public void blankAndNullInputsDoNotThrow() {
        assertEquals(DiagnosticOrigin.UNKNOWN, DiagnosticOrigin.classify(null, null));
        assertEquals(DiagnosticOrigin.UNKNOWN, DiagnosticOrigin.classify("", "")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(DiagnosticOrigin.UNKNOWN, DiagnosticOrigin.classify("   ", null, false, false)); //$NON-NLS-1$
        assertEquals(DiagnosticOrigin.UNKNOWN, DiagnosticOrigin.classify(null, "  ", true, true)); //$NON-NLS-1$
    }

    @Test
    public void classificationIsCaseAndWhitespaceInsensitive() {
        assertEquals(DiagnosticOrigin.REVIEW_ANNOTATION,
                DiagnosticOrigin.classify("  COM.DUDKO.EDT.REVIEW.CommentMarker  ", " MARKER ")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(DiagnosticOrigin.ANALYZER,
                DiagnosticOrigin.classify(null, " Marker_Manager ")); //$NON-NLS-1$
    }

    // --- filter --------------------------------------------------------------

    @Test
    public void defaultFilterExcludesOnlyReviewAnnotations() {
        for (String filter : new String[] {null, "", "  ", DiagnosticOrigin.FILTER_DIAGNOSTICS}) { //$NON-NLS-1$ //$NON-NLS-2$
            assertFalse("review overlays must be excluded by default (filter=" + filter + ")", //$NON-NLS-1$ //$NON-NLS-2$
                    DiagnosticOrigin.accepts(filter, DiagnosticOrigin.REVIEW_ANNOTATION));
            assertTrue(DiagnosticOrigin.accepts(filter, DiagnosticOrigin.COMPILER));
            assertTrue(DiagnosticOrigin.accepts(filter, DiagnosticOrigin.ANALYZER));
            assertTrue(DiagnosticOrigin.accepts(filter, DiagnosticOrigin.CUSTOM_CHECK));
            assertTrue(DiagnosticOrigin.accepts(filter, DiagnosticOrigin.UNKNOWN));
            // A null origin must not be filtered out — it degrades to "unknown".
            assertTrue(DiagnosticOrigin.accepts(filter, null));
        }
    }

    @Test
    public void allFilterKeepsEverything() {
        assertTrue(DiagnosticOrigin.accepts(DiagnosticOrigin.FILTER_ALL, DiagnosticOrigin.REVIEW_ANNOTATION));
        assertTrue(DiagnosticOrigin.accepts(" ALL ", DiagnosticOrigin.REVIEW_ANNOTATION)); //$NON-NLS-1$
        assertTrue(DiagnosticOrigin.accepts(DiagnosticOrigin.FILTER_ALL, DiagnosticOrigin.COMPILER));
    }

    @Test
    public void explicitOriginNarrowsTheAnswer() {
        assertTrue(DiagnosticOrigin.accepts(
                DiagnosticOrigin.REVIEW_ANNOTATION, DiagnosticOrigin.REVIEW_ANNOTATION));
        assertFalse(DiagnosticOrigin.accepts(
                DiagnosticOrigin.REVIEW_ANNOTATION, DiagnosticOrigin.COMPILER));
        assertTrue(DiagnosticOrigin.accepts("compiler,analyzer", DiagnosticOrigin.ANALYZER)); //$NON-NLS-1$
        assertFalse(DiagnosticOrigin.accepts("compiler,analyzer", DiagnosticOrigin.UNKNOWN)); //$NON-NLS-1$
    }

    @Test
    public void unrecognizedFilterFallsBackToDefaultInsteadOfEmptyResult() {
        assertTrue(DiagnosticOrigin.accepts("bogus", DiagnosticOrigin.COMPILER)); //$NON-NLS-1$
        assertFalse(DiagnosticOrigin.accepts("bogus", DiagnosticOrigin.REVIEW_ANNOTATION)); //$NON-NLS-1$
    }

    // --- helpers -------------------------------------------------------------

    @Test
    public void isReviewAnnotationRecognizesNormalizedInput() {
        assertTrue(DiagnosticOrigin.isReviewAnnotation(" Review-Annotation ")); //$NON-NLS-1$
        assertFalse(DiagnosticOrigin.isReviewAnnotation(null));
        assertFalse(DiagnosticOrigin.isReviewAnnotation(DiagnosticOrigin.ANALYZER));
    }

    @Test
    public void knownOriginsAndDefaults() {
        assertTrue(DiagnosticOrigin.isKnownOrigin(DiagnosticOrigin.COMPILER));
        assertTrue(DiagnosticOrigin.isKnownOrigin(DiagnosticOrigin.REVIEW_ANNOTATION));
        assertFalse(DiagnosticOrigin.isKnownOrigin(DiagnosticOrigin.FILTER_ALL));
        assertFalse(DiagnosticOrigin.isKnownOrigin(null));
        assertEquals(DiagnosticOrigin.FILTER_DIAGNOSTICS, DiagnosticOrigin.defaultFilter());
        assertEquals(DiagnosticOrigin.UNKNOWN, DiagnosticOrigin.normalize(null));
        assertEquals(DiagnosticOrigin.UNKNOWN, DiagnosticOrigin.normalize("  ")); //$NON-NLS-1$
        assertEquals(DiagnosticOrigin.COMPILER, DiagnosticOrigin.normalize(" Compiler ")); //$NON-NLS-1$
    }
}
