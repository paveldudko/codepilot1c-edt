/*******************************************************************************
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 * Copyright (C) 2026 codepilot1c-edt contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License v3.0 as published by the
 * Free Software Foundation.
 ******************************************************************************/
package com.codepilot1c.core.state;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.codepilot1c.core.edt.runtime.lease.InfobaseLeaseStore;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Exercises the parts of the state beacon that need neither a running EDT nor OSGi: the atomic
 * file write, the FS-safe file naming for awkward stack ids, delete-on-stop, and the always-present
 * core of a partial snapshot. The EDT-dependent fields (index / bound_infobases / workspace) are
 * covered only by live smoke, not here.
 */
public class EdtStateBeaconTest {

    /** A stack id with a drive-letter colon and path separators — must fold to one FS-safe file. */
    private static final String AWKWARD_STACK_ID = "C:\\stacks\\stack-1/workspace"; //$NON-NLS-1$

    private Path dir;

    @Before
    public void setUp() throws IOException {
        dir = Files.createTempDirectory("state-beacon-test"); //$NON-NLS-1$
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

    @Test
    public void writeAtomically_producesFsSafeFileWithValidJson() throws IOException {
        Path target = EdtStateBeacon.beaconFile(dir, AWKWARD_STACK_ID);
        assertEquals("the beacon must use the lease store's FS-safe naming", //$NON-NLS-1$
                InfobaseLeaseStore.fileNameFor(AWKWARD_STACK_ID), target.getFileName().toString());
        assertFalse("no path separators may survive the FS-safe fold", //$NON-NLS-1$
                target.getFileName().toString().contains("/")); //$NON-NLS-1$
        assertFalse(target.getFileName().toString().contains("\\")); //$NON-NLS-1$
        assertFalse(target.getFileName().toString().contains(":")); //$NON-NLS-1$

        JsonObject payload = new JsonObject();
        payload.addProperty("beacon_version", 1); //$NON-NLS-1$
        payload.addProperty("stack_id", AWKWARD_STACK_ID); //$NON-NLS-1$
        EdtStateBeacon.writeAtomically(target, payload.toString().getBytes(StandardCharsets.UTF_8));

        assertTrue(Files.exists(target));
        JsonObject read = JsonParser.parseString(Files.readString(target, StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(1, read.get("beacon_version").getAsInt()); //$NON-NLS-1$
        assertEquals(AWKWARD_STACK_ID, read.get("stack_id").getAsString()); //$NON-NLS-1$
    }

    @Test
    public void writeAtomically_secondWriteReplaces() throws IOException {
        Path target = EdtStateBeacon.beaconFile(dir, "stack-2"); //$NON-NLS-1$
        EdtStateBeacon.writeAtomically(target, "{\"n\":1}".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
        EdtStateBeacon.writeAtomically(target, "{\"n\":2}".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$

        JsonObject read = JsonParser.parseString(Files.readString(target, StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals("a second write must atomically replace the first, not append/duplicate", //$NON-NLS-1$
                2, read.get("n").getAsInt()); //$NON-NLS-1$
        try (Stream<Path> files = Files.list(dir)) {
            long jsonFiles = files.filter(p -> p.getFileName().toString().endsWith(".json") //$NON-NLS-1$
                    && !p.getFileName().toString().startsWith(".tmp-")).count(); //$NON-NLS-1$
            assertEquals("only one beacon file must remain (no leaked temp files)", 1, jsonFiles); //$NON-NLS-1$
        }
    }

    @Test
    public void tickThenStop_writesThenDeletesBeaconFile() throws IOException {
        JsonObject fixed = new JsonObject();
        fixed.addProperty("beacon_version", 1); //$NON-NLS-1$
        fixed.addProperty("stack_id", "stack-3"); //$NON-NLS-1$ //$NON-NLS-2$
        // A stub service so the tick does not touch EDT/OSGi.
        EdtWorkspaceStateService stub = new EdtWorkspaceStateService() {
            @Override
            public JsonObject buildSnapshot(boolean includeSlowFields) {
                return fixed;
            }
        };
        EdtStateBeacon beacon = EdtStateBeacon.createForTest(dir, "stack-3", stub); //$NON-NLS-1$
        Path target = EdtStateBeacon.beaconFile(dir, "stack-3"); //$NON-NLS-1$

        beacon.tick();
        assertTrue("a tick must write the beacon file", Files.exists(target)); //$NON-NLS-1$

        beacon.stop();
        assertFalse("stop() must delete the beacon file so no stale beacon is left behind", //$NON-NLS-1$
                Files.exists(target));

        // Idempotent: a second stop() must not throw.
        beacon.stop();
    }

    @Test
    public void baseSnapshot_partialPayloadCarriesCoreFields() {
        JsonObject base = EdtWorkspaceStateService.baseSnapshot("stack:with/sep"); //$NON-NLS-1$
        assertEquals("beacon_version must be the schema literal even in a partial (pre-index) beacon", //$NON-NLS-1$
                EdtWorkspaceStateService.BEACON_VERSION, base.get("beacon_version").getAsInt()); //$NON-NLS-1$
        assertTrue("a partial beacon must still identify its stack", base.has("stack_id")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("stack:with/sep", base.get("stack_id").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("a partial beacon must still carry a liveness timestamp", base.has("updated_at")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("updated_at must not be blank", base.get("updated_at").getAsString().isBlank()); //$NON-NLS-1$ //$NON-NLS-2$
        // The index probe is NOT part of the base — that is what makes the beacon "partial".
        assertFalse("baseSnapshot must not attempt the (EDT-dependent) index field", base.has("index")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void beaconFile_distinctStackIdsDoNotCollide() {
        String a = EdtStateBeacon.beaconFile(dir, "feature/x").getFileName().toString(); //$NON-NLS-1$
        String b = EdtStateBeacon.beaconFile(dir, "feature_x").getFileName().toString(); //$NON-NLS-1$
        assertNotEquals("distinct stack ids must map to distinct beacon files", a, b); //$NON-NLS-1$
    }
}
