/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.internal;

import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;
import org.eclipse.core.runtime.preferences.DefaultScope;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;

import com.codepilot1c.core.mcp.host.McpHostConfig;
import com.codepilot1c.core.settings.VibePreferenceConstants;

/**
 * Initializes default preference values for 1C Copilot plugin.
 */
public class VibePreferenceInitializer extends AbstractPreferenceInitializer {

    @Override
    public void initializeDefaultPreferences() {
        IEclipsePreferences defaults = DefaultScope.INSTANCE.getNode(VibeCorePlugin.PLUGIN_ID);

        // General settings
        defaults.putInt(VibePreferenceConstants.PREF_REQUEST_TIMEOUT, 60);

        // QA / terminal utility settings
        defaults.put(VibePreferenceConstants.PREF_QA_VA_EPF_PATH, ""); //$NON-NLS-1$
        defaults.put(VibePreferenceConstants.PREF_TERMINAL_CWD_MODE, "project"); //$NON-NLS-1$
        defaults.putBoolean(VibePreferenceConstants.PREF_TERMINAL_NO_COLOR, false);
        defaults.put(VibePreferenceConstants.PREF_TERMINAL_TITLE_PREFIX, ""); //$NON-NLS-1$
        defaults.putBoolean(VibePreferenceConstants.PREF_TERMINAL_ALWAYS_USE_ACTIVE_PROJECT, false);

        // HTTP defaults (based on Workmate patterns)
        defaults.putBoolean(VibePreferenceConstants.PREF_HTTP_HTTP2_ENABLED, true);
        defaults.putBoolean(VibePreferenceConstants.PREF_HTTP_USE_SYSTEM_PROXY, true);
        defaults.putBoolean(VibePreferenceConstants.PREF_HTTP_GZIP_ENABLED, true);
        defaults.putInt(VibePreferenceConstants.PREF_HTTP_GZIP_MIN_BYTES, 1024); // Compress if > 1KB

        // MCP host defaults
        defaults.putBoolean(VibePreferenceConstants.PREF_MCP_HOST_ENABLED, true);
        defaults.putBoolean(VibePreferenceConstants.PREF_MCP_HOST_HTTP_ENABLED, true);
        defaults.put(VibePreferenceConstants.PREF_MCP_HOST_HTTP_BIND_ADDRESS, "127.0.0.1"); //$NON-NLS-1$
        defaults.putInt(VibePreferenceConstants.PREF_MCP_HOST_HTTP_PORT, 8765);
        defaults.put(VibePreferenceConstants.PREF_MCP_HOST_AUTH_MODE,
            McpHostConfig.AuthMode.OAUTH_OR_BEARER.name());
        defaults.put(VibePreferenceConstants.PREF_MCP_HOST_POLICY_DEFAULT_MUTATION_DECISION,
            McpHostConfig.MutationPolicy.ALLOW.name());
        defaults.put(VibePreferenceConstants.PREF_MCP_HOST_POLICY_EXPOSED_TOOLS, "*"); //$NON-NLS-1$

        // Diagnostics defaults
        defaults.putBoolean(VibePreferenceConstants.PREF_DIAGNOSTICS_VERBOSE, false);

        // Completion/review are not part of OSS edition; their preferences are not initialized here.
    }
}
