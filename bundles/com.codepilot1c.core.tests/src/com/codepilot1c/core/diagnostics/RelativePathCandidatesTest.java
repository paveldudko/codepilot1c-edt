/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.Test;

/**
 * Pure-Java tests for the candidate-path builder used by
 * {@code EdtDiagnosticsCollector}. Pins the project-name-stripping
 * behaviour that fixes the silent-zero diagnostics bug for projects
 * whose name is 3+ chars (incl. names with spaces).
 *
 * <p>See {@code 2026-05-19-diagnostics-space-in-project-name.md} for the
 * full incident report. Briefly: when the first path segment matches a
 * workspace project, the project name was previously kept as a "match
 * token" with the ALL-tokens threshold — guaranteeing rejection of
 * every runtime marker, because EDT marker haystacks never carry the
 * project name. Stripping it from the candidate makes the existing
 * {@code PathMatchTokens.buildMatchTokens} produce a sane set.</p>
 */
public class RelativePathCandidatesTest {

    private static final Set<String> KNOWN = Set.of("AM", "Accounting management", "BookOfRoles");

    // --- happy path ----------------------------------------------------------

    @Test
    public void stripsKnownProjectPrefixWhenPathStartsWithIt() {
        List<String> candidates = RelativePathCandidates.build(
                "Accounting management/src/CommonModules/Integration_HighRadius/Module.bsl",
                KNOWN);
        assertTrue("workspace-rooted form must be present", //$NON-NLS-1$
                candidates.contains("Accounting management/src/CommonModules/Integration_HighRadius/Module.bsl"));
        assertTrue("project-stripped form must be added", //$NON-NLS-1$
                candidates.contains("src/CommonModules/Integration_HighRadius/Module.bsl"));
    }

    @Test
    public void stripsSingleWordProjectPrefix() {
        List<String> candidates = RelativePathCandidates.build(
                "AM/src/Documents/SalesOrder/Ext/ObjectModule.bsl", KNOWN);
        assertTrue(candidates.contains("AM/src/Documents/SalesOrder/Ext/ObjectModule.bsl"));
        assertTrue(candidates.contains("src/Documents/SalesOrder/Ext/ObjectModule.bsl"));
    }

    @Test
    public void doesNotStripWhenFirstSegmentIsNotAKnownProject() {
        List<String> candidates = RelativePathCandidates.build(
                "SomeOtherFolder/src/Documents/X/ObjectModule.bsl", KNOWN);
        // Only the original survives — we have no evidence the first
        // segment is a project, so we don't risk a misleading shortcut.
        assertEquals(1, candidates.size());
        assertTrue(candidates.contains("SomeOtherFolder/src/Documents/X/ObjectModule.bsl"));
    }

    @Test
    public void doesNotStripWhenInputIsAlreadyProjectRelative() {
        // Path starts with "src/" — no project prefix to strip.
        List<String> candidates = RelativePathCandidates.build(
                "src/CommonModules/X/Module.bsl", KNOWN);
        assertEquals(1, candidates.size());
        assertTrue(candidates.contains("src/CommonModules/X/Module.bsl"));
    }

    @Test
    public void handlesCyrillicProjectName() {
        Set<String> known = Set.of("Учёт");
        List<String> candidates = RelativePathCandidates.build(
                "Учёт/src/CommonModules/Общего/Module.bsl", known);
        assertTrue(candidates.contains("Учёт/src/CommonModules/Общего/Module.bsl"));
        assertTrue(candidates.contains("src/CommonModules/Общего/Module.bsl"));
    }

    // --- Configuration/... legacy prefix -------------------------------------

    @Test
    public void preservesConfigurationPrefixStrippingForLegacyPaths() {
        List<String> candidates = RelativePathCandidates.build(
                "Configuration/src/Documents/X/ObjectModule.bsl", KNOWN);
        assertTrue("Configuration-prefixed form must survive", //$NON-NLS-1$
                candidates.contains("Configuration/src/Documents/X/ObjectModule.bsl"));
        assertTrue("Configuration prefix must be stripped (legacy convention)", //$NON-NLS-1$
                candidates.contains("src/Documents/X/ObjectModule.bsl"));
    }

    @Test
    public void preservesCyrillicConfigurationPrefixStripping() {
        List<String> candidates = RelativePathCandidates.build(
                "Конфигурация/src/Documents/X/ObjectModule.bsl", KNOWN);
        assertTrue(candidates.contains("Конфигурация/src/Documents/X/ObjectModule.bsl"));
        assertTrue(candidates.contains("src/Documents/X/ObjectModule.bsl"));
    }

    @Test
    public void stripsProjectThenConfigurationOnNestedPrefix() {
        Set<String> known = Set.of("MyProject");
        List<String> candidates = RelativePathCandidates.build(
                "MyProject/Configuration/src/Documents/X/ObjectModule.bsl", known);
        assertTrue("must include original", //$NON-NLS-1$
                candidates.contains("MyProject/Configuration/src/Documents/X/ObjectModule.bsl"));
        assertTrue("must include project-stripped form", //$NON-NLS-1$
                candidates.contains("Configuration/src/Documents/X/ObjectModule.bsl"));
        assertTrue("must include both-stripped form", //$NON-NLS-1$
                candidates.contains("src/Documents/X/ObjectModule.bsl"));
    }

    // --- edge cases ----------------------------------------------------------

    @Test
    public void blankInputReturnsEmptyList() {
        assertTrue(RelativePathCandidates.build("", KNOWN).isEmpty());
        assertTrue(RelativePathCandidates.build(null, KNOWN).isEmpty());
        assertTrue(RelativePathCandidates.build("   ", KNOWN).isEmpty());
    }

    @Test
    public void nullKnownSetIsTreatedAsEmpty() {
        List<String> candidates = RelativePathCandidates.build(
                "AM/src/Module.bsl", null);
        // Without project-name knowledge we can't strip — only original kept.
        assertEquals(1, candidates.size());
        assertTrue(candidates.contains("AM/src/Module.bsl"));
    }

    @Test
    public void pathWithoutSlashIsReturnedAsSingleCandidate() {
        List<String> candidates = RelativePathCandidates.build("LoneFile.bsl", KNOWN);
        assertEquals(1, candidates.size());
        assertTrue(candidates.contains("LoneFile.bsl"));
    }

    @Test
    public void leadingSlashIsRemoved() {
        List<String> candidates = RelativePathCandidates.build(
                "/AM/src/Module.bsl", KNOWN);
        assertTrue(candidates.contains("AM/src/Module.bsl"));
        assertTrue(candidates.contains("src/Module.bsl"));
    }

    @Test
    public void backslashesNormalisedToForwardSlashes() {
        List<String> candidates = RelativePathCandidates.build(
                "AM\\src\\CommonModules\\X\\Module.bsl", KNOWN);
        assertTrue(candidates.contains("AM/src/CommonModules/X/Module.bsl"));
        assertTrue(candidates.contains("src/CommonModules/X/Module.bsl"));
    }

    @Test
    public void projectPrefixWithoutRestProducesOnlyOriginal() {
        // "AM" alone — there's no rest to strip down to.
        List<String> candidates = RelativePathCandidates.build("AM/", KNOWN);
        assertEquals(1, candidates.size());
        assertTrue(candidates.contains("AM/"));
    }

    // --- regression: token set after stripping -------------------------------

    @Test
    public void afterStrippingTheTokenSetContainsNoProjectName() {
        // The whole point of the fix — show that downstream PathMatchTokens
        // no longer produces a "project name" token that blocks all matches.
        List<String> candidates = RelativePathCandidates.build(
                "Accounting management/src/CommonModules/Integration_HighRadius/Module.bsl",
                KNOWN);
        // Use the project-stripped form for tokenisation — that's the
        // intended flow.
        String stripped = candidates.stream()
                .filter(c -> !c.startsWith("Accounting management/"))
                .findFirst()
                .orElseThrow();
        List<String> tokens = PathMatchTokens.buildMatchTokens(List.of(stripped));
        assertTrue("integration_highradius must survive as a discriminator", //$NON-NLS-1$
                tokens.contains("integration_highradius"));
        assertTrue("project name (with space) must NOT appear as a token", //$NON-NLS-1$
                !tokens.contains("accounting management"));
    }
}
