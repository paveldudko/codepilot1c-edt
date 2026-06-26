package com.codepilot1c.core.mcp.host;

import java.util.List;
import java.util.Map;

import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.tools.ITool;
import com.codepilot1c.core.tools.ToolRegistry;
import com.codepilot1c.core.tools.surface.ToolGroupVisibility;
import com.codepilot1c.core.mcp.host.prompt.PromptTemplateProvider;
import com.codepilot1c.core.mcp.host.resource.DiagnosticsResourceProvider;
import com.codepilot1c.core.mcp.host.resource.IMcpResourceProvider;
import com.codepilot1c.core.mcp.host.resource.StateResourceProvider;
import com.codepilot1c.core.mcp.host.resource.WorkspaceResourceProvider;
import com.codepilot1c.core.mcp.host.session.McpHostSession;
import com.codepilot1c.core.mcp.host.transport.McpHostHttpTransport;
import com.codepilot1c.core.mcp.host.transport.McpHostOAuthService;

/**
 * One MCP endpoint: an HTTP listener bound to a single {@link ProfileEndpoint}'s
 * port + token, announcing only that profile's tool set. Host-level settings
 * (bind address, auth mode, mutation policy) are shared via {@link McpHostConfig}.
 */
public class McpHostServer implements IMcpHostServer {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(McpHostServer.class);

    private McpHostConfig config;
    private ProfileEndpoint profile;
    private McpHostHttpTransport httpTransport;
    private McpHostRequestRouter router;
    private volatile boolean running;

    /**
     * Legacy single-endpoint constructor — wraps the legacy port/token/filter into
     * an enabled {@code full}-style profile. Retained for backward compatibility.
     */
    public McpHostServer(McpHostConfig config) {
        this(config, ProfileEndpoint.fullDefault(
                config.getPort(), config.getBearerToken(), config.getExposedToolsFilter()));
    }

    public McpHostServer(McpHostConfig config, ProfileEndpoint profile) {
        this.config = config;
        this.profile = profile;
    }

    public ProfileEndpoint getProfile() {
        return profile;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        if (!config.isEnabled()) {
            LOG.info("MCP host is disabled"); //$NON-NLS-1$
            return;
        }

        // Per-profile gate, unless the raw CODEPILOT1C_* env override is active —
        // then it forces the same surface onto every endpoint (fleet-wide switch).
        ToolGroupVisibility groupVisibility = ToolGroupVisibility.isEnvironmentConfigured()
                ? ToolGroupVisibility.fromEnvironment()
                : profile.toGroupVisibility();
        DefaultMcpToolExposurePolicy exposurePolicy = new DefaultMcpToolExposurePolicy(
                config, profile.getExposedToolsFilter(), groupVisibility);
        List<IMcpResourceProvider> resourceProviders = List.of(
            new WorkspaceResourceProvider(),
            new DiagnosticsResourceProvider(),
            new StateResourceProvider()
        );
        router = new McpHostRequestRouter(
            exposurePolicy,
            resourceProviders,
            new PromptTemplateProvider(),
            config.getMutationPolicy()
        );

        if (config.isHttpEnabled()) {
            String bearerToken = config.getAuthMode() == McpHostConfig.AuthMode.OAUTH_ONLY
                    ? "" //$NON-NLS-1$
                    : profile.getBearerToken();
            McpHostOAuthService oauthService = new McpHostOAuthService(
                config.getBindAddress(),
                profile.getPort(),
                bearerToken
            );
            httpTransport = new McpHostHttpTransport(
                config.getBindAddress(),
                profile.getPort(),
                oauthService,
                router,
                config.getAuthMode()
            );
            httpTransport.start();
        }

        running = true;
        LOG.info("MCP host endpoint '%s' started on %s:%d (http=%s, auth=%s)", //$NON-NLS-1$
            profile.getName(), config.getBindAddress(), Integer.valueOf(profile.getPort()),
            Boolean.valueOf(config.isHttpEnabled()), config.getAuthMode());
        logAnnouncedToolSurface(exposurePolicy);
    }

    /**
     * Log how many tools this endpoint announces and the rough size of their input
     * schemas, so a tuned group/per-tool config can be A/B-compared against the
     * default surface for tool-definition context. Backs feedback
     * {@code 2026-06-26-toggleable-tool-groups.md} (the "measurability" ask).
     */
    private void logAnnouncedToolSurface(DefaultMcpToolExposurePolicy exposurePolicy) {
        try {
            int total = 0;
            int announced = 0;
            long announcedSchemaChars = 0;
            for (ITool tool : ToolRegistry.getInstance().getAllTools()) {
                total++;
                if (!exposurePolicy.isExposed(tool.getName())) {
                    continue;
                }
                announced++;
                String schema = tool.getParameterSchema();
                if (schema != null) {
                    announcedSchemaChars += schema.length();
                }
            }
            LOG.info("MCP host endpoint '%s' tool surface: announcing %d/%d tools, ~%d chars of input schema (group filter active=%s)", //$NON-NLS-1$
                    profile.getName(),
                    Integer.valueOf(announced),
                    Integer.valueOf(total),
                    Long.valueOf(announcedSchemaChars),
                    Boolean.valueOf(exposurePolicy.getGroupVisibility().isActive()));
        } catch (RuntimeException e) {
            LOG.warn("Failed to log announced tool surface for endpoint '%s': %s", //$NON-NLS-1$
                    profile.getName(), e.getMessage());
        }
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        if (httpTransport != null) {
            httpTransport.stop();
            httpTransport = null;
        }
        running = false;
        LOG.info("MCP host endpoint '%s' stopped", profile.getName()); //$NON-NLS-1$
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public synchronized void reloadConfig() {
        McpHostConfig fresh = McpHostConfigStore.getInstance().load();
        this.config = fresh;
        // Re-fetch this endpoint's profile by name from the fresh config; fall back
        // to the previously bound profile if it was removed.
        this.profile = fresh.getProfiles().stream()
                .filter(p -> p.getName() != null && p.getName().equals(profile.getName()))
                .findFirst()
                .orElse(profile);
        stop();
        start();
    }

    @Override
    public Map<String, Object> getCapabilities() {
        if (router == null) {
            return Map.of();
        }
        return router.capabilitiesSnapshot();
    }

    @Override
    public List<McpHostSession> getSessions() {
        if (httpTransport == null) {
            return List.of();
        }
        return List.copyOf(httpTransport.getSessionsSnapshot());
    }
}
