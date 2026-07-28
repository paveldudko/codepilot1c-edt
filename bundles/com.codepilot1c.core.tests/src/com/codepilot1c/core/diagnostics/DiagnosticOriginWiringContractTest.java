/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.diagnostics;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

/**
 * Source-grep contract test pinning the {@code origin} (marker provenance)
 * wiring inside the UI bundle. The rules themselves are unit-tested in
 * {@link DiagnosticOriginTest}; this test asserts that the collector and the
 * diagnostic record actually USE them — the step that turns a unit-tested rule
 * into live behaviour.
 *
 * <p>Same technique (and same reason) as
 * {@link EdtDiagnosticsCollectorWiringContractTest}: {@code com.codepilot1c.ui}
 * classes are not reachable from this headless test bundle.</p>
 */
public class DiagnosticOriginWiringContractTest {

    private static final String COLLECTOR_PATH =
            "bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/diagnostics/EdtDiagnosticsCollector.java"; //$NON-NLS-1$
    private static final String DIAGNOSTIC_PATH =
            "bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/diagnostics/EdtDiagnostic.java"; //$NON-NLS-1$

    // --- EdtDiagnostic --------------------------------------------------------

    @Test
    public void diagnosticRecordCarriesOriginComponent() throws Exception {
        String src = read(DIAGNOSTIC_PATH);
        assertTrue("EdtDiagnostic must declare the 'origin' record component", //$NON-NLS-1$
                src.contains("String origin)")); //$NON-NLS-1$
        assertTrue("EdtDiagnostic must classify via the core DiagnosticOrigin utility", //$NON-NLS-1$
                src.contains("import com.codepilot1c.core.diagnostics.DiagnosticOrigin")); //$NON-NLS-1$
        assertTrue("every factory must fill origin — expected DiagnosticOrigin.classify calls", //$NON-NLS-1$
                countOccurrences(src, "DiagnosticOrigin.classify(") >= 3); //$NON-NLS-1$
    }

    @Test
    public void originIsRenderedOutsideTheDebugGate() throws Exception {
        String src = read(DIAGNOSTIC_PATH);
        int originRender = src.indexOf("origin: "); //$NON-NLS-1$
        int debugGate = src.indexOf("if (includeDebug) {"); //$NON-NLS-1$
        assertTrue("formatForLlm must render the origin label", originRender > 0); //$NON-NLS-1$
        assertTrue("formatForLlm must keep the debug-gated provenance block", debugGate > 0); //$NON-NLS-1$
        assertTrue("origin must be printed BEFORE (i.e. outside) the includeDebug gate — " //$NON-NLS-1$
                + "provenance of non-compiler/analyzer entries is not debug-only info", //$NON-NLS-1$
                originRender < debugGate);
    }

    // --- EdtDiagnosticsCollector ---------------------------------------------

    @Test
    public void collectorClassifiesEveryUnrestrictedMarkerScan() throws Exception {
        String src = read(COLLECTOR_PATH);
        assertTrue("collector must import the DiagnosticOrigin classifier", //$NON-NLS-1$
                src.contains("import com.codepilot1c.core.diagnostics.DiagnosticOrigin")); //$NON-NLS-1$

        // findMarkers(null, ...) stays deliberately type-unrestricted, but every
        // such scan MUST classify provenance nearby — that classification is the
        // only thing separating EDT diagnostics from foreign marker payloads.
        int scans = 0;
        int from = 0;
        while (true) {
            int at = src.indexOf("findMarkers(null", from); //$NON-NLS-1$
            if (at < 0) {
                break;
            }
            scans++;
            String window = src.substring(at, Math.min(src.length(), at + 2500));
            assertTrue("findMarkers(null, ...) at offset " + at //$NON-NLS-1$
                    + " must classify marker provenance (DiagnosticOrigin.classify) before emitting", //$NON-NLS-1$
                    window.contains("DiagnosticOrigin.classify(")); //$NON-NLS-1$
            assertTrue("findMarkers(null, ...) at offset " + at //$NON-NLS-1$
                    + " must honour the caller's origin filter", //$NON-NLS-1$
                    window.contains("DiagnosticOrigin.accepts(")); //$NON-NLS-1$
            from = at + 1;
        }
        assertTrue("expected the file- and project-scope marker scans to still exist", scans >= 2); //$NON-NLS-1$
    }

    @Test
    public void queryCarriesOriginFilterWithReviewExcludedByDefault() throws Exception {
        String src = read(COLLECTOR_PATH);
        assertTrue("DiagnosticsQuery must expose an originFilter component", //$NON-NLS-1$
                src.contains("String originFilter)")); //$NON-NLS-1$
        assertTrue("query defaults must come from DiagnosticOrigin.defaultFilter()", //$NON-NLS-1$
                src.contains("DiagnosticOrigin.defaultFilter()")); //$NON-NLS-1$
        assertTrue("every scope must apply the origin filter centrally", //$NON-NLS-1$
                countOccurrences(src, "applyOriginFilter(diagnostics") >= 4); //$NON-NLS-1$
    }

    @Test
    public void reviewEntriesAreExcludedFromSeverityCounters() throws Exception {
        String src = read(COLLECTOR_PATH);
        assertTrue("severity counting must go through countBySeverity", //$NON-NLS-1$
                src.contains("countBySeverity(")); //$NON-NLS-1$
        assertTrue("countBySeverity must skip review overlays", //$NON-NLS-1$
                src.contains("isReviewAnnotation()")); //$NON-NLS-1$
        assertFalse("the old inline severity counting must be gone — it counted review overlays " //$NON-NLS-1$
                + "as info and inflated the 'is this file clean' signal", //$NON-NLS-1$
                src.contains("d.severity() == Severity.ERROR).count()")); //$NON-NLS-1$
    }

    @Test
    public void collectorSourceHasNoNulByte() throws Exception {
        // A stray NUL inside a string literal made ripgrep treat the whole file
        // as binary (silently skipping it in searches). Keep it out.
        String src = read(COLLECTOR_PATH);
        assertFalse("EdtDiagnosticsCollector.java must not contain a NUL byte", //$NON-NLS-1$
                src.indexOf('\0') >= 0);
    }

    // --- helpers --------------------------------------------------------------

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int at = haystack.indexOf(needle, from);
            if (at < 0) {
                return count;
            }
            count++;
            from = at + needle.length();
        }
    }

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
