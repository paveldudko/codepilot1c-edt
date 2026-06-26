/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.surface;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Operator-configurable gate for which tools the server announces, by
 * <em>group</em> and by individual tool. Resolved once per server instance from
 * environment variables (with {@code -D} system-property overrides), so each
 * fleet surface — which already starts its own process — can carry only the
 * tools its role needs and cut tool-definition context.
 *
 * <p>Config (denylist by default — empty config = everything announced):</p>
 * <pre>
 *   CODEPILOT1C_DISABLE_GROUPS=forms.write,metadata.write,qa,extensions
 *   CODEPILOT1C_DISABLE_TOOLS=render_template
 *   # or allowlist mode (carry only the listed groups; meta should be included):
 *   CODEPILOT1C_ENABLE_GROUPS=diagnostics,bsl,metadata.read,forms.read,files,meta
 *   CODEPILOT1C_ENABLE_TOOLS=git_inspect
 * </pre>
 * <p>System-property equivalents (override env):
 * {@code codepilot.mcp.host.tools.disableGroups} / {@code .disableTools} /
 * {@code .enableGroups} / {@code .enableTools}.</p>
 *
 * <p>Precedence (highest first): {@code discover_tools} is always announced
 * (the lazy-reveal floor); then per-tool disable; then per-tool enable; then
 * the group decision. Group names are uniform with {@link ToolGroupTaxonomy} —
 * a bare token (e.g. {@code metadata}) matches every facet, a faceted token
 * (e.g. {@code metadata.write}) only that facet.</p>
 *
 * <p>Wired into {@code DefaultMcpToolExposurePolicy.isExposed}, so the gate
 * applies to BOTH {@code tools/list} (announce) and {@code tools/call}
 * (execution) — a true hard ceiling that {@code discover_tools} cannot bypass.</p>
 *
 * <p>Backs feedback {@code 2026-06-26-toggleable-tool-groups.md}.</p>
 */
public final class ToolGroupVisibility {

    private static final String ENV_DISABLE_GROUPS = "CODEPILOT1C_DISABLE_GROUPS"; //$NON-NLS-1$
    private static final String ENV_DISABLE_TOOLS = "CODEPILOT1C_DISABLE_TOOLS"; //$NON-NLS-1$
    private static final String ENV_ENABLE_GROUPS = "CODEPILOT1C_ENABLE_GROUPS"; //$NON-NLS-1$
    private static final String ENV_ENABLE_TOOLS = "CODEPILOT1C_ENABLE_TOOLS"; //$NON-NLS-1$

    private static final String PROP_DISABLE_GROUPS = "codepilot.mcp.host.tools.disableGroups"; //$NON-NLS-1$
    private static final String PROP_DISABLE_TOOLS = "codepilot.mcp.host.tools.disableTools"; //$NON-NLS-1$
    private static final String PROP_ENABLE_GROUPS = "codepilot.mcp.host.tools.enableGroups"; //$NON-NLS-1$
    private static final String PROP_ENABLE_TOOLS = "codepilot.mcp.host.tools.enableTools"; //$NON-NLS-1$

    private static final String ALWAYS_ON_TOOL = "discover_tools"; //$NON-NLS-1$

    private final Set<String> disableGroups;
    private final Set<String> disableTools;
    private final Set<String> enableGroups;
    private final Set<String> enableTools;
    private final boolean allowlistMode;

    private ToolGroupVisibility(
            Set<String> disableGroups,
            Set<String> disableTools,
            Set<String> enableGroups,
            Set<String> enableTools) {
        this.disableGroups = disableGroups;
        this.disableTools = disableTools;
        this.enableGroups = enableGroups;
        this.enableTools = enableTools;
        this.allowlistMode = !enableGroups.isEmpty();
    }

    /** Build from raw config strings (comma-separated). Used by tests and {@link #fromEnvironment()}. */
    public static ToolGroupVisibility of(
            String disableGroups,
            String disableTools,
            String enableGroups,
            String enableTools) {
        return new ToolGroupVisibility(
                parse(disableGroups, true),
                parse(disableTools, false),
                parse(enableGroups, true),
                parse(enableTools, false));
    }

    /** Resolve from environment variables, with {@code -D} system properties taking precedence. */
    public static ToolGroupVisibility fromEnvironment() {
        return of(
                resolve(PROP_DISABLE_GROUPS, ENV_DISABLE_GROUPS),
                resolve(PROP_DISABLE_TOOLS, ENV_DISABLE_TOOLS),
                resolve(PROP_ENABLE_GROUPS, ENV_ENABLE_GROUPS),
                resolve(PROP_ENABLE_TOOLS, ENV_ENABLE_TOOLS));
    }

    /** {@code true} when any group/tool filter is active (config is non-empty). */
    public boolean isActive() {
        return !disableGroups.isEmpty() || !disableTools.isEmpty()
                || !enableGroups.isEmpty() || !enableTools.isEmpty();
    }

    public boolean isAllowlistMode() {
        return allowlistMode;
    }

    /**
     * Whether a tool of the given group should be announced/callable.
     *
     * @param toolName the tool name (case-sensitive, as registered)
     * @param group    the tool's group from {@link ToolGroupTaxonomy#groupOf}
     */
    public boolean isToolVisible(String toolName, String group) {
        if (ALWAYS_ON_TOOL.equals(toolName)) {
            return true; // lazy-reveal floor — discover_tools is never gated
        }
        if (toolName != null && disableTools.contains(toolName)) {
            return false; // per-tool disable wins over its group
        }
        if (toolName != null && enableTools.contains(toolName)) {
            return true; // per-tool enable wins over its group
        }
        if (allowlistMode) {
            return matchesGroupToken(enableGroups, group);
        }
        return !matchesGroupToken(disableGroups, group);
    }

    /**
     * Whether a group token from config matches a resolved group. A bare token
     * (e.g. {@code metadata}) matches every facet; a faceted token
     * (e.g. {@code metadata.write}) matches only that facet.
     */
    public static boolean matchesGroupToken(Set<String> tokens, String group) {
        if (tokens.isEmpty() || group == null) {
            return false;
        }
        return tokens.contains(group) || tokens.contains(ToolGroupTaxonomy.baseGroup(group));
    }

    private static Set<String> parse(String raw, boolean lowerCase) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        // Group tokens are matched against lower-case group names, so lower-case
        // them here; tool names keep their exact registered casing.
        return Arrays.stream(raw.split(",")) //$NON-NLS-1$
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> lowerCase ? s.toLowerCase(Locale.ROOT) : s)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static String resolve(String propKey, String envKey) {
        String prop = System.getProperty(propKey);
        if (prop != null && !prop.isBlank()) {
            return prop;
        }
        return System.getenv(envKey);
    }
}
