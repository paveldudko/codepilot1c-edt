/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.workspace;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.runtime.EdtRuntimeService;
import com.codepilot1c.core.edt.runtime.InfobaseSiblingResolver;
import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.tools.AbstractTool;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolResult;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
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
 *
 * <p><b>Shared infobase.</b> EDT's equality state is per (project, infobase) PAIR, so a green answer
 * for one project says nothing about the other projects bound to the same infobase (a configuration
 * and its extensions, or two configuration projects on one {@code .1CD}). The tool therefore fans out
 * over its siblings ({@link InfobaseSiblingResolver}) and reports {@code siblings},
 * {@code shared_infobase}, {@code sibling_projects_stale} and {@code all_projects_work_ready}
 * alongside the unchanged {@code work_ready} — that field keeps its original per-project meaning for
 * backward compatibility. Opt out with {@code include_siblings=false}.</p>
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
                },
                "include_siblings": {
                  "type": "boolean",
                  "description": "Also report the OTHER open projects bound to the same infobase (a configuration and its extensions share one) with their own equality state. Default: true — a green state for this project alone does NOT mean the shared infobase is up to date. Set false to skip the fan-out."
                }
              },
              "required": ["project_name"]
            }
            """; //$NON-NLS-1$

    private final EdtRuntimeService runtimeService;
    private final InfobaseSiblingResolver siblingResolver;

    public GetInfobaseSyncStateTool() {
        this(new EdtRuntimeService());
    }

    public GetInfobaseSyncStateTool(EdtRuntimeService runtimeService) {
        this(runtimeService, new InfobaseSiblingResolver());
    }

    public GetInfobaseSyncStateTool(EdtRuntimeService runtimeService, InfobaseSiblingResolver siblingResolver) {
        this.runtimeService = runtimeService;
        this.siblingResolver = siblingResolver;
    }

    @Override
    public String getDescription() {
        return "Reports whether an EDT project is in sync with its primary infobase (work-ready) or " //$NON-NLS-1$
                + "needs an incremental update_infobase — the in-memory equality state, read without " //$NON-NLS-1$
                + "launching a configurator. Use as a post-rebind readiness check before running tests. " //$NON-NLS-1$
                + "Also reports the other projects sharing that infobase (extensions), whose state is " //$NON-NLS-1$
                + "independent. EQUAL means the config comparison converged — not that a deferred " //$NON-NLS-1$
                + "exclusive restructure ran (see dynamic_only on update_infobase)."; //$NON-NLS-1$
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
        boolean includeSiblings = params.optBoolean("include_siblings", true); //$NON-NLS-1$
        return CompletableFuture.completedFuture(read(projectName, includeSiblings));
    }

    private ToolResult read(String projectName, boolean includeSiblings) {
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
        } else if (workReady) {
            // Cross-note for the deceptive-EQUAL mode (issues/deceptive-equal-after-dynamic-only-update):
            // EDT compares the STORED configuration, and a dynamic (non-exclusive) update commits the
            // config while deferring the physical restructure — so EQUAL can be true while the schema is
            // not usable. The update side already emits dynamic_only; this is the read side's caveat.
            json.addProperty("hint", //$NON-NLS-1$
                    "EQUAL means EDT's configuration comparison converged. It does NOT prove a deferred " //$NON-NLS-1$
                            + "schema restructure ran: if the preceding update_infobase reported " //$NON-NLS-1$
                            + "dynamic_only=true, the new tables/columns are NOT live — re-apply with an " //$NON-NLS-1$
                            + "exclusive lock (close client/test sessions, or kill_agent_mode=true) and " //$NON-NLS-1$
                            + "re-check before trusting this EQUAL."); //$NON-NLS-1$
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
        addSiblings(json, projectName, state, includeSiblings);
        LOG.info("get_infobase_sync_state: project=%s equality_state=%s work_ready=%s", //$NON-NLS-1$
                projectName, state, Boolean.valueOf(workReady));
        return ToolResult.success(pretty(json), ToolResult.ToolResultType.CODE);
    }

    /**
     * Fans out over the other open projects bound to the SAME infobase and decorates the payload with
     * {@code siblings} / {@code shared_infobase} / {@code sibling_projects_stale} /
     * {@code all_projects_work_ready}. The existing {@code work_ready} keeps its per-project meaning
     * (backward compatibility) — the aggregate verdict is the new field.
     *
     * <p>When a must-converge sibling is NOT_EQUAL the payload also carries a warning: this project's
     * green state is genuine, yet the shared infobase is not up to date because the sibling's content
     * (typically an extension) has not been applied — it needs its OWN update_infobase.</p>
     */
    private void addSiblings(JsonObject json, String projectName, String state, boolean includeSiblings) {
        json.addProperty("siblings_included", includeSiblings); //$NON-NLS-1$
        if (!includeSiblings) {
            return;
        }
        List<InfobaseSiblingResolver.Sibling> siblings;
        try {
            siblings = siblingResolver.siblingsOf(projectName);
        } catch (RuntimeException | LinkageError e) {
            // Best-effort: the fan-out must never fail the primary read (LinkageError covers a missing
            // EDT/Eclipse runtime, e.g. headless unit tests).
            LOG.warn("get_infobase_sync_state: sibling fan-out failed for %s: %s", //$NON-NLS-1$
                    projectName, e.getMessage());
            return;
        }
        if (siblings == null) {
            siblings = List.of();
        }
        JsonArray rendered = new JsonArray();
        for (InfobaseSiblingResolver.Sibling sibling : siblings) {
            JsonObject entry = new JsonObject();
            entry.addProperty("project", sibling.project()); //$NON-NLS-1$
            entry.addProperty("relation", sibling.relation()); //$NON-NLS-1$
            entry.addProperty("equality_state", sibling.equalityState()); //$NON-NLS-1$
            entry.addProperty("work_ready", //$NON-NLS-1$
                    InfobaseSiblingResolver.STATE_EQUAL.equals(sibling.equalityState()));
            rendered.add(entry);
        }
        json.add("siblings", rendered); //$NON-NLS-1$
        json.addProperty("shared_infobase", !siblings.isEmpty()); //$NON-NLS-1$
        List<String> stale = InfobaseSiblingResolver.staleProjects(siblings);
        JsonArray staleArray = new JsonArray();
        for (String project : stale) {
            staleArray.add(project);
        }
        json.add("sibling_projects_stale", staleArray); //$NON-NLS-1$
        json.addProperty("all_projects_work_ready", //$NON-NLS-1$
                InfobaseSiblingResolver.allWorkReady(state, siblings));
        if (stale.isEmpty()) {
            return;
        }
        String warning = "This infobase is SHARED with project(s) " + String.join(", ", stale) //$NON-NLS-1$ //$NON-NLS-2$
                + ", which report NOT_EQUAL. A green status for '" + projectName //$NON-NLS-1$
                + "' is NOT the same as an up-to-date infobase: EDT's equality state is per " //$NON-NLS-1$
                + "(project, infobase) pair, so each of those projects must be updated separately " //$NON-NLS-1$
                + "(update_infobase with its own project_name) before the infobase is fully current."; //$NON-NLS-1$
        json.addProperty("sibling_warning", warning); //$NON-NLS-1$
        if (json.has("hint")) { //$NON-NLS-1$
            json.addProperty("hint", json.get("hint").getAsString() + " " + warning); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        } else {
            json.addProperty("hint", warning); //$NON-NLS-1$
        }
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
