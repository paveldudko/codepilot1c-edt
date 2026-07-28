/*******************************************************************************
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 * Copyright (C) 2026 codepilot1c-edt contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License v3.0 as published by the
 * Free Software Foundation.
 ******************************************************************************/
package com.codepilot1c.core.edt.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * Covers the PURE half of the thin-client resolution: the {@link EdtRuntimeService.ThinClientResolution}
 * record, the failure text a caller sees, and the preference policy that decides which installed platform
 * is tried first.
 *
 * <p>Why a policy and not an API flag: {@code RuntimeInstallation} exposes only version / build / location
 * / arch / isTraining — there is no "pre-release" bit — so a beta can only lose by being ranked below the
 * version something actually asked for. Walking the candidates in that order (instead of taking EDT's
 * single newest-wins pick) is what the ranking here feeds.</p>
 *
 * <p>The resolution loop itself talks to the EDT runtime registry and is live-only; everything decidable
 * without EDT lives in the static helpers exercised below.</p>
 */
public class ThinClientResolutionPolicyTest {

    private static final String LOCATION = "file:/C:/Program%20Files/1cv8/8.3.27.2074/"; //$NON-NLS-1$

    // ---- ThinClientResolution record ---------------------------------------------------------

    @Test
    public void resolvedIsDrivenByThePresenceOfABinary() {
        EdtRuntimeService.ThinClientResolution resolved = new EdtRuntimeService.ThinClientResolution(
                new File("1cv8c.exe"), "8.3.27.2074", LOCATION, //$NON-NLS-1$ //$NON-NLS-2$
                EdtRuntimeService.RUNTIME_SOURCE_AUTO, List.of(), List.of());
        EdtRuntimeService.ThinClientResolution failed = new EdtRuntimeService.ThinClientResolution(
                null, null, null, EdtRuntimeService.RUNTIME_SOURCE_AUTO, List.of(), List.of());

        assertTrue(resolved.resolved());
        assertFalse(failed.resolved());
    }

    @Test
    public void nullDiagnosticListsBecomeEmptyAndAreDefensivelyCopied() {
        List<String> mutable = new ArrayList<>(List.of("8.3.27.2074")); //$NON-NLS-1$
        EdtRuntimeService.ThinClientResolution resolution = new EdtRuntimeService.ThinClientResolution(
                null, null, null, EdtRuntimeService.RUNTIME_SOURCE_AUTO, mutable, null);
        mutable.add("8.5.1.1302"); //$NON-NLS-1$

        assertNotNull(resolution.rejectReasons());
        assertTrue(resolution.rejectReasons().isEmpty());
        assertEquals(List.of("8.3.27.2074"), resolution.candidatesTried()); //$NON-NLS-1$
    }

    @Test
    public void withSourceRelabelsWithoutLosingTheAuditTrail() {
        EdtRuntimeService.ThinClientResolution resolution = new EdtRuntimeService.ThinClientResolution(
                new File("1cv8c.exe"), "8.3.27.2074", LOCATION, //$NON-NLS-1$ //$NON-NLS-2$
                EdtRuntimeService.RUNTIME_SOURCE_PARAM, List.of("8.3.27.2074"), List.of("beta: missing")); //$NON-NLS-1$ //$NON-NLS-2$

        EdtRuntimeService.ThinClientResolution relabelled =
                resolution.withSource(EdtRuntimeService.RUNTIME_SOURCE_LAUNCH_CONFIG);

        assertEquals(EdtRuntimeService.RUNTIME_SOURCE_LAUNCH_CONFIG, relabelled.source());
        assertEquals(resolution.versionWithBuild(), relabelled.versionWithBuild());
        assertEquals(resolution.location(), relabelled.location());
        assertEquals(resolution.candidatesTried(), relabelled.candidatesTried());
        assertEquals(resolution.rejectReasons(), relabelled.rejectReasons());
    }

    @Test
    public void describeNamesTheVersionLocationAndSource() {
        EdtRuntimeService.ThinClientResolution resolution = new EdtRuntimeService.ThinClientResolution(
                new File("1cv8c.exe"), "8.3.27.2074", LOCATION, //$NON-NLS-1$ //$NON-NLS-2$
                EdtRuntimeService.RUNTIME_SOURCE_LAUNCH_CONFIG, List.of(), List.of());

        String described = resolution.describe();

        assertTrue(described, described.contains("8.3.27.2074")); //$NON-NLS-1$
        assertTrue(described, described.contains(LOCATION));
        assertTrue(described, described.contains("source=launch_config")); //$NON-NLS-1$
    }

    // ---- failure text ------------------------------------------------------------------------

    @Test
    public void failureTextListsEveryCandidateAndEveryRejectReason() {
        String text = EdtRuntimeService.formatResolutionFailure(
                List.of("8.5.1.1302 (file:/beta)", "8.3.27.2074 (file:/stable)"), //$NON-NLS-1$ //$NON-NLS-2$
                List.of("8.5.1.1302 (file:/beta): 1cv8c.exe missing on disk", //$NON-NLS-1$
                        "8.3.27.2074 (file:/stable): resolveExecutor failed")); //$NON-NLS-1$

        assertTrue(text, text.contains("Tried 2 installation(s)")); //$NON-NLS-1$
        assertTrue(text, text.contains("8.5.1.1302 (file:/beta)")); //$NON-NLS-1$
        assertTrue(text, text.contains("8.3.27.2074 (file:/stable)")); //$NON-NLS-1$
        assertTrue(text, text.contains("Rejected:")); //$NON-NLS-1$
        assertTrue(text, text.contains("1cv8c.exe missing on disk")); //$NON-NLS-1$
        assertTrue(text, text.contains("resolveExecutor failed")); //$NON-NLS-1$
    }

    @Test
    public void failureTextExplainsAnEmptyCandidateSetInsteadOfSayingNothing() {
        // This is the branch that used to return null without logging a single line.
        String text = EdtRuntimeService.formatResolutionFailure(List.of(), List.of());

        assertTrue(text, text.contains("No platform installation was even considered")); //$NON-NLS-1$
        assertTrue(text, text.contains("Runtimes")); //$NON-NLS-1$
    }

    @Test
    public void failureTextKeepsAnEnumerationLevelReasonWithoutClaimingNothingWasConsidered() {
        // An explicit version that matches no installed platform: there is no candidate to list, but the
        // reason is the whole answer, so it must not be drowned by the "registry returned nothing" text.
        String text = EdtRuntimeService.formatResolutionFailure(List.of(),
                List.of("no installed platform matches the requested version 8.9.9")); //$NON-NLS-1$

        assertFalse(text, text.contains("was even considered")); //$NON-NLS-1$
        assertTrue(text, text.contains("no installed platform matches the requested version 8.9.9")); //$NON-NLS-1$
    }

    @Test
    public void failureTextToleratesNullLists() {
        String text = EdtRuntimeService.formatResolutionFailure(null, null);

        assertTrue(text, text.contains("No platform installation was even considered")); //$NON-NLS-1$
    }

    // ---- version matching --------------------------------------------------------------------

    @Test
    public void versionLineMatchAcceptsABuildInsideTheRequestedLine() {
        assertTrue(EdtRuntimeService.matchesVersionLine("8.3.27.2074", "8.3.27")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(EdtRuntimeService.matchesVersionLine("8.3.27.2074", "8.3.27.2074")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(EdtRuntimeService.matchesVersionLine("8.3.27.2074", " 8.3.27 ")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void versionLineMatchIsDirectionalSoALineCannotSatisfyAnExactBuildRequest() {
        // The HARD filter direction: asking for 8.3.27.2074 must not be satisfied by 8.3.27.
        assertFalse(EdtRuntimeService.matchesVersionLine("8.3.27", "8.3.27.2074")); //$NON-NLS-1$ //$NON-NLS-2$
        // And a neighbouring line must never look like a prefix match.
        assertFalse(EdtRuntimeService.matchesVersionLine("8.3.270.1", "8.3.27")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(EdtRuntimeService.matchesVersionLine("8.5.1.1302", "8.3.27")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(EdtRuntimeService.matchesVersionLine(null, "8.3.27")); //$NON-NLS-1$
        assertFalse(EdtRuntimeService.matchesVersionLine("8.3.27.2074", null)); //$NON-NLS-1$
        assertFalse(EdtRuntimeService.matchesVersionLine("8.3.27.2074", "  ")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void relatedVersionLineIsSymmetricForRankingOnly() {
        assertTrue(EdtRuntimeService.relatedVersionLine("8.3.27", "8.3.27.2074")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(EdtRuntimeService.relatedVersionLine("8.3.27.2074", "8.3.27")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(EdtRuntimeService.relatedVersionLine("8.5.1.1302", "8.3.27")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ---- preference policy -------------------------------------------------------------------

    @Test
    public void explicitMaskOutranksPinWhichOutranksInfobaseVersion() {
        String mask = "8.3.25"; //$NON-NLS-1$
        String pin = "8.3.27"; //$NON-NLS-1$
        String infobaseVersion = "8.3.24"; //$NON-NLS-1$

        assertEquals(0, EdtRuntimeService.runtimePreferenceRank("8.3.25.1", mask, pin, infobaseVersion)); //$NON-NLS-1$
        assertEquals(1, EdtRuntimeService.runtimePreferenceRank("8.3.27.2074", mask, pin, infobaseVersion)); //$NON-NLS-1$
        assertEquals(2, EdtRuntimeService.runtimePreferenceRank("8.3.24.1", mask, pin, infobaseVersion)); //$NON-NLS-1$
        // The newest installed platform — typically the pre-release nobody asked for — ranks last.
        assertEquals(3, EdtRuntimeService.runtimePreferenceRank("8.5.1.1302", mask, pin, infobaseVersion)); //$NON-NLS-1$
    }

    @Test
    public void withoutAnyPinEveryCandidateTiesAndOrderFallsBackToNewestFirst() {
        assertEquals(3, EdtRuntimeService.runtimePreferenceRank("8.3.27.2074", null, null, null)); //$NON-NLS-1$
        assertEquals(3, EdtRuntimeService.runtimePreferenceRank("8.5.1.1302", null, null, null)); //$NON-NLS-1$
        assertTrue(EdtRuntimeService.compareVersionsDescending("8.5.1.1302", "8.3.27.2074") < 0); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void pinIsHonouredEvenWhenTheInfobaseNamesAnotherVersion() {
        // The EDT-stored per-project+infobase pin is the owner's explicit choice; the infobase's own
        // version field is a weaker signal and must not outrank it.
        int pinned = EdtRuntimeService.runtimePreferenceRank("8.3.27.2074", null, "8.3.27.2074", "8.5.1"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        int infobaseOnly = EdtRuntimeService.runtimePreferenceRank("8.5.1.1302", null, "8.3.27.2074", "8.5.1"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertTrue(pinned + " must outrank " + infobaseOnly, pinned < infobaseOnly); //$NON-NLS-1$
    }

    // ---- version ordering --------------------------------------------------------------------

    @Test
    public void newerBuildsSortFirst() {
        assertTrue(EdtRuntimeService.compareVersionsDescending("8.3.27.2074", "8.3.27.1786") < 0); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(EdtRuntimeService.compareVersionsDescending("8.3.27.1786", "8.3.27.2074") > 0); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(0, EdtRuntimeService.compareVersionsDescending("8.3.27.2074", "8.3.27.2074")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void segmentsCompareNumericallyNotLexicographically() {
        // "1786" vs "999": a string comparison would put 999 first.
        assertTrue(EdtRuntimeService.compareVersionsDescending("8.3.27.1786", "8.3.27.999") < 0); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void missingSegmentsCountAsZero() {
        assertEquals(0, EdtRuntimeService.compareVersionsDescending("8.3.27", "8.3.27.0")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(EdtRuntimeService.compareVersionsDescending("8.3.27.1", "8.3.27") < 0); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void nonNumericSegmentsFallBackToTextAndNullsSortLast() {
        assertEquals(0, EdtRuntimeService.compareVersionsDescending("8.3.beta", "8.3.beta")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(EdtRuntimeService.compareVersionsDescending("8.3.beta", "8.3.alpha") < 0); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(EdtRuntimeService.compareVersionsDescending(null, "8.3.27") > 0); //$NON-NLS-1$
        assertTrue(EdtRuntimeService.compareVersionsDescending("8.3.27", null) < 0); //$NON-NLS-1$
        assertEquals(0, EdtRuntimeService.compareVersionsDescending(null, null));
    }
}
