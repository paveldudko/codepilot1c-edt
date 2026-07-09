/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.workspace;

import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.state.EdtWorkspaceStateService;
import com.codepilot1c.core.tools.AbstractTool;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolResult;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

/**
 * On-demand facade over {@link EdtWorkspaceStateService}: returns THIS EDT stack's live state
 * snapshot in one MCP call.
 *
 * <p>An external stack-pool orchestrator normally reads each stack's periodic beacon file (written
 * by {@code EdtStateBeacon}) to poll liveness WITHOUT touching the MCP port. This tool exposes the
 * exact same snapshot over MCP for when a caller is already talking to the port and wants a fresh,
 * on-demand read (identity, MCP endpoints/port, index readiness, and — optionally — bound
 * infobases). The underlying service is stateless and best-effort: it degrades to a partial
 * snapshot (never throws) when EDT services are cold or absent, so this tool answers fast even
 * before the workspace index is ready.</p>
 */
@ToolMeta(
        name = "get_workspace_state",
        category = "diagnostics",
        surfaceCategory = "smoke_runtime_recovery",
        mutating = false,
        tags = {"workspace", "edt", "diagnostics"})
public class GetWorkspaceStateTool extends AbstractTool {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(GetWorkspaceStateTool.class);

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "include_bound_infobases": {
                  "type": "boolean",
                  "description": "Include the (heavier) per-project bound-infobase list. Default true; pass false for a faster identity+index-only snapshot."
                }
              }
            }
            """; //$NON-NLS-1$

    @Override
    public String getDescription() {
        return "Returns this EDT stack's live state in one call — plugin version, stack id, " //$NON-NLS-1$
                + "MCP endpoints, index readiness, and bound infobases. " //$NON-NLS-1$
                + "Use for stack-pool liveness/coordination checks without opening files or the workbench."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    public boolean requiresConfirmation() {
        return false;
    }

    @Override
    public boolean isDestructive() {
        return false;
    }

    @Override
    protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        boolean includeBound = params.optBoolean("include_bound_infobases", true); //$NON-NLS-1$
        return CompletableFuture.completedFuture(snapshot(includeBound));
    }

    private ToolResult snapshot(boolean includeBound) {
        try {
            // Stateless, best-effort service: a fresh instance per call is correct (mirrors
            // EdtStateBeacon) and buildSnapshot never throws — it degrades to a partial snapshot.
            JsonObject state = new EdtWorkspaceStateService().buildSnapshot(includeBound);
            LOG.info("get_workspace_state: include_bound_infobases=%s", includeBound); //$NON-NLS-1$
            return ToolResult.success(pretty(state), ToolResult.ToolResultType.CODE);
        } catch (Exception e) {
            // Defensive: the service is documented as non-throwing, so reaching here is truly
            // unexpected. Report a structured failure rather than propagating.
            String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            LOG.warn("get_workspace_state failed unexpectedly: %s", detail); //$NON-NLS-1$
            return ToolResult.failure("Failed to build workspace state snapshot: " + detail); //$NON-NLS-1$
        }
    }

    private static String pretty(JsonObject object) {
        return new GsonBuilder().setPrettyPrinting().create().toJson(object);
    }
}
