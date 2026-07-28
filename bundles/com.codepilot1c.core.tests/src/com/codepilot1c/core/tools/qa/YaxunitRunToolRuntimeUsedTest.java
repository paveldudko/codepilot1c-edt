/*******************************************************************************
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 * Copyright (C) 2026 codepilot1c-edt contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License v3.0 as published by the
 * Free Software Foundation.
 ******************************************************************************/
package com.codepilot1c.core.tools.qa;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.codepilot1c.core.edt.runtime.EdtProjectResolver;
import com.codepilot1c.core.edt.runtime.EdtRuntimeService;
import com.codepilot1c.core.tools.ToolResult;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * {@code yaxunit_run} observability: the tool must say WHICH 1C platform it resolved, WHERE the version
 * came from, and — when nothing resolves — WHAT it tried and why each candidate lost.
 *
 * <p>Background: the thin-client resolution had two silent failure branches and the tool surfaced a single
 * generic sentence, so a resolve-fail cost the owner four blind attempts (feedback 2026-07-03). Sibling
 * tools ({@code launch_app}, {@code update_infobase}) already report {@code runtime_used}; this one
 * reported nothing at all, and it spelled the version parameter {@code version_mask} while every sibling
 * spells it {@code runtime_version}.</p>
 *
 * <p>{@code dry_run} is asserted on purpose: it must keep resolving the runtime, because it is the only
 * way to ask "which client would you launch?" without spending a real run.</p>
 */
public class YaxunitRunToolRuntimeUsedTest {

    private static final String RESOLVED_VERSION = "8.3.27.2074"; //$NON-NLS-1$
    private static final String RESOLVED_LOCATION = "file:/C:/Program%20Files/1cv8/8.3.27.2074/"; //$NON-NLS-1$
    private static final String GHOST_REJECTION =
            "8.5.1.1302 (file:/C:/beta/): 1cv8c.exe missing on disk at C:\\beta\\bin\\1cv8c.exe"; //$NON-NLS-1$

    @Test
    public void dryRunStillResolvesAndReportsTheRuntime() throws Exception {
        StubRuntimeService runtimeService = new StubRuntimeService();
        ToolResult result = run(runtimeService, new StubProjectResolver(null), Map.of("dry_run", Boolean.TRUE)); //$NON-NLS-1$

        assertTrue(result.getContent(), result.isSuccess());
        JsonObject json = json(result);
        assertEquals("dry_run", json.get("status").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject runtimeUsed = json.getAsJsonObject("runtime_used"); //$NON-NLS-1$
        assertEquals(RESOLVED_VERSION, runtimeUsed.get("version").getAsString()); //$NON-NLS-1$
        assertEquals(RESOLVED_LOCATION, runtimeUsed.get("location").getAsString()); //$NON-NLS-1$
        assertEquals(EdtRuntimeService.RUNTIME_SOURCE_AUTO, runtimeUsed.get("source").getAsString()); //$NON-NLS-1$
        assertTrue(runtimeUsed.get("binary").getAsString().endsWith("1cv8c.exe")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aRejectedCandidateIsReportedEvenOnASuccessfulResolution() throws Exception {
        // A ghost registry entry that lost its turn is exactly the signal the owner needs to clean up
        // Preferences > Runtimes; hiding it because the run eventually succeeded wastes the observation.
        ToolResult result = run(new StubRuntimeService(), new StubProjectResolver(null),
                Map.of("dry_run", Boolean.TRUE)); //$NON-NLS-1$

        JsonObject runtimeUsed = json(result).getAsJsonObject("runtime_used"); //$NON-NLS-1$
        assertEquals(2, runtimeUsed.getAsJsonArray("candidates_tried").size()); //$NON-NLS-1$
        assertEquals(GHOST_REJECTION, runtimeUsed.getAsJsonArray("reject_reasons").get(0).getAsString()); //$NON-NLS-1$
    }

    @Test
    public void runtimeVersionParamIsAcceptedAndReportedAsTheSource() throws Exception {
        StubRuntimeService runtimeService = new StubRuntimeService();
        ToolResult result = run(runtimeService, new StubProjectResolver("8.3.27"), //$NON-NLS-1$
                Map.of("runtime_version", "8.3.27.2074", "dry_run", Boolean.TRUE)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertTrue(result.getContent(), result.isSuccess());
        assertEquals("8.3.27.2074", runtimeService.capturedVersionMask); //$NON-NLS-1$
        JsonObject runtimeUsed = json(result).getAsJsonObject("runtime_used"); //$NON-NLS-1$
        assertEquals(EdtRuntimeService.RUNTIME_SOURCE_PARAM, runtimeUsed.get("source").getAsString()); //$NON-NLS-1$
        assertEquals("8.3.27.2074", runtimeUsed.get("requested").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void legacyVersionMaskParamKeepsWorkingAsASynonym() throws Exception {
        StubRuntimeService runtimeService = new StubRuntimeService();
        ToolResult result = run(runtimeService, new StubProjectResolver(null),
                Map.of("version_mask", "8.3.27", "dry_run", Boolean.TRUE)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertTrue(result.getContent(), result.isSuccess());
        assertEquals("8.3.27", runtimeService.capturedVersionMask); //$NON-NLS-1$
        assertEquals(EdtRuntimeService.RUNTIME_SOURCE_PARAM,
                json(result).getAsJsonObject("runtime_used").get("source").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void runtimeVersionWinsOverAConflictingVersionMaskAndSaysSo() throws Exception {
        StubRuntimeService runtimeService = new StubRuntimeService();
        Map<String, Object> params = new HashMap<>();
        params.put("runtime_version", "8.3.27.2074"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("version_mask", "8.5.1"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("dry_run", Boolean.TRUE); //$NON-NLS-1$

        ToolResult result = run(runtimeService, new StubProjectResolver(null), params);

        assertEquals("8.3.27.2074", runtimeService.capturedVersionMask); //$NON-NLS-1$
        assertEquals("8.5.1", //$NON-NLS-1$
                json(result).getAsJsonObject("runtime_used").get("ignored_version_mask").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void launchConfigPinIsInheritedWhenNoVersionParamIsPassed() throws Exception {
        // Priority: param > .launch pin > auto. Without the pin step this tool auto-resolved to the
        // newest installed platform even when the environment owner had pinned the project.
        StubRuntimeService runtimeService = new StubRuntimeService();
        ToolResult result = run(runtimeService, new StubProjectResolver("8.3.27"), //$NON-NLS-1$
                Map.of("dry_run", Boolean.TRUE)); //$NON-NLS-1$

        assertEquals("8.3.27", runtimeService.capturedVersionMask); //$NON-NLS-1$
        assertEquals(EdtRuntimeService.RUNTIME_SOURCE_LAUNCH_CONFIG,
                json(result).getAsJsonObject("runtime_used").get("source").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void withoutAnyPinTheSourceIsReportedAsAuto() throws Exception {
        StubRuntimeService runtimeService = new StubRuntimeService();
        ToolResult result = run(runtimeService, new StubProjectResolver(null), Map.of("dry_run", Boolean.TRUE)); //$NON-NLS-1$

        assertNull(runtimeService.capturedVersionMask);
        JsonObject runtimeUsed = json(result).getAsJsonObject("runtime_used"); //$NON-NLS-1$
        assertEquals(EdtRuntimeService.RUNTIME_SOURCE_AUTO, runtimeUsed.get("source").getAsString()); //$NON-NLS-1$
        assertEquals("", runtimeUsed.get("requested").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aBrokenPinReaderDegradesToAutoInsteadOfFailingTheRun() throws Exception {
        StubRuntimeService runtimeService = new StubRuntimeService();
        ToolResult result = run(runtimeService, new ThrowingProjectResolver(), Map.of("dry_run", Boolean.TRUE)); //$NON-NLS-1$

        assertTrue(result.getContent(), result.isSuccess());
        assertNull(runtimeService.capturedVersionMask);
        assertEquals(EdtRuntimeService.RUNTIME_SOURCE_AUTO,
                json(result).getAsJsonObject("runtime_used").get("source").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void anUnresolvedRuntimeReportsTheCandidatesAndTheReasons() throws Exception {
        StubRuntimeService runtimeService = new StubRuntimeService().failing();
        ToolResult result = run(runtimeService, new StubProjectResolver("8.3.27"), //$NON-NLS-1$
                Map.of("dry_run", Boolean.TRUE)); //$NON-NLS-1$

        assertFalse(result.getErrorMessage(), result.isSuccess());
        // ToolResult.failure carries the payload as the error message, not as content.
        JsonObject json = JsonParser.parseString(result.getErrorMessage()).getAsJsonObject();
        assertEquals("runtime_not_resolved", json.get("status").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("thin_client_not_resolved", json.get("reason").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        String message = json.get("message").getAsString(); //$NON-NLS-1$
        assertTrue(message, message.contains("YAxUnit requires a 1C client")); //$NON-NLS-1$
        assertTrue(message, message.contains("Tried 1 installation(s)")); //$NON-NLS-1$
        assertTrue(message, message.contains("1cv8c.exe missing on disk")); //$NON-NLS-1$
        JsonObject runtimeUsed = json.getAsJsonObject("runtime_used"); //$NON-NLS-1$
        assertEquals("", runtimeUsed.get("version").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(EdtRuntimeService.RUNTIME_SOURCE_LAUNCH_CONFIG, runtimeUsed.get("source").getAsString()); //$NON-NLS-1$
        assertEquals(1, runtimeUsed.getAsJsonArray("candidates_tried").size()); //$NON-NLS-1$
        assertEquals(GHOST_REJECTION, runtimeUsed.getAsJsonArray("reject_reasons").get(0).getAsString()); //$NON-NLS-1$
    }

    // ---- harness -----------------------------------------------------------------------------

    private static ToolResult run(StubRuntimeService runtimeService, EdtProjectResolver projectResolver,
            Map<String, Object> extraParams) throws Exception {
        File workspaceRoot = Files.createTempDirectory("yaxunit-runtime-used").toFile(); //$NON-NLS-1$
        YaxunitRunTool tool = new TestYaxunitRunTool(runtimeService, projectResolver, workspaceRoot);
        Map<String, Object> params = new HashMap<>(extraParams);
        params.put("project_name", "Demo"); //$NON-NLS-1$ //$NON-NLS-2$
        return tool.execute(params).join();
    }

    private static JsonObject json(ToolResult result) {
        return JsonParser.parseString(result.getContent()).getAsJsonObject();
    }

    private static final class TestYaxunitRunTool extends YaxunitRunTool {
        private final File workspaceRoot;

        TestYaxunitRunTool(EdtRuntimeService runtimeService, EdtProjectResolver projectResolver,
                File workspaceRoot) {
            super(runtimeService, ProcessBuilder::start, projectResolver);
            this.workspaceRoot = workspaceRoot;
        }

        @Override
        protected File getWorkspaceRoot() {
            return workspaceRoot;
        }
    }

    private static class StubRuntimeService extends EdtRuntimeService {
        volatile String capturedVersionMask;
        private boolean fail;

        StubRuntimeService failing() {
            this.fail = true;
            return this;
        }

        @Override
        public boolean isFileInfobase(String projectName) {
            return false; // keep the credential default out of this test's way
        }

        @Override
        public UnitTestLaunch buildUnitTestLaunch(String projectName, File configPath, File logFile,
                String versionMask, AccessSettings explicitAccessSettings) {
            capturedVersionMask = versionMask;
            if (fail) {
                throw new ThinClientNotResolvedException("YAxUnit requires a 1C client", //$NON-NLS-1$
                        new ThinClientResolution(null, null, null, RUNTIME_SOURCE_AUTO,
                                List.of("8.5.1.1302 (file:/C:/beta/)"), List.of(GHOST_REJECTION))); //$NON-NLS-1$
            }
            File binary = new File(new File(System.getProperty("java.io.tmpdir")), "1cv8c.exe"); //$NON-NLS-1$ //$NON-NLS-2$
            ThinClientResolution runtime = new ThinClientResolution(binary, RESOLVED_VERSION, RESOLVED_LOCATION,
                    RUNTIME_SOURCE_AUTO,
                    List.of("8.5.1.1302 (file:/C:/beta/)", RESOLVED_VERSION + " (" + RESOLVED_LOCATION + ")"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    List.of(GHOST_REJECTION));
            return new UnitTestLaunch(new ProcessBuilder(binary.getAbsolutePath(), "/RunUnitTests"), runtime); //$NON-NLS-1$
        }
    }

    private static class StubProjectResolver extends EdtProjectResolver {
        private final String pinnedVersion;

        StubProjectResolver(String pinnedVersion) {
            this.pinnedVersion = pinnedVersion;
        }

        @Override
        public String resolvePinnedRuntimeVersion(String projectName, File workspaceRoot) {
            return pinnedVersion;
        }
    }

    /** The pin reader is best-effort by contract: a failure must not take the run down with it. */
    private static final class ThrowingProjectResolver extends StubProjectResolver {
        ThrowingProjectResolver() {
            super(null);
        }

        @Override
        public String resolvePinnedRuntimeVersion(String projectName, File workspaceRoot) {
            throw new IllegalStateException("launch configuration store unavailable"); //$NON-NLS-1$
        }
    }
}
