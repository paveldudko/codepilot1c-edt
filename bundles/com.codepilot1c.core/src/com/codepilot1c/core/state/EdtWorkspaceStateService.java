/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.state;

import java.net.InetAddress;
import java.time.Instant;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

import com.codepilot1c.core.edt.ast.EdtServiceGateway;
import com.codepilot1c.core.edt.ast.ProjectReadinessChecker;
import com.codepilot1c.core.edt.runtime.EdtInfobaseAssociationService;
import com.codepilot1c.core.edt.runtime.EdtInfobaseAssociationService.ContextView;
import com.codepilot1c.core.edt.runtime.EdtInfobaseAssociationService.InfobaseView;
import com.codepilot1c.core.edt.runtime.lease.InfobaseLeaseGuard;
import com.codepilot1c.core.internal.VibeCorePlugin;
import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.mcp.host.McpHostConfig;
import com.codepilot1c.core.mcp.host.McpHostConfigStore;
import com.codepilot1c.core.mcp.host.McpHostManager;
import com.codepilot1c.core.mcp.host.ProfileEndpoint;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Best-effort builder of a small JSON "state snapshot" describing THIS EDT stack — its identity
 * (stack id / port / profile), liveness (pid / host / updated-at), workspace path, MCP endpoints,
 * derived-data (index) readiness and, optionally, its currently-bound infobases.
 *
 * <p>The snapshot backs two consumers: the periodic {@link EdtStateBeacon} (a shared file an
 * external stack-pool orchestrator polls WITHOUT touching the MCP port) and, later, a
 * {@code get_workspace_state} MCP tool. Every sub-part is gathered defensively: any piece that
 * fails (EDT not up yet, service missing, no OSGi runtime) is omitted or replaced by an
 * {@code UNKNOWN} placeholder and logged at debug — {@link #buildSnapshot(boolean)} never throws,
 * so a partial beacon can appear before the workspace index is ready.</p>
 */
public class EdtWorkspaceStateService {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(EdtWorkspaceStateService.class);

    /** Beacon payload schema version. Bump on an incompatible field-shape change. */
    public static final int BEACON_VERSION = 1;

    /** Per-instance forced profile selector (twin of {@link McpHostManager#ENV_PORT}). */
    private static final String ENV_PROFILE = "CODEPILOT1C_PROFILE"; //$NON-NLS-1$

    /**
     * Builds the full state snapshot. All fields are best-effort; {@code includeSlowFields}
     * additionally gathers {@code bound_infobases}, which walks the EDT association manager and
     * is therefore kept off the fast cadence.
     *
     * @param includeSlowFields include the heavier {@code bound_infobases} field
     * @return a Gson {@link JsonObject}; never {@code null}, never throws
     */
    public JsonObject buildSnapshot(boolean includeSlowFields) {
        JsonObject root = baseSnapshot(resolveStackId());
        addWorkspace(root);
        addPortProfileEndpoints(root);
        addIndex(root);
        if (includeSlowFields) {
            addBoundInfobases(root);
        }
        return root;
    }

    /**
     * The always-present core of a snapshot, assembled from primitives that need neither a running
     * workspace nor EDT services — safe to build (and unit-test) outside OSGi. {@code plugin_version}
     * / {@code pid} / {@code host} are still best-effort and simply omitted when unavailable.
     *
     * @param stackId the stack identity, or {@code null} to omit it
     * @return the base snapshot object
     */
    static JsonObject baseSnapshot(String stackId) {
        JsonObject root = new JsonObject();
        root.addProperty("beacon_version", BEACON_VERSION); //$NON-NLS-1$
        addPluginVersion(root);
        if (stackId != null && !stackId.isBlank()) {
            root.addProperty("stack_id", stackId); //$NON-NLS-1$
        }
        root.addProperty("updated_at", Instant.now().toString()); //$NON-NLS-1$
        try {
            root.addProperty("pid", Long.valueOf(ProcessHandle.current().pid())); //$NON-NLS-1$
        } catch (RuntimeException e) {
            LOG.debug("state snapshot: pid unavailable: %s", detail(e)); //$NON-NLS-1$
        }
        String host = hostName();
        if (host != null) {
            root.addProperty("host", host); //$NON-NLS-1$
        }
        return root;
    }

    private static String resolveStackId() {
        try {
            return InfobaseLeaseGuard.fromEnvironment().stackId();
        } catch (RuntimeException e) {
            LOG.debug("state snapshot: stack id unavailable: %s", detail(e)); //$NON-NLS-1$
            return null;
        }
    }

    private static void addPluginVersion(JsonObject root) {
        try {
            Bundle bundle = FrameworkUtil.getBundle(EdtWorkspaceStateService.class);
            if (bundle != null && bundle.getVersion() != null) {
                root.addProperty("plugin_version", bundle.getVersion().toString()); //$NON-NLS-1$
            }
        } catch (RuntimeException e) {
            LOG.debug("state snapshot: plugin version unavailable: %s", detail(e)); //$NON-NLS-1$
        }
    }

    private void addWorkspace(JsonObject root) {
        try {
            IWorkspace workspace = ResourcesPlugin.getWorkspace();
            if (workspace != null && workspace.getRoot() != null
                    && workspace.getRoot().getLocation() != null) {
                root.addProperty("workspace", workspace.getRoot().getLocation().toOSString()); //$NON-NLS-1$
            }
        } catch (RuntimeException e) {
            // ResourcesPlugin.getWorkspace() throws when the platform is not running.
            LOG.debug("state snapshot: workspace path unavailable: %s", detail(e)); //$NON-NLS-1$
        }
    }

    private void addPortProfileEndpoints(JsonObject root) {
        String portEnv = System.getenv(McpHostManager.ENV_PORT);
        if (portEnv != null && !portEnv.isBlank()) {
            try {
                root.addProperty("port", Integer.valueOf(Integer.parseInt(portEnv.trim()))); //$NON-NLS-1$
            } catch (NumberFormatException e) {
                LOG.debug("state snapshot: %s='%s' is not an int", McpHostManager.ENV_PORT, portEnv); //$NON-NLS-1$
            }
        }
        String profile = System.getenv(ENV_PROFILE);
        if (profile != null && !profile.isBlank()) {
            root.addProperty("profile", profile.trim()); //$NON-NLS-1$
        }
        try {
            McpHostConfig cfg = McpHostConfigStore.getInstance().load();
            List<ProfileEndpoint> profiles = cfg == null ? null : cfg.getProfiles();
            if (profiles != null && !profiles.isEmpty()) {
                JsonArray endpoints = new JsonArray();
                for (ProfileEndpoint endpoint : profiles) {
                    if (endpoint == null) {
                        continue;
                    }
                    JsonObject entry = new JsonObject();
                    entry.addProperty("name", endpoint.getName()); //$NON-NLS-1$
                    entry.addProperty("port", Integer.valueOf(endpoint.getPort())); //$NON-NLS-1$
                    entry.addProperty("enabled", Boolean.valueOf(endpoint.isEnabled())); //$NON-NLS-1$
                    endpoints.add(entry);
                }
                root.add("endpoints", endpoints); //$NON-NLS-1$
            }
        } catch (RuntimeException e) {
            LOG.debug("state snapshot: MCP endpoints unavailable: %s", detail(e)); //$NON-NLS-1$
        }
    }

    /**
     * Derived-data (index) readiness, mirroring {@code EdtIndexStatusTool.probe} but gated by a
     * NON-BLOCKING service peek: the blocking gateway getters wait up to 30 s for a missing EDT
     * service, which would stall the beacon on a cold EDT. When the core project service is not yet
     * registered the probe is skipped and {@code index} is the {@code UNKNOWN} placeholder — the
     * "partial beacon before the workspace is ready" case.
     */
    private void addIndex(JsonObject root) {
        try {
            IWorkspace workspace = ResourcesPlugin.getWorkspace();
            VibeCorePlugin plugin = VibeCorePlugin.getDefault();
            if (workspace == null || plugin == null || plugin.peekV8ProjectManager() == null) {
                root.add("index", unknownIndex()); //$NON-NLS-1$
                return;
            }
            ProjectReadinessChecker checker = new ProjectReadinessChecker(new EdtServiceGateway());
            JsonArray projects = new JsonArray();
            boolean anyIndexing = false;
            int considered = 0;
            for (IProject project : workspace.getRoot().getProjects()) {
                if (project == null || !project.exists() || !project.isOpen()) {
                    continue;
                }
                considered++;
                JsonObject entry = new JsonObject();
                entry.addProperty("name", project.getName()); //$NON-NLS-1$
                try {
                    ProjectReadinessChecker.Result result = checker.check(project);
                    entry.addProperty("state", result.getState().name()); //$NON-NLS-1$
                    if (result.getState() == ProjectReadinessChecker.State.BUILDING) {
                        anyIndexing = true;
                    }
                } catch (RuntimeException e) {
                    entry.addProperty("state", "UNKNOWN"); //$NON-NLS-1$ //$NON-NLS-2$
                }
                projects.add(entry);
            }
            if (considered == 0) {
                root.add("index", unknownIndex()); //$NON-NLS-1$
                return;
            }
            JsonObject index = new JsonObject();
            index.addProperty("ready", Boolean.valueOf(!anyIndexing)); //$NON-NLS-1$
            index.addProperty("is_indexing", Boolean.valueOf(anyIndexing)); //$NON-NLS-1$
            index.addProperty("projects_considered", Integer.valueOf(considered)); //$NON-NLS-1$
            index.add("projects", projects); //$NON-NLS-1$
            root.add("index", index); //$NON-NLS-1$
        } catch (RuntimeException e) {
            LOG.debug("state snapshot: index probe unavailable: %s", detail(e)); //$NON-NLS-1$
            root.add("index", unknownIndex()); //$NON-NLS-1$
        }
    }

    private static JsonObject unknownIndex() {
        JsonObject index = new JsonObject();
        index.addProperty("ready", Boolean.FALSE); //$NON-NLS-1$
        index.addProperty("state", "UNKNOWN"); //$NON-NLS-1$ //$NON-NLS-2$
        return index;
    }

    /**
     * The infobases bound to each open project's CURRENT association context. Gated by the same
     * non-blocking readiness peek as the index probe (the association manager getter also blocks
     * up to 30 s), and strictly read-only — it never opens a Designer/credential session. On any
     * failure the field degrades to an empty array; the beacon is never blocked on it.
     */
    private void addBoundInfobases(JsonObject root) {
        JsonArray bound = new JsonArray();
        try {
            IWorkspace workspace = ResourcesPlugin.getWorkspace();
            VibeCorePlugin plugin = VibeCorePlugin.getDefault();
            if (workspace == null || plugin == null || plugin.peekV8ProjectManager() == null) {
                root.add("bound_infobases", bound); //$NON-NLS-1$
                return;
            }
            EdtInfobaseAssociationService associations = new EdtInfobaseAssociationService();
            for (IProject project : workspace.getRoot().getProjects()) {
                if (project == null || !project.exists() || !project.isOpen()) {
                    continue;
                }
                try {
                    for (ContextView context : associations.listContexts(project.getName())) {
                        if (context == null || !context.current() || context.infobases() == null) {
                            continue;
                        }
                        for (InfobaseView infobase : context.infobases()) {
                            if (infobase == null) {
                                continue;
                            }
                            JsonObject entry = new JsonObject();
                            entry.addProperty("project", project.getName()); //$NON-NLS-1$
                            if (infobase.name() != null) {
                                entry.addProperty("infobase_name", infobase.name()); //$NON-NLS-1$
                            }
                            if (infobase.connection() != null) {
                                entry.addProperty("connection", infobase.connection()); //$NON-NLS-1$
                            }
                            entry.addProperty("default", Boolean.valueOf(infobase.isDefault())); //$NON-NLS-1$
                            bound.add(entry);
                        }
                    }
                } catch (RuntimeException e) {
                    LOG.debug("state snapshot: bound_infobases failed for project '%s': %s", //$NON-NLS-1$
                            project.getName(), detail(e));
                }
            }
        } catch (RuntimeException e) {
            LOG.debug("state snapshot: bound_infobases unavailable: %s", detail(e)); //$NON-NLS-1$
        }
        root.add("bound_infobases", bound); //$NON-NLS-1$
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return null;
        }
    }

    private static String detail(Throwable t) {
        if (t == null) {
            return ""; //$NON-NLS-1$
        }
        String message = t.getMessage();
        return message != null && !message.isBlank() ? message : t.getClass().getSimpleName();
    }
}
