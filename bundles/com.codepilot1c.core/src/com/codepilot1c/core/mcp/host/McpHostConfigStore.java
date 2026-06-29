package com.codepilot1c.core.mcp.host;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * Preference-backed config store for the MCP host.
 *
 * <p>Storage is split: the profile <em>set</em> — names, tool sets, bearer tokens,
 * enabled flags — is machine-shared via {@link SharedProfileStore} (a per-user
 * file), so every plugin instance/EDT installation of the same OS user sees the
 * same profiles. Only each instance's <em>port</em> assignment per profile is kept
 * locally in {@code InstanceScope} ({@link VibePreferenceConstants#PREF_MCP_HOST_PROFILE_PORTS}),
 * alongside the host-level settings (enabled/HTTP/bind/auth/mutation), which stay
 * per-instance.</p>
 */
public class McpHostConfigStore {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(McpHostConfigStore.class);
    private static final String TOKEN_SECURE_KEY = "mcp.host.http.bearerToken"; //$NON-NLS-1$
    private static final Gson GSON = new Gson();
    private static final Type PROFILE_LIST_TYPE = new TypeToken<List<ProfileEndpoint>>() { }.getType();
    private static final Type PORT_MAP_TYPE = new TypeToken<Map<String, Integer>>() { }.getType();

    private static McpHostConfigStore instance;

    public static synchronized McpHostConfigStore getInstance() {
        if (instance == null) {
            instance = new McpHostConfigStore();
        }
        return instance;
    }

    private SharedProfileStore sharedStore() {
        return SharedProfileStore.getDefault();
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

        cfg.setProfiles(loadProfiles(prefs, cfg));

        applySystemOverrides(cfg);
        return cfg;
    }

    /**
     * Resolve the runtime profile list = machine-shared definitions merged with this
     * instance's local port assignments. On the very first run (no shared file yet)
     * the legacy per-instance profiles are migrated into the shared file (or, on a
     * clean install, the starter set is seeded), and the corresponding ports recorded
     * locally.
     */
    private List<ProfileEndpoint> loadProfiles(IEclipsePreferences prefs, McpHostConfig cfg) {
        SharedProfileStore shared = sharedStore();
        List<SharedProfile> defs = shared.load();
        Map<String, Integer> portMap = loadPortMap(prefs);

        if (defs == null) {
            // No shared file yet — first run of any instance after the upgrade.
            MigratedSeed seed = buildInitialSeed(prefs, cfg);
            shared.save(seed.profiles);
            persistPortMap(prefs, seed.ports);
            LOG.info("Seeded machine-shared MCP profiles file at %s (%d profiles)", //$NON-NLS-1$
                    shared.getFile(), Integer.valueOf(seed.profiles.size()));
            defs = seed.profiles;
            portMap = seed.ports;
        }

        List<ProfileEndpoint> legacy = legacyProfiles(prefs);
        List<ProfileEndpoint> endpoints = new ArrayList<>();
        Map<String, Integer> resolvedPorts = new LinkedHashMap<>();
        boolean portsChanged = false;
        int basePort = cfg.getPort();
        int index = 0;
        for (SharedProfile def : defs) {
            String name = def.getName();
            Integer port = portMap.get(name);
            if (port == null) {
                port = Integer.valueOf(resolvePort(name, index, basePort, legacy, resolvedPorts.values()));
                portsChanged = true;
            }
            resolvedPorts.put(name, port);
            endpoints.add(def.toEndpoint(port.intValue()));
            index++;
        }
        if (portsChanged || resolvedPorts.size() != portMap.size()) {
            persistPortMap(prefs, resolvedPorts);
        }
        return endpoints;
    }

    /** Decide a port for a profile missing from the local map: legacy carry-over, else first free. */
    private int resolvePort(String name, int index, int basePort,
            List<ProfileEndpoint> legacy, java.util.Collection<Integer> used) {
        for (ProfileEndpoint p : legacy) {
            if (name != null && name.equals(p.getName()) && p.getPort() > 0 && !used.contains(Integer.valueOf(p.getPort()))) {
                return p.getPort(); // preserve this instance's previous port for the profile
            }
        }
        int candidate = basePort + index;
        while (used.contains(Integer.valueOf(candidate)) || candidate < 1 || candidate > 65535) {
            candidate++;
        }
        return candidate;
    }

    private MigratedSeed buildInitialSeed(IEclipsePreferences prefs, McpHostConfig cfg) {
        List<ProfileEndpoint> legacy = legacyProfiles(prefs);
        List<ProfileEndpoint> source;
        if (!legacy.isEmpty()) {
            source = legacy; // migrate this instance's existing endpoints into the shared file
        } else {
            source = new ArrayList<>();
            source.add(ProfileEndpoint.fullDefault(cfg.getPort(), cfg.getBearerToken(), cfg.getExposedToolsFilter()));
            source.addAll(ProfileEndpoint.seededDefaults(cfg.getPort()));
        }
        MigratedSeed seed = new MigratedSeed();
        for (ProfileEndpoint p : source) {
            seed.profiles.add(SharedProfile.fromEndpoint(p));
            seed.ports.put(p.getName(), Integer.valueOf(p.getPort()));
        }
        return seed;
    }

    private static final class MigratedSeed {
        final List<SharedProfile> profiles = new ArrayList<>();
        final Map<String, Integer> ports = new LinkedHashMap<>();
    }

    private List<ProfileEndpoint> legacyProfiles(IEclipsePreferences prefs) {
        return parseProfiles(prefs.get(VibePreferenceConstants.PREF_MCP_HOST_PROFILES, "")); //$NON-NLS-1$
    }

    private List<ProfileEndpoint> parseProfiles(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<ProfileEndpoint> parsed = GSON.fromJson(json, PROFILE_LIST_TYPE);
            return parsed != null ? parsed : new ArrayList<>();
        } catch (RuntimeException e) {
            LOG.error("Failed to parse legacy MCP host profiles JSON; treating as empty", e); //$NON-NLS-1$
            return new ArrayList<>();
        }
    }

    private Map<String, Integer> loadPortMap(IEclipsePreferences prefs) {
        String json = prefs.get(VibePreferenceConstants.PREF_MCP_HOST_PROFILE_PORTS, ""); //$NON-NLS-1$
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Integer> parsed = GSON.fromJson(json, PORT_MAP_TYPE);
            return parsed != null ? new LinkedHashMap<>(parsed) : new LinkedHashMap<>();
        } catch (RuntimeException e) {
            LOG.error("Failed to parse MCP profile port map; treating as empty", e); //$NON-NLS-1$
            return new LinkedHashMap<>();
        }
    }

    private void persistPortMap(IEclipsePreferences prefs, Map<String, Integer> ports) {
        prefs.put(VibePreferenceConstants.PREF_MCP_HOST_PROFILE_PORTS, GSON.toJson(ports, PORT_MAP_TYPE));
        try {
            prefs.flush();
        } catch (Exception e) {
            LOG.error("Failed to flush MCP profile port map", e); //$NON-NLS-1$
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

        // Profiles (when carried) split into the machine-shared set + this instance's
        // port map. An empty list here is a no-op for profiles (host-level only save);
        // use saveProfiles() to intentionally clear the shared set.
        if (cfg.getProfiles() != null && !cfg.getProfiles().isEmpty()) {
            persistSplitProfiles(prefs, cfg.getProfiles());
        }

        try {
            prefs.flush();
        } catch (Exception e) {
            LOG.error("Failed to save MCP host preferences", e); //$NON-NLS-1$
        }
    }

    /**
     * Persist an explicit profile list (the endpoints UI / programmatic edits):
     * the shared definitions go to the per-user file, the ports to this instance's
     * local map. An empty list clears the shared file.
     */
    public void saveProfiles(List<ProfileEndpoint> profiles) {
        IEclipsePreferences prefs = InstanceScope.INSTANCE.getNode(VibeCorePlugin.PLUGIN_ID);
        persistSplitProfiles(prefs, profiles != null ? profiles : new ArrayList<>());
        try {
            prefs.flush();
        } catch (Exception e) {
            LOG.error("Failed to save MCP host profiles", e); //$NON-NLS-1$
        }
    }

    private void persistSplitProfiles(IEclipsePreferences prefs, List<ProfileEndpoint> profiles) {
        List<SharedProfile> shared = new ArrayList<>();
        Map<String, Integer> ports = new LinkedHashMap<>();
        for (ProfileEndpoint p : profiles) {
            shared.add(SharedProfile.fromEndpoint(p));
            ports.put(p.getName(), Integer.valueOf(p.getPort()));
        }
        sharedStore().save(shared);
        prefs.put(VibePreferenceConstants.PREF_MCP_HOST_PROFILE_PORTS, GSON.toJson(ports, PORT_MAP_TYPE));
    }
}
