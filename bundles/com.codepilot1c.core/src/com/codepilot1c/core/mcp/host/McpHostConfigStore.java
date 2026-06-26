package com.codepilot1c.core.mcp.host;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;

import com.codepilot1c.core.internal.VibeCorePlugin;
import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.settings.SecureStorageUtil;
import com.codepilot1c.core.settings.VibePreferenceConstants;
import com.codepilot1c.core.settings.WorkspaceScope;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

/**
 * Preference-backed config store for MCP host.
 */
public class McpHostConfigStore {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(McpHostConfigStore.class);
    private static final String TOKEN_SECURE_KEY = "mcp.host.http.bearerToken"; //$NON-NLS-1$
    private static final Gson GSON = new Gson();
    private static final Type PROFILE_LIST_TYPE = new TypeToken<List<ProfileEndpoint>>() { }.getType();

    private static McpHostConfigStore instance;

    public static synchronized McpHostConfigStore getInstance() {
        if (instance == null) {
            instance = new McpHostConfigStore();
        }
        return instance;
    }

    public McpHostConfig load() {
        IEclipsePreferences prefs = InstanceScope.INSTANCE.getNode(VibeCorePlugin.PLUGIN_ID);
        McpHostConfig cfg = McpHostConfig.defaults();
        cfg.setEnabled(prefs.getBoolean(VibePreferenceConstants.PREF_MCP_HOST_ENABLED, cfg.isEnabled()));
        cfg.setHttpEnabled(prefs.getBoolean(VibePreferenceConstants.PREF_MCP_HOST_HTTP_ENABLED, cfg.isHttpEnabled()));
        cfg.setBindAddress(prefs.get(VibePreferenceConstants.PREF_MCP_HOST_HTTP_BIND_ADDRESS, cfg.getBindAddress()));
        cfg.setPort(prefs.getInt(VibePreferenceConstants.PREF_MCP_HOST_HTTP_PORT, cfg.getPort()));
        cfg.setAuthMode(McpHostConfig.AuthMode.from(
            prefs.get(VibePreferenceConstants.PREF_MCP_HOST_AUTH_MODE, cfg.getAuthMode().name())));
        cfg.setMutationPolicy(McpHostConfig.MutationPolicy.from(
            prefs.get(VibePreferenceConstants.PREF_MCP_HOST_POLICY_DEFAULT_MUTATION_DECISION, cfg.getMutationPolicy().name())));
        cfg.setExposedToolsFilter(prefs.get(
            VibePreferenceConstants.PREF_MCP_HOST_POLICY_EXPOSED_TOOLS,
            cfg.getExposedToolsFilter()));

        String token = SecureStorageUtil.retrieveWorkspaceSecurely(TOKEN_SECURE_KEY, ""); //$NON-NLS-1$
        if (token.isBlank()) {
            // Migrate a token from the previously shared default store so an existing setup
            // keeps the bearer token its MCP client is configured with. Try the workspace-scoped
            // key first (written by the prior key-scoping fix), then the legacy unscoped key.
            String legacy = SecureStorageUtil.retrieveSecurely(WorkspaceScope.scopedKey(TOKEN_SECURE_KEY), ""); //$NON-NLS-1$
            if (legacy.isBlank()) {
                legacy = SecureStorageUtil.retrieveSecurely(TOKEN_SECURE_KEY, ""); //$NON-NLS-1$
            }
            if (!legacy.isBlank()) {
                token = legacy;
                SecureStorageUtil.storeWorkspaceSecurely(TOKEN_SECURE_KEY, token);
            }
        }
        if (token.isBlank()) {
            token = McpHostConfig.generateToken();
            SecureStorageUtil.storeWorkspaceSecurely(TOKEN_SECURE_KEY, token);
        }
        cfg.setBearerToken(token);

        cfg.setProfiles(loadOrMigrateProfiles(prefs, cfg));

        applySystemOverrides(cfg);
        return cfg;
    }

    /**
     * Load the JSON profile list. On the first run (no profiles + never seeded),
     * migrate the legacy single endpoint into an enabled {@code full} profile and
     * seed the disabled starter set, persisting the result so it survives.
     */
    private List<ProfileEndpoint> loadOrMigrateProfiles(IEclipsePreferences prefs, McpHostConfig cfg) {
        String json = prefs.get(VibePreferenceConstants.PREF_MCP_HOST_PROFILES, ""); //$NON-NLS-1$
        boolean seeded = prefs.getBoolean(VibePreferenceConstants.PREF_MCP_HOST_PROFILES_SEEDED, false);

        List<ProfileEndpoint> profiles = parseProfiles(json);
        if (!profiles.isEmpty()) {
            return profiles;
        }
        if (seeded) {
            // Operator intentionally cleared all profiles — respect the empty list.
            return new ArrayList<>();
        }

        // First run: migrate the legacy single endpoint + seed the starter set.
        List<ProfileEndpoint> migrated = new ArrayList<>();
        migrated.add(ProfileEndpoint.fullDefault(cfg.getPort(), cfg.getBearerToken(), cfg.getExposedToolsFilter()));
        migrated.addAll(ProfileEndpoint.seededDefaults(cfg.getPort()));
        persistProfiles(prefs, migrated);
        try {
            prefs.flush();
        } catch (Exception e) {
            LOG.error("Failed to flush migrated MCP host profiles", e); //$NON-NLS-1$
        }
        LOG.info("Migrated legacy MCP endpoint -> 'full' profile on port %d and seeded %d disabled starter profiles", //$NON-NLS-1$
                Integer.valueOf(cfg.getPort()), Integer.valueOf(migrated.size() - 1));
        return migrated;
    }

    private List<ProfileEndpoint> parseProfiles(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<ProfileEndpoint> parsed = GSON.fromJson(json, PROFILE_LIST_TYPE);
            return parsed != null ? parsed : new ArrayList<>();
        } catch (RuntimeException e) {
            LOG.error("Failed to parse MCP host profiles JSON; treating as empty", e); //$NON-NLS-1$
            return new ArrayList<>();
        }
    }

    private void applySystemOverrides(McpHostConfig cfg) {
        String enabled = coalesce(
                System.getProperty("codepilot.mcp.host.enabled"), //$NON-NLS-1$
                System.getProperty("codepilot.mcp.enabled")); //$NON-NLS-1$
        if (enabled != null) {
            cfg.setEnabled(Boolean.parseBoolean(enabled.trim()));
        }

        String httpEnabled = System.getProperty("codepilot.mcp.host.http.enabled"); //$NON-NLS-1$
        if (httpEnabled != null) {
            cfg.setHttpEnabled(Boolean.parseBoolean(httpEnabled.trim()));
        }

        String bind = System.getProperty("codepilot.mcp.host.http.bindAddress"); //$NON-NLS-1$
        if (bind != null && !bind.isBlank()) {
            cfg.setBindAddress(bind.trim());
        }

        String port = System.getProperty("codepilot.mcp.host.http.port"); //$NON-NLS-1$
        if (port != null && !port.isBlank()) {
            try {
                cfg.setPort(Integer.parseInt(port.trim()));
            } catch (NumberFormatException ignored) {
                // keep preference value
            }
        }

        String authMode = System.getProperty("codepilot.mcp.host.auth.mode"); //$NON-NLS-1$
        if (authMode != null && !authMode.isBlank()) {
            cfg.setAuthMode(McpHostConfig.AuthMode.from(authMode));
        }

        String mutationPolicy = System.getProperty("codepilot.mcp.host.policy.defaultMutationDecision"); //$NON-NLS-1$
        if (mutationPolicy != null && !mutationPolicy.isBlank()) {
            cfg.setMutationPolicy(McpHostConfig.MutationPolicy.from(mutationPolicy));
        }

        String exposedTools = System.getProperty("codepilot.mcp.host.policy.exposedTools"); //$NON-NLS-1$
        if (exposedTools != null && !exposedTools.isBlank()) {
            cfg.setExposedToolsFilter(exposedTools.trim());
        }

        String bearer = System.getProperty("codepilot.mcp.host.http.bearerToken"); //$NON-NLS-1$
        if (bearer != null && !bearer.isBlank()) {
            cfg.setBearerToken(bearer.trim());
        }

        // CODEPILOT1C_PROFILE (env) / -Dcodepilot.mcp.host.profile selects/forces a
        // single profile context for this instance — the manager brings up only it.
        String selectedProfile = coalesce(
                System.getProperty("codepilot.mcp.host.profile"), //$NON-NLS-1$
                System.getenv("CODEPILOT1C_PROFILE")); //$NON-NLS-1$
        if (selectedProfile != null && !selectedProfile.isBlank()) {
            cfg.setSelectedProfileName(selectedProfile.trim());
        }
    }

    private String coalesce(String primary, String secondary) {
        return primary != null ? primary : secondary;
    }

    public void save(McpHostConfig cfg) {
        IEclipsePreferences prefs = InstanceScope.INSTANCE.getNode(VibeCorePlugin.PLUGIN_ID);
        prefs.putBoolean(VibePreferenceConstants.PREF_MCP_HOST_ENABLED, cfg.isEnabled());
        prefs.putBoolean(VibePreferenceConstants.PREF_MCP_HOST_HTTP_ENABLED, cfg.isHttpEnabled());
        prefs.put(VibePreferenceConstants.PREF_MCP_HOST_HTTP_BIND_ADDRESS, cfg.getBindAddress());
        prefs.putInt(VibePreferenceConstants.PREF_MCP_HOST_HTTP_PORT, cfg.getPort());
        prefs.put(VibePreferenceConstants.PREF_MCP_HOST_AUTH_MODE, cfg.getAuthMode().name());
        prefs.put(VibePreferenceConstants.PREF_MCP_HOST_POLICY_DEFAULT_MUTATION_DECISION, cfg.getMutationPolicy().name());
        prefs.put(VibePreferenceConstants.PREF_MCP_HOST_POLICY_EXPOSED_TOOLS, cfg.getExposedToolsFilter());
        SecureStorageUtil.storeWorkspaceSecurely(TOKEN_SECURE_KEY, cfg.getBearerToken());

        if (cfg.getProfiles() != null && !cfg.getProfiles().isEmpty()) {
            // Caller carries an explicit profile list (Phase-2 endpoints UI / programmatic).
            persistProfiles(prefs, cfg.getProfiles());
        } else {
            // Legacy single-endpoint save path (current preference page): fold the
            // legacy port/token/name-filter into the stored 'full' profile so the
            // page keeps driving the default endpoint until the Phase-2 UI lands.
            // Never clobbers the other profiles.
            syncLegacyIntoFullProfile(prefs, cfg);
        }

        try {
            prefs.flush();
        } catch (Exception e) {
            LOG.error("Failed to save MCP host preferences", e); //$NON-NLS-1$
        }
    }

    /**
     * Persist the profile list explicitly (Phase-2 UI / programmatic edits).
     * Always writes — including an empty list (records the intentional clear via
     * the seeded marker so the starter set is not re-added on the next load).
     */
    public void saveProfiles(List<ProfileEndpoint> profiles) {
        IEclipsePreferences prefs = InstanceScope.INSTANCE.getNode(VibeCorePlugin.PLUGIN_ID);
        persistProfiles(prefs, profiles != null ? profiles : new ArrayList<>());
        try {
            prefs.flush();
        } catch (Exception e) {
            LOG.error("Failed to save MCP host profiles", e); //$NON-NLS-1$
        }
    }

    /**
     * Fold the legacy single-endpoint fields into the stored {@code full} profile
     * (creating it if absent), leaving every other profile untouched. This is the
     * Phase-1 bridge that keeps the existing single-port preference page driving
     * the default endpoint until the Phase-2 endpoints table replaces it.
     */
    private void syncLegacyIntoFullProfile(IEclipsePreferences prefs, McpHostConfig cfg) {
        List<ProfileEndpoint> profiles = parseProfiles(prefs.get(VibePreferenceConstants.PREF_MCP_HOST_PROFILES, "")); //$NON-NLS-1$
        ProfileEndpoint full = profiles.stream()
                .filter(p -> "full".equals(p.getName())) //$NON-NLS-1$
                .findFirst()
                .orElse(null);
        if (full == null) {
            full = ProfileEndpoint.fullDefault(cfg.getPort(), cfg.getBearerToken(), cfg.getExposedToolsFilter());
            profiles.add(0, full);
        } else {
            full.setPort(cfg.getPort());
            full.setBearerToken(cfg.getBearerToken());
            full.setExposedToolsFilter(cfg.getExposedToolsFilter());
            full.setEnabled(true);
        }
        persistProfiles(prefs, profiles);
    }

    private void persistProfiles(IEclipsePreferences prefs, List<ProfileEndpoint> profiles) {
        prefs.put(VibePreferenceConstants.PREF_MCP_HOST_PROFILES, GSON.toJson(profiles, PROFILE_LIST_TYPE));
        prefs.putBoolean(VibePreferenceConstants.PREF_MCP_HOST_PROFILES_SEEDED, true);
    }
}
