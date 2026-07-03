/*******************************************************************************
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 * Copyright (C) 2026 codepilot1c-edt contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License v3.0 as published by the
 * Free Software Foundation.
 ******************************************************************************/
package com.codepilot1c.core.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.codepilot1c.core.edt.runtime.lease.InfobaseLeaseGuard;
import com.codepilot1c.core.tools.workspace.ManageLeasesTool;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Payload contract of the manage_leases tool: status/take/release shapes, EDT_LEASE_HELD on a
 * conflict with the holder attached, the steal path reporting the previous holder, and the
 * lease_disabled hint when the pool is not configured.
 */
public class ManageLeasesToolTest {

    private Path dir;
    private ManageLeasesTool stack3Tool;
    private ManageLeasesTool stack4Tool;

    @Before
    public void setUp() throws IOException {
        dir = Files.createTempDirectory("lease-tool-test"); //$NON-NLS-1$
        stack3Tool = new ManageLeasesTool(new InfobaseLeaseGuard(dir, "stack-3", "C:\\ws3")); //$NON-NLS-1$ //$NON-NLS-2$
        stack4Tool = new ManageLeasesTool(new InfobaseLeaseGuard(dir, "stack-4", "C:\\ws4")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @After
    public void tearDown() throws IOException {
        if (dir != null && Files.exists(dir)) {
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // best-effort cleanup
                    }
                });
            }
        }
    }

    private static JsonObject payload(ToolResult result) {
        String raw = result.isSuccess() ? result.getContent() : result.getErrorMessage();
        return JsonParser.parseString(raw).getAsJsonObject();
    }

    @Test
    public void takeStatusReleaseRoundtrip() {
        ToolResult take = stack3Tool.execute(Map.of("action", "take", "branch", "task-C", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "ib_path", "C:\\db\\task-C")).join(); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(take.isSuccess());
        assertTrue(payload(take).get("taken").getAsBoolean()); //$NON-NLS-1$

        ToolResult status = stack4Tool.execute(Map.of("action", "status")).join(); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(status.isSuccess());
        JsonObject statusJson = payload(status);
        assertEquals("stack-4", statusJson.get("stack_id").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        JsonArray leases = statusJson.getAsJsonArray("leases"); //$NON-NLS-1$
        assertEquals(1, leases.size());
        JsonObject lease = leases.get(0).getAsJsonObject();
        assertEquals("task-C", lease.get("branch").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("stack-3", lease.getAsJsonObject("holder").get("stack_id").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse("the asking stack does not own this lease", lease.get("own").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$

        ToolResult release = stack3Tool.execute(Map.of("action", "release", "branch", "task-C")).join(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertTrue(release.isSuccess());
        assertTrue(payload(release).get("released").getAsBoolean()); //$NON-NLS-1$
    }

    @Test
    public void conflictingTakeFailsWithLeaseHeldAndHolder() {
        stack3Tool.execute(Map.of("action", "take", "branch", "task-C")).join(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        ToolResult conflict = stack4Tool.execute(Map.of("action", "take", "branch", "task-C")).join(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertFalse(conflict.isSuccess());
        JsonObject json = payload(conflict);
        assertEquals("EDT_LEASE_HELD", json.get("error_code").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("stack-3", //$NON-NLS-1$
                json.getAsJsonObject("holder").getAsJsonObject("holder").get("stack_id").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(json.has("hint")); //$NON-NLS-1$
    }

    @Test
    public void forcedTakeStealsAndReportsPreviousHolder() {
        stack3Tool.execute(Map.of("action", "take", "branch", "task-C")).join(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        ToolResult stolen = stack4Tool.execute(Map.of("action", "take", "branch", "task-C", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "force", Boolean.TRUE)).join(); //$NON-NLS-1$
        assertTrue(stolen.isSuccess());
        JsonObject json = payload(stolen);
        assertTrue(json.get("taken").getAsBoolean()); //$NON-NLS-1$
        assertTrue(json.get("forced").getAsBoolean()); //$NON-NLS-1$
        assertEquals("the steal must journal whom it stole from", "stack-3", //$NON-NLS-1$ //$NON-NLS-2$
                json.getAsJsonObject("previous_holder").getAsJsonObject("holder") //$NON-NLS-1$ //$NON-NLS-2$
                        .get("stack_id").getAsString()); //$NON-NLS-1$
    }

    @Test
    public void foreignReleaseNeedsForce() {
        stack3Tool.execute(Map.of("action", "take", "branch", "task-C")).join(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        ToolResult refused = stack4Tool.execute(Map.of("action", "release", "branch", "task-C")).join(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertFalse(refused.isSuccess());
        assertEquals("EDT_LEASE_HELD", payload(refused).get("error_code").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$

        ToolResult forced = stack4Tool.execute(Map.of("action", "release", "branch", "task-C", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "force", Boolean.TRUE)).join(); //$NON-NLS-1$
        assertTrue(forced.isSuccess());
        assertTrue(payload(forced).get("released").getAsBoolean()); //$NON-NLS-1$
    }

    @Test
    public void takeIsIdempotentForTheOwner() {
        stack3Tool.execute(Map.of("action", "take", "branch", "task-C")).join(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        ToolResult again = stack3Tool.execute(Map.of("action", "take", "branch", "task-C")).join(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertTrue(again.isSuccess());
        JsonObject json = payload(again);
        assertTrue(json.get("taken").getAsBoolean()); //$NON-NLS-1$
        assertTrue(json.get("already_own").getAsBoolean()); //$NON-NLS-1$
    }

    @Test
    public void unconfiguredPoolReportsDisabledWithHint() {
        ManageLeasesTool disabled = new ManageLeasesTool(new InfobaseLeaseGuard(null, "stack-x", null)); //$NON-NLS-1$
        ToolResult result = disabled.execute(Map.of("action", "status")).join(); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(result.isSuccess());
        JsonObject json = payload(result);
        assertEquals("lease_disabled", json.get("error").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the hint must name the env variable to set", //$NON-NLS-1$
                json.get("hint").getAsString().contains(InfobaseLeaseGuard.ENV_LEASE_DIR)); //$NON-NLS-1$
    }

    @Test
    public void missingBranchIsInvalidArgument() {
        ToolResult result = stack3Tool.execute(Map.of("action", "take")).join(); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(result.isSuccess());
        assertEquals("INVALID_ARGUMENT", payload(result).get("error_code").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void takeConflictsAcrossBranchesOnTheSameInfobase() {
        stack3Tool.execute(Map.of("action", "take", "branch", "BF-1-phase1", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "ib_path", "C:\\db\\BF-1")).join(); //$NON-NLS-1$ //$NON-NLS-2$
        // The lease is keyed by the infobase: another branch over the same folder must conflict.
        ToolResult conflict = stack4Tool.execute(Map.of("action", "take", "branch", "BF-1-phase2", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "ib_path", "C:\\db\\BF-1")).join(); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(conflict.isSuccess());
        assertEquals("EDT_LEASE_HELD", payload(conflict).get("error_code").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void releaseWithAmbiguousBranchDemandsIbPath() {
        stack3Tool.execute(Map.of("action", "take", "branch", "task-C", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "ib_path", "C:\\db\\A")).join(); //$NON-NLS-1$ //$NON-NLS-2$
        stack3Tool.execute(Map.of("action", "take", "branch", "task-C", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "ib_path", "C:\\db\\B")).join(); //$NON-NLS-1$ //$NON-NLS-2$

        ToolResult ambiguous = stack3Tool.execute(Map.of("action", "release", "branch", "task-C")).join(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertFalse("a branch attribute matching several leases must not release an arbitrary one", //$NON-NLS-1$
                ambiguous.isSuccess());
        JsonObject json = payload(ambiguous);
        assertEquals("INVALID_ARGUMENT", json.get("error_code").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(2, json.getAsJsonArray("candidates").size()); //$NON-NLS-1$

        ToolResult released = stack3Tool.execute(Map.of("action", "release", "branch", "task-C", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "ib_path", "C:\\db\\A")).join(); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(released.isSuccess());
        assertTrue(payload(released).get("released").getAsBoolean()); //$NON-NLS-1$
    }
}
