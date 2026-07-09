package com.codepilot1c.core.tools.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Guards the root cause of the 2026-05-29 launch_app dry_run regression: MCP clients strip
 * arguments that aren't declared in the advertised schema (despite server-side
 * {@code additionalProperties:true}), so every launch_app pass-through param MUST be declared on
 * the composite edt_diagnostics schema or it silently never reaches the delegate — for dry_run that
 * meant a real process spawn when the caller asked only to preview.
 */
public class EdtDiagnosticsToolSchemaTest {

    @Test
    public void declaresAllLaunchAppPassThroughParams() {
        JsonObject schema = JsonParser.parseString(new EdtDiagnosticsTool().getParameterSchema())
                .getAsJsonObject();
        JsonObject props = schema.getAsJsonObject("properties"); //$NON-NLS-1$

        for (String field : new String[] {
                // launch_app pass-through
                "dry_run", "wait_for_exit", "timeout_s", "additional_parameters", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "mode", "user", "password", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                // update_infobase pass-through
                "keep_connected", "async", "kill_agent_mode", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "allow_webserver_running", "skip_if_current"}) { //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue("edt_diagnostics schema must declare delegate param '" + field //$NON-NLS-1$
                    + "' (undeclared params are stripped by MCP clients before dispatch)", //$NON-NLS-1$
                    props.has(field));
        }
    }

    /**
     * skip_if_current (opt-in equality short-circuit added to EdtUpdateInfobaseTool in 027d623)
     * is reachable ONLY through this dispatcher; it must be declared as a boolean or MCP clients
     * strip it and the feature stays unreachable from the tool surface.
     */
    @Test
    public void declaresSkipIfCurrentAsBoolean() {
        JsonObject schema = JsonParser.parseString(new EdtDiagnosticsTool().getParameterSchema())
                .getAsJsonObject();
        JsonObject props = schema.getAsJsonObject("properties"); //$NON-NLS-1$
        assertTrue("edt_diagnostics schema must declare 'skip_if_current'", //$NON-NLS-1$
                props.has("skip_if_current")); //$NON-NLS-1$
        assertEquals("skip_if_current must be typed boolean", "boolean", //$NON-NLS-1$ //$NON-NLS-2$
                props.getAsJsonObject("skip_if_current").get("type").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
