package com.codepilot1c.core.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.Test;

import com.google.gson.JsonObject;

/**
 * Behavioural tests for the generic unknown-parameter advisory {@code AbstractTool.execute} appends
 * (L1) — the layer that covers the read-only tools.
 *
 * <p>Two live instances of the class it closes: {@code scan_metadata_index}'s filter is {@code scope},
 * but a call with {@code kinds=…} returned everything unfiltered with no warning; and
 * {@code get_diagnostics}' project parameter is {@code project_name}, but a call with {@code project=…}
 * silently fell back to the default project. Advisory ONLY — the assertions below pin that a call is
 * never failed, never re-routed and never loses its structured data, so a tool whose schema
 * under-declares a pass-through key cannot regress.</p>
 */
public class AbstractToolUnknownParameterAdvisoryTest {

    @Test
    public void anUnknownParameterIsNamedInTheResultWithItsLikelyIntent() {
        ToolResult result = new ScopeFilterTool().execute(Map.of(
                "projectName", "Sandbox", //$NON-NLS-1$ //$NON-NLS-2$
                "scopes", "catalogs")).join(); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("the call itself must still succeed", result.isSuccess()); //$NON-NLS-1$
        assertTrue(result.getContent(), result.getContent().startsWith("{\"ok\":true}")); //$NON-NLS-1$
        assertTrue(result.getContent(), result.getContent().contains("ignored an unknown parameter")); //$NON-NLS-1$
        assertTrue(result.getContent(), result.getContent().contains("'scopes'")); //$NON-NLS-1$
        assertTrue(result.getContent(), result.getContent().contains("did you mean 'scope'?")); //$NON-NLS-1$
    }

    @Test
    public void anAcceptedParameterSetLeavesTheResultByteIdentical() {
        // The regression guard for every existing caller: no advisory, no trailing whitespace, nothing.
        ToolResult result = new ScopeFilterTool().execute(Map.of(
                "projectName", "Sandbox", //$NON-NLS-1$ //$NON-NLS-2$
                "scope", "catalogs")).join(); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("{\"ok\":true}", result.getContent()); //$NON-NLS-1$
    }

    @Test
    public void anEmptyParameterMapLeavesTheResultAlone() {
        ToolResult result = new ScopeFilterTool().execute(Map.of()).join();
        assertEquals("{\"ok\":true}", result.getContent()); //$NON-NLS-1$
    }

    @Test
    public void structuredDataSurvivesTheAdvisory() {
        ToolResult result = new StructuredTool().execute(Map.of("nonsense", "1")).join(); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(result.isSuccess());
        assertTrue(result.getContent().contains("ignored an unknown parameter")); //$NON-NLS-1$
        assertEquals(ToolResult.ToolResultType.SEARCH_RESULTS, result.getType());
        assertTrue("the structured half must not be dropped", result.hasStructuredData()); //$NON-NLS-1$
        assertEquals("Sandbox", result.getStructuredString("project")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aFailingCallKeepsFailingAndGetsTheAdvisoryToo() {
        ToolResult result = new FailingTool().execute(Map.of("nonsense", "1")).join(); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage(), result.getErrorMessage().startsWith("boom")); //$NON-NLS-1$
        assertTrue(result.getErrorMessage(), result.getErrorMessage().contains("'nonsense'")); //$NON-NLS-1$
    }

    @Test
    public void aToolWithoutAnEnumeratingSchemaIsNeverAdvisedAbout() {
        // additionalProperties:true is how the dispatchers declare that they route keys they do not
        // list (edt_diagnostics, qa_inspect, qa_generate). The guard must stay silent there.
        ToolResult result = new OpenSchemaTool().execute(Map.of("anything", "goes")).join(); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("{\"ok\":true}", result.getContent()); //$NON-NLS-1$
    }

    @Test
    public void aBlankSchemaIsNeverAdvisedAbout() {
        ToolResult result = new NoSchemaTool().execute(Map.of("anything", "goes")).join(); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("{\"ok\":true}", result.getContent()); //$NON-NLS-1$
    }

    // --- fixtures -----------------------------------------------------------

    @ToolMeta(name = "scope_filter_probe", category = "test")
    private static final class ScopeFilterTool extends AbstractTool {
        @Override
        public String getDescription() {
            return "probe"; //$NON-NLS-1$
        }

        @Override
        public String getParameterSchema() {
            return """
                    {
                      "type": "object",
                      "properties": {
                        "projectName": {"type": "string", "description": "EDT project"},
                        "scope": {"type": "string", "description": "Scope filter"}
                      }
                    }
                    """;
        }

        @Override
        protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
            return CompletableFuture.completedFuture(ToolResult.success("{\"ok\":true}")); //$NON-NLS-1$
        }
    }

    @ToolMeta(name = "structured_probe", category = "test")
    private static final class StructuredTool extends AbstractTool {
        @Override
        public String getDescription() {
            return "probe"; //$NON-NLS-1$
        }

        @Override
        public String getParameterSchema() {
            return "{\"type\":\"object\",\"properties\":{\"project\":{\"type\":\"string\"}}}"; //$NON-NLS-1$
        }

        @Override
        protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
            JsonObject structured = new JsonObject();
            structured.addProperty("project", "Sandbox"); //$NON-NLS-1$ //$NON-NLS-2$
            return CompletableFuture.completedFuture(ToolResult.success(
                    "{\"ok\":true}", ToolResult.ToolResultType.SEARCH_RESULTS, structured)); //$NON-NLS-1$
        }
    }

    @ToolMeta(name = "failing_probe", category = "test")
    private static final class FailingTool extends AbstractTool {
        @Override
        public String getDescription() {
            return "probe"; //$NON-NLS-1$
        }

        @Override
        public String getParameterSchema() {
            return "{\"type\":\"object\",\"properties\":{\"project\":{\"type\":\"string\"}}}"; //$NON-NLS-1$
        }

        @Override
        protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
            return CompletableFuture.completedFuture(ToolResult.failure("boom")); //$NON-NLS-1$
        }
    }

    @ToolMeta(name = "open_schema_probe", category = "test")
    private static final class OpenSchemaTool extends AbstractTool {
        @Override
        public String getDescription() {
            return "probe"; //$NON-NLS-1$
        }

        @Override
        public String getParameterSchema() {
            return "{\"type\":\"object\",\"properties\":{\"command\":{\"type\":\"string\"}}," //$NON-NLS-1$
                    + "\"additionalProperties\":true}"; //$NON-NLS-1$
        }

        @Override
        protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
            return CompletableFuture.completedFuture(ToolResult.success("{\"ok\":true}")); //$NON-NLS-1$
        }
    }

    @ToolMeta(name = "no_schema_probe", category = "test")
    private static final class NoSchemaTool extends AbstractTool {
        @Override
        public String getDescription() {
            return "probe"; //$NON-NLS-1$
        }

        @Override
        public String getParameterSchema() {
            return ""; //$NON-NLS-1$
        }

        @Override
        protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
            return CompletableFuture.completedFuture(ToolResult.success("{\"ok\":true}")); //$NON-NLS-1$
        }
    }
}
