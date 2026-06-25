/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.workspace;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;

import com.codepilot1c.core.edt.ast.EdtServiceGateway;
import com.codepilot1c.core.edt.ast.ProjectReadinessChecker;
import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.tools.AbstractTool;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolResult;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Lightweight, non-blocking probe for EDT's derived-data (index / validation) readiness.
 *
 * <p>After a large merge or branch-switch EDT re-validates the project for many minutes; during
 * that window every <em>work-performing</em> MCP tool blocks on the index and times out, while the
 * plain HTTP {@code /health} endpoint keeps returning {@code ok}. There was no way to ask "is EDT
 * still indexing?" — so callers blind-waited and risked a false-clean (a half-built index reports 0
 * diagnostics). Feedback {@code 2026-06-11-postmerge-edt-reindex-tooling.md §1}.</p>
 *
 * <p>This tool only reads {@code IDerivedDataManager.isIdle()/isAllComputed()} (both return
 * immediately) so it answers fast even mid-reindex — it never waits for the index to finish.</p>
 */
@ToolMeta(
        name = "edt_index_status",
        category = "diagnostics",
        surfaceCategory = "smoke_runtime_recovery",
        mutating = false,
        tags = {"workspace", "edt", "diagnostics"})
public class EdtIndexStatusTool extends AbstractTool {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(EdtIndexStatusTool.class);

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "project_name": {
                  "type": "string",
                  "description": "Optional EDT project name to probe. When omitted, every open project in the workspace is reported."
                }
              }
            }
            """; //$NON-NLS-1$

    private final EdtServiceGateway gateway;

    public EdtIndexStatusTool() {
        this(new EdtServiceGateway());
    }

    EdtIndexStatusTool(EdtServiceGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public String getDescription() {
        return "Быстрый неблокирующий пробник готовности индекса EDT (derived-data). " //$NON-NLS-1$
                + "Отвечает мгновенно даже во время переиндексации после большого мерджа/branch-switch — " //$NON-NLS-1$
                + "когда обычные tool-вызовы таймаутят, а /health всё равно возвращает ok. " //$NON-NLS-1$
                + "is_indexing=true означает: не доверяй 0 диагностик (индекс ещё строится), подожди и повтори."; //$NON-NLS-1$
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
        Map<String, Object> parameters = params.getRaw();
        String projectName = asString(parameters == null ? null : parameters.get("project_name")); //$NON-NLS-1$
        return CompletableFuture.completedFuture(probe(projectName));
    }

    private ToolResult probe(String projectName) {
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        if (workspace == null) {
            return ToolResult.failure("Workspace is not available (EDT not started)"); //$NON-NLS-1$
        }
        ProjectReadinessChecker checker = new ProjectReadinessChecker(gateway);
        JsonArray projects = new JsonArray();
        boolean anyIndexing = false;
        int considered = 0;

        IProject[] all = projectName != null && !projectName.isBlank()
                ? new IProject[] { gateway.resolveProject(projectName) }
                : workspace.getRoot().getProjects();

        for (IProject project : all) {
            if (project == null || !project.exists() || !project.isOpen()) {
                continue;
            }
            considered++;
            JsonObject entry = new JsonObject();
            entry.addProperty("name", project.getName()); //$NON-NLS-1$
            try {
                ProjectReadinessChecker.Result result = checker.check(project);
                entry.addProperty("state", result.getState().name()); //$NON-NLS-1$
                entry.addProperty("message", result.getMessage()); //$NON-NLS-1$
                if (result.getState() == ProjectReadinessChecker.State.BUILDING) {
                    anyIndexing = true;
                }
            } catch (Exception e) {
                // Best-effort: a per-project failure (services not yet up) is reported, not fatal.
                entry.addProperty("state", "UNKNOWN"); //$NON-NLS-1$ //$NON-NLS-2$
                entry.addProperty("message", //$NON-NLS-1$
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
            projects.add(entry);
        }

        JsonObject out = new JsonObject();
        out.addProperty("is_indexing", anyIndexing); //$NON-NLS-1$
        out.addProperty("ready", !anyIndexing && considered > 0); //$NON-NLS-1$
        out.addProperty("projects_considered", considered); //$NON-NLS-1$
        out.add("projects", projects); //$NON-NLS-1$
        if (anyIndexing) {
            out.addProperty("hint", //$NON-NLS-1$
                    "EDT is still building derived data — work tools may time out and a diagnostics " //$NON-NLS-1$
                    + "scan can under-report (a half-built index reports 0). Re-probe before trusting results."); //$NON-NLS-1$
        }
        LOG.info("edt_index_status: considered=%d is_indexing=%s", considered, anyIndexing); //$NON-NLS-1$
        return ToolResult.success(pretty(out), ToolResult.ToolResultType.CODE);
    }

    private static String pretty(JsonObject object) {
        return new GsonBuilder().setPrettyPrinting().create().toJson(object);
    }

    private static String asString(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }
}
