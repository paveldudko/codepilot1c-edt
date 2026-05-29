package com.codepilot1c.core.tools;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;

import org.junit.Test;

import com.codepilot1c.core.tools.diagnostics.EdtDiagnosticsTool;
import com.codepilot1c.core.tools.qa.QaGenerateTool;
import com.codepilot1c.core.tools.qa.QaInspectTool;
import com.codepilot1c.core.tools.qa.QaRunTool;
import com.codepilot1c.core.tools.qa.YaxunitRunTool;
import com.codepilot1c.core.tools.workspace.ConnectInfobaseTool;
import com.codepilot1c.core.tools.workspace.EdtLaunchAppTool;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Guards that every tool's advertised {@code getParameterSchema()} is well-formed JSON.
 *
 * <p>Regression target: codepilot1c-feedback/2026-05-29-qa-run-filter-bypass.md — qa_run's schema
 * had an unescaped {@code "} inside {@code test_client_login.description}, so the MCP host's schema
 * parse failed and clients fell back to stringifying array args (the {@code features} filter was
 * silently bypassed). A malformed tool schema is invisible at runtime until it corrupts argument
 * delivery; this test makes it a compile-cycle failure instead.</p>
 */
public class ToolSchemaValidityTest {

    @Test
    public void toolSchemasAreWellFormedJson() {
        List<ITool> tools = List.of(
                new QaRunTool(),
                new YaxunitRunTool(),
                new QaInspectTool(),
                new QaGenerateTool(),
                new ConnectInfobaseTool(),
                new EdtDiagnosticsTool(),
                new EdtLaunchAppTool());

        for (ITool tool : tools) {
            String schema = tool.getParameterSchema();
            if (schema == null || schema.isBlank()) {
                continue; // a tool may legitimately advertise no schema
            }
            try {
                JsonObject obj = JsonParser.parseString(schema).getAsJsonObject();
                assertTrue(tool.getName() + " schema must declare a 'properties' object", //$NON-NLS-1$
                        obj.has("properties")); //$NON-NLS-1$
            } catch (RuntimeException e) {
                fail(tool.getName() + " getParameterSchema() is not well-formed JSON: " //$NON-NLS-1$
                        + e.getMessage());
            }
        }
    }
}
