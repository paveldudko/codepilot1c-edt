package com.codepilot1c.core.tools.workspace;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import org.junit.Test;

import com.codepilot1c.core.edt.runtime.EdtRuntimeService;
import com.codepilot1c.core.tools.ToolResult;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Hermetic (plain-JUnit, no live EDT) tests for {@code get_infobase_sync_state}: metadata,
 * schema shape, and the equality-state -&gt; work_ready mapping over a stubbed
 * {@link EdtRuntimeService#readInfobaseEqualityState(String)}.
 */
public class GetInfobaseSyncStateToolTest {

    /** {@link EdtRuntimeService} stub returning a fixed equality-state (no live EDT touched). */
    private static EdtRuntimeService serviceReturning(String state) {
        return new EdtRuntimeService() {
            @Override
            public String readInfobaseEqualityState(String projectName) {
                return state;
            }
        };
    }

    private static JsonObject run(EdtRuntimeService service, Map<String, Object> params) {
        ToolResult result = new GetInfobaseSyncStateTool(service).execute(params).join();
        assertNotNull("result must not be null", result); //$NON-NLS-1$
        assertTrue("expected success: " + result.getErrorMessage(), result.isSuccess()); //$NON-NLS-1$
        return JsonParser.parseString(result.getContent()).getAsJsonObject();
    }

    @Test
    public void metadataIsReadOnlyDiagnostic() {
        GetInfobaseSyncStateTool tool = new GetInfobaseSyncStateTool();
        assertEquals("get_infobase_sync_state", tool.getName()); //$NON-NLS-1$
        assertFalse("tool must not be mutating", tool.isMutating()); //$NON-NLS-1$
        assertFalse("tool must not be destructive", tool.isDestructive()); //$NON-NLS-1$
        assertFalse("tool must not require confirmation", tool.requiresConfirmation()); //$NON-NLS-1$
    }

    @Test
    public void schemaRequiresProjectName() {
        GetInfobaseSyncStateTool tool = new GetInfobaseSyncStateTool();
        JsonObject schema = JsonParser.parseString(tool.getParameterSchema()).getAsJsonObject();
        assertEquals("object", schema.get("type").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("project_name must be a declared property", //$NON-NLS-1$
                schema.getAsJsonObject("properties").has("project_name")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("project_name", //$NON-NLS-1$
                schema.getAsJsonArray("required").get(0).getAsString()); //$NON-NLS-1$
    }

    @Test
    public void equalMapsToWorkReady() {
        JsonObject json = run(serviceReturning("EQUAL"), Map.of("project_name", "Demo")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("Demo", json.get("project").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("EQUAL", json.get("equality_state").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("EQUAL must be determinable", json.get("determinable").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("EQUAL must be work_ready", json.get("work_ready").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("EQUAL must not need update", json.get("needs_update").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void notEqualMapsToNeedsUpdate() {
        JsonObject json = run(serviceReturning("NOT_EQUAL"), Map.of("project_name", "Demo")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue("NOT_EQUAL must be determinable", json.get("determinable").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("NOT_EQUAL must not be work_ready", json.get("work_ready").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("NOT_EQUAL must need update", json.get("needs_update").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void unknownStateIsNotWorkReadyAndCarriesHint() {
        JsonObject json = run(serviceReturning(null), Map.of("project_name", "Demo")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("equality_state must be JSON null when undeterminable", //$NON-NLS-1$
                json.get("equality_state").isJsonNull()); //$NON-NLS-1$
        assertFalse("unknown must NOT be determinable", json.get("determinable").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("unknown must NEVER read as work_ready", json.get("work_ready").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("unknown must carry an actionable hint", json.has("hint")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void missingProjectNameFails() {
        ToolResult result = new GetInfobaseSyncStateTool(serviceReturning("EQUAL")) //$NON-NLS-1$
                .execute(Map.of()).join();
        assertNotNull(result);
        assertFalse("a missing project_name must fail, not silently pass", result.isSuccess()); //$NON-NLS-1$
    }
}
