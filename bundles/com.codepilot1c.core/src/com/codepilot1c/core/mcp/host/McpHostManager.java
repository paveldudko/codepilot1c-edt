package com.codepilot1c.core.mcp.host;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.tools.surface.ToolGroupVisibility;

/**
 * Singleton manager for MCP host lifecycle. One EDT instance can bring up several
 * MCP endpoints concurrently — one {@link McpHostServer} per enabled
 * {@link ProfileEndpoint} (its own port + token + tool set).
 */
public class McpHostManager {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(McpHostManager.class);

    private static McpHostManager instance;

    private final List<IMcpHostServer> servers = new ArrayList<>();

    /**
     * Content signature of the config the running servers were last (re)started
     * from. A watcher compares this against {@link #signatureOf(McpHostConfig)} of
     * the on-disk shared profiles to tell a real external/cross-instance edit apart
     * from this instance's own save (which restarts and so keeps the two in sync).
     */
    private volatile String lastStartedSignature = ""; //$NON-NLS-1$

    public static synchronized McpHostManager getInstance() {
        if (instance == null) {
            instance = new McpHostManager();
        }
        return instance;
    }

    public synchronized void startIfEnabled() {
        McpHostConfig cfg = McpHostConfigStore.getInstance().load();
        // Record the config we are about to bring up (even when disabled, so a later
        // "was disabled, now enabled" file edit is detected as drift).
        lastStartedSignature = signatureOf(cfg);
        if (!cfg.isEnabled()) {
            LOG.info("MCP host is disabled by preference"); //$NON-NLS-1$
            return;
        }

        List<ProfileEndpoint> toStart = selectProfilesToStart(cfg);
        if (toStart.isEmpty()) {
            LOG.warn("MCP host enabled but no endpoints to start (no enabled profiles)"); //$NON-NLS-1$
            return;
        }

        Set<Integer> usedPorts = new HashSet<>();
        for (ProfileEndpoint profile : toStart) {
            if (!usedPorts.add(Integer.valueOf(profile.getPort()))) {
                LOG.warn("Skipping MCP endpoint '%s' — port %d already taken by another enabled profile", //$NON-NLS-1$
                        profile.getName(), Integer.valueOf(profile.getPort()));
                continue;
            }
            try {
                McpHostServer server = new McpHostServer(cfg, profile);
                server.start();
                servers.add(server);
            } catch (RuntimeException e) {
                LOG.error("Failed to start MCP endpoint '" + profile.getName() //$NON-NLS-1$
                        + "' on port " + profile.getPort(), e); //$NON-NLS-1$
            }
        }
    }

    /** Launch-time port override for the {@code CODEPILOT1C_PROFILE}-forced endpoint. */
    public static final String ENV_PORT = "CODEPILOT1C_PORT"; //$NON-NLS-1$
    /** System-property twin of {@link #ENV_PORT} (takes precedence; handy in an .ini). */
    public static final String SYSPROP_PORT = "codepilot.mcp.host.profile.port"; //$NON-NLS-1$

    /**
     * The profiles to bring up: {@code CODEPILOT1C_PROFILE} forces a single named
     * one (regardless of its enabled flag); otherwise every enabled profile.
     *
     * <p>The forced endpoint's port may be overridden per launch via
     * {@code -Dcodepilot.mcp.host.profile.port} / env {@code CODEPILOT1C_PORT}, so a
     * stack's start script fully describes its identity (profile + port + stack id +
     * lease dir) with no per-workspace GUI step. The override is EPHEMERAL by design —
     * never written back to the instance's port map, so dropping the variable returns
     * the profile to its stored port. Without a forced profile the target endpoint
     * would be ambiguous, so the override is ignored (with a warning).</p>
     */
    private List<ProfileEndpoint> selectProfilesToStart(McpHostConfig cfg) {
        List<ProfileEndpoint> profiles = cfg.getProfiles();
        if (profiles == null || profiles.isEmpty()) {
            return List.of();
        }
        String portOverride = System.getProperty(SYSPROP_PORT);
        if (portOverride == null || portOverride.isBlank()) {
            portOverride = System.getenv(ENV_PORT);
        }
        String selected = cfg.getSelectedProfileName();
        if (selected != null && !selected.isBlank()) {
            ProfileEndpoint match = profiles.stream()
                    .filter(p -> selected.equals(p.getName()))
                    .findFirst()
                    .orElse(null);
            if (match != null) {
                LOG.info("CODEPILOT1C_PROFILE=%s — forcing only that endpoint", selected); //$NON-NLS-1$
                return List.of(applyPortOverride(match, portOverride));
            }
            LOG.warn("CODEPILOT1C_PROFILE=%s not found among %d profiles; starting enabled profiles instead", //$NON-NLS-1$
                    selected, Integer.valueOf(profiles.size()));
        }
        if (portOverride != null && !portOverride.isBlank()) {
            LOG.warn("%s is set but no single endpoint is forced via CODEPILOT1C_PROFILE — ignoring the port override", //$NON-NLS-1$
                    ENV_PORT);
        }
        return profiles.stream().filter(ProfileEndpoint::isEnabled).toList();
    }

    /**
     * Applies a raw port-override value to an endpoint: a valid port yields a COPY of the
     * endpoint with that port (the original stays untouched — it mirrors the persisted
     * config); a missing/invalid value keeps the endpoint as is, with a warning, so a
     * typo in the start script degrades to the stored port instead of a dead stack.
     */
    static ProfileEndpoint applyPortOverride(ProfileEndpoint profile, String rawPort) {
        if (profile == null || rawPort == null || rawPort.isBlank()) {
            return profile;
        }
        int port;
        try {
            port = Integer.parseInt(rawPort.trim());
        } catch (NumberFormatException e) {
            LOG.warn("Ignoring invalid %s value '%s' — endpoint '%s' keeps port %d", //$NON-NLS-1$
                    ENV_PORT, rawPort, profile.getName(), Integer.valueOf(profile.getPort()));
            return profile;
        }
        if (port < 1 || port > 65535) {
            LOG.warn("Ignoring out-of-range %s value %d — endpoint '%s' keeps port %d", //$NON-NLS-1$
                    ENV_PORT, Integer.valueOf(port), profile.getName(), Integer.valueOf(profile.getPort()));
            return profile;
        }
        if (port == profile.getPort()) {
            return profile;
        }
        LOG.info("Port override for endpoint '%s': %d -> %d (launch-time only, not persisted)", //$NON-NLS-1$
                profile.getName(), Integer.valueOf(profile.getPort()), Integer.valueOf(port));
        return SharedProfile.fromEndpoint(profile).toEndpoint(port);
    }

    public synchronized void restart() {
        stopAll();
        startIfEnabled();
    }

    public synchronized void stopAll() {
        for (IMcpHostServer server : servers) {
            try {
                server.stop();
            } catch (RuntimeException e) {
                LOG.error("Failed to stop an MCP endpoint", e); //$NON-NLS-1$
            }
        }
        servers.clear();
    }

    public synchronized boolean isRunning() {
        return servers.stream().anyMatch(IMcpHostServer::isRunning);
    }

    public synchronized List<IMcpHostServer> getServers() {
        return List.copyOf(servers);
    }

    /** First running endpoint (or {@code null}) — back-compat for single-endpoint callers. */
    public synchronized IMcpHostServer getServer() {
        return servers.isEmpty() ? null : servers.get(0);
    }

    /**
     * Signature of the config the running servers were last started from. A
     * profiles-file watcher compares this with {@link #signatureOf(McpHostConfig)}
     * of a fresh {@code load()} to decide whether the shared file drifted from what
     * is actually live (and thus whether to offer a reload).
     */
    public String getLastStartedSignature() {
        return lastStartedSignature;
    }

    /**
     * Stable, content-based signature of a host config's profile set, plus the
     * host enabled flag, selected-profile override, and the env tool-surface
     * override state — everything that changes the served tool surface. Two configs
     * with the same signature bring up the same endpoints with the same tools.
     */
    public static String signatureOf(McpHostConfig cfg) {
        if (cfg == null) {
            return ""; //$NON-NLS-1$
        }
        StringBuilder sb = new StringBuilder();
        sb.append(cfg.isEnabled()).append('|');
        sb.append(ToolGroupVisibility.isEnvironmentConfigured()).append('|');
        sb.append(nz(cfg.getSelectedProfileName())).append('\n');
        List<ProfileEndpoint> profiles = cfg.getProfiles();
        if (profiles != null) {
            for (ProfileEndpoint p : profiles) {
                if (p == null) {
                    continue;
                }
                sb.append(nz(p.getName())).append('\u0001')
                  .append(p.isEnabled()).append('\u0001')
                  .append(p.getPort()).append('\u0001')
                  .append(nz(p.getBearerToken())).append('\u0001')
                  .append(nz(p.getEnableGroups())).append('\u0001')
                  .append(nz(p.getEnableTools())).append('\u0001')
                  .append(nz(p.getDisableGroups())).append('\u0001')
                  .append(nz(p.getDisableTools())).append('\u0001')
                  .append(nz(p.getExposedToolsFilter())).append('\u0002');
            }
        }
        return sb.toString();
    }

    private static String nz(String s) {
        return s == null ? "" : s; //$NON-NLS-1$
    }
}
