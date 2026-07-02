/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edt.runtime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;

import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociation;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationContextProvider;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAssociationContext;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAssociationSettings;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseReferences;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.services.model.Section;
import com.codepilot1c.core.edt.runtime.lease.InfobaseLease;
import com.codepilot1c.core.edt.runtime.lease.InfobaseLeaseGuard;
import com.codepilot1c.core.logging.VibeLogger;

/**
 * Per-branch infobase association surgery WITHOUT a checkout — backing service of the
 * {@code manage_associations} tool.
 *
 * <p>Grounded against EDT 2025.2.x: the git context provider builds the association context as
 * {@code InfobaseAssociationContext.of(repository.getFullBranch())} — a single segment holding
 * the full ref ({@code refs/heads/<branch>}), and {@code of(String)} is public, so the exact
 * context of ANY branch is synthesizable byte-for-byte. The association manager API is fully
 * context-parameterized ({@code getAssociationContexts} / {@code getAssociation} /
 * {@code associate} / {@code dissociate} / {@code setDefaultInfobase}).</p>
 *
 * <p>Deliberate division of labor: this service NEVER creates or repairs {@code ibases.v8i}
 * registry rows — registry lifecycle belongs to {@code connect_infobase}. Here only EXISTING
 * registry rows are attached to / detached from branch contexts.</p>
 */
public class EdtInfobaseAssociationService {

    private static final VibeLogger.CategoryLogger LOG =
            VibeLogger.forClass(EdtInfobaseAssociationService.class);

    private static final String BRANCH_REF_PREFIX = "refs/heads/"; //$NON-NLS-1$

    private final EdtRuntimeGateway gateway;
    private final InfobaseLeaseGuard leaseGuard;

    public EdtInfobaseAssociationService() {
        this(new EdtRuntimeGateway());
    }

    public EdtInfobaseAssociationService(EdtRuntimeGateway gateway) {
        this(gateway, InfobaseLeaseGuard.fromEnvironment());
    }

    public EdtInfobaseAssociationService(EdtRuntimeGateway gateway, InfobaseLeaseGuard leaseGuard) {
        this.gateway = gateway;
        this.leaseGuard = leaseGuard;
    }

    public record InfobaseView(String name, String uuid, String connection, boolean isDefault) {
    }

    public record ContextView(String context, String branch, boolean current, List<InfobaseView> infobases) {
    }

    public record BindOutcome(String context, String infobaseName, String uuid, boolean setDefault) {
    }

    public record CopyOutcome(String fromContext, String toContext, List<String> copied, String defaultName) {
    }

    public record DissociateOutcome(String context, List<String> removed) {
    }

    // -- list -------------------------------------------------------------------------------

    /**
     * All persisted association contexts of the project (plus the CURRENT provider context when
     * it has nothing persisted yet), each with its bound infobases and default marker.
     */
    public List<ContextView> listContexts(String projectName) {
        IProject project = resolveProject(projectName);
        IInfobaseAssociationManager manager = associationManager();
        Collection<InfobaseAssociationContext> contexts;
        try {
            contexts = manager.getAssociationContexts(project);
        } catch (RuntimeException e) {
            throw new EdtToolException(EdtToolErrorCode.EDT_SERVICE_UNAVAILABLE,
                    "Failed to enumerate association contexts: " + detail(e), e); //$NON-NLS-1$
        }
        InfobaseAssociationContext current = currentContext(project);
        List<ContextView> views = new ArrayList<>();
        boolean currentSeen = false;
        if (contexts != null) {
            for (InfobaseAssociationContext ctx : contexts) {
                if (ctx == null) {
                    continue;
                }
                boolean isCurrent = current != null && current.equals(ctx);
                currentSeen |= isCurrent;
                views.add(view(project, manager, ctx, isCurrent));
            }
        }
        if (current != null && !currentSeen) {
            // Fresh branch: the provider already reports it, but nothing is persisted yet.
            views.add(view(project, manager, current, true));
        }
        return views;
    }

    private ContextView view(IProject project, IInfobaseAssociationManager manager,
            InfobaseAssociationContext ctx, boolean isCurrent) {
        String value = contextValue(ctx);
        List<InfobaseView> infobases = new ArrayList<>();
        try {
            Optional<IInfobaseAssociation> assoc = manager.getAssociation(project, ctx);
            if (assoc.isPresent()) {
                InfobaseReference defaultRef = assoc.get().getDefaultInfobase();
                String defaultIdentity = InfobaseIdentity.identityOf(defaultRef);
                Collection<InfobaseReference> refs = assoc.get().getInfobases();
                if (refs != null) {
                    for (InfobaseReference ref : refs) {
                        if (ref == null) {
                            continue;
                        }
                        String identity = InfobaseIdentity.identityOf(ref);
                        boolean isDefault = defaultRef != null
                                && (Objects.equals(defaultRef.getUuid(), ref.getUuid())
                                        || InfobaseIdentity.matches(identity, defaultIdentity));
                        infobases.add(new InfobaseView(ref.getName(),
                                ref.getUuid() == null ? null : ref.getUuid().toString(),
                                identity, isDefault));
                    }
                }
            }
        } catch (RuntimeException e) {
            LOG.warn("manage_associations: failed to read association for context '%s': %s", //$NON-NLS-1$
                    value, e.getMessage());
        }
        return new ContextView(value, InfobaseLeaseGuard.branchFromContext(value), isCurrent, infobases);
    }

    // -- bind -------------------------------------------------------------------------------

    /**
     * Attaches an EXISTING registry infobase (by name and/or path) to the context of
     * {@code branch} — no checkout needed. Registry rows are never created here.
     */
    public BindOutcome bind(String projectName, String branch, String infobaseName,
            String databasePath, boolean setDefault) {
        IProject project = resolveProject(projectName);
        InfobaseAssociationContext ctx = contextForBranch(branch);
        InfobaseReference row = resolveRegistryRow(infobaseName, databasePath);
        enforceLease(shortBranch(branch), row);
        IInfobaseAssociationManager manager = associationManager();
        InfobaseAssociationSettings settings = new InfobaseAssociationSettings(false, ctx);
        try {
            manager.associate(project, row, settings);
        } catch (RuntimeException e) {
            // "Infobase ... is already connected" == idempotent re-bind; anything else is fatal.
            if (!isAlreadyConnected(e)) {
                throw new EdtToolException(EdtToolErrorCode.EDT_SERVICE_UNAVAILABLE,
                        "Failed to associate infobase with branch context: " + detail(e), e); //$NON-NLS-1$
            }
            LOG.info("manage_associations bind: already associated — proceeding (%s)", e.getMessage()); //$NON-NLS-1$
        }
        if (setDefault) {
            try {
                manager.setDefaultInfobase(project, row, ctx);
            } catch (RuntimeException e) {
                throw new EdtToolException(EdtToolErrorCode.EDT_SERVICE_UNAVAILABLE,
                        "Associated, but failed to set default infobase: " + detail(e), e); //$NON-NLS-1$
            }
        }
        return new BindOutcome(contextValue(ctx), row.getName(),
                row.getUuid() == null ? null : row.getUuid().toString(), setDefault);
    }

    // -- copy -------------------------------------------------------------------------------

    /** Copies the association set (and optionally the default) from one branch context to another. */
    public CopyOutcome copy(String projectName, String fromBranch, String toBranch, boolean setDefault) {
        IProject project = resolveProject(projectName);
        InfobaseAssociationContext fromCtx = contextForBranch(fromBranch);
        InfobaseAssociationContext toCtx = contextForBranch(toBranch);
        if (contextValue(fromCtx).equals(contextValue(toCtx))) {
            throw new EdtToolException(EdtToolErrorCode.INVALID_ARGUMENT,
                    "from_branch and to_branch are the same context"); //$NON-NLS-1$
        }
        IInfobaseAssociationManager manager = associationManager();
        IInfobaseAssociation source = associationOrThrow(project, manager, fromCtx, fromBranch);
        Collection<InfobaseReference> refs = source.getInfobases();
        if (refs == null || refs.isEmpty()) {
            throw new EdtToolException(EdtToolErrorCode.INFOBASE_ASSOCIATION_NOT_FOUND,
                    "Branch '" + fromBranch + "' has no bound infobases to copy"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        InfobaseReference defaultRef = source.getDefaultInfobase();
        enforceLease(shortBranch(toBranch), defaultRef != null ? defaultRef : refs.iterator().next());
        InfobaseAssociationSettings settings = new InfobaseAssociationSettings(false, toCtx);
        List<String> copied = new ArrayList<>();
        for (InfobaseReference ref : refs) {
            if (ref == null) {
                continue;
            }
            try {
                manager.associate(project, ref, settings);
            } catch (RuntimeException e) {
                if (!isAlreadyConnected(e)) {
                    throw new EdtToolException(EdtToolErrorCode.EDT_SERVICE_UNAVAILABLE,
                            "Failed to copy association of '" + ref.getName() + "': " + detail(e), e); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
            copied.add(ref.getName());
        }
        String defaultName = null;
        if (setDefault && defaultRef != null) {
            try {
                manager.setDefaultInfobase(project, defaultRef, toCtx);
                defaultName = defaultRef.getName();
            } catch (RuntimeException e) {
                throw new EdtToolException(EdtToolErrorCode.EDT_SERVICE_UNAVAILABLE,
                        "Copied, but failed to set default infobase: " + detail(e), e); //$NON-NLS-1$
            }
        }
        return new CopyOutcome(contextValue(fromCtx), contextValue(toCtx), copied, defaultName);
    }

    // -- dissociate -------------------------------------------------------------------------

    /**
     * Detaches infobase(s) from the branch context: the named one, or ALL bound ones when
     * {@code infobaseName} is null. Registry rows and lease files are left untouched.
     */
    public DissociateOutcome dissociate(String projectName, String branch, String infobaseName) {
        IProject project = resolveProject(projectName);
        InfobaseAssociationContext ctx = contextForBranch(branch);
        IInfobaseAssociationManager manager = associationManager();
        IInfobaseAssociation assoc = associationOrThrow(project, manager, ctx, branch);
        Collection<InfobaseReference> refs = assoc.getInfobases();
        List<InfobaseReference> targets = new ArrayList<>();
        if (refs != null) {
            for (InfobaseReference ref : refs) {
                if (ref != null && (infobaseName == null || infobaseName.equals(ref.getName()))) {
                    targets.add(ref);
                }
            }
        }
        if (targets.isEmpty()) {
            throw new EdtToolException(EdtToolErrorCode.INFOBASE_NOT_FOUND,
                    infobaseName == null
                            ? "Branch '" + branch + "' has no bound infobases" //$NON-NLS-1$ //$NON-NLS-2$
                            : "Infobase '" + infobaseName + "' is not bound under branch '" + branch + "'"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        List<String> removed = new ArrayList<>();
        for (InfobaseReference ref : targets) {
            try {
                manager.dissociate(project, ref, ctx);
                removed.add(ref.getName());
            } catch (RuntimeException e) {
                throw new EdtToolException(EdtToolErrorCode.EDT_SERVICE_UNAVAILABLE,
                        "Failed to dissociate '" + ref.getName() + "': " + detail(e), e); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        return new DissociateOutcome(contextValue(ctx), removed);
    }

    // -- plumbing ---------------------------------------------------------------------------

    /**
     * Synthesizes the association context of a branch exactly the way EDT's git provider does:
     * a single-segment context holding the full ref. Accepts either a short name ({@code task-C})
     * or a full ref ({@code refs/heads/task-C}).
     */
    public static InfobaseAssociationContext contextForBranch(String branch) {
        if (branch == null || branch.isBlank()) {
            throw new EdtToolException(EdtToolErrorCode.INVALID_ARGUMENT, "branch is required"); //$NON-NLS-1$
        }
        return InfobaseAssociationContext.of(BRANCH_REF_PREFIX + shortBranch(branch));
    }

    /** Short branch name: strips a {@code refs/heads/} prefix if the caller passed a full ref. */
    public static String shortBranch(String branch) {
        String trimmed = branch == null ? "" : branch.trim(); //$NON-NLS-1$
        if (trimmed.toLowerCase(Locale.ROOT).startsWith(BRANCH_REF_PREFIX)) {
            trimmed = trimmed.substring(BRANCH_REF_PREFIX.length());
        }
        if (trimmed.isBlank()) {
            throw new EdtToolException(EdtToolErrorCode.INVALID_ARGUMENT, "branch is required"); //$NON-NLS-1$
        }
        return trimmed;
    }

    static String contextValue(InfobaseAssociationContext ctx) {
        return ctx == null ? "" : ctx.getContext().orElse(""); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Finds the EXISTING registry row by display name and/or file path (both given — both must
     * match). Never creates rows: an unknown infobase is a typed error pointing at
     * {@code connect_infobase}, whose job registry lifecycle is.
     */
    protected InfobaseReference resolveRegistryRow(String infobaseName, String databasePath) {
        if ((infobaseName == null || infobaseName.isBlank())
                && (databasePath == null || databasePath.isBlank())) {
            throw new EdtToolException(EdtToolErrorCode.INVALID_ARGUMENT,
                    "infobase_name or database_path is required"); //$NON-NLS-1$
        }
        IInfobaseManager manager;
        try {
            manager = gateway.getInfobaseManager();
        } catch (IllegalStateException e) {
            throw new EdtToolException(EdtToolErrorCode.EDT_SERVICE_UNAVAILABLE,
                    "IInfobaseManager service unavailable — EDT may not be fully initialized", e); //$NON-NLS-1$
        }
        String pathIdentity = null;
        if (databasePath != null && !databasePath.isBlank()) {
            pathIdentity = InfobaseIdentity.identityOf(
                    InfobaseReferences.newFileInfobaseReference(databasePath.trim()));
        }
        List<InfobaseReference> matches = new ArrayList<>();
        try {
            for (Section section : manager.getAll()) {
                if (!(section instanceof InfobaseReference row)) {
                    continue;
                }
                if (infobaseName != null && !infobaseName.isBlank()
                        && !infobaseName.equals(row.getName())) {
                    continue;
                }
                if (pathIdentity != null
                        && !InfobaseIdentity.matches(pathIdentity, InfobaseIdentity.identityOf(row))) {
                    continue;
                }
                matches.add(row);
            }
        } catch (RuntimeException e) {
            throw new EdtToolException(EdtToolErrorCode.EDT_SERVICE_UNAVAILABLE,
                    "Failed to read the infobase registry: " + detail(e), e); //$NON-NLS-1$
        }
        if (matches.isEmpty()) {
            throw new EdtToolException(EdtToolErrorCode.INFOBASE_NOT_FOUND,
                    "No registered infobase matches " //$NON-NLS-1$
                            + (infobaseName == null ? "" : "name '" + infobaseName + "' ") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                            + (databasePath == null ? "" : "path '" + databasePath + "'") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                            + ". manage_associations only attaches EXISTING registry entries; " //$NON-NLS-1$
                            + "register a new infobase with connect_infobase first."); //$NON-NLS-1$
        }
        if (matches.size() > 1) {
            throw new EdtToolException(EdtToolErrorCode.INVALID_ARGUMENT,
                    "Ambiguous infobase: " + matches.size() //$NON-NLS-1$
                            + " registry entries match — pass both infobase_name and database_path"); //$NON-NLS-1$
        }
        return matches.get(0);
    }

    private IInfobaseAssociation associationOrThrow(IProject project,
            IInfobaseAssociationManager manager, InfobaseAssociationContext ctx, String branch) {
        Optional<IInfobaseAssociation> assoc;
        try {
            assoc = manager.getAssociation(project, ctx);
        } catch (RuntimeException e) {
            throw new EdtToolException(EdtToolErrorCode.EDT_SERVICE_UNAVAILABLE,
                    "Failed to read association of branch '" + branch + "': " + detail(e), e); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (assoc.isEmpty()) {
            throw new EdtToolException(EdtToolErrorCode.INFOBASE_ASSOCIATION_NOT_FOUND,
                    "Branch '" + branch + "' has no infobase association"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return assoc.get();
    }

    private void enforceLease(String branch, InfobaseReference reference) {
        if (leaseGuard == null || !leaseGuard.isEnabled()) {
            return;
        }
        String identity = InfobaseIdentity.identityOf(reference);
        InfobaseLeaseGuard.Decision decision = leaseGuard.checkOrAcquire(branch, identity, identity, null);
        if (decision.outcome() != InfobaseLeaseGuard.Outcome.DENIED) {
            return;
        }
        InfobaseLease holder = decision.lease();
        throw new EdtToolException(EdtToolErrorCode.EDT_LEASE_HELD,
                "lease_held: branch '" + branch + "' is leased by " //$NON-NLS-1$ //$NON-NLS-2$
                        + (holder == null ? "another stack" : holder.describeHolder()) //$NON-NLS-1$
                        + ". Release it on the holding stack (manage_leases action=release) " //$NON-NLS-1$
                        + "or steal a stale one (manage_leases action=take force=true), then retry."); //$NON-NLS-1$
    }

    private IInfobaseAssociationManager associationManager() {
        try {
            return gateway.getInfobaseAssociationManager();
        } catch (IllegalStateException e) {
            throw new EdtToolException(EdtToolErrorCode.EDT_SERVICE_UNAVAILABLE,
                    "IInfobaseAssociationManager service unavailable — EDT may not be fully initialized", e); //$NON-NLS-1$
        }
    }

    private InfobaseAssociationContext currentContext(IProject project) {
        try {
            IInfobaseAssociationContextProvider provider = gateway.peekInfobaseAssociationContextProvider();
            if (provider == null) {
                return null;
            }
            return provider.get(project);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private IProject resolveProject(String projectName) {
        if (projectName == null || projectName.isBlank()) {
            throw new EdtToolException(EdtToolErrorCode.INVALID_ARGUMENT, "project_name is required"); //$NON-NLS-1$
        }
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace() == null
                ? null : ResourcesPlugin.getWorkspace().getRoot();
        IProject project = root == null ? null : root.getProject(projectName);
        if (project == null || !project.exists()) {
            throw new EdtToolException(EdtToolErrorCode.PROJECT_NOT_FOUND,
                    "EDT project not found: " + projectName); //$NON-NLS-1$
        }
        return project;
    }

    private static boolean isAlreadyConnected(RuntimeException e) {
        String message = e.getMessage();
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("already connected") || lower.contains("already associated"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String detail(Throwable t) {
        if (t == null) {
            return ""; //$NON-NLS-1$
        }
        String message = t.getMessage();
        return message != null && !message.isBlank() ? message : t.getClass().getSimpleName();
    }
}
