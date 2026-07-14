/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.meta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import java.util.function.Predicate;

import com.codepilot1c.core.model.ToolDefinition;
import com.codepilot1c.core.tools.AbstractTool;
import com.codepilot1c.core.tools.ITool;
import com.codepilot1c.core.tools.ToolExecutionContext;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolRegistry;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.core.tools.surface.BuiltinToolTaxonomy;
import com.codepilot1c.core.tools.surface.DeferredToolSession;
import com.codepilot1c.core.tools.surface.DeferredToolSet;
import com.codepilot1c.core.tools.surface.ToolCategory;
import com.codepilot1c.core.tools.surface.ToolGroupTaxonomy;
import com.codepilot1c.core.tools.surface.ToolGroupVisibility;
import com.codepilot1c.core.tools.surface.ToolSurfaceContext;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Meta-tool for discovering domain-specific tools by category.
 *
 * <p>When deferred loading is active, the LLM sees only core tools
 * (~11) plus this tool. To access domain-specific tools (BSL, metadata,
 * forms, DCS, QA, etc.), the LLM calls this tool with the desired
 * category. The tools are then injected into subsequent requests.</p>
 *
 * <p>Categories: bsl, metadata, forms, extensions, dcs, qa,
 * diagnostics, workspace.</p>
 */
@ToolMeta(name = "discover_tools", category = "general",
        tags = {"meta"})
public class DiscoverToolsTool extends AbstractTool {

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "category": {
                  "type": "string",
                  "description": "Tool category to discover: bsl|metadata|forms|extensions|dcs|qa|diagnostics|workspace",
                  "enum": ["bsl", "metadata", "forms", "extensions", "dcs", "qa", "diagnostics", "workspace"]
                }
              },
              "required": ["category"]
            }
            """; //$NON-NLS-1$

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final ToolRegistry toolRegistry;
    private volatile DeferredToolSession session;

    public DiscoverToolsTool(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    /**
     * Sets the deferred tool session for tracking discovered categories.
     *
     * @param session the session (must not be null)
     */
    public void setSession(DeferredToolSession session) {
        this.session = session;
    }

    /**
     * Returns the current session, or null if not set.
     */
    public DeferredToolSession getSession() {
        return session;
    }

    @Override
    public String getDescription() {
        return "Reveals hidden domain tools by category when the current surface is not enough. Does no work itself; it only exposes tools."; //$NON-NLS-1$
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
        Map<String, Object> p = params.getRaw();
        String categoryName = p.get("category") != null //$NON-NLS-1$
                ? String.valueOf(p.get("category")) : null; //$NON-NLS-1$

        if (categoryName == null || !DeferredToolSet.CATEGORY_NAMES.contains(
                categoryName.toLowerCase(java.util.Locale.ROOT))) {
            return CompletableFuture.completedFuture(
                    ToolResult.failure("Unknown category: " + categoryName + //$NON-NLS-1$
                            ". Available: " + String.join(", ", DeferredToolSet.CATEGORY_NAMES))); //$NON-NLS-1$ //$NON-NLS-2$
        }

        ToolCategory category = DeferredToolSet.resolveCategory(categoryName);
        if (category == null) {
            return CompletableFuture.completedFuture(
                    ToolResult.failure("Could not resolve category: " + categoryName)); //$NON-NLS-1$
        }

        // Mark category as discovered in the session
        DeferredToolSession currentSession = this.session;
        if (currentSession != null) {
            currentSession.markDiscovered(category);
        }

        // Collect tools for this category
        List<ToolSummary> toolSummaries = new ArrayList<>();
        ToolSurfaceContext surfaceContext = toolRegistry.createRuntimeSurfaceContext(
                ToolSurfaceContext.defaultProfile());
        // Honor the exposure gate as a hard ceiling: a category disabled for this
        // endpoint must not be revealed through discover_tools either. When invoked
        // via the MCP host, the router supplies the CALLING PORT's visibility so the
        // answer is strictly per-profile; otherwise (in-process agent) fall back to
        // the global env view.
        Predicate<String> endpointVisible = ToolExecutionContext.endpointToolVisibility();
        ToolGroupVisibility envVisibility = endpointVisible == null
                ? ToolGroupVisibility.fromEnvironment() : null;
        int matchedInCategory = 0;
        int hiddenByConfig = 0;
        // Tools that belong to this category but are gated out for the calling
        // endpoint (group/per-tool config). Reported back with available:false so
        // a caller never mistakes a gated tool for a callable one — the tool's own
        // "now available" note used to imply every category tool was invokable.
        List<String> gatedOut = new ArrayList<>();

        for (ITool tool : toolRegistry.getAllTools()) {
            ToolCategory toolCategory = BuiltinToolTaxonomy.categoryOf(tool);
            if (toolCategory == category) {
                matchedInCategory++;
                boolean visible = endpointVisible != null
                        ? endpointVisible.test(tool.getName())
                        : envVisibility.isToolVisible(tool.getName(), ToolGroupTaxonomy.groupOf(tool));
                if (!visible) {
                    hiddenByConfig++;
                    gatedOut.add(tool.getName());
                    continue;
                }
                ToolDefinition def = toolRegistry.getToolDefinition(tool, surfaceContext);
                toolSummaries.add(new ToolSummary(
                        def.getName(),
                        def.getDescription(),
                        def.getParametersSchema() != null));
            }
        }

        if (toolSummaries.isEmpty()) {
            if (matchedInCategory > 0 && hiddenByConfig == matchedInCategory) {
                return CompletableFuture.completedFuture(
                        ToolResult.success("Category '" + categoryName //$NON-NLS-1$
                                + "' is disabled for this endpoint's profile " //$NON-NLS-1$
                                + "(or server config: CODEPILOT1C_DISABLE_GROUPS / CODEPILOT1C_ENABLE_GROUPS). " //$NON-NLS-1$
                                + "No tools to reveal on this port.")); //$NON-NLS-1$
            }
            return CompletableFuture.completedFuture(
                    ToolResult.success("No tools found for category: " + categoryName + //$NON-NLS-1$
                            ". This category may not be available in the current workspace.")); //$NON-NLS-1$
        }

        // Build response
        JsonObject result = new JsonObject();
        result.addProperty("category", categoryName); //$NON-NLS-1$
        result.addProperty("tools_count", toolSummaries.size()); //$NON-NLS-1$
        result.addProperty("status", "discovered"); //$NON-NLS-1$ //$NON-NLS-2$

        JsonArray toolsArray = new JsonArray();
        for (ToolSummary summary : toolSummaries) {
            JsonObject toolObj = new JsonObject();
            toolObj.addProperty("name", summary.name); //$NON-NLS-1$
            toolObj.addProperty("description", summary.description); //$NON-NLS-1$
            toolObj.addProperty("has_parameters", summary.hasParameters); //$NON-NLS-1$
            toolObj.addProperty("available", true); //$NON-NLS-1$
            toolsArray.add(toolObj);
        }
        result.add("tools", toolsArray); //$NON-NLS-1$

        // Surface any category tools the calling endpoint gates out, so the caller
        // sees the full picture instead of assuming everything listed is callable.
        if (!gatedOut.isEmpty()) {
            JsonArray unavailable = new JsonArray();
            for (String hidden : gatedOut) {
                JsonObject u = new JsonObject();
                u.addProperty("name", hidden); //$NON-NLS-1$
                u.addProperty("available", false); //$NON-NLS-1$
                u.addProperty("reason", //$NON-NLS-1$
                        "not enabled in this endpoint's profile (group/tool gate) — tools/call will reject it"); //$NON-NLS-1$
                unavailable.add(u);
            }
            result.add("unavailable", unavailable); //$NON-NLS-1$
            result.addProperty("unavailable_count", gatedOut.size()); //$NON-NLS-1$
        }

        result.addProperty("note", //$NON-NLS-1$
                "Tools under 'tools' are exposed on THIS endpoint and callable directly. " //$NON-NLS-1$
                + "Availability is per-endpoint/profile: any entries under 'unavailable' are gated " //$NON-NLS-1$
                + "out here and a tools/call for them will be rejected as not exposed."); //$NON-NLS-1$

        return CompletableFuture.completedFuture(
                ToolResult.success(GSON.toJson(result)));
    }

    private record ToolSummary(String name, String description, boolean hasParameters) {
    }
}
