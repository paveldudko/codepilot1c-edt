package com.codepilot1c.core.mcp.host;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.codepilot1c.core.tools.ITool;
import com.codepilot1c.core.tools.ToolRegistry;
import com.codepilot1c.core.tools.surface.ToolGroupTaxonomy;
import com.codepilot1c.core.tools.surface.ToolGroupVisibility;

/**
 * Default tool exposure policy with wildcard and deny-by-name support, plus an
 * operator-configurable group/per-tool announce gate ({@link ToolGroupVisibility}).
 */
public class DefaultMcpToolExposurePolicy implements McpToolExposurePolicy {

    private final McpHostConfig config;
    private final Set<String> explicitAllow;
    private final Set<String> explicitDeny;
    private final ToolGroupVisibility groupVisibility;

    public DefaultMcpToolExposurePolicy(McpHostConfig config) {
        this(config, config.getExposedToolsFilter(), ToolGroupVisibility.fromEnvironment());
    }

    public DefaultMcpToolExposurePolicy(McpHostConfig config, ToolGroupVisibility groupVisibility) {
        this(config, config.getExposedToolsFilter(), groupVisibility);
    }

    /**
     * Per-profile policy: the name allow/deny filter comes from the profile, the
     * group/per-tool gate from the profile's {@link ToolGroupVisibility}, and the
     * mutation policy stays host-level (shared) via {@code config}.
     */
    public DefaultMcpToolExposurePolicy(McpHostConfig config, String exposedToolsFilter,
            ToolGroupVisibility groupVisibility) {
        this.config = config;
        this.explicitAllow = new HashSet<>();
        this.explicitDeny = new HashSet<>();
        this.groupVisibility = groupVisibility;
        parse(exposedToolsFilter);
    }

    public ToolGroupVisibility getGroupVisibility() {
        return groupVisibility;
    }

    private void parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        Arrays.stream(raw.split(",")) //$NON-NLS-1$
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .forEach(token -> {
                if ("*".equals(token)) { //$NON-NLS-1$
                    explicitAllow.add(token);
                } else if (token.startsWith("-")) { //$NON-NLS-1$
                    explicitDeny.add(token.substring(1));
                } else {
                    explicitAllow.add(token);
                }
            });
    }

    @Override
    public boolean isExposed(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return false;
        }
        if (explicitDeny.contains(toolName)) {
            return false;
        }
        boolean allowedByNameFilter = explicitAllow.contains("*") || explicitAllow.contains(toolName); //$NON-NLS-1$
        if (!allowedByNameFilter) {
            return false;
        }
        // Operator-configurable group/per-tool gate (env-driven). Applies to both
        // tools/list (announce) and tools/call (execution) — a hard ceiling.
        return groupVisibility.isToolVisible(toolName, resolveGroup(toolName));
    }

    private String resolveGroup(String toolName) {
        ITool tool = ToolRegistry.getInstance().getTool(toolName);
        return tool != null ? ToolGroupTaxonomy.groupOf(tool) : "dynamic"; //$NON-NLS-1$
    }

    @Override
    public boolean requiresConfirmation(String toolName, Map<String, Object> args) {
        ITool tool = ToolRegistry.getInstance().getTool(toolName);
        if (tool == null) {
            return true;
        }
        if (tool.requiresConfirmation()) {
            return true;
        }
        return isDestructive(toolName);
    }

    @Override
    public boolean isDestructive(String toolName) {
        ITool tool = ToolRegistry.getInstance().getTool(toolName);
        if (tool == null) {
            return true;
        }
        if (tool.isDestructive()) {
            return true;
        }
        return switch (config.getMutationPolicy()) {
            case DENY, ASK -> true;
            case ALLOW -> false;
        };
    }

    public Set<String> getExplicitAllow() {
        return Collections.unmodifiableSet(explicitAllow);
    }

    public Set<String> getExplicitDeny() {
        return Collections.unmodifiableSet(explicitDeny);
    }
}
