package com.codepilot1c.core.tools.qa;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Same guard as {@code EdtDiagnosticsToolSchemaTest}, for the QA dispatcher tools: every param a
 * delegate reads must be declared on the composite schema, or MCP clients strip it before dispatch
 * (e.g. qa_inspect(command=steps_search, query=...) silently lost {@code query}).
 */
public class QaDispatcherSchemaTest {

    private static JsonObject props(String schema) {
        return JsonParser.parseString(schema).getAsJsonObject().getAsJsonObject("properties"); //$NON-NLS-1$
    }

    private static void assertDeclares(JsonObject props, String tool, String... fields) {
        for (String field : fields) {
            assertTrue(tool + " schema must declare delegate param '" + field //$NON-NLS-1$
                    + "' (undeclared params are stripped by MCP clients before dispatch)", //$NON-NLS-1$
                    props.has(field));
        }
    }

    @Test
    public void qaInspectDeclaresDelegateParams() {
        assertDeclares(props(new QaInspectTool().getParameterSchema()), "qa_inspect", //$NON-NLS-1$
                "config_path", "include_contract", "validate_ports", "use_edt_runtime", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "use_test_manager", "project_name", "auto_create_config", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "query", "limit", "only_placeholders", "include_regex"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    @Test
    public void qaGenerateDeclaresDelegateParams() {
        assertDeclares(props(new QaGenerateTool().getParameterSchema()), "qa_generate", //$NON-NLS-1$
                "config_path", "project_name", "epf_path", "params_template", "force", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                "dry_run", "create_backup", "plan", "auto_create_config", "overwrite", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                "feature_title", "feature_file", "language"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }
}
