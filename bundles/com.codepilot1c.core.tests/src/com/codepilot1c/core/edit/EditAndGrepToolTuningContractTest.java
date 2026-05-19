/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edit;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

/**
 * Source-grep contract test that pins the new code paths added in Phase 1 of
 * the local BSL-tooling tuning: {@code edit_file} dry-run / replaceLines /
 * replaceMethod / return_diff, and {@code grep} compact / match_kind.
 *
 * <p>The behaviour-end of these changes touches {@code IFile} and the
 * Eclipse workspace API, which can't be exercised from the plain-Maven
 * test bundle. So we pin the structure: the contract test reads the
 * source files as plain strings and asserts that the required code paths
 * exist. This mirrors {@code FormSerializationDefaultsContractTest}.</p>
 */
public class EditAndGrepToolTuningContractTest {

    private static final String EDIT_TOOL_PATH =
            "bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/file/EditFileTool.java"; //$NON-NLS-1$
    private static final String GREP_TOOL_PATH =
            "bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/file/GrepTool.java"; //$NON-NLS-1$

    // --- EditFileTool: schema -------------------------------------------------

    @Test
    public void editFileSchemaAdvertisesModeReplaceLines() throws Exception {
        String src = read(EDIT_TOOL_PATH);
        assertTrue("schema must advertise mode enum with replaceLines", //$NON-NLS-1$
                src.contains("\"replaceLines\"")); //$NON-NLS-1$
    }

    @Test
    public void editFileSchemaAdvertisesModeReplaceMethod() throws Exception {
        String src = read(EDIT_TOOL_PATH);
        assertTrue("schema must advertise mode enum with replaceMethod", //$NON-NLS-1$
                src.contains("\"replaceMethod\"")); //$NON-NLS-1$
    }

    @Test
    public void editFileSchemaAdvertisesDryRunAndReturnDiff() throws Exception {
        String src = read(EDIT_TOOL_PATH);
        assertTrue("schema must declare dry_run", src.contains("\"dry_run\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare return_diff", src.contains("\"return_diff\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void editFileSchemaAdvertisesLineFromLineTo() throws Exception {
        String src = read(EDIT_TOOL_PATH);
        assertTrue("schema must declare line_from", src.contains("\"line_from\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare line_to", src.contains("\"line_to\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void editFileSchemaAdvertisesMethodName() throws Exception {
        String src = read(EDIT_TOOL_PATH);
        assertTrue("schema must declare method_name", src.contains("\"method_name\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // --- EditFileTool: dispatch + helpers -------------------------------------

    @Test
    public void editFileDispatchesReplaceLinesMode() throws Exception {
        String src = read(EDIT_TOOL_PATH);
        assertTrue("dispatch must branch on mode == replaceLines", //$NON-NLS-1$
                src.contains("\"replaceLines\".equals(mode)")); //$NON-NLS-1$
        assertTrue("must call replaceLineRangeMode helper", //$NON-NLS-1$
                src.contains("replaceLineRangeMode(")); //$NON-NLS-1$
    }

    @Test
    public void editFileDispatchesReplaceMethodMode() throws Exception {
        String src = read(EDIT_TOOL_PATH);
        assertTrue("dispatch must branch on mode == replaceMethod", //$NON-NLS-1$
                src.contains("\"replaceMethod\".equals(mode)")); //$NON-NLS-1$
        assertTrue("must call replaceMethodMode helper", //$NON-NLS-1$
                src.contains("replaceMethodMode(")); //$NON-NLS-1$
    }

    @Test
    public void editFileWiresBslMethodParser() throws Exception {
        String src = read(EDIT_TOOL_PATH);
        assertTrue("replaceMethod mode must look up method via BslMethodParser", //$NON-NLS-1$
                src.contains("methodParser.findByName(")); //$NON-NLS-1$
    }

    @Test
    public void editFileWiresDiffComputerForDryRunAndReturnDiff() throws Exception {
        String src = read(EDIT_TOOL_PATH);
        assertTrue("must use DiffComputer for unified diffs", //$NON-NLS-1$
                src.contains("diffComputer.unifiedDiff(")); //$NON-NLS-1$
        assertTrue("must expose dry-run helper", //$NON-NLS-1$
                src.contains("buildDryRunResult(")); //$NON-NLS-1$
        assertTrue("must expose return_diff helper", //$NON-NLS-1$
                src.contains("buildApplyWithDiffResult(")); //$NON-NLS-1$
    }

    @Test
    public void editFileExposesAmbiguityFieldForLiteralMatches() throws Exception {
        String src = read(EDIT_TOOL_PATH);
        // The dry-run payload contains an "ambiguity" array when old_text
        // matches in more than one literal location. The helper that
        // collects literal offsets is the linchpin.
        assertTrue("must scan literal occurrences of old_text", //$NON-NLS-1$
                src.contains("findLiteralMatches(")); //$NON-NLS-1$
        assertTrue("must emit ambiguity payload when count > 1", //$NON-NLS-1$
                src.contains("\"ambiguity\"")); //$NON-NLS-1$
    }

    @Test
    public void editFileDelegatesSplicingToLineRangeReplacer() throws Exception {
        String src = read(EDIT_TOOL_PATH);
        assertTrue("must delegate to LineRangeReplacer.replaceLines (covered by LineRangeReplacerTest)", //$NON-NLS-1$
                src.contains("LineRangeReplacer.replaceLines(")); //$NON-NLS-1$
    }

    // --- GrepTool: schema -----------------------------------------------------

    @Test
    public void grepSchemaAdvertisesOutputMode() throws Exception {
        String src = read(GREP_TOOL_PATH);
        assertTrue("schema must declare output_mode", src.contains("\"output_mode\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must allow compact output", src.contains("\"compact\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void grepSchemaAdvertisesMatchKind() throws Exception {
        String src = read(GREP_TOOL_PATH);
        assertTrue("schema must declare match_kind", src.contains("\"match_kind\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must allow call", src.contains("\"call\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must allow definition", src.contains("\"definition\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // --- GrepTool: filtering + compact ----------------------------------------

    @Test
    public void grepAppliesMatchKindFilterOnlyForBslFiles() throws Exception {
        String src = read(GREP_TOOL_PATH);
        assertTrue("match_kind filter must be gated on .bsl filename", //$NON-NLS-1$
                src.contains("isBsl = file.getName().toLowerCase().endsWith(\".bsl\")")); //$NON-NLS-1$
        assertTrue("match_kind filter must delegate to BslMethodParser.passesMatchKindFilter (covered by BslMethodParserStaticHelpersTest)", //$NON-NLS-1$
                src.contains("BslMethodParser.passesMatchKindFilter(")); //$NON-NLS-1$
    }

    @Test
    public void grepFindsEnclosingMethodForBslMatches() throws Exception {
        String src = read(GREP_TOOL_PATH);
        assertTrue("compact format must delegate to BslMethodParser.findEnclosingMethodName (covered by BslMethodParserStaticHelpersTest)", //$NON-NLS-1$
                src.contains("BslMethodParser.findEnclosingMethodName(")); //$NON-NLS-1$
    }

    @Test
    public void grepCompactOutputFollowsFileLineSymbolFormat() throws Exception {
        String src = read(GREP_TOOL_PATH);
        // The compact format must include the " >>> " separator between the
        // [file:line OptionalSymbol] header and the matched snippet, so a
        // downstream agent can split lines deterministically.
        assertTrue("compact branch must emit the >>> separator", //$NON-NLS-1$
                src.contains("\" >>> \"")); //$NON-NLS-1$
        assertTrue("compact branch must conditionally emit [EnclosingSymbol]", //$NON-NLS-1$
                src.contains("match.enclosingSymbol != null")); //$NON-NLS-1$
    }

    // --- Helpers --------------------------------------------------------------

    private String read(String relativePath) throws Exception {
        return Files.readString(findRepoRoot().resolve(relativePath), StandardCharsets.UTF_8);
    }

    private Path findRepoRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize(); //$NON-NLS-1$
        while (current != null) {
            if (Files.isDirectory(current.resolve("bundles")) //$NON-NLS-1$
                    && Files.isRegularFile(current.resolve("pom.xml")) //$NON-NLS-1$
                    && Files.isRegularFile(current.resolve("LICENSE"))) { //$NON-NLS-1$
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Cannot locate repository root"); //$NON-NLS-1$
    }
}
