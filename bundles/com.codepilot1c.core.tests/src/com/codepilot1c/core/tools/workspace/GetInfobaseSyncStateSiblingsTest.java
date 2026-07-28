package com.codepilot1c.core.tools.workspace;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.codepilot1c.core.edt.runtime.EdtRuntimeService;
import com.codepilot1c.core.edt.runtime.InfobaseSiblingResolver;
import com.codepilot1c.core.edt.runtime.InfobaseSiblingResolver.Sibling;
import com.codepilot1c.core.tools.ToolResult;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Hermetic tests for the shared-infobase fan-out in {@code get_infobase_sync_state}: EDT's equality
 * state is per (project, infobase) PAIR, so a green answer for one project is NOT a statement about the
 * infobase when other projects (a configuration and its extensions) are bound to it. Both the runtime
 * service and the sibling resolver are stubbed — no live EDT.
 */
public class GetInfobaseSyncStateSiblingsTest {

    private static EdtRuntimeService serviceReturning(String state) {
        return new EdtRuntimeService() {
            @Override
            public String readInfobaseEqualityState(String projectName) {
                return state;
            }
        };
    }

    private static InfobaseSiblingResolver resolverReturning(List<Sibling> siblings) {
        return new InfobaseSiblingResolver() {
            @Override
            public List<Sibling> siblingsOf(String projectName) {
                return siblings;
            }
        };
    }

    private static JsonObject run(String state, List<Sibling> siblings, Map<String, Object> params) {
        ToolResult result = new GetInfobaseSyncStateTool(serviceReturning(state), resolverReturning(siblings))
                .execute(params).join();
        assertTrue("expected success: " + result.getErrorMessage(), result.isSuccess()); //$NON-NLS-1$
        return JsonParser.parseString(result.getContent()).getAsJsonObject();
    }

    private static Sibling stale(String project) {
        return new Sibling(project, InfobaseSiblingResolver.RELATION_EXTENSION_OF, "NOT_EQUAL", true); //$NON-NLS-1$
    }

    private static Sibling green(String project) {
        return new Sibling(project, InfobaseSiblingResolver.RELATION_EXTENSION_OF, "EQUAL", false); //$NON-NLS-1$
    }

    @Test
    public void schemaDeclaresIncludeSiblingsOptOut() {
        JsonObject schema = JsonParser.parseString(new GetInfobaseSyncStateTool().getParameterSchema())
                .getAsJsonObject();
        JsonObject props = schema.getAsJsonObject("properties"); //$NON-NLS-1$
        assertTrue("include_siblings must be declared or MCP clients strip it", //$NON-NLS-1$
                props.has("include_siblings")); //$NON-NLS-1$
        assertEquals("boolean", //$NON-NLS-1$
                props.getAsJsonObject("include_siblings").get("type").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void equalProjectWithStaleExtension_keepsWorkReadyButFlagsTheInfobase() {
        JsonObject json = run("EQUAL", List.of(stale("AM_Ext")), //$NON-NLS-1$ //$NON-NLS-2$
                Map.of("project_name", "AM")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("work_ready keeps its per-project meaning (backward compatibility)", //$NON-NLS-1$
                json.get("work_ready").getAsBoolean()); //$NON-NLS-1$
        assertTrue("shared infobase must be flagged", json.get("shared_infobase").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("the aggregate verdict must be false while a sibling diverges", //$NON-NLS-1$
                json.get("all_projects_work_ready").getAsBoolean()); //$NON-NLS-1$
        assertEquals(1, json.getAsJsonArray("sibling_projects_stale").size()); //$NON-NLS-1$
        assertEquals("AM_Ext", //$NON-NLS-1$
                json.getAsJsonArray("sibling_projects_stale").get(0).getAsString()); //$NON-NLS-1$
        assertTrue("a stale sibling must produce an explicit warning", //$NON-NLS-1$
                json.has("sibling_warning")); //$NON-NLS-1$
        assertTrue("the warning must name the project to update separately", //$NON-NLS-1$
                json.get("sibling_warning").getAsString().contains("AM_Ext")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the hint must carry the warning too (callers read hint)", //$NON-NLS-1$
                json.get("hint").getAsString().contains("AM_Ext")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void siblingEntriesCarryRelationStateAndWorkReady() {
        JsonObject json = run("EQUAL", List.of(green("AM_Ext")), //$NON-NLS-1$ //$NON-NLS-2$
                Map.of("project_name", "AM")); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject entry = json.getAsJsonArray("siblings").get(0).getAsJsonObject(); //$NON-NLS-1$
        assertEquals("AM_Ext", entry.get("project").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(InfobaseSiblingResolver.RELATION_EXTENSION_OF, entry.get("relation").getAsString()); //$NON-NLS-1$
        assertEquals("EQUAL", entry.get("equality_state").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(entry.get("work_ready").getAsBoolean()); //$NON-NLS-1$
        assertTrue("all EQUAL means the whole infobase is ready", //$NON-NLS-1$
                json.get("all_projects_work_ready").getAsBoolean()); //$NON-NLS-1$
        assertFalse("nothing stale, so no warning", json.has("sibling_warning")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void unsharedInfobaseReportsAnEmptyFanOut() {
        JsonObject json = run("EQUAL", List.of(), Map.of("project_name", "AM")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse("a single-project infobase is not shared", //$NON-NLS-1$
                json.get("shared_infobase").getAsBoolean()); //$NON-NLS-1$
        assertEquals(0, json.getAsJsonArray("siblings").size()); //$NON-NLS-1$
        assertEquals(0, json.getAsJsonArray("sibling_projects_stale").size()); //$NON-NLS-1$
        assertTrue(json.get("all_projects_work_ready").getAsBoolean()); //$NON-NLS-1$
    }

    @Test
    public void includeSiblingsFalseSkipsTheFanOut() {
        JsonObject json = run("EQUAL", List.of(stale("AM_Ext")), //$NON-NLS-1$ //$NON-NLS-2$
                Map.of("project_name", "AM", "include_siblings", Boolean.FALSE)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse(json.get("siblings_included").getAsBoolean()); //$NON-NLS-1$
        assertFalse("opting out must not emit the fan-out fields", json.has("siblings")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(json.has("sibling_projects_stale")); //$NON-NLS-1$
        assertTrue("the original per-project answer is unchanged", //$NON-NLS-1$
                json.get("work_ready").getAsBoolean()); //$NON-NLS-1$
    }

    @Test
    public void unknownSiblingStateIsNotReportedAsStale() {
        JsonObject json = run("EQUAL", //$NON-NLS-1$
                List.of(new Sibling("AM_Ext", InfobaseSiblingResolver.RELATION_EXTENSION_OF, //$NON-NLS-1$
                        InfobaseSiblingResolver.STATE_UNKNOWN, false)),
                Map.of("project_name", "AM")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(0, json.getAsJsonArray("sibling_projects_stale").size()); //$NON-NLS-1$
        assertFalse("unknown is not stale…", json.has("sibling_warning")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("…but it is not 'ready' either", //$NON-NLS-1$
                json.get("all_projects_work_ready").getAsBoolean()); //$NON-NLS-1$
    }

    /** The deceptive-EQUAL cross-note (issues/deceptive-equal-after-dynamic-only-update). */
    @Test
    public void equalCarriesTheDeferredRestructureCaveat() {
        JsonObject json = run("EQUAL", List.of(), Map.of("project_name", "AM")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String hint = json.get("hint").getAsString(); //$NON-NLS-1$
        assertTrue("EQUAL must warn about a deferred (dynamic_only) restructure", //$NON-NLS-1$
                hint.contains("dynamic_only")); //$NON-NLS-1$
    }
}
