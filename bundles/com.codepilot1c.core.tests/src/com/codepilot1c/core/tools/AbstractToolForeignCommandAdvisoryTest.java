package com.codepilot1c.core.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.Test;

import com.codepilot1c.core.tools.dcs.DcsManageTool;

/**
 * Behavioural tests for the L1 half of the composite-command key guard: the advisory
 * {@code AbstractTool.execute} appends when a call passed a parameter belonging to a DIFFERENT
 * {@code command} of the same composite tool.
 *
 * <p>This is the half that covers the READ commands. {@code dcs_manage command=list_nodes} with
 * {@code dataset_name}, or {@code external_manage command=list_objects} with {@code object_fqn}, never
 * asks for a validation token, so the L2 refusal in {@code edt_validate_request} never sees it — the
 * key was simply ignored and the caller told nothing. Advisory ONLY: the assertions below pin that a
 * call is never failed, re-routed or stripped of its structured data because of it.</p>
 *
 * <p>The probe tools below run the composite tools' REAL schema text through {@code execute}, so the
 * advisory is produced from the shipped descriptions rather than a hand-made imitation of them. The
 * real tools themselves are not executed here because their {@code doExecute} reaches into the EDT
 * runtime; the schema is the part this layer depends on.</p>
 */
public class AbstractToolForeignCommandAdvisoryTest {

    @Test
    public void aMutatingCommandsKeyPassedToAReadCommandIsNamedWithItsOwner() {
        ToolResult result = new DcsSchemaProbe().execute(Map.of(
                "command", "list_nodes",
                "project", "Sandbox",
                "owner_fqn", "Report.Sales",
                "dataset_name", "DataSet1")).join();

        assertTrue("the call itself must still succeed", result.isSuccess());
        assertTrue(result.getContent(), result.getContent().startsWith("{\"ok\":true}"));
        assertTrue(result.getContent(),
                result.getContent().contains("ignored a parameter that belongs to another command"));
        assertTrue(result.getContent(),
                result.getContent().contains("'dataset_name' (belongs to 'upsert_dataset')"));
        assertTrue(result.getContent(), result.getContent().contains("not to 'list_nodes'"));
        assertTrue("the advisory must say what list_nodes does accept: " + result.getContent(),
                result.getContent().contains("Parameters accepted by 'list_nodes'")
                        && result.getContent().contains("node_kind"));
    }

    @Test
    public void aLegitimateReadCallLeavesTheResultByteIdentical() {
        // The regression guard for every existing caller of the composite tools.
        ToolResult result = new DcsSchemaProbe().execute(Map.of(
                "command", "list_nodes",
                "project", "Sandbox",
                "owner_fqn", "Report.Sales",
                "node_kind", "dataset",
                "name_contains", "Sales",
                "limit", Integer.valueOf(10),
                "offset", Integer.valueOf(0))).join();

        assertEquals("{\"ok\":true}", result.getContent());
    }

    @Test
    public void aLegitimateMutatingCallLeavesTheResultByteIdentical() {
        ToolResult result = new DcsSchemaProbe().execute(Map.of(
                "command", "upsert_field",
                "project", "Sandbox",
                "owner_fqn", "Report.Sales",
                "data_path", "Total",
                "expression", "Amount * 2",
                "presentation_expression", "Total",
                "validation_token", "t-1")).join();

        assertEquals("{\"ok\":true}", result.getContent());
    }

    @Test
    public void aCallWithoutACommandIsNotAdvisedAbout() {
        // The tool answers that itself ("Unknown command: null"); a second, guessed complaint would only
        // muddy it.
        ToolResult result = new DcsSchemaProbe().execute(Map.of(
                "project", "Sandbox",
                "dataset_name", "DataSet1")).join();

        assertEquals("{\"ok\":true}", result.getContent());
    }

    @Test
    public void anUnknownCommandIsNotAdvisedAbout() {
        ToolResult result = new DcsSchemaProbe().execute(Map.of(
                "command", "drop_everything",
                "dataset_name", "DataSet1")).join();

        assertEquals("{\"ok\":true}", result.getContent());
    }

    @Test
    public void anUndeclaredKeyAndAForeignKeyBothGetTheirOwnNote() {
        // Two different mistakes, two different fixes: 'datasetName' is a spelling the tool never
        // declares, 'template_name' is declared but only for create_schema.
        ToolResult result = new DcsSchemaProbe().execute(Map.of(
                "command", "list_nodes",
                "project", "Sandbox",
                "datasetName", "DataSet1",
                "template_name", "Main")).join();

        assertTrue(result.isSuccess());
        assertTrue(result.getContent(), result.getContent().contains("ignored an unknown parameter"));
        assertTrue(result.getContent(), result.getContent().contains("'datasetName'"));
        assertTrue(result.getContent(),
                result.getContent().contains("'template_name' (belongs to 'create_schema')"));
    }

    @Test
    public void aFailingCallKeepsFailingAndStillGetsTheNote() {
        ToolResult result = new FailingDcsSchemaProbe().execute(Map.of(
                "command", "get_summary",
                "project", "Sandbox",
                "owner_fqn", "Report.Sales",
                "parameter_name", "Period")).join();

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage(), result.getErrorMessage().startsWith("boom"));
        assertTrue(result.getErrorMessage(),
                result.getErrorMessage().contains("'parameter_name' (belongs to 'upsert_param')"));
    }

    @Test
    public void aPlainCommandToolWithoutTheTagConventionIsNeverAdvisedAbout() {
        // Only a schema that both enumerates its commands and tags its per-command properties can be
        // judged; anything else must behave exactly as it did before.
        ToolResult result = new UntaggedCommandProbe().execute(Map.of(
                "command", "read",
                "payload", "x")).join();

        assertEquals("{\"ok\":true}", result.getContent());
    }

    // --- fixtures -----------------------------------------------------------

    /** Carries dcs_manage's real schema; the body is a stub so no EDT runtime is needed. */
    @ToolMeta(name = "dcs_manage_probe", category = "test")
    private static class DcsSchemaProbe extends AbstractTool {
        @Override
        public String getDescription() {
            return "probe"; //$NON-NLS-1$
        }

        @Override
        public String getParameterSchema() {
            return new DcsManageTool().getParameterSchema();
        }

        @Override
        protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
            return CompletableFuture.completedFuture(ToolResult.success("{\"ok\":true}")); //$NON-NLS-1$
        }
    }

    @ToolMeta(name = "dcs_manage_failing_probe", category = "test")
    private static final class FailingDcsSchemaProbe extends DcsSchemaProbe {
        @Override
        protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
            return CompletableFuture.completedFuture(ToolResult.failure("boom")); //$NON-NLS-1$
        }
    }

    @ToolMeta(name = "untagged_command_probe", category = "test")
    private static final class UntaggedCommandProbe extends AbstractTool {
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
                        "command": {"type": "string", "enum": ["read", "write"], "description": "which"},
                        "payload": {"type": "string", "description": "the data"}
                      }
                    }
                    """;
        }

        @Override
        protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
            return CompletableFuture.completedFuture(ToolResult.success("{\"ok\":true}")); //$NON-NLS-1$
        }
    }
}
