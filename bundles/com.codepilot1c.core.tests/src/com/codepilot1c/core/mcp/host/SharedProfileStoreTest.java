/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.mcp.host;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for {@link SharedProfileStore} and the {@link SharedProfile} ↔
 * {@link ProfileEndpoint} mapping. Uses a temporary file, no preferences runtime.
 */
public class SharedProfileStoreTest {

    private Path dir;
    private Path file;

    @Before
    public void setUp() throws IOException {
        dir = Files.createTempDirectory("codepilot-shared-profiles-test"); //$NON-NLS-1$
        file = dir.resolve("mcp-profiles.json"); //$NON-NLS-1$
    }

    @After
    public void tearDown() throws IOException {
        if (file != null) {
            Files.deleteIfExists(file);
        }
        if (dir != null) {
            Files.deleteIfExists(dir);
        }
    }

    @Test
    public void loadOnMissingFileReturnsNull() {
        assertNull(new SharedProfileStore(file).load());
    }

    @Test
    public void saveThenLoadRoundTripsAllFields() {
        ProfileEndpoint qa = new ProfileEndpoint("qa", 8788, "tok-qa", false); //$NON-NLS-1$ //$NON-NLS-2$
        qa.setEnableGroups("diagnostics,qa,meta"); //$NON-NLS-1$
        qa.setDisableTools("render_template"); //$NON-NLS-1$
        ProfileEndpoint full = ProfileEndpoint.fullDefault(8763, "tok-full", "*"); //$NON-NLS-1$ //$NON-NLS-2$

        SharedProfileStore store = new SharedProfileStore(file);
        store.save(List.of(SharedProfile.fromEndpoint(full), SharedProfile.fromEndpoint(qa)));
        assertTrue(store.exists());

        List<SharedProfile> loaded = store.load();
        assertEquals(2, loaded.size());
        assertEquals("full", loaded.get(0).getName()); //$NON-NLS-1$
        SharedProfile qaLoaded = loaded.get(1);
        assertEquals("qa", qaLoaded.getName()); //$NON-NLS-1$
        assertEquals("tok-qa", qaLoaded.getBearerToken()); //$NON-NLS-1$
        assertFalse(qaLoaded.isEnabled());
        assertEquals("diagnostics,qa,meta", qaLoaded.getEnableGroups()); //$NON-NLS-1$
        assertEquals("render_template", qaLoaded.getDisableTools()); //$NON-NLS-1$
    }

    @Test
    public void sharedProfileDropsPortAndReattachesOnToEndpoint() {
        ProfileEndpoint qa = new ProfileEndpoint("qa", 8788, "tok", false); //$NON-NLS-1$ //$NON-NLS-2$
        qa.setEnableGroups("qa,meta"); //$NON-NLS-1$
        SharedProfile shared = SharedProfile.fromEndpoint(qa);
        // The shared half carries no port; it is supplied per-instance on rehydration.
        ProfileEndpoint rebuilt = shared.toEndpoint(9001);
        assertEquals(9001, rebuilt.getPort());
        assertEquals("qa", rebuilt.getName()); //$NON-NLS-1$
        assertEquals("qa,meta", rebuilt.getEnableGroups()); //$NON-NLS-1$
        assertEquals("tok", rebuilt.getBearerToken()); //$NON-NLS-1$
    }

    @Test
    public void emptyListSavesAndLoadsAsEmptyNotNull() {
        SharedProfileStore store = new SharedProfileStore(file);
        store.save(List.of());
        assertTrue(store.exists());
        List<SharedProfile> loaded = store.load();
        assertEquals(0, loaded.size());
    }
}
