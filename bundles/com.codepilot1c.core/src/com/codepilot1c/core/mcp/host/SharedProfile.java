/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.mcp.host;

/**
 * The machine-shared half of a {@link ProfileEndpoint}: everything except the
 * port. Persisted by {@link SharedProfileStore} to a per-user JSON file so the
 * tool set, token, enabled flag and name are common to every plugin instance,
 * while each instance keeps only its own port mapping (in {@code InstanceScope}).
 *
 * <p>Plain Gson bean — field names are the JSON keys; do not rename without a
 * migration.</p>
 */
public class SharedProfile {

    private String name;
    private boolean enabled;
    private String bearerToken;
    private String enableGroups;
    private String enableTools;
    private String disableGroups;
    private String disableTools;
    private String exposedToolsFilter;

    public SharedProfile() {
        // Gson / bean
    }

    /** Project the shared (port-less) half out of a runtime endpoint. */
    public static SharedProfile fromEndpoint(ProfileEndpoint p) {
        SharedProfile s = new SharedProfile();
        s.name = p.getName();
        s.enabled = p.isEnabled();
        s.bearerToken = p.getBearerToken();
        s.enableGroups = p.getEnableGroups();
        s.enableTools = p.getEnableTools();
        s.disableGroups = p.getDisableGroups();
        s.disableTools = p.getDisableTools();
        s.exposedToolsFilter = p.getExposedToolsFilter();
        return s;
    }

    /** Combine this shared half with an instance-local port into a runtime endpoint. */
    public ProfileEndpoint toEndpoint(int port) {
        ProfileEndpoint p = new ProfileEndpoint(name, port, bearerToken, enabled);
        p.setEnableGroups(enableGroups);
        p.setEnableTools(enableTools);
        p.setDisableGroups(disableGroups);
        p.setDisableTools(disableTools);
        p.setExposedToolsFilter(exposedToolsFilter);
        return p;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBearerToken() {
        return bearerToken;
    }

    public void setBearerToken(String bearerToken) {
        this.bearerToken = bearerToken;
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
        return exposedToolsFilter;
    }

    public void setExposedToolsFilter(String exposedToolsFilter) {
        this.exposedToolsFilter = exposedToolsFilter;
    }
}
