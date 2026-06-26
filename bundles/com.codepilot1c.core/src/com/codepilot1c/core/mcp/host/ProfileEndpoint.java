/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.mcp.host;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.codepilot1c.core.tools.surface.ToolGroupVisibility;

/**
 * A named MCP endpoint ("profile"). One EDT + one plugin instance can bring up
 * several of these concurrently — each binds its own TCP port with its own
 * bearer token and announces only the tools its role needs. The tool set reuses
 * the {@link ToolGroupVisibility} facet vocabulary (enable/disable groups +
 * per-tool overrides).
 *
 * <p>Profiles collapse "profile definition" and "endpoint binding" into one
 * concept: name + port + token + tool-set + enabled. Host-level settings
 * (bind address, auth mode, mutation policy) stay shared on {@link McpHostConfig}.</p>
 *
 * <p>Persisted as JSON in {@code InstanceScope} preferences (see
 * {@link McpHostConfigStore}). Backs the multi-endpoint tool-profiles feature.</p>
 */
public class ProfileEndpoint {

    private String name;
    private int port;
    private String bearerToken;
    private boolean enabled;

    /** Tool-set facets — same vocabulary as {@link ToolGroupVisibility}. */
    private String enableGroups;
    private String enableTools;
    private String disableGroups;
    private String disableTools;

    /** Name allow/deny filter ({@code *}, {@code -toolName}); default {@code *}. */
    private String exposedToolsFilter;

    public ProfileEndpoint() {
        // Gson / bean
    }

    public ProfileEndpoint(String name, int port, String bearerToken, boolean enabled) {
        this.name = name;
        this.port = port;
        this.bearerToken = bearerToken;
        this.enabled = enabled;
        this.exposedToolsFilter = "*"; //$NON-NLS-1$
    }

    /** Build the per-profile announce/execute gate from this profile's facets. */
    public ToolGroupVisibility toGroupVisibility() {
        return ToolGroupVisibility.of(disableGroups, disableTools, enableGroups, enableTools);
    }

    /** {@code true} when this profile announces everything (no group/name filter). */
    public boolean announcesEverything() {
        return !toGroupVisibility().isActive() && "*".equals(getExposedToolsFilter()); //$NON-NLS-1$
    }

    /**
     * Compact, language-neutral one-line summary of the tool set for the UI table:
     * {@code *} = everything, otherwise the enabled groups (or {@code all except …}
     * in denylist mode) plus any per-tool {@code +enable}/{@code -disable} overrides
     * and a trailing {@code [name-filter]} when one is set.
     */
    public String toolsSummary() {
        if (announcesEverything()) {
            return "*"; //$NON-NLS-1$
        }
        StringBuilder sb = new StringBuilder();
        if (enableGroups != null && !enableGroups.isBlank()) {
            sb.append(enableGroups.trim());
        } else if (disableGroups != null && !disableGroups.isBlank()) {
            sb.append("all except ").append(disableGroups.trim()); //$NON-NLS-1$
        } else {
            sb.append('*');
        }
        if (enableTools != null && !enableTools.isBlank()) {
            sb.append(" +").append(enableTools.trim()); //$NON-NLS-1$
        }
        if (disableTools != null && !disableTools.isBlank()) {
            sb.append(" -").append(disableTools.trim()); //$NON-NLS-1$
        }
        if (!"*".equals(getExposedToolsFilter())) { //$NON-NLS-1$
            sb.append(" [").append(getExposedToolsFilter()).append(']'); //$NON-NLS-1$
        }
        return sb.toString();
    }

    /**
     * Suggest a name not already taken (for add/duplicate): {@code base},
     * {@code base-2}, {@code base-3}, … Case-insensitive against {@code existing}.
     */
    public static String suggestUniqueName(String base, Collection<String> existing) {
        String root = (base == null || base.isBlank()) ? "endpoint" : base.trim(); //$NON-NLS-1$
        Set<String> taken = new HashSet<>();
        if (existing != null) {
            for (String n : existing) {
                if (n != null) {
                    taken.add(n.toLowerCase(java.util.Locale.ROOT));
                }
            }
        }
        if (!taken.contains(root.toLowerCase(java.util.Locale.ROOT))) {
            return root;
        }
        for (int i = 2; i < 10_000; i++) {
            String candidate = root + "-" + i; //$NON-NLS-1$
            if (!taken.contains(candidate.toLowerCase(java.util.Locale.ROOT))) {
                return candidate;
            }
        }
        return root + "-x"; //$NON-NLS-1$
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getBearerToken() {
        return bearerToken;
    }

    public void setBearerToken(String bearerToken) {
        this.bearerToken = bearerToken;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getEnableGroups() {
        return enableGroups;
    }

    public void setEnableGroups(String enableGroups) {
        this.enableGroups = enableGroups;
    }

    public String getEnableTools() {
        return enableTools;
    }

    public void setEnableTools(String enableTools) {
        this.enableTools = enableTools;
    }

    public String getDisableGroups() {
        return disableGroups;
    }

    public void setDisableGroups(String disableGroups) {
        this.disableGroups = disableGroups;
    }

    public String getDisableTools() {
        return disableTools;
    }

    public void setDisableTools(String disableTools) {
        this.disableTools = disableTools;
    }

    public String getExposedToolsFilter() {
        return exposedToolsFilter == null || exposedToolsFilter.isBlank()
                ? "*" //$NON-NLS-1$
                : exposedToolsFilter;
    }

    public void setExposedToolsFilter(String exposedToolsFilter) {
        this.exposedToolsFilter = exposedToolsFilter;
    }

    /** Defensive deep copy (profiles are mutable POJOs edited by the UI). */
    public ProfileEndpoint copy() {
        ProfileEndpoint c = new ProfileEndpoint(name, port, bearerToken, enabled);
        c.enableGroups = enableGroups;
        c.enableTools = enableTools;
        c.disableGroups = disableGroups;
        c.disableTools = disableTools;
        c.exposedToolsFilter = exposedToolsFilter;
        return c;
    }

    /**
     * The default endpoint: everything announced (empty filter). Carries the
     * migrated legacy port/token/name-filter so an existing client keeps working.
     */
    public static ProfileEndpoint fullDefault(int port, String bearerToken, String exposedToolsFilter) {
        ProfileEndpoint p = new ProfileEndpoint("full", port, bearerToken, true); //$NON-NLS-1$
        p.exposedToolsFilter = (exposedToolsFilter == null || exposedToolsFilter.isBlank())
                ? "*" //$NON-NLS-1$
                : exposedToolsFilter;
        return p;
    }

    /**
     * The seeded starter set (all DISABLED with suggested ports — the operator
     * enables + assigns ports per instance). Tool sets per the agreed design.
     */
    public static List<ProfileEndpoint> seededDefaults(int basePort) {
        List<ProfileEndpoint> seeds = new ArrayList<>();

        ProfileEndpoint orchestrator = new ProfileEndpoint(
                "orchestrator", basePort + 1, McpHostConfig.generateToken(), false); //$NON-NLS-1$
        orchestrator.enableGroups = "diagnostics,bsl,metadata.read,forms.read,files.read,meta"; //$NON-NLS-1$
        seeds.add(orchestrator);

        ProfileEndpoint dev = new ProfileEndpoint(
                "dev", basePort + 2, McpHostConfig.generateToken(), false); //$NON-NLS-1$
        dev.enableGroups = "diagnostics,bsl,metadata,forms,dcs,extensions,files,workspace.read,meta"; //$NON-NLS-1$
        dev.disableTools = "connect_infobase"; //$NON-NLS-1$
        seeds.add(dev);

        ProfileEndpoint qa = new ProfileEndpoint(
                "qa", basePort + 3, McpHostConfig.generateToken(), false); //$NON-NLS-1$
        qa.enableGroups = "diagnostics,qa,forms.read,files,workspace.read,meta"; //$NON-NLS-1$
        seeds.add(qa);

        ProfileEndpoint infra = new ProfileEndpoint(
                "infra", basePort + 4, McpHostConfig.generateToken(), false); //$NON-NLS-1$
        infra.enableGroups = "diagnostics,workspace,files,meta"; //$NON-NLS-1$
        seeds.add(infra);

        return seeds;
    }
}
