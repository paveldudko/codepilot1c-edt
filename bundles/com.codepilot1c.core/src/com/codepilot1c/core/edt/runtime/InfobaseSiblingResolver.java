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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;

import com._1c.g5.v8.dt.core.platform.IExtensionProject;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com.codepilot1c.core.logging.VibeLogger;

/**
 * Fan-out over the OTHER open workspace projects that sit on the SAME physical infobase.
 *
 * <p>EDT's equality state belongs to a (project, infobase) PAIR — that is correct semantics, not a
 * bug: {@code getEqualityState} answers "does THIS project's configuration equal the infobase".
 * When several projects share one infobase (a configuration plus its extensions, or two
 * configuration projects bound to the same {@code .1CD}), a green answer for one project says
 * nothing about the others: an extension can still be un-applied while its parent reports EQUAL,
 * which reads as a false green to an operator or agent. EDT exposes no enumeration of the projects
 * bound to an infobase ({@code IInfobaseAssociationManager.getAssociation(InfobaseReference)} is a
 * first-match lookup, not a listing), so the only working path is to run
 * {@link EdtRuntimeService#resolveDefaultInfobase(String)} over the open projects and match on
 * {@link InfobaseIdentity#canonical(String)} — the plugin's single "same infobase" rule, already
 * used by the lease guard.</p>
 *
 * <p>Read-only and best-effort throughout: it never opens a Designer/credential session, never
 * throws out, and degrades to an empty list when EDT is not up yet. The classification of the
 * relation matters as much as the fan-out itself — two independent CONFIGURATION projects on one
 * infobase are mutually exclusive by construction (both cannot be EQUAL), so flagging them as stale
 * would be a permanent false alarm; only the must-converge relations (a configuration and its
 * extensions) are treated as a staleness signal.</p>
 */
public class InfobaseSiblingResolver {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(InfobaseSiblingResolver.class);

    /** EDT {@code InfobaseEqualityState} constant names, as returned by readInfobaseEqualityState. */
    public static final String STATE_EQUAL = "EQUAL"; //$NON-NLS-1$
    public static final String STATE_NOT_EQUAL = "NOT_EQUAL"; //$NON-NLS-1$
    public static final String STATE_LOADING = "LOADING"; //$NON-NLS-1$
    /**
     * Synthetic state for LOADING / absent: EDT has no verdict yet (it recomputes the comparison
     * right after an update). Deliberately NOT reported as stale — an "unknown" must never read as
     * "diverged" any more than it reads as "ready".
     */
    public static final String STATE_UNKNOWN = "unknown"; //$NON-NLS-1$

    /** The sibling is an extension project whose parent is the anchor project. Must converge. */
    public static final String RELATION_EXTENSION_OF = "extension_of"; //$NON-NLS-1$
    /** The sibling is the parent (base configuration) of the anchor extension project. Must converge. */
    public static final String RELATION_PARENT_OF = "parent_of"; //$NON-NLS-1$
    /** The sibling is another extension of the anchor's own parent project. Must converge. */
    public static final String RELATION_CO_EXTENSION = "co_extension"; //$NON-NLS-1$
    /**
     * Two independent CONFIGURATION projects bound to the same infobase — mutually exclusive, so
     * both being EQUAL is impossible. Informational only; never a staleness verdict.
     */
    public static final String RELATION_SAME_IB_CONFIGURATION = "same_ib_configuration"; //$NON-NLS-1$

    /**
     * One other project on the same infobase: its Eclipse name, the {@link #RELATION_EXTENSION_OF
     * relation} to the anchor project, its normalized equality state, and whether that state is a
     * genuine staleness signal for the shared infobase.
     */
    public record Sibling(String project, String relation, String equalityState, boolean stale) {
    }

    /**
     * Raw input row for {@link #aggregate}: a candidate project with its resolved infobase identity
     * (raw connection string — canonicalization happens inside the aggregation), its relation to the
     * anchor and its raw equality state. Lets the whole aggregation be unit-tested without EDT.
     */
    public record SiblingCandidate(String project, String identity, String relation, String equalityState) {
    }

    private final EdtRuntimeGateway gateway;
    private final EdtRuntimeService runtimeService;

    public InfobaseSiblingResolver() {
        this(new EdtRuntimeGateway(), new EdtRuntimeService());
    }

    public InfobaseSiblingResolver(EdtRuntimeGateway gateway, EdtRuntimeService runtimeService) {
        this.gateway = gateway;
        this.runtimeService = runtimeService;
    }

    /**
     * The other open projects bound to {@code projectName}'s default infobase, with their equality
     * state. Empty when EDT is not up yet, the project's infobase does not resolve, or no other
     * project shares it. Never throws.
     */
    public List<Sibling> siblingsOf(String projectName) {
        String requested = projectName == null ? null : projectName.trim();
        if (requested == null || requested.isEmpty()) {
            return List.of();
        }
        try {
            // Same non-blocking readiness gate as EdtWorkspaceStateService#addBoundInfobases: without
            // it the first association-manager lookup below can park the caller for 30 s on a cold EDT.
            IV8ProjectManager v8ProjectManager = gateway.peekV8ProjectManager();
            if (v8ProjectManager == null) {
                LOG.debug("sibling fan-out skipped for %s: EDT project manager not registered yet", //$NON-NLS-1$
                        requested);
                return List.of();
            }
            IWorkspace workspace = ResourcesPlugin.getWorkspace();
            if (workspace == null) {
                return List.of();
            }
            IProject anchorProject = gateway.resolveProject(requested);
            String anchorName = anchorProject != null && anchorProject.exists()
                    ? anchorProject.getName()
                    : requested;
            String anchorIdentity = identityOf(anchorName);
            if (anchorIdentity == null) {
                LOG.debug("sibling fan-out skipped for %s: no infobase resolved", anchorName); //$NON-NLS-1$
                return List.of();
            }
            Map<String, String> extensionParents = extensionParents(v8ProjectManager);
            List<SiblingCandidate> candidates = new ArrayList<>();
            for (IProject project : workspace.getRoot().getProjects()) {
                if (project == null || !project.exists() || !project.isOpen()) {
                    continue;
                }
                String name = project.getName();
                if (name.equals(anchorName)) {
                    continue;
                }
                String identity = identityOf(name);
                // Pre-filter with the SAME predicate the aggregation re-applies, purely to skip the
                // reflective equality read for projects that sit on a different infobase.
                if (identity == null || !InfobaseIdentity.matches(anchorIdentity, identity)) {
                    continue;
                }
                candidates.add(new SiblingCandidate(name, identity,
                        classifyRelation(anchorName, name, extensionParents), equalityStateOf(name)));
            }
            List<Sibling> siblings = aggregate(anchorName, anchorIdentity, candidates);
            if (!siblings.isEmpty()) {
                LOG.info("sibling fan-out: project=%s shares its infobase with %s project(s), stale=%s", //$NON-NLS-1$
                        anchorName, Integer.valueOf(siblings.size()), staleProjects(siblings));
            }
            return siblings;
        } catch (RuntimeException | LinkageError e) {
            // LinkageError: the EDT/Eclipse runtime may be absent entirely (headless unit tests) — the
            // fan-out is advisory, so it degrades to "no siblings" instead of failing the caller.
            LOG.warn("sibling fan-out failed for %s: %s", requested, detail(e)); //$NON-NLS-1$
            return List.of();
        }
    }

    /**
     * Pure aggregation: keeps the candidates whose infobase identity matches the anchor's, normalizes
     * their equality state and decides staleness from the relation. No EDT, no workspace — this is
     * the unit-testable core of the fan-out.
     *
     * @param anchorProject  the project the caller asked about (excluded from the result)
     * @param anchorIdentity its raw infobase connection string; {@code null} yields an empty result
     * @param candidates     every other project considered, with raw identity/relation/equality state
     */
    public static List<Sibling> aggregate(String anchorProject, String anchorIdentity,
            Collection<SiblingCandidate> candidates) {
        if (anchorIdentity == null || candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        String anchor = anchorProject == null ? "" : anchorProject.trim(); //$NON-NLS-1$
        List<Sibling> siblings = new ArrayList<>();
        for (SiblingCandidate candidate : candidates) {
            if (candidate == null || candidate.project() == null || candidate.project().isBlank()) {
                continue;
            }
            String project = candidate.project().trim();
            if (project.equals(anchor)) {
                continue;
            }
            // InfobaseIdentity is the ONE "same infobase" rule in this plugin: it folds slash
            // direction, case and a trailing separator, which raw string equality would miss.
            if (!InfobaseIdentity.matches(anchorIdentity, candidate.identity())) {
                continue;
            }
            String relation = relationOrDefault(candidate.relation());
            String state = normalizeState(candidate.equalityState());
            boolean stale = mustConverge(relation) && STATE_NOT_EQUAL.equals(state);
            siblings.add(new Sibling(project, relation, state, stale));
        }
        siblings.sort(Comparator.comparing(Sibling::project, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(siblings);
    }

    /** Names of the siblings that must converge with the anchor but currently do not. */
    public static List<String> staleProjects(List<Sibling> siblings) {
        if (siblings == null || siblings.isEmpty()) {
            return List.of();
        }
        List<String> stale = new ArrayList<>();
        for (Sibling sibling : siblings) {
            if (sibling != null && sibling.stale()) {
                stale.add(sibling.project());
            }
        }
        return List.copyOf(stale);
    }

    /**
     * True when the anchor AND every must-converge sibling report EQUAL. Informational
     * {@link #RELATION_SAME_IB_CONFIGURATION} siblings are excluded on purpose: they can never all
     * be EQUAL at once, so including them would pin this flag to {@code false} forever. An unknown
     * state is never counted as ready.
     */
    public static boolean allWorkReady(String anchorEqualityState, List<Sibling> siblings) {
        if (!STATE_EQUAL.equals(anchorEqualityState)) {
            return false;
        }
        if (siblings == null) {
            return true;
        }
        for (Sibling sibling : siblings) {
            if (sibling != null && mustConverge(sibling.relation())
                    && !STATE_EQUAL.equals(sibling.equalityState())) {
                return false;
            }
        }
        return true;
    }

    /**
     * True for the relations that MUST converge with the shared infobase (a configuration and its
     * extensions). {@link #RELATION_SAME_IB_CONFIGURATION} is mutually exclusive by construction and
     * therefore excluded — see the class comment.
     */
    public static boolean mustConverge(String relation) {
        return RELATION_EXTENSION_OF.equals(relation)
                || RELATION_PARENT_OF.equals(relation)
                || RELATION_CO_EXTENSION.equals(relation);
    }

    /**
     * Classifies a candidate project against the anchor using the extension&#x2192;parent map.
     * Anything that is not an extension relationship between the two is
     * {@link #RELATION_SAME_IB_CONFIGURATION} — including the degenerate case of an extension of a
     * THIRD configuration that happens to share this infobase, which is left informational rather
     * than raising an alarm we cannot substantiate. Package-private for unit tests.
     */
    static String classifyRelation(String anchorProject, String candidateProject,
            Map<String, String> extensionParents) {
        if (extensionParents == null || extensionParents.isEmpty()) {
            return RELATION_SAME_IB_CONFIGURATION;
        }
        String anchorParent = extensionParents.get(anchorProject);
        String candidateParent = extensionParents.get(candidateProject);
        if (candidateParent != null && candidateParent.equals(anchorProject)) {
            return RELATION_EXTENSION_OF;
        }
        if (anchorParent != null && anchorParent.equals(candidateProject)) {
            return RELATION_PARENT_OF;
        }
        if (anchorParent != null && anchorParent.equals(candidateParent)) {
            return RELATION_CO_EXTENSION;
        }
        return RELATION_SAME_IB_CONFIGURATION;
    }

    /** Maps LOADING / absent to {@link #STATE_UNKNOWN}; passes EDT's enum names through. */
    static String normalizeState(String rawState) {
        if (rawState == null || rawState.isBlank()) {
            return STATE_UNKNOWN;
        }
        String state = rawState.trim();
        return STATE_LOADING.equalsIgnoreCase(state) ? STATE_UNKNOWN : state;
    }

    private static String relationOrDefault(String relation) {
        return relation == null || relation.isBlank() ? RELATION_SAME_IB_CONFIGURATION : relation.trim();
    }

    /** Raw infobase connection string of a project's default infobase, or {@code null}. */
    private String identityOf(String projectName) {
        try {
            return InfobaseIdentity.identityOf(runtimeService.resolveDefaultInfobase(projectName));
        } catch (RuntimeException e) {
            // Expected for projects with no association at all; nothing to report to the caller.
            LOG.debug("sibling fan-out: no infobase for project %s (%s)", projectName, detail(e)); //$NON-NLS-1$
            return null;
        }
    }

    /** EDT's in-memory equality state for a project, or {@code null} when undeterminable. */
    private String equalityStateOf(String projectName) {
        try {
            return runtimeService.readInfobaseEqualityState(projectName);
        } catch (RuntimeException e) {
            LOG.debug("sibling fan-out: equality read failed for project %s (%s)", projectName, detail(e)); //$NON-NLS-1$
            return null;
        }
    }

    /** Extension project name &#x2192; parent (base configuration) project name, for the open workspace. */
    private static Map<String, String> extensionParents(IV8ProjectManager v8ProjectManager) {
        Map<String, String> parents = new LinkedHashMap<>();
        try {
            for (IExtensionProject extensionProject : v8ProjectManager.getProjects(IExtensionProject.class)) {
                if (extensionProject == null || extensionProject.getProject() == null) {
                    continue;
                }
                IProject parent = extensionProject.getParentProject();
                if (parent == null) {
                    continue;
                }
                parents.put(extensionProject.getProject().getName(), parent.getName());
            }
        } catch (RuntimeException e) {
            // Without the map every sibling degrades to same_ib_configuration (informational) — a
            // missed alarm, never a false one.
            LOG.debug("sibling fan-out: extension enumeration failed (%s)", detail(e)); //$NON-NLS-1$
        }
        return parents;
    }

    private static String detail(Throwable t) {
        if (t == null) {
            return ""; //$NON-NLS-1$
        }
        String message = t.getMessage();
        return message != null && !message.isBlank() ? message : t.getClass().getSimpleName();
    }
}
