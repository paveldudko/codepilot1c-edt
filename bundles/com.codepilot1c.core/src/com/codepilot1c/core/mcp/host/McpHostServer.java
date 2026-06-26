package com.codepilot1c.core.mcp.host;

import java.util.List;
import java.util.Map;

import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.tools.ITool;
import com.codepilot1c.core.tools.ToolRegistry;
import com.codepilot1c.core.mcp.host.prompt.PromptTemplateProvider;
import com.codepilot1c.core.mcp.host.resource.DiagnosticsResourceProvider;
import com.codepilot1c.core.mcp.host.resource.IMcpResourceProvider;
import com.codepilot1c.core.mcp.host.resource.StateResourceProvider;
import com.codepilot1c.core.mcp.host.resource.WorkspaceResourceProvider;
import com.codepilot1c.core.mcp.host.session.McpHostSession;
import com.codepilot1c.core.mcp.host.transport.McpHostHttpTransport;
import com.codepilot1c.core.mcp.host.transport.McpHostOAuthService;

public class McpHostServer implements IMcpHostServer {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(McpHostServer.class);

    private McpHostConfig config;
    private McpHostHttpTransport httpTransport;
    private McpHostRequestRouter router;
    private volatile boolean running;

    public McpHostServer(McpHostConfig config) {
        this.config = config;
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

        DefaultMcpToolExposurePolicy exposurePolicy = new DefaultMcpToolExposurePolicy(config);
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
                    : config.getBearerToken();
            McpHostOAuthService oauthService = new McpHostOAuthService(
                config.getBindAddress(),
                config.getPort(),
                bearerToken
            );
            httpTransport = new McpHostHttpTransport(
                config.getBindAddress(),
                config.getPort(),
                oauthService,
                router,
                config.getAuthMode()
            );
            httpTransport.start();
        }

        running = true;
        LOG.info("MCP host server started (http=%s, auth=%s)", //$NON-NLS-1$
            Boolean.valueOf(config.isHttpEnabled()), config.getAuthMode());
        logAnnouncedToolSurface(exposurePolicy);
    }

    /**
     * Log how many tools the server announces and the rough size of their input
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
            LOG.info("MCP host tool surface: announcing %d/%d tools, ~%d chars of input schema (group filter active=%s)", //$NON-NLS-1$
                    Integer.valueOf(announced),
                    Integer.valueOf(total),
                    Long.valueOf(announcedSchemaChars),
                    Boolean.valueOf(exposurePolicy.getGroupVisibility().isActive()));
        } catch (RuntimeException e) {
            LOG.warn("Failed to log announced tool surface: %s", e.getMessage()); //$NON-NLS-1$
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
        LOG.info("MCP host server stopped"); //$NON-NLS-1$
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public synchronized void reloadConfig() {
        this.config = McpHostConfigStore.getInstance().load();
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
