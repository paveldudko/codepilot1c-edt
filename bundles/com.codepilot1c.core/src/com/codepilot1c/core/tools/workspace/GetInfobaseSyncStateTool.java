/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.workspace;

import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.runtime.EdtRuntimeService;
import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.tools.AbstractTool;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolResult;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

/**
 * Read-only probe of a project's configuration&#x2194;infobase synchronization state, WITHOUT
 * spawning a configurator/DESIGNER and WITHOUT mutating anything.
 *
 * <p>After a stack rebind ({@code Move-TaskStack} / {@code connect_infobase}) the destination
 * project may not be work-ready — EDT flags "Incremental change required" until the infobase is
 * brought up to the project configuration. This tool exposes EDT's in-memory equality state so an
 * automated runner can decide whether an incremental {@code update_infobase} is needed BEFORE it
 * runs tests, instead of discovering the mismatch mid-run.</p>
 *
 * <p>It is a thin facade over {@link EdtRuntimeService#readInfobaseEqualityState(String)} — the
 * exact same read that backs {@code update_infobase}'s {@code skip_if_current} pre-check. Best-effort:
 * when the state cannot be determined (EDT cold, project/infobase unresolved, or the API is absent on
 * this EDT version) it returns {@code determinable=false} with {@code equality_state=null}. A caller
 * that cannot determine the state should treat the project as NOT ready and run a normal update — the
 * unknown case must never be read as "ready".</p>
 */
@ToolMeta(
        name = "get_infobase_sync_state",
        category = "diagnostics",
        surfaceCategory = "smoke_runtime_recovery",
        mutating = false,
        tags = {"workspace", "edt", "diagnostics"})
public class GetInfobaseSyncStateTool extends AbstractTool {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(GetInfobaseSyncStateTool.class);

    /** EDT enum constant: project configuration equals the infobase (work-ready). */
    private static final String EQUALITY_EQUAL = "EQUAL"; //$NON-NLS-1$
    /** EDT enum constant: project configuration differs — an incremental update is required. */
    private static final String EQUALITY_NOT_EQUAL = "NOT_EQUAL"; //$NON-NLS-1$
    /** EDT enum constant: EDT is still computing the comparison. */
    private static final String EQUALITY_LOADING = "LOADING"; //$NON-NLS-1$

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "project_name": {
                  "type": "string",
                  "description": "EDT project whose configuration<->infobase sync state to read (against its primary/default infobase)."
                }
              },
              "required": ["project_name"]
            }
            """; //$NON-NLS-1$

    private final EdtRuntimeService runtimeService;

    public GetInfobaseSyncStateTool() {
        this(new EdtRuntimeService());
    }

    public GetInfobaseSyncStateTool(EdtRuntimeService runtimeService) {
        this.runtimeService = runtimeService;
    }

    @Override
    public String getDescription() {
        return "Reports whether an EDT project is in sync with its primary infobase (work-ready) or " //$NON-NLS-1$
                + "needs an incremental update_infobase — the in-memory equality state, read without " //$NON-NLS-1$
                + "launching a configurator. Use as a post-rebind readiness check before running tests."; //$NON-NLS-1$
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
        String projectName = trimToNull(params.optString("project_name", null)); //$NON-NLS-1$
        if (projectName == null) {
            return CompletableFuture.completedFuture(ToolResult.failure("project_name is required")); //$NON-NLS-1$
        }
        return CompletableFuture.completedFuture(read(projectName));
    }

    private ToolResult read(String projectName) {
        // readInfobaseEqualityState is best-effort and never throws; the try is pure defense-in-depth.
        String state;
        try {
            state = runtimeService.readInfobaseEqualityState(projectName);
        } catch (RuntimeException e) {
            String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            LOG.warn("get_infobase_sync_state: equality read failed for %s: %s", projectName, detail); //$NON-NLS-1$
            state = null;
        }
        boolean determinable = state != null;
        boolean workReady = EQUALITY_EQUAL.equals(state);
        boolean needsUpdate = EQUALITY_NOT_EQUAL.equals(state);
        boolean loading = EQUALITY_LOADING.equals(state);

        JsonObject json = new JsonObject();
        json.addProperty("project", projectName); //$NON-NLS-1$
        if (state != null) {
            json.addProperty("equality_state", state); //$NON-NLS-1$
        } else {
            json.add("equality_state", com.google.gson.JsonNull.INSTANCE); //$NON-NLS-1$
        }
        json.addProperty("determinable", determinable); //$NON-NLS-1$
        json.addProperty("work_ready", workReady); //$NON-NLS-1$
        json.addProperty("needs_update", needsUpdate); //$NON-NLS-1$
        json.addProperty("loading", loading); //$NON-NLS-1$
        if (!determinable) {
            json.addProperty("hint", //$NON-NLS-1$
                    "equality state unavailable (EDT cold, project/primary-infobase unresolved, or API " //$NON-NLS-1$
                            + "absent on this EDT build). Treat as NOT work-ready and run " //$NON-NLS-1$
                            + "update_infobase — never read 'unknown' as ready."); //$NON-NLS-1$
        } else if (loading) {
            json.addProperty("hint", //$NON-NLS-1$
                    "EDT is still computing the comparison; poll again shortly before deciding."); //$NON-NLS-1$
        } else if (needsUpdate) {
            json.addProperty("hint", //$NON-NLS-1$
                    "project differs from the infobase — run update_infobase (incremental) before tests. " //$NON-NLS-1$
                            + "IF update_infobase ALREADY returned updated:true and this still reports " //$NON-NLS-1$
                            + "NOT_EQUAL, do NOT loop re-running the same update — it will not converge. That " //$NON-NLS-1$
                            + "is a known non-convergence mode: the update most likely applied DYNAMICALLY " //$NON-NLS-1$
                            + "(main<->DB config still diverged until an EXCLUSIVE update) or EDT's in-memory " //$NON-NLS-1$
                            + "equality state has not refreshed. Remediation: run one EXCLUSIVE update " //$NON-NLS-1$
                            + "(update_infobase with no other client/Designer session holding the IB), or " //$NON-NLS-1$
                            + "treat work_ready as advisory for a change with no schema impact. NB: EDT exposes " //$NON-NLS-1$
                            + "only this coarse equality state — no object-level list of WHAT differs is " //$NON-NLS-1$
                            + "available without a full Designer comparison."); //$NON-NLS-1$
        }
        LOG.info("get_infobase_sync_state: project=%s equality_state=%s work_ready=%s", //$NON-NLS-1$
                projectName, state, Boolean.valueOf(workReady));
        return ToolResult.success(pretty(json), ToolResult.ToolResultType.CODE);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String pretty(JsonObject object) {
        // serializeNulls: keep the explicit "equality_state": null in the undeterminable case — Gson
        // drops null members by default, which would silently omit the field the caller checks.
        return new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(object);
    }
}
