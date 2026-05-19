/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.diagnostics;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

/**
 * Source-grep contract test pinning the fix for the silent-zero
 * {@code get_diagnostics scope=file} regression on multi-segment project
 * names. See {@code 2026-05-19-diagnostics-space-in-project-name.md}.
 *
 * <p>The pure-Java {@link RelativePathCandidates} behaviour is covered
 * by {@code RelativePathCandidatesTest}. This test asserts only that
 * {@code EdtDiagnosticsCollector} (in the UI bundle) actually delegates
 * to that utility — the wiring step that converts a unit-tested rule
 * into a live runtime behaviour.</p>
 */
public class EdtDiagnosticsCollectorWiringContractTest {

    private static final String COLLECTOR_PATH =
            "bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/diagnostics/EdtDiagnosticsCollector.java"; //$NON-NLS-1$

    @Test
    public void collectorImportsRelativePathCandidates() throws Exception {
        String src = read(COLLECTOR_PATH);
        assertTrue("collector must import RelativePathCandidates utility", //$NON-NLS-1$
                src.contains("import com.codepilot1c.core.diagnostics.RelativePathCandidates")); //$NON-NLS-1$
    }

    @Test
    public void buildRelativePathCandidatesDelegatesToUtility() throws Exception {
        String src = read(COLLECTOR_PATH);
        assertTrue("buildRelativePathCandidates must call RelativePathCandidates.build", //$NON-NLS-1$
                src.contains("RelativePathCandidates.build(")); //$NON-NLS-1$
    }

    @Test
    public void collectorPassesKnownWorkspaceProjectNamesToUtility() throws Exception {
        String src = read(COLLECTOR_PATH);
        // The utility only strips the first-segment prefix when it's a
        // known workspace project — the collector must wire the actual
        // project list, not pass an empty set.
        assertTrue("collector must enumerate workspace projects for the strip rule", //$NON-NLS-1$
                src.contains("knownWorkspaceProjectNames(")); //$NON-NLS-1$
        assertTrue("knownWorkspaceProjectNames must source from ResourcesPlugin", //$NON-NLS-1$
                src.contains("ResourcesPlugin.getWorkspace().getRoot()") //$NON-NLS-1$
                        && src.contains(".getProjects()")); //$NON-NLS-1$
    }

    // --- helpers --------------------------------------------------------------

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
