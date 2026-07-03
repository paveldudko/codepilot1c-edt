/*******************************************************************************
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 * Copyright (C) 2026 codepilot1c-edt contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License v3.0 as published by the
 * Free Software Foundation.
 ******************************************************************************/
package com.codepilot1c.core.mcp.host;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Launch-time port override for the {@code CODEPILOT1C_PROFILE}-forced endpoint
 * ({@code CODEPILOT1C_PORT} / {@code -Dcodepilot.mcp.host.profile.port}): a valid value
 * yields a copy with the new port (the stored config object stays untouched — the override
 * must never leak into the persisted port map), anything invalid degrades to the stored
 * port instead of killing the stack.
 */
public class McpHostManagerPortOverrideTest {

    private static ProfileEndpoint endpoint() {
        ProfileEndpoint p = new ProfileEndpoint("AM", 8766, "secret-token", true); //$NON-NLS-1$ //$NON-NLS-2$
        p.setExposedToolsFilter("*"); //$NON-NLS-1$
        return p;
    }

    @Test
    public void validOverrideYieldsACopyWithTheNewPort() {
        ProfileEndpoint original = endpoint();
        ProfileEndpoint overridden = McpHostManager.applyPortOverride(original, " 8812 "); //$NON-NLS-1$

        assertNotSame("the override must produce a COPY — the original mirrors persisted config " //$NON-NLS-1$
                + "and must never carry the launch-time port", original, overridden); //$NON-NLS-1$
        assertEquals(8812, overridden.getPort());
        assertEquals("stored config object must stay untouched", 8766, original.getPort()); //$NON-NLS-1$
        assertEquals("AM", overridden.getName()); //$NON-NLS-1$
        assertEquals("secret-token", overridden.getBearerToken()); //$NON-NLS-1$
        assertEquals("*", overridden.getExposedToolsFilter()); //$NON-NLS-1$
        assertTrue(overridden.isEnabled());
    }

    @Test
    public void missingOrBlankOverrideKeepsTheEndpoint() {
        ProfileEndpoint original = endpoint();
        assertSame(original, McpHostManager.applyPortOverride(original, null));
        assertSame(original, McpHostManager.applyPortOverride(original, "  ")); //$NON-NLS-1$
    }

    @Test
    public void invalidValuesDegradeToTheStoredPort() {
        ProfileEndpoint original = endpoint();
        assertSame("a typo in the start script must degrade to the stored port, not a dead stack", //$NON-NLS-1$
                original, McpHostManager.applyPortOverride(original, "88o1")); //$NON-NLS-1$
        assertSame(original, McpHostManager.applyPortOverride(original, "0")); //$NON-NLS-1$
        assertSame(original, McpHostManager.applyPortOverride(original, "-5")); //$NON-NLS-1$
        assertSame(original, McpHostManager.applyPortOverride(original, "70000")); //$NON-NLS-1$
        assertEquals(8766, original.getPort());
    }

    @Test
    public void samePortIsANoOp() {
        ProfileEndpoint original = endpoint();
        assertSame(original, McpHostManager.applyPortOverride(original, "8766")); //$NON-NLS-1$
    }

    @Test
    public void nullEndpointPassesThrough() {
        assertNull(McpHostManager.applyPortOverride(null, "8812")); //$NON-NLS-1$
    }
}
