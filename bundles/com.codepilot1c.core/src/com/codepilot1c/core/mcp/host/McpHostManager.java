package com.codepilot1c.core.mcp.host;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.codepilot1c.core.logging.VibeLogger;

/**
 * Singleton manager for MCP host lifecycle. One EDT instance can bring up several
 * MCP endpoints concurrently — one {@link McpHostServer} per enabled
 * {@link ProfileEndpoint} (its own port + token + tool set).
 */
public class McpHostManager {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(McpHostManager.class);

    private static McpHostManager instance;

    private final List<IMcpHostServer> servers = new ArrayList<>();

    public static synchronized McpHostManager getInstance() {
        if (instance == null) {
            instance = new McpHostManager();
        }
        return instance;
    }

    public synchronized void startIfEnabled() {
        McpHostConfig cfg = McpHostConfigStore.getInstance().load();
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

    /**
     * The profiles to bring up: {@code CODEPILOT1C_PROFILE} forces a single named
     * one (regardless of its enabled flag); otherwise every enabled profile.
     */
    private List<ProfileEndpoint> selectProfilesToStart(McpHostConfig cfg) {
        List<ProfileEndpoint> profiles = cfg.getProfiles();
        if (profiles == null || profiles.isEmpty()) {
            return List.of();
        }
        String selected = cfg.getSelectedProfileName();
        if (selected != null && !selected.isBlank()) {
            ProfileEndpoint match = profiles.stream()
                    .filter(p -> selected.equals(p.getName()))
                    .findFirst()
                    .orElse(null);
            if (match != null) {
                LOG.info("CODEPILOT1C_PROFILE=%s — forcing only that endpoint", selected); //$NON-NLS-1$
                return List.of(match);
            }
            LOG.warn("CODEPILOT1C_PROFILE=%s not found among %d profiles; starting enabled profiles instead", //$NON-NLS-1$
                    selected, Integer.valueOf(profiles.size()));
        }
        return profiles.stream().filter(ProfileEndpoint::isEnabled).toList();
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
}
