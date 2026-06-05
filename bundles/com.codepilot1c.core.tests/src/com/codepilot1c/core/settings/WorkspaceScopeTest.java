package com.codepilot1c.core.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Contract tests for {@link WorkspaceScope}.
 *
 * <p>The actual workspace id depends on the running platform, so these tests
 * only assert the invariants that hold in both modes: the unscoped fallback,
 * determinism, and that distinct base keys never collide.</p>
 */
public class WorkspaceScopeTest {

    private static final String BASE = "mcp.host.http.bearerToken"; //$NON-NLS-1$

    @Test
    public void instanceIdIsDeterministic() {
        assertEquals(WorkspaceScope.instanceId(), WorkspaceScope.instanceId());
    }

    @Test
    public void scopedKeyIsDeterministic() {
        assertEquals(WorkspaceScope.scopedKey(BASE), WorkspaceScope.scopedKey(BASE));
    }

    @Test
    public void scopedKeyEitherEqualsBaseOrSuffixesIt() {
        String scoped = WorkspaceScope.scopedKey(BASE);
        assertNotNull(scoped);
        String id = WorkspaceScope.instanceId();
        if (id.isEmpty()) {
            // No platform instance location (headless): keep the legacy unscoped key.
            assertEquals(BASE, scoped);
        } else {
            assertEquals(BASE + "." + id, scoped); //$NON-NLS-1$
            assertTrue(scoped.startsWith(BASE + ".")); //$NON-NLS-1$
        }
    }

    @Test
    public void distinctBaseKeysNeverCollide() {
        assertNotEquals(
            WorkspaceScope.scopedKey("mcp.host.http.bearerToken"), //$NON-NLS-1$
            WorkspaceScope.scopedKey("mcp.host.oauth.state")); //$NON-NLS-1$
    }
}
