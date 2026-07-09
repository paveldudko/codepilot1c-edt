package com.codepilot1c.core.tools.workspace;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import org.junit.Test;

import com.codepilot1c.core.tools.ToolResult;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Hermetic (plain-JUnit, no live EDT) tests for the {@code get_workspace_state} tool: metadata,
 * parameter-schema shape, and that a fresh instance renders a JSON snapshot without throwing.
 *
 * <p>Outside OSGi the backing {@link com.codepilot1c.core.state.EdtWorkspaceStateService} degrades
 * to a partial snapshot (it never throws), so the always-present {@code beacon_version} field is a
 * stable anchor to assert against.</p>
 */
public class GetWorkspaceStateToolTest {

    @Test
    public void metadataIsReadOnlyDiagnostic() {
        GetWorkspaceStateTool tool = new GetWorkspaceStateTool();
        assertEquals("get_workspace_state", tool.getName()); //$NON-NLS-1$
        assertFalse("tool must not be mutating", tool.isMutating()); //$NON-NLS-1$
        assertFalse("tool must not be destructive", tool.isDestructive()); //$NON-NLS-1$
        assertFalse("tool must not require confirmation", tool.requiresConfirmation()); //$NON-NLS-1$
    }

    @Test
    public void parameterSchemaDeclaresIncludeBoundInfobases() {
        GetWorkspaceStateTool tool = new GetWorkspaceStateTool();
        JsonObject schema = JsonParser.parseString(tool.getParameterSchema()).getAsJsonObject();
        assertEquals("object", schema.get("type").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject properties = schema.getAsJsonObject("properties"); //$NON-NLS-1$
        assertNotNull("schema must declare properties", properties); //$NON-NLS-1$
        assertTrue("schema must declare include_bound_infobases", //$NON-NLS-1$
                properties.has("include_bound_infobases")); //$NON-NLS-1$
        assertEquals("boolean", //$NON-NLS-1$
                properties.getAsJsonObject("include_bound_infobases").get("type").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void executeDefaultReturnsJsonSnapshotWithBeaconVersion() throws Exception {
        GetWorkspaceStateTool tool = new GetWorkspaceStateTool();
        ToolResult result = tool.execute(Map.of()).join();
        assertNotNull("result must not be null", result); //$NON-NLS-1$
        assertTrue("snapshot must succeed even without a live EDT: " + result.getErrorMessage(), //$NON-NLS-1$
                result.isSuccess());
        assertEquals(ToolResult.ToolResultType.CODE, result.getType());
        JsonObject json = JsonParser.parseString(result.getContent()).getAsJsonObject();
        assertTrue("snapshot must carry beacon_version", json.has("beacon_version")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void executeWithIncludeBoundFalseAlsoSucceeds() throws Exception {
        GetWorkspaceStateTool tool = new GetWorkspaceStateTool();
        ToolResult result = tool.execute(Map.of("include_bound_infobases", Boolean.FALSE)).join(); //$NON-NLS-1$
        assertNotNull(result);
        assertTrue("identity-only snapshot must succeed", result.isSuccess()); //$NON-NLS-1$
        JsonObject json = JsonParser.parseString(result.getContent()).getAsJsonObject();
        assertTrue("snapshot must carry beacon_version", json.has("beacon_version")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
