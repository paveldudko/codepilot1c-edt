/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.ui.diagnostics;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.Path;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.source.Annotation;
import org.eclipse.jface.text.source.IAnnotationModel;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.part.FileEditorInput;
import org.eclipse.ui.texteditor.IDocumentProvider;
import org.eclipse.ui.texteditor.ITextEditor;
import org.eclipse.ui.texteditor.MarkerAnnotation;

import com._1c.g5.v8.dt.validation.marker.IMarkerManager;
import com._1c.g5.v8.dt.validation.marker.Marker;
import com._1c.g5.v8.dt.validation.marker.MarkerFilter;
import com._1c.g5.v8.dt.validation.marker.MarkerSeverity;
import com.e1c.g5.dt.applications.IApplicationManager;
import com.e1c.g5.v8.dt.check.settings.CheckUid;
import com.e1c.g5.v8.dt.check.settings.ICheckDescription;
import com.e1c.g5.v8.dt.check.settings.ICheckRepository;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;

import com.codepilot1c.core.diagnostics.BslLiveValidator;
import com.codepilot1c.core.diagnostics.BslLiveValidator.BslLiveIssue;
import com.codepilot1c.core.diagnostics.DcsSchemaValidator;
import com.codepilot1c.core.diagnostics.DcsSchemaValidator.DcsSchemaIssue;
import com.codepilot1c.core.diagnostics.CheckInfoResolver;
import com.codepilot1c.core.diagnostics.DiagnosticsLineFilter;
import com.codepilot1c.core.diagnostics.PathMatchTokens;
import com.codepilot1c.core.diagnostics.RelativePathCandidates;
import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.internal.VibeCorePlugin;
import com.codepilot1c.core.settings.VibePreferenceConstants;
import com.codepilot1c.ui.diagnostics.EdtDiagnostic.Severity;

/**
 * Collects EDT diagnostics from Eclipse markers and annotations.
 *
 * <p>Provides two collection strategies:
 * <ul>
 * <li><b>Markers</b> - for saved files (persistent diagnostics)</li>
 * <li><b>Annotations</b> - for unsaved/dirty editors (real-time diagnostics)</li>
 * </ul>
 */
public class EdtDiagnosticsCollector {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(EdtDiagnosticsCollector.class);

    /**
     * Toggle for the noisy {@code [get_diagnostics]} progress logs. Off by default.
     *
     * <p>Primary control: Window → Preferences → 1C Copilot → "Verbose get_diagnostics logging"
     * ({@link VibePreferenceConstants#PREF_DIAGNOSTICS_VERBOSE} in the {@code com.codepilot1c.core}
     * node). Takes effect on the next tool call, no restart needed.</p>
     *
     * <p>Escape hatch for non-OSGi contexts (plain JUnit, CLI smoke tests): JVM system property
     * {@code -Dcodepilot1c.diagnostics.verbose=true}.</p>
     */
    private static final String CORE_PLUGIN_ID = "com.codepilot1c.core"; //$NON-NLS-1$
    private static final String PROP_DIAG_VERBOSE = "codepilot1c.diagnostics.verbose"; //$NON-NLS-1$

    private static void diagInfo(String format, Object... args) {
        if (isDiagVerbose()) {
            LOG.info(format, args);
        }
    }

    private static boolean isDiagVerbose() {
        try {
            IEclipsePreferences prefs = InstanceScope.INSTANCE.getNode(CORE_PLUGIN_ID);
            if (prefs.getBoolean(VibePreferenceConstants.PREF_DIAGNOSTICS_VERBOSE, false)) {
                return true;
            }
        } catch (RuntimeException ignored) {
            // Non-OSGi runtime (plain JUnit) — fall through to system-property fallback.
        }
        String raw = System.getProperty(PROP_DIAG_VERBOSE);
        return raw != null && Boolean.parseBoolean(raw.trim());
    }

    private static final int MAX_SNIPPET_LENGTH = 120;

    private static EdtDiagnosticsCollector instance;

    private EdtDiagnosticsCollector() {
        // singleton
    }

    /**
     * Returns the singleton instance.
     */
    public static synchronized EdtDiagnosticsCollector getInstance() {
        if (instance == null) {
            instance = new EdtDiagnosticsCollector();
        }
        return instance;
    }

    /**
     * Query parameters for collecting diagnostics.
     */
    public record DiagnosticsQuery(
            Severity minSeverity,
            int maxItems,
            boolean includeSnippets,
            long waitMs,
            boolean includeRuntimeMarkers,
            int lineFrom,
            int lineTo,
            boolean includeCheckHelp,
            String helpLocale) {

        public static DiagnosticsQuery defaults() {
            return new DiagnosticsQuery(Severity.INFO, 0, true, 0, true, 0, 0, false, "en"); //$NON-NLS-1$
        }

        public static DiagnosticsQuery withSeverity(Severity minSeverity) {
            return new DiagnosticsQuery(minSeverity, 0, true, 0, true, 0, 0, false, "en"); //$NON-NLS-1$
        }

        /**
         * Backwards-compatible constructor for callers that predate the
         * {@code includeCheckHelp} / {@code helpLocale} fields.
         */
        public DiagnosticsQuery(
                Severity minSeverity, int maxItems, boolean includeSnippets, long waitMs,
                boolean includeRuntimeMarkers, int lineFrom, int lineTo) {
            this(minSeverity, maxItems, includeSnippets, waitMs, includeRuntimeMarkers,
                    lineFrom, lineTo, false, "en"); //$NON-NLS-1$
        }
    }

    /**
     * Single rich check-description block — emitted once per unique
     * {@code checkId} present in the diagnostics, only when the resolver
     * found bundled Markdown. Checks with no shipped description are simply
     * omitted from the {@code checkDetails} list.
     */
    public record CheckDetail(String checkId, String markdown) {}

    /**
     * Result of diagnostics collection.
     */
    public record DiagnosticsResult(
            String filePath,
            boolean editorDirty,
            List<EdtDiagnostic> diagnostics,
            int errorCount,
            int warningCount,
            int infoCount,
            List<CheckDetail> checkDetails) {

        /**
         * Backwards-compatible constructor for callers (and tests) that
         * predate {@code checkDetails}. Defaults to an empty list.
         */
        public DiagnosticsResult(
                String filePath, boolean editorDirty, List<EdtDiagnostic> diagnostics,
                int errorCount, int warningCount, int infoCount) {
            this(filePath, editorDirty, diagnostics, errorCount, warningCount, infoCount, List.of());
        }

        public boolean hasErrors() {
            return errorCount > 0;
        }

        public boolean hasDiagnostics() {
            return !diagnostics.isEmpty();
        }

        /**
         * Formats result for LLM consumption. Diagnostics that share a rule
         * (kebab check id, or identical message when the rule is unknown) are
         * collapsed into one compact line with an occurrence count and the list
         * of lines, so a module with hundreds of same-rule warnings stays
         * token-cheap. Singletons keep the detailed single-line form.
         */
        public String formatForLlm() {
            StringBuilder sb = new StringBuilder();
            sb.append("## Diagnostics: ").append(filePath).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$

            if (editorDirty) {
                sb.append("⚠️ *File not saved — diagnostics may be incomplete*\n\n"); //$NON-NLS-1$
            }

            if (diagnostics.isEmpty()) {
                sb.append("**Total:** 0 errors, 0 warnings, 0 info\n\n"); //$NON-NLS-1$
                sb.append("✅ No diagnostics found.\n"); //$NON-NLS-1$
                return sb.toString();
            }

            // Group preserving first-seen order; key = severity + rule (or message).
            java.util.LinkedHashMap<String, List<EdtDiagnostic>> groups = new java.util.LinkedHashMap<>();
            for (EdtDiagnostic d : diagnostics) {
                String key = d.severity().name() + " " + d.groupKey(); //$NON-NLS-1$
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(d);
            }

            sb.append("**Total:** "); //$NON-NLS-1$
            sb.append(errorCount).append(" errors, "); //$NON-NLS-1$
            sb.append(warningCount).append(" warnings, "); //$NON-NLS-1$
            sb.append(infoCount).append(" info ("); //$NON-NLS-1$
            sb.append(groups.size()).append(" unique rules)\n\n"); //$NON-NLS-1$

            boolean includeDebug = isDiagVerbose();
            // Partition groups into per-severity sections so the **SEVERITY** tag
            // is printed once as a section header, not repeated on every line.
            List<List<EdtDiagnostic>> errorGroups = new ArrayList<>();
            List<List<EdtDiagnostic>> warningGroups = new ArrayList<>();
            List<List<EdtDiagnostic>> infoGroups = new ArrayList<>();
            for (List<EdtDiagnostic> group : groups.values()) {
                switch (group.get(0).severity()) {
                    case ERROR -> errorGroups.add(group);
                    case WARNING -> warningGroups.add(group);
                    default -> infoGroups.add(group);
                }
            }
            appendSeveritySection(sb, "Errors", errorGroups, includeDebug); //$NON-NLS-1$
            appendSeveritySection(sb, "Warnings", warningGroups, includeDebug); //$NON-NLS-1$
            appendSeveritySection(sb, "Info", infoGroups, includeDebug); //$NON-NLS-1$

            if (checkDetails != null && !checkDetails.isEmpty()) {
                sb.append("\n## Check details\n\n"); //$NON-NLS-1$
                boolean first = true;
                for (CheckDetail detail : checkDetails) {
                    if (!first) {
                        sb.append("\n---\n\n"); //$NON-NLS-1$
                    }
                    first = false;
                    sb.append("### ").append(detail.checkId()).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
                    sb.append(detail.markdown());
                    if (!detail.markdown().endsWith("\n")) { //$NON-NLS-1$
                        sb.append("\n"); //$NON-NLS-1$
                    }
                }
            }

            return sb.toString();
        }

        /**
         * Appends a per-severity section ({@code ### Errors|Warnings|Info}) with
         * its groups. Singletons render in detail (no severity prefix — the
         * header carries it); repeats collapse to one grouped line. No-op when
         * the section is empty.
         */
        private static void appendSeveritySection(
                StringBuilder sb, String title, List<List<EdtDiagnostic>> groups, boolean includeDebug) {
            if (groups.isEmpty()) {
                return;
            }
            sb.append("### ").append(title).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
            for (List<EdtDiagnostic> group : groups) {
                if (group.size() == 1) {
                    sb.append(group.get(0).formatForLlm(includeDebug, false)).append("\n"); //$NON-NLS-1$
                } else {
                    sb.append(formatGroup(group)).append("\n"); //$NON-NLS-1$
                }
            }
            sb.append("\n"); //$NON-NLS-1$
        }

        /** Caps the representative-message sample shown for a collapsed group. */
        private static final int GROUP_SAMPLE_MAX = 160;

        /**
         * Renders a collapsed group of same-rule diagnostics:
         * <pre>- &lt;rule&gt; ×N — lines: 41(×2), 3376, 3917
         *     &lt;one representative message&gt;</pre>
         * Severity comes from the section header. Each line carries its own
         * occurrence count {@code (×k)} when more than one diagnostic of the
         * rule sits on it, so the total {@code ×N} always reconciles with the
         * list. A sample message gives the human-readable nature of the rule
         * (one of the group's messages — exact per-line specifics are at the
         * listed lines).
         */
        private static String formatGroup(List<EdtDiagnostic> group) {
            EdtDiagnostic head = group.get(0);
            StringBuilder sb = new StringBuilder();
            sb.append("- ").append(head.groupLabel()).append(" ×").append(group.size()); //$NON-NLS-1$ //$NON-NLS-2$

            // line -> occurrences on that line (sorted by line number)
            java.util.TreeMap<Integer, Integer> lineCounts = new java.util.TreeMap<>();
            for (EdtDiagnostic d : group) {
                int ln = d.lineNumber();
                if (ln > 0) {
                    lineCounts.merge(ln, 1, Integer::sum);
                }
            }
            if (!lineCounts.isEmpty()) {
                sb.append(" — lines: "); //$NON-NLS-1$
                boolean first = true;
                for (Map.Entry<Integer, Integer> e : lineCounts.entrySet()) {
                    if (!first) {
                        sb.append(", "); //$NON-NLS-1$
                    }
                    first = false;
                    sb.append(e.getKey());
                    if (e.getValue() > 1) {
                        sb.append("(×").append(e.getValue()).append(")"); //$NON-NLS-1$ //$NON-NLS-2$
                    }
                }
            }

            String msg = head.message();
            if (msg != null && !msg.isBlank()) {
                String sample = msg.strip();
                if (sample.length() > GROUP_SAMPLE_MAX) {
                    sample = sample.substring(0, GROUP_SAMPLE_MAX - 1) + "…"; //$NON-NLS-1$
                }
                sb.append("\n    ").append(sample); //$NON-NLS-1$
            }
            return sb.toString();
        }
    }

    /**
     * Builds a deduplicated {@link CheckDetail} list for the diagnostics. Only
     * checks whose contributor bundle actually ships an HTML description make
     * it into the list — others are silently omitted so callers don't pay
     * tokens for empty entries.
     */
    static List<CheckDetail> buildCheckDetails(List<EdtDiagnostic> diagnostics, String locale) {
        if (diagnostics == null || diagnostics.isEmpty()) {
            return List.of();
        }
        CheckInfoResolver resolver = CheckInfoResolver.getInstance();
        LinkedHashSet<String> seenIds = new LinkedHashSet<>();
        List<CheckDetail> details = new ArrayList<>();
        for (EdtDiagnostic d : diagnostics) {
            String id = d.checkId();
            if (id == null || id.isBlank() || !seenIds.add(id)) {
                continue;
            }
            resolver.findMarkdown(id, locale).ifPresent(md -> details.add(new CheckDetail(id, md)));
        }
        return List.copyOf(details);
    }

    /**
     * Collects diagnostics for the active editor.
     *
     * @param query collection parameters
     * @return future with diagnostics result
     */
    public CompletableFuture<DiagnosticsResult> collectFromActiveEditor(DiagnosticsQuery query) {
        // Wait in background thread (if requested), then collect on UI thread
        return CompletableFuture.supplyAsync(() -> {
            // Wait if requested (for EDT to recalculate diagnostics) - in background
            if (query.waitMs() > 0) {
                try {
                    Thread.sleep(query.waitMs());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return query;
        }).thenCompose(q -> {
            // Collect diagnostics on UI thread
            CompletableFuture<DiagnosticsResult> future = new CompletableFuture<>();

            Display.getDefault().asyncExec(() -> {
                try {
                    ITextEditor editor = getActiveTextEditor();
                    if (editor == null) {
                        future.complete(new DiagnosticsResult(
                                "no active editor", false, List.of(), 0, 0, 0)); //$NON-NLS-1$
                        return;
                    }

                    IEditorInput input = editor.getEditorInput();
                    IFile file = resolveFile(input);
                    String filePath = file != null ? file.getFullPath().toString() : input.getName();
                    boolean dirty = editor.isDirty();

                    List<EdtDiagnostic> diagnostics = new ArrayList<>();
                    Set<String> seen = new HashSet<>();

                    // Strategy 1: Collect from markers (saved state)
                    // Skip markers if editor is dirty - they reflect saved state, not current buffer
                    if (file != null && !dirty) {
                        collectFromMarkers(file, filePath, q, diagnostics, seen);
                    }

                    // Strategy 2: Collect from annotations (unsaved/real-time)
                    // For dirty editors, annotations are the primary source
                    IDocumentProvider docProvider = editor.getDocumentProvider();
                    IDocument document = docProvider != null ? docProvider.getDocument(input) : null;
                    IAnnotationModel annotationModel = docProvider != null
                            ? docProvider.getAnnotationModel(input) : null;

                    if (annotationModel != null && document != null) {
                        collectFromAnnotations(annotationModel, document, filePath,
                                file != null ? file.getProject() : null, q, diagnostics, seen);
                    }

                    // Sort by severity (errors first) then by line
                    diagnostics.sort(Comparator
                            .comparing((EdtDiagnostic d) -> d.severity().getLevel()).reversed()
                            .thenComparing(EdtDiagnostic::lineNumber));

                    // Filter by line range (no-op when lineFrom=lineTo=0) then limit
                    diagnostics = applyLineFilter(diagnostics, q);
                    diagnostics = applyResultLimit(diagnostics, q.maxItems());

                    // Count by severity
                    int errors = (int) diagnostics.stream().filter(d -> d.severity() == Severity.ERROR).count();
                    int warnings = (int) diagnostics.stream().filter(d -> d.severity() == Severity.WARNING).count();
                    int infos = diagnostics.size() - errors - warnings;

                    List<CheckDetail> details = q.includeCheckHelp()
                            ? buildCheckDetails(diagnostics, q.helpLocale())
                            : List.of();
                    future.complete(new DiagnosticsResult(
                            filePath, dirty, diagnostics, errors, warnings, infos, details));

                } catch (Exception e) {
                    LOG.error("Error collecting diagnostics: %s", e.getMessage()); //$NON-NLS-1$
                    future.completeExceptionally(e);
                }
            });

            return future;
        });
    }

    /**
     * Collects diagnostics for a specific file by path.
     *
     * @param filePath workspace-relative path (e.g., "/Project/src/Module.bsl")
     * @param query collection parameters
     * @return future with diagnostics result
     */
    public CompletableFuture<DiagnosticsResult> collectFromFile(String filePath, DiagnosticsQuery query) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                diagInfo("[get_diagnostics] scope=file path='%s' severity=%s maxItems=%d includeRuntime=%s", //$NON-NLS-1$
                        filePath, query.minSeverity(), query.maxItems(), query.includeRuntimeMarkers());
                if (query.waitMs() > 0) {
                    try {
                        Thread.sleep(query.waitMs());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }

                ResolvedFileContext context = resolveFileContext(filePath);
                if (context.file() == null) {
                    throw new IllegalArgumentException("File not found in workspace: " + filePath); //$NON-NLS-1$
                }

                String resultPath = context.resolvedPath() != null ? context.resolvedPath() : normalizePath(filePath);
                List<EdtDiagnostic> diagnostics = new ArrayList<>();
                Set<String> seen = new HashSet<>();

                collectFromMarkers(context.file(), resultPath, query, diagnostics, seen);
                if (query.includeRuntimeMarkers() && context.project() != null) {
                    collectRuntimeFileMarkers(context, query, diagnostics, seen);
                }
                collectFromOpenEditorAnnotations(context.file(), resultPath, query, diagnostics, seen);
                collectFromDcsSchemaValidator(context.file(), resultPath, query, diagnostics, seen);

                diagnostics.sort(Comparator
                        .comparing((EdtDiagnostic d) -> d.severity().getLevel()).reversed()
                        .thenComparing(EdtDiagnostic::filePath, Comparator.nullsLast(String::compareTo))
                        .thenComparing(EdtDiagnostic::lineNumber));

                diagnostics = applyLineFilter(diagnostics, query);
                diagnostics = applyResultLimit(diagnostics, query.maxItems());

                int errors = (int) diagnostics.stream().filter(d -> d.severity() == Severity.ERROR).count();
                int warnings = (int) diagnostics.stream().filter(d -> d.severity() == Severity.WARNING).count();
                int infos = diagnostics.size() - errors - warnings;

                diagInfo("[get_diagnostics] result: %d items (errors=%d warnings=%d infos=%d) path='%s'", //$NON-NLS-1$
                        diagnostics.size(), errors, warnings, infos, resultPath);
                List<CheckDetail> details = query.includeCheckHelp()
                        ? buildCheckDetails(diagnostics, query.helpLocale())
                        : List.of();
                return new DiagnosticsResult(
                        resultPath, false, diagnostics, errors, warnings, infos, details);

            } catch (IllegalArgumentException e) {
                LOG.warn("[get_diagnostics] failed for path='%s': %s", filePath, e.getMessage()); //$NON-NLS-1$
                throw e; // propagate as exceptional future completion (e.g. file-not-found)
            } catch (Exception e) {
                LOG.error("[get_diagnostics] internal error for path='%s': %s — %s", //$NON-NLS-1$
                        filePath, e.getClass().getSimpleName(), e.getMessage());
                return new DiagnosticsResult(filePath, false, List.of(), 0, 0, 0);
            }
        });
    }

    private ResolvedFileContext resolveFileContext(String requestedPath) {
        String normalizedPath = normalizePath(requestedPath);
        String pathWithoutLeadingSlash = removeLeadingSlash(normalizedPath);
        List<String> relativeCandidates = buildRelativePathCandidates(pathWithoutLeadingSlash);
        // The match-only subset drops candidates that still carry a known
        // workspace project name as their first segment. Tokens / pathHints
        // come from THIS subset so the project name never enters the
        // ALL-tokens marker filter — without this the with-project-prefix
        // input shape silently returned 0/0/0 because the project segment
        // became a "discriminating" token that never appeared in any
        // marker haystack.
        List<String> matchCandidates = RelativePathCandidates.buildForMatch(
                pathWithoutLeadingSlash, knownWorkspaceProjectNames());

        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();

        // 1) Workspace-relative form: /<project>/...
        IFile directFile = root.getFile(new Path(withLeadingSlash(pathWithoutLeadingSlash)));
        if (directFile != null && directFile.exists()) {
            String resolvedPath = directFile.getFullPath().toString();
            return new ResolvedFileContext(
                    requestedPath,
                    resolvedPath,
                    directFile.getProject(),
                    directFile,
                    buildRuntimePathHints(pathWithoutLeadingSlash, matchCandidates),
                    buildMatchTokens(matchCandidates),
                    computeTokenThreshold(buildMatchTokens(matchCandidates)));
        }

        // 2) Project-relative form: src/... or Configuration/src/...
        List<IProject> projects = resolveDiagnosticsProjects();
        if (projects.isEmpty()) {
            projects = Arrays.stream(root.getProjects())
                    .filter(this::isAccessibleProject)
                    .sorted(Comparator.comparing(IProject::getName, String.CASE_INSENSITIVE_ORDER))
                    .toList();
        }
        for (IProject project : projects) {
            for (String candidate : relativeCandidates) {
                IFile file = project.getFile(candidate);
                if (file != null && file.exists()) {
                    String resolvedPath = file.getFullPath().toString();
                    return new ResolvedFileContext(
                            requestedPath,
                            resolvedPath,
                            project,
                            file,
                            buildRuntimePathHints(candidate, matchCandidates),
                            buildMatchTokens(matchCandidates),
                            computeTokenThreshold(buildMatchTokens(matchCandidates)));
                }
            }
        }

        IProject project = resolveProjectForPath(pathWithoutLeadingSlash, projects, root);
        String synthesizedPath = project != null
                ? "/" + project.getName() + "/" + preferredRelativePath(matchCandidates) //$NON-NLS-1$ //$NON-NLS-2$
                : withLeadingSlash(pathWithoutLeadingSlash);
        List<String> tokens = buildMatchTokens(matchCandidates);
        return new ResolvedFileContext(
                requestedPath,
                synthesizedPath,
                project,
                null,
                buildRuntimePathHints(preferredRelativePath(matchCandidates), matchCandidates),
                tokens,
                computeTokenThreshold(tokens));
    }

    private IProject resolveProjectForPath(String pathWithoutLeadingSlash, List<IProject> projects, IWorkspaceRoot root) {
        String normalized = normalizePath(pathWithoutLeadingSlash);
        int firstSlash = normalized.indexOf('/');
        String firstSegment = firstSlash > 0 ? normalized.substring(0, firstSlash) : normalized;
        if (!firstSegment.isBlank()) {
            IProject byName = root.getProject(firstSegment);
            if (isAccessibleProject(byName)) {
                return byName;
            }
        }

        String defaultProjectName = resolveDefaultProjectName();
        if (defaultProjectName != null && !defaultProjectName.isBlank()) {
            IProject defaultProject = root.getProject(defaultProjectName);
            if (isAccessibleProject(defaultProject)) {
                return defaultProject;
            }
        }

        return projects.size() == 1 ? projects.get(0) : null;
    }

    private String normalizePath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return ""; //$NON-NLS-1$
        }
        return rawPath.trim().replace('\\', '/');
    }

    private String removeLeadingSlash(String path) {
        if (path == null) {
            return ""; //$NON-NLS-1$
        }
        String normalized = path;
        while (normalized.startsWith("/")) { //$NON-NLS-1$
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    private String withLeadingSlash(String pathWithoutLeadingSlash) {
        String normalized = removeLeadingSlash(pathWithoutLeadingSlash);
        return "/" + normalized; //$NON-NLS-1$
    }

    private List<String> buildRelativePathCandidates(String pathWithoutLeadingSlash) {
        // Delegates to the pure-Java RelativePathCandidates utility so the
        // project-name-stripping rule can be unit-tested without an open
        // workspace. See 2026-05-19-diagnostics-space-in-project-name.md.
        return RelativePathCandidates.build(pathWithoutLeadingSlash, knownWorkspaceProjectNames());
    }

    private Set<String> knownWorkspaceProjectNames() {
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
        IProject[] projects = root.getProjects();
        if (projects == null || projects.length == 0) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (IProject project : projects) {
            if (project != null && project.getName() != null && !project.getName().isBlank()) {
                out.add(project.getName());
            }
        }
        return out;
    }

    private String preferredRelativePath(List<String> relativeCandidates) {
        if (relativeCandidates == null || relativeCandidates.isEmpty()) {
            return ""; //$NON-NLS-1$
        }
        for (String candidate : relativeCandidates) {
            String lower = candidate.toLowerCase(Locale.ROOT);
            if (!lower.startsWith("configuration/") && !lower.startsWith("конфигурация/")) { //$NON-NLS-1$ //$NON-NLS-2$
                return candidate;
            }
        }
        return relativeCandidates.get(0);
    }

    private List<String> buildRuntimePathHints(String preferredRelativePath, List<String> relativeCandidates) {
        LinkedHashSet<String> hints = new LinkedHashSet<>();
        if (preferredRelativePath != null && !preferredRelativePath.isBlank()) {
            String normalized = preferredRelativePath.toLowerCase(Locale.ROOT);
            hints.add(normalized);
            hints.add(normalized.replace('/', '.'));
        }

        for (String candidate : relativeCandidates) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            String normalized = candidate.toLowerCase(Locale.ROOT);
            hints.add(normalized);
            hints.add(normalized.replace('/', '.'));
        }
        return List.copyOf(hints);
    }

    private List<String> buildMatchTokens(List<String> relativeCandidates) {
        return PathMatchTokens.buildMatchTokens(relativeCandidates);
    }

    private int computeTokenThreshold(List<String> tokens) {
        return PathMatchTokens.computeTokenThreshold(tokens);
    }

    private void collectRuntimeFileMarkers(
            ResolvedFileContext context,
            DiagnosticsQuery query,
            List<EdtDiagnostic> diagnostics,
            Set<String> seen) {

        IMarkerManager markerManager = getMarkerManager();
        if (markerManager == null || context.project() == null) {
            diagInfo("[get_diagnostics] runtime-markers: SKIPPED (markerManager=%s project=%s)", //$NON-NLS-1$
                    markerManager != null, context.project() != null);
            return;
        }

        Map<String, CheckMetadata> checkMetadata = loadCheckMetadata();
        MarkerFilter projectFilter = MarkerFilter.createProjectFilter(context.project());
        int sizeBefore = diagnostics.size();
        int[] counters = new int[] {0, 0, 0, 0}; // raw, matched, severityDropped, blankDropped
        StringBuilder sampleSink = new StringBuilder();

        try (Stream<Marker> stream = markerManager.markers(projectFilter)) {
            int preLimit = getSoftScanLimit(query.maxItems(), 10);
            stream
                    .peek(marker -> {
                        counters[0]++;
                        if (counters[0] <= 3) {
                            // Sample first three markers raw to help diagnose
                            // why file-scope reports 0/0/0 while EDT GUI shows
                            // warnings. Captures the haystack fields the
                            // context-token filter compares against.
                            sampleSink.append("  raw[").append(counters[0]).append("]: ") //$NON-NLS-1$ //$NON-NLS-2$
                                    .append("checkId=").append(safeString(marker.getCheckId())) //$NON-NLS-1$
                                    .append(" sev=").append(marker.getSeverity()) //$NON-NLS-1$
                                    .append(" location=").append(safeString(marker.getLocation())) //$NON-NLS-1$
                                    .append(" objPres=").append(safeString(marker.getObjectPresentation())) //$NON-NLS-1$
                                    .append(" sourceObjId=").append(safeObjectString(marker.getSourceObjectId())) //$NON-NLS-1$
                                    .append(" topObjId=").append(safeObjectString(marker.getTopObjectId())) //$NON-NLS-1$
                                    .append('\n');
                        }
                    })
                    .filter(marker -> markerMatchesContext(marker, context))
                    .peek(marker -> counters[1]++)
                    .limit(preLimit)
                    .forEach(marker -> {
                        Severity sev = fromRuntimeSeverity(marker.getSeverity());
                        if (sev.getLevel() < query.minSeverity().getLevel()) {
                            counters[2]++;
                            return;
                        }

                        String message = safeString(marker.getMessage());
                        if (message.isBlank()) {
                            counters[3]++;
                            return;
                        }

                        String checkId = safeString(marker.getCheckId());
                        CheckMetadata meta = checkMetadata.get(checkId);
                        String kebab = resolveCheckIdFromShortUid(checkId, context.project());
                        if (kebab != null) {
                            checkId = kebab;
                        }
                        String key = context.resolvedPath() + ":" + checkId + ":" + message + ":" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                                + safeString(marker.getLocation()) + ":" + safeString(marker.getObjectPresentation()); //$NON-NLS-1$
                        if (!seen.add(key)) {
                            return;
                        }

                        String locationText = safeString(marker.getLocation());
                        diagnostics.add(EdtDiagnostic.fromRuntimeMarker(
                                context.resolvedPath(),
                                parseLineFromLocation(locationText),
                                message,
                                sev,
                                safeString(marker.getSourceType()),
                                checkId,
                                meta != null ? meta.title() : null,
                                meta != null ? meta.description() : null,
                                meta != null ? meta.issueType() : null,
                                meta != null ? meta.issueSeverity() : null,
                                safeString(marker.getObjectPresentation()),
                                locationText));
                    });
            diagInfo("[get_diagnostics] runtime-markers: raw=%d matched=%d sevDrop=%d blankDrop=%d emitted=%d tokens=%s threshold=%d hints=%d", //$NON-NLS-1$
                    counters[0], counters[1], counters[2], counters[3],
                    diagnostics.size() - sizeBefore,
                    context.matchTokens(),
                    context.tokenThreshold(),
                    context.pathHints() != null ? context.pathHints().size() : 0);
            if (sampleSink.length() > 0) {
                diagInfo("[get_diagnostics] runtime-markers sample (first %d raw):\n%s", //$NON-NLS-1$
                        Math.min(counters[0], 3), sampleSink.toString());
            }
        } catch (Exception e) {
            LOG.warn("[get_diagnostics] Runtime marker manager file diagnostics unavailable for %s: %s", //$NON-NLS-1$
                    context.resolvedPath(), e.getMessage());
        }
    }

    private boolean markerMatchesContext(Marker marker, ResolvedFileContext context) {
        if (marker == null || context == null) {
            return false;
        }
        String haystack = buildRuntimeMarkerHaystack(marker);
        if (haystack.isBlank()) {
            return false;
        }

        for (String hint : context.pathHints()) {
            if (hint != null && !hint.isBlank() && haystack.contains(hint)) {
                return true;
            }
        }

        int threshold = context.tokenThreshold();
        if (threshold <= 0) {
            return false;
        }

        int matches = 0;
        for (String token : context.matchTokens()) {
            if (token != null && !token.isBlank()
                    && com.codepilot1c.core.diagnostics.PathMatchTokens.matchesAsWord(haystack, token)) {
                matches++;
                if (matches >= threshold) {
                    return true;
                }
            }
        }
        return false;
    }

    private String buildRuntimeMarkerHaystack(Marker marker) {
        String markerObjectId = safeObjectString(marker.getMarkerObjectId());
        String sourceObjectId = safeObjectString(marker.getSourceObjectId());
        String topObjectId = safeObjectString(marker.getTopObjectId());
        String objectPresentation = safeString(marker.getObjectPresentation());
        String location = safeString(marker.getLocation());
        String haystack = String.join(" ", markerObjectId, sourceObjectId, topObjectId, objectPresentation, location); //$NON-NLS-1$
        return decodePercentEncoded(haystack).toLowerCase(Locale.ROOT);
    }

    private String safeObjectString(Object value) {
        return value != null ? String.valueOf(value) : ""; //$NON-NLS-1$
    }

    private String decodePercentEncoded(String value) {
        if (value == null || value.isBlank() || value.indexOf('%') < 0) {
            return value != null ? value : ""; //$NON-NLS-1$
        }
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return value;
        }
    }

    private List<EdtDiagnostic> applyResultLimit(List<EdtDiagnostic> diagnostics, int maxItems) {
        if (diagnostics == null || diagnostics.isEmpty() || maxItems <= 0) {
            return diagnostics;
        }
        if (diagnostics.size() <= maxItems) {
            return diagnostics;
        }
        return new ArrayList<>(diagnostics.subList(0, maxItems));
    }

    private List<EdtDiagnostic> applyLineFilter(List<EdtDiagnostic> diagnostics, DiagnosticsQuery query) {
        if (diagnostics == null || diagnostics.isEmpty() || query == null) {
            return diagnostics;
        }
        if (DiagnosticsLineFilter.isDisabled(query.lineFrom(), query.lineTo())) {
            return diagnostics;
        }
        int[] range = DiagnosticsLineFilter.normalize(query.lineFrom(), query.lineTo());
        int from = range[0];
        int to = range[1];
        List<EdtDiagnostic> filtered = new ArrayList<>(diagnostics.size());
        for (EdtDiagnostic d : diagnostics) {
            if (DiagnosticsLineFilter.matches(d.lineNumber(), from, to)) {
                filtered.add(d);
            }
        }
        return filtered;
    }

    private int getSoftScanLimit(int maxItems, int multiplier) {
        if (maxItems <= 0) {
            return Integer.MAX_VALUE;
        }
        long result = (long) maxItems * Math.max(1, multiplier);
        return result >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) result;
    }

    /**
     * Collects diagnostics for the whole project.
     *
     * @param projectName workspace project name
     * @param query collection parameters
     * @return future with diagnostics result
     */
    public CompletableFuture<DiagnosticsResult> collectFromProject(String projectName, DiagnosticsQuery query) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (query.waitMs() > 0) {
                    try {
                        Thread.sleep(query.waitMs());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }

                if (projectName == null || projectName.isBlank()) {
                    return new DiagnosticsResult("project not specified", false, List.of(), 0, 0, 0); //$NON-NLS-1$
                }

                IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
                IProject project = root.getProject(projectName);
                if (project == null || !project.exists()) {
                    return new DiagnosticsResult("/" + projectName, false, List.of(), 0, 0, 0); //$NON-NLS-1$
                }

                List<EdtDiagnostic> diagnostics = new ArrayList<>();
                Set<String> seen = new HashSet<>();

                collectWorkspaceProjectMarkers(project, query, diagnostics, seen);
                if (query.includeRuntimeMarkers()) {
                    collectRuntimeProjectMarkers(project, query, diagnostics, seen);
                }

                diagnostics.sort(Comparator
                        .comparing((EdtDiagnostic d) -> d.severity().getLevel()).reversed()
                        .thenComparing(EdtDiagnostic::filePath, Comparator.nullsLast(String::compareTo))
                        .thenComparing(EdtDiagnostic::lineNumber));

                diagnostics = applyLineFilter(diagnostics, query);
                diagnostics = applyResultLimit(diagnostics, query.maxItems());

                int errors = (int) diagnostics.stream().filter(d -> d.severity() == Severity.ERROR).count();
                int warnings = (int) diagnostics.stream().filter(d -> d.severity() == Severity.WARNING).count();
                int infos = diagnostics.size() - errors - warnings;

                List<CheckDetail> details = query.includeCheckHelp()
                        ? buildCheckDetails(diagnostics, query.helpLocale())
                        : List.of();
                return new DiagnosticsResult(
                        "/" + projectName, false, diagnostics, errors, warnings, infos, details); //$NON-NLS-1$

            } catch (Exception e) {
                LOG.error("Error collecting diagnostics for project %s: %s", projectName, e.getMessage()); //$NON-NLS-1$
                return new DiagnosticsResult("/" + projectName, false, List.of(), 0, 0, 0); //$NON-NLS-1$
            }
        });
    }

    /**
     * Collects diagnostics across workspace projects when a single target project
     * cannot be resolved.
     *
     * @param query collection parameters
     * @return future with diagnostics result
     */
    public CompletableFuture<DiagnosticsResult> collectFromWorkspace(DiagnosticsQuery query) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (query.waitMs() > 0) {
                    try {
                        Thread.sleep(query.waitMs());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }

                List<IProject> projects = resolveDiagnosticsProjects();
                if (projects.isEmpty()) {
                    return new DiagnosticsResult("/workspace", false, List.of(), 0, 0, 0); //$NON-NLS-1$
                }

                List<EdtDiagnostic> diagnostics = new ArrayList<>();
                Set<String> seen = new HashSet<>();

                for (IProject project : projects) {
                    collectWorkspaceProjectMarkers(project, query, diagnostics, seen);
                    if (query.includeRuntimeMarkers()) {
                        collectRuntimeProjectMarkers(project, query, diagnostics, seen);
                    }
                }

                diagnostics.sort(Comparator
                        .comparing((EdtDiagnostic d) -> d.severity().getLevel()).reversed()
                        .thenComparing(EdtDiagnostic::filePath, Comparator.nullsLast(String::compareTo))
                        .thenComparing(EdtDiagnostic::lineNumber));

                diagnostics = applyLineFilter(diagnostics, query);
                diagnostics = applyResultLimit(diagnostics, query.maxItems());

                int errors = (int) diagnostics.stream().filter(d -> d.severity() == Severity.ERROR).count();
                int warnings = (int) diagnostics.stream().filter(d -> d.severity() == Severity.WARNING).count();
                int infos = diagnostics.size() - errors - warnings;

                List<CheckDetail> details = query.includeCheckHelp()
                        ? buildCheckDetails(diagnostics, query.helpLocale())
                        : List.of();
                return new DiagnosticsResult(
                        "/workspace", false, diagnostics, errors, warnings, infos, details); //$NON-NLS-1$
            } catch (Exception e) {
                LOG.error("Error collecting diagnostics for workspace: %s", e.getMessage()); //$NON-NLS-1$
                return new DiagnosticsResult("/workspace", false, List.of(), 0, 0, 0); //$NON-NLS-1$
            }
        });
    }

    /**
     * Resolves a default project suitable for diagnostics. Returns {@code null}
     * when project cannot be determined unambiguously.
     *
     * @return project name or {@code null}
     */
    public String resolveDefaultProjectName() {
        IApplicationManager applicationManager = getApplicationManager();
        if (applicationManager != null) {
            try {
                var defaultProject = applicationManager.getDefaultProject();
                if (defaultProject.isPresent() && isAccessibleProject(defaultProject.get())) {
                    return defaultProject.get().getName();
                }
            } catch (Exception e) {
                LOG.warn("Unable to resolve default EDT project from application manager: %s", e.getMessage()); //$NON-NLS-1$
            }
        }

        List<IProject> projects = resolveDiagnosticsProjects();
        return projects.size() == 1 ? projects.get(0).getName() : null;
    }

    private List<IProject> resolveDiagnosticsProjects() {
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
        List<IProject> openProjects = Arrays.stream(root.getProjects())
                .filter(this::isAccessibleProject)
                .sorted(Comparator.comparing(IProject::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();
        if (openProjects.isEmpty()) {
            return List.of();
        }

        IApplicationManager applicationManager = getApplicationManager();
        if (applicationManager != null) {
            List<IProject> applicationProjects = new ArrayList<>();
            for (IProject project : openProjects) {
                try {
                    if (!applicationManager.getApplications(project).isEmpty()) {
                        applicationProjects.add(project);
                    }
                } catch (Exception e) {
                    LOG.debug("Application probing failed for project %s: %s", //$NON-NLS-1$
                            project.getName(), e.getMessage());
                }
            }
            if (!applicationProjects.isEmpty()) {
                return applicationProjects;
            }
        }

        List<IProject> edtLayoutProjects = openProjects.stream()
                .filter(this::looksLikeEdtProject)
                .toList();
        if (!edtLayoutProjects.isEmpty()) {
            return edtLayoutProjects;
        }

        return openProjects;
    }

    private boolean isAccessibleProject(IProject project) {
        return project != null && project.exists() && project.isAccessible();
    }

    private boolean looksLikeEdtProject(IProject project) {
        return project.getFile("src/Configuration/Configuration.mdo").exists() //$NON-NLS-1$
                || project.getFile("Configuration/Configuration.mdo").exists(); //$NON-NLS-1$
    }

    private void collectFromMarkers(
            IFile file,
            String filePath,
            DiagnosticsQuery query,
            List<EdtDiagnostic> diagnostics,
            Set<String> seen) {

        int sizeBefore = diagnostics.size();
        Map<String, Integer> typeHistogram = new HashMap<>();
        try {
            IMarker[] markers = file.findMarkers(null, true, IResource.DEPTH_ZERO);
            for (IMarker probe : markers) {
                String t;
                try { t = probe.getType(); } catch (CoreException ex) { t = "?"; } //$NON-NLS-1$
                typeHistogram.merge(t, 1, Integer::sum);
            }
            diagInfo("[get_diagnostics] file-markers: file=%s rawCount=%d types=%s", //$NON-NLS-1$
                    file.getFullPath(), markers.length, typeHistogram);

            for (IMarker marker : markers) {
                int severity = marker.getAttribute(IMarker.SEVERITY, -1);
                Severity sev = Severity.fromMarkerSeverity(severity);

                // Filter by minimum severity
                if (sev.getLevel() < query.minSeverity().getLevel()) {
                    continue;
                }

                String message = String.valueOf(marker.getAttribute(IMarker.MESSAGE, "")); //$NON-NLS-1$
                int line = marker.getAttribute(IMarker.LINE_NUMBER, -1);
                int charStart = marker.getAttribute(IMarker.CHAR_START, -1);
                int charEnd = marker.getAttribute(IMarker.CHAR_END, -1);
                String markerType = safeGetMarkerType(marker);

                // Deduplicate by location + message
                String key = line + ":" + charStart + ":" + message; //$NON-NLS-1$ //$NON-NLS-2$
                if (seen.contains(key)) {
                    continue;
                }
                seen.add(key);

                // Skip empty messages
                if (message.isBlank()) {
                    continue;
                }

                String snippet = query.includeSnippets()
                        ? getSnippetFromFile(file, line, charStart, charEnd)
                        : null;

                diagnostics.add(EdtDiagnostic.fromMarker(
                        filePath, line, charStart, charEnd, message, severity, markerType, snippet));
            }
            diagInfo("[get_diagnostics] file-markers emitted=%d (of %d raw)", //$NON-NLS-1$
                    diagnostics.size() - sizeBefore, typeHistogram.values().stream().mapToInt(Integer::intValue).sum());
        } catch (CoreException e) {
            LOG.error("Error finding markers: %s", e.getMessage()); //$NON-NLS-1$
        }
    }

    private void collectWorkspaceProjectMarkers(
            IProject project,
            DiagnosticsQuery query,
            List<EdtDiagnostic> diagnostics,
            Set<String> seen) {

        try {
            IMarker[] markers = project.findMarkers(null, true, IResource.DEPTH_INFINITE);
            LOG.debug("Found %d workspace markers for project %s", markers.length, project.getName()); //$NON-NLS-1$

            int preLimit = getSoftScanLimit(query.maxItems(), 5);
            int count = 0;
            for (IMarker marker : markers) {
                if (count >= preLimit) {
                    break;
                }

                int severity = marker.getAttribute(IMarker.SEVERITY, -1);
                Severity sev = Severity.fromMarkerSeverity(severity);
                if (sev.getLevel() < query.minSeverity().getLevel()) {
                    continue;
                }

                String message = String.valueOf(marker.getAttribute(IMarker.MESSAGE, "")); //$NON-NLS-1$
                if (message.isBlank()) {
                    continue;
                }

                int line = marker.getAttribute(IMarker.LINE_NUMBER, -1);
                int charStart = marker.getAttribute(IMarker.CHAR_START, -1);
                int charEnd = marker.getAttribute(IMarker.CHAR_END, -1);
                String markerType = safeGetMarkerType(marker);
                String markerPath = marker.getResource() != null
                        ? marker.getResource().getFullPath().toString()
                        : project.getFullPath().toString();

                String key = markerPath + ":" + line + ":" + charStart + ":" + message; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                if (!seen.add(key)) {
                    continue;
                }

                diagnostics.add(EdtDiagnostic.fromMarker(
                        markerPath, line, charStart, charEnd, message, severity, markerType, null));
                count++;
            }
        } catch (CoreException e) {
            LOG.error("Error collecting project markers for %s: %s", project.getName(), e.getMessage()); //$NON-NLS-1$
        }
    }

    private void collectRuntimeProjectMarkers(
            IProject project,
            DiagnosticsQuery query,
            List<EdtDiagnostic> diagnostics,
            Set<String> seen) {

        IMarkerManager markerManager = getMarkerManager();
        if (markerManager == null) {
            return;
        }

        Map<String, CheckMetadata> checkMetadata = loadCheckMetadata();
        MarkerFilter projectFilter = MarkerFilter.createProjectFilter(project);

        try (Stream<Marker> stream = markerManager.markers(projectFilter)) {
            int preLimit = getSoftScanLimit(query.maxItems(), 5);
            stream.limit(preLimit).forEach(marker -> {
                Severity sev = fromRuntimeSeverity(marker.getSeverity());
                if (sev.getLevel() < query.minSeverity().getLevel()) {
                    return;
                }

                String message = safeString(marker.getMessage());
                if (message.isBlank()) {
                    return;
                }

                String checkId = safeString(marker.getCheckId());
                CheckMetadata meta = checkMetadata.get(checkId);
                String kebab = resolveCheckIdFromShortUid(checkId, project);
                if (kebab != null) {
                    checkId = kebab;
                }
                String markerPath = marker.getProject() != null
                        ? marker.getProject().getFullPath().toString()
                        : project.getFullPath().toString();
                String location = safeString(marker.getLocation());
                String objectPresentation = safeString(marker.getObjectPresentation());

                String key = markerPath + ":" + checkId + ":" + message + ":" + location + ":" + objectPresentation; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                if (!seen.add(key)) {
                    return;
                }

                diagnostics.add(EdtDiagnostic.fromRuntimeMarker(
                        markerPath,
                        parseLineFromLocation(location),
                        message,
                        sev,
                        safeString(marker.getSourceType()),
                        checkId,
                        meta != null ? meta.title() : null,
                        meta != null ? meta.description() : null,
                        meta != null ? meta.issueType() : null,
                        meta != null ? meta.issueSeverity() : null,
                        objectPresentation,
                        location));
            });
        } catch (Exception e) {
            LOG.warn("Runtime marker manager diagnostics unavailable for %s: %s", //$NON-NLS-1$
                    project.getName(), e.getMessage());
        }
    }

    private void collectFromAnnotations(
            IAnnotationModel model,
            IDocument document,
            String filePath,
            IProject project,
            DiagnosticsQuery query,
            List<EdtDiagnostic> diagnostics,
            Set<String> seen) {

        Iterator<?> it = model.getAnnotationIterator();
        int count = 0;
        int totalSeen = 0;
        int problemMatched = 0;
        Map<String, Integer> typeHistogram = new HashMap<>();
        int sizeBefore = diagnostics.size();

        while (it.hasNext()) {
            Object obj = it.next();
            if (!(obj instanceof Annotation ann)) {
                continue;
            }
            totalSeen++;

            String annType = ann.getType();
            if (annType != null) {
                typeHistogram.merge(annType, 1, Integer::sum);
            }
            if (annType == null || !isProblemAnnotation(annType)) {
                continue;
            }
            problemMatched++;

            String text = Objects.toString(ann.getText(), ""); //$NON-NLS-1$
            if (text.isBlank()) {
                continue;
            }

            Position pos = model.getPosition(ann);
            int offset = pos != null ? pos.offset : -1;
            int length = pos != null ? pos.length : 0;
            int line = safeGetLineOfOffset(document, offset);
            int charEnd = offset + length;

            Severity sev = getSeverityFromAnnotationType(annType);
            if (sev.getLevel() < query.minSeverity().getLevel()) {
                continue;
            }

            String key = line + ":" + offset + ":" + text; //$NON-NLS-1$ //$NON-NLS-2$
            if (seen.contains(key)) {
                continue;
            }
            seen.add(key);

            String markerType = null;
            if (ann instanceof MarkerAnnotation markerAnn) {
                IMarker marker = markerAnn.getMarker();
                if (marker != null) {
                    markerType = safeGetMarkerType(marker);
                }
            }

            String snippet = query.includeSnippets()
                    ? getSnippetFromDocument(document, offset, length)
                    : null;

            // Open Xtext editors expose problem annotations as XtextAnnotation,
            // which carries the check code via getIssueCode(). We avoid a hard
            // dependency on org.eclipse.xtext.ui (not imported by this UI
            // bundle) and probe it reflectively — the recovered code is the
            // candidate v8-code-style identifier we want to surface.
            // Resolve the stable kebab check id (used for grouping, the [code]
            // tag, and check-help lookup); fall back to the SU short code when
            // the registry can't resolve it. Cheap in-memory lookup.
            String issueCode = reflectIssueCode(ann);
            String checkId = resolveCheckIdFromShortUid(issueCode, project);
            if (checkId == null) {
                checkId = issueCode;
            }

            diagnostics.add(EdtDiagnostic.fromAnnotation(
                    filePath, line, offset, charEnd, text, sev,
                    markerType != null ? markerType : annType, snippet, checkId));
            count++;
            if (count >= getSoftScanLimit(query.maxItems(), 2)) {
                break;
            }
        }
        diagInfo("[get_diagnostics] annotations: file=%s totalSeen=%d problemMatched=%d emitted=%d types=%s", //$NON-NLS-1$
                filePath, totalSeen, problemMatched, diagnostics.size() - sizeBefore, typeHistogram);
    }

    private boolean isProblemAnnotation(String type) {
        return type.contains("problem") || type.contains("error") //$NON-NLS-1$ //$NON-NLS-2$
                || type.contains("warning") || type.contains("info") //$NON-NLS-1$ //$NON-NLS-2$
                || type.contains("check"); //$NON-NLS-1$
    }

    private Severity getSeverityFromAnnotationType(String type) {
        if (type.contains("error")) return Severity.ERROR; //$NON-NLS-1$
        if (type.contains("warning")) return Severity.WARNING; //$NON-NLS-1$
        return Severity.INFO;
    }

    /**
     * Reflectively reads the Xtext issue code from a problem annotation. Open
     * Xtext editors expose problems as
     * {@code org.eclipse.xtext.ui.editor.validation.XtextAnnotation}, which has
     * {@code String getIssueCode()} — the check identifier (our candidate
     * v8-code-style code). Done reflectively because this UI bundle does not
     * import {@code org.eclipse.xtext.ui}. Returns {@code null} for annotations
     * with no such method (plain MarkerAnnotation, quickdiff, etc.).
     */
    private static String reflectIssueCode(Annotation ann) {
        if (ann == null) {
            return null;
        }
        try {
            java.lang.reflect.Method m = ann.getClass().getMethod("getIssueCode"); //$NON-NLS-1$
            Object value = m.invoke(ann);
            if (value == null) {
                return null;
            }
            String code = value.toString().trim();
            return code.isEmpty() ? null : code;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private Severity fromRuntimeSeverity(MarkerSeverity severity) {
        if (severity == null) {
            return Severity.INFO;
        }
        return switch (severity) {
            case BLOCKER, CRITICAL, ERRORS, MAJOR -> Severity.ERROR;
            case MINOR -> Severity.WARNING;
            case TRIVIAL, NONE -> Severity.INFO;
        };
    }

    private Map<String, CheckMetadata> loadCheckMetadata() {
        ICheckRepository repository = getCheckRepository();
        if (repository == null) {
            return Map.of();
        }

        try {
            Map<String, CheckMetadata> byCheckId = new HashMap<>();
            Map<CheckUid, ICheckDescription> descriptions = repository.getChecksWithDescriptions();
            for (Map.Entry<CheckUid, ICheckDescription> entry : descriptions.entrySet()) {
                CheckUid uid = entry.getKey();
                ICheckDescription description = entry.getValue();
                if (uid == null || description == null) {
                    continue;
                }
                String checkId = safeString(uid.getCheckId());
                if (checkId.isBlank() || byCheckId.containsKey(checkId)) {
                    continue;
                }
                byCheckId.put(checkId, new CheckMetadata(
                        safeString(description.getTitle()),
                        safeString(description.getDescription()),
                        description.getType() != null ? description.getType().name() : null,
                        description.getSeverity() != null ? description.getSeverity().name() : null));
            }
            return byCheckId;
        } catch (Exception e) {
            LOG.warn("Check repository metadata unavailable: %s", e.getMessage()); //$NON-NLS-1$
            return Map.of();
        }
    }

    private IMarkerManager getMarkerManager() {
        VibeCorePlugin plugin = VibeCorePlugin.getDefault();
        return plugin != null ? plugin.getMarkerManager() : null;
    }

    private ICheckRepository getCheckRepository() {
        VibeCorePlugin plugin = VibeCorePlugin.getDefault();
        return plugin != null ? plugin.getCheckRepository() : null;
    }

    /**
     * Resolves an EDT "short uid" check code (e.g. {@code SU4}, what the editor
     * and marker manager expose) to the stable kebab check id (e.g.
     * {@code export-procedure-missing-comment}) via the check repository's
     * short-uid index ({@code ICheckRepository.getUidForShortUid}). The kebab id
     * is the v8-code-style identifier external services key on. Returns
     * {@code null} when the repository is unavailable, the project is unknown,
     * or the code does not resolve (e.g. a non-SU code).
     */
    private String resolveCheckIdFromShortUid(String shortUid, IProject project) {
        if (shortUid == null || shortUid.isBlank() || project == null) {
            return null;
        }
        ICheckRepository repository = getCheckRepository();
        if (repository == null) {
            return null;
        }
        try {
            CheckUid uid = repository.getUidForShortUid(shortUid.trim(), project);
            if (uid == null) {
                return null;
            }
            String checkId = safeString(uid.getCheckId());
            return checkId.isBlank() ? null : checkId;
        } catch (Exception e) {
            // Best-effort: registry may not be ready, or the code may be from a
            // non-EDT-check source. Fall back to the caller's short code.
            return null;
        }
    }

    private IApplicationManager getApplicationManager() {
        VibeCorePlugin plugin = VibeCorePlugin.getDefault();
        return plugin != null ? plugin.getApplicationManager() : null;
    }

    private String safeString(String value) {
        return value != null ? value : ""; //$NON-NLS-1$
    }

    private ITextEditor getActiveTextEditor() {
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if (window == null) return null;

        IWorkbenchPage page = window.getActivePage();
        if (page == null) return null;

        IEditorPart editor = page.getActiveEditor();
        if (editor instanceof ITextEditor textEditor) {
            return textEditor;
        }
        return null;
    }

    /**
     * Looks up the open editor for {@code file} (if any) and reads its
     * XText annotation model. Annotations carry live BSL-checker info /
     * warning hints that don't materialize into IMarkerManager.
     *
     * <p>Runs the editor lookup on the UI thread via {@link Display#syncExec}.
     * No-op if the file is not currently open in any editor.</p>
     */
    private final BslLiveValidator bslLiveValidator = new BslLiveValidator();
    private final DcsSchemaValidator dcsSchemaValidator = new DcsSchemaValidator();

    /**
     * Runs the Xtext BSL validator on the file even when no editor is open.
     * Closes the "false-clean" gap: region-structure / handler-placement
     * checks live as Xtext {@code Issue} objects, which Eclipse only
     * persists as workspace markers when an editor's parsing pass writes
     * them — closed files were therefore invisible to {@code scope=file}.
     *
     * <p>Skips non-{@code .bsl} files inside the validator itself, so this
     * method is safe to call unconditionally.</p>
     */
    private void collectFromBslLiveValidator(
            ResolvedFileContext context, String filePath, DiagnosticsQuery query,
            List<EdtDiagnostic> diagnostics, Set<String> seen) {
        if (context.file() == null) {
            return;
        }
        List<BslLiveIssue> issues = bslLiveValidator.validate(context.file(), context.project());
        int sizeBefore = diagnostics.size();
        for (BslLiveIssue issue : issues) {
            Severity sev = bslLiveSeverity(issue.severity());
            if (sev.getLevel() < query.minSeverity().getLevel()) {
                continue;
            }
            int line = issue.line();
            int offset = issue.offset();
            int charEnd = offset >= 0 ? offset + issue.length() : -1;
            String key = "xtext:" + line + ":" + offset + ":" + issue.message(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            if (!seen.add(key)) {
                continue;
            }
            String shortUid = issue.code();
            String checkId = resolveCheckIdFromShortUid(shortUid, context.project());
            if (checkId == null && shortUid != null && !shortUid.isBlank()) {
                checkId = shortUid;
            }
            String typeLabel = shortUid != null && !shortUid.isBlank()
                    ? "xtext:" + shortUid //$NON-NLS-1$
                    : "xtext"; //$NON-NLS-1$
            diagnostics.add(EdtDiagnostic.fromAnnotation(
                    filePath, line, offset, charEnd, issue.message(), sev, typeLabel, null, checkId));
        }
        diagInfo("[get_diagnostics] xtext-live: file=%s issuesScanned=%d emitted=%d", //$NON-NLS-1$
                filePath, issues.size(), diagnostics.size() - sizeBefore);
    }

    private Severity bslLiveSeverity(String severity) {
        if (severity == null) {
            return Severity.INFO;
        }
        return switch (severity) {
            case "error" -> Severity.ERROR; //$NON-NLS-1$
            case "warning" -> Severity.WARNING; //$NON-NLS-1$
            default -> Severity.INFO;
        };
    }

    /**
     * Surfaces DCS-schema problems for {@code .dcs} files (e.g. an {@code <editFormat>}
     * element that EDT's lenient loader silently drops). Skips non-{@code .dcs} files
     * inside the validator, so it is safe to call unconditionally.
     *
     * <p>The validator is a curated denylist (text scan for elements known to be invalid
     * in a {@code .dcs} and silently dropped by EDT's importer), so findings are confident
     * and surfaced at their natural severity — see {@link DcsSchemaValidator} for why full
     * schema validation is not feasible here.</p>
     */
    private void collectFromDcsSchemaValidator(
            IFile file, String filePath, DiagnosticsQuery query,
            List<EdtDiagnostic> diagnostics, Set<String> seen) {
        if (file == null) {
            return;
        }
        List<DcsSchemaIssue> issues = dcsSchemaValidator.validate(file);
        int sizeBefore = diagnostics.size();
        for (DcsSchemaIssue issue : issues) {
            Severity sev = dcsSchemaSeverity(issue.severity());
            if (sev.getLevel() < query.minSeverity().getLevel()) {
                continue;
            }
            int line = issue.line();
            String key = "dcs-schema:" + line + ":" + issue.column() + ":" + issue.message(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            if (!seen.add(key)) {
                continue;
            }
            diagnostics.add(EdtDiagnostic.fromAnnotation(
                    filePath, line, -1, -1, "DCS schema: " + issue.message(), sev, "dcs-schema", null)); //$NON-NLS-1$ //$NON-NLS-2$
        }
        diagInfo("[get_diagnostics] dcs-schema: file=%s issuesScanned=%d emitted=%d", //$NON-NLS-1$
                filePath, issues.size(), diagnostics.size() - sizeBefore);
    }

    private Severity dcsSchemaSeverity(String severity) {
        return switch (severity == null ? "" : severity) { //$NON-NLS-1$
            case "error" -> Severity.ERROR; //$NON-NLS-1$
            case "warning" -> Severity.WARNING; //$NON-NLS-1$
            default -> Severity.INFO;
        };
    }

    private void collectFromOpenEditorAnnotations(
            IFile file, String filePath, DiagnosticsQuery query,
            List<EdtDiagnostic> diagnostics, Set<String> seen) {
        if (file == null) {
            return;
        }
        final IDocument[] documentRef = {null};
        final IAnnotationModel[] modelRef = {null};
        final int[] refCounter = {0};
        final StringBuilder refDump = new StringBuilder();
        try {
            Display.getDefault().syncExec(() -> {
                try {
                    IWorkbench workbench = PlatformUI.getWorkbench();
                    if (workbench == null) {
                        return;
                    }
                    IWorkbenchWindow[] windows = workbench.getWorkbenchWindows();
                    if (windows == null) {
                        return;
                    }
                    for (IWorkbenchWindow window : windows) {
                        for (IWorkbenchPage page : window.getPages()) {
                            IEditorReference[] refs = page.getEditorReferences();
                            if (refs == null) continue;
                            for (IEditorReference ref : refs) {
                                refCounter[0]++;
                                // Per-ref dump: input class + resolved IFile + match outcome.
                                // Helps diagnose annotation: SKIPPED when the file is
                                // visibly open but our resolver can't bind to the editor.
                                try {
                                    IEditorInput input = ref.getEditorInput();
                                    IFile candidate = resolveFile(input);
                                    boolean inputMatches = candidate != null && candidate.equals(file);
                                    if (refDump.length() < 4000) { // soft cap
                                        refDump.append("  ref[").append(refCounter[0]).append("]: ") //$NON-NLS-1$ //$NON-NLS-2$
                                                .append("inputClass=").append(input != null ? input.getClass().getName() : "null") //$NON-NLS-1$ //$NON-NLS-2$
                                                .append(" candidateFile=").append(candidate != null ? candidate.getFullPath() : "null") //$NON-NLS-1$ //$NON-NLS-2$
                                                .append(" matchesTarget=").append(inputMatches) //$NON-NLS-1$
                                                .append(" editorId=").append(ref.getId()) //$NON-NLS-1$
                                                .append('\n');
                                    }
                                } catch (Exception probe) {
                                    if (refDump.length() < 4000) {
                                        refDump.append("  ref[").append(refCounter[0]).append("]: probe-failed=") //$NON-NLS-1$ //$NON-NLS-2$
                                                .append(probe.getClass().getSimpleName()).append('\n');
                                    }
                                }
                                if (matchEditorForFile(ref, file, documentRef, modelRef)) {
                                    return;
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    LOG.warn("[get_diagnostics] editor annotation lookup failed: %s — %s", //$NON-NLS-1$
                            e.getClass().getSimpleName(), e.getMessage());
                }
            });
        } catch (Exception e) {
            LOG.warn("[get_diagnostics] syncExec for annotation lookup failed: %s — %s", //$NON-NLS-1$
                    e.getClass().getSimpleName(), e.getMessage());
            return;
        }
        IDocument document = documentRef[0];
        IAnnotationModel annotationModel = modelRef[0];
        if (document != null && annotationModel != null) {
            collectFromAnnotations(annotationModel, document, filePath,
                    file != null ? file.getProject() : null, query, diagnostics, seen);
            return;
        }

        // Headless-open fallback for .bsl files: nothing in the workbench
        // is currently showing this file, so the live Xtext ValidationJob
        // hasn't run. Open the file in a non-activated editor, wait briefly
        // for the parse + validate pipeline to populate the annotation
        // model, harvest the issues, then close the editor (only if we
        // were the one that opened it — pre-existing editors stay).
        if (file.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".bsl")) { //$NON-NLS-1$
            IEditorPart[] openedByUs = {null};
            try {
                if (tryHeadlessOpenForAnnotations(file, documentRef, modelRef, openedByUs)) {
                    document = documentRef[0];
                    annotationModel = modelRef[0];
                    if (document != null && annotationModel != null) {
                        diagInfo("[get_diagnostics] annotations: headless-open succeeded for %s", file.getFullPath()); //$NON-NLS-1$
                        collectFromAnnotations(annotationModel, document, filePath,
                                file != null ? file.getProject() : null, query, diagnostics, seen);
                        return;
                    }
                }
            } finally {
                closeHeadlessIfNeeded(openedByUs[0]);
            }
        }

        diagInfo("[get_diagnostics] annotations: SKIPPED (file not open in any editor — live Xtext hints unavailable). target=%s refsScanned=%d\n%s", //$NON-NLS-1$
                file.getFullPath(), refCounter[0],
                refDump.length() == 0 ? "  (no editor references in workbench)\n" : refDump.toString());
    }

    /** Upper bound on how long to wait for Xtext ValidationJob to populate annotations after headless open. */
    private static final long HEADLESS_OPEN_VALIDATION_MAX_WAIT_MS = 5000L;
    /** Poll interval while waiting for validation annotations to appear. */
    private static final long HEADLESS_OPEN_VALIDATION_POLL_INTERVAL_MS = 200L;

    /**
     * Opens {@code file} in a non-activated editor so its Xtext annotation
     * model populates with live validation issues, then extracts the
     * document + annotation model. Records the editor in
     * {@code openedByUsRef} only when this call was the one that opened
     * it — pre-existing editors don't get closed by {@link #closeHeadlessIfNeeded}.
     */
    private boolean tryHeadlessOpenForAnnotations(
            IFile file, IDocument[] documentRef, IAnnotationModel[] modelRef, IEditorPart[] openedByUsRef) {
        final IEditorPart[] editorRef = {null};
        try {
            Display.getDefault().syncExec(() -> {
                try {
                    IWorkbench workbench = PlatformUI.getWorkbench();
                    if (workbench == null) {
                        return;
                    }
                    IWorkbenchWindow window = workbench.getActiveWorkbenchWindow();
                    if (window == null) {
                        IWorkbenchWindow[] windows = workbench.getWorkbenchWindows();
                        if (windows != null && windows.length > 0) {
                            window = windows[0];
                        }
                    }
                    if (window == null) {
                        return;
                    }
                    IWorkbenchPage page = window.getActivePage();
                    if (page == null) {
                        return;
                    }
                    FileEditorInput input = new FileEditorInput(file);
                    IEditorPart existing = page.findEditor(input);
                    if (existing != null) {
                        editorRef[0] = existing; // pre-existing — do NOT mark as opened-by-us
                        return;
                    }
                    // activate=false → no focus steal, the editor opens in
                    // background and Xtext starts parsing immediately.
                    IEditorPart opened = IDE.openEditor(page, file, false);
                    if (opened != null) {
                        editorRef[0] = opened;
                        openedByUsRef[0] = opened;
                    }
                } catch (PartInitException e) {
                    LOG.warn("[get_diagnostics] headless openEditor failed for %s: %s", //$NON-NLS-1$
                            file.getFullPath(), e.getMessage());
                } catch (RuntimeException e) {
                    LOG.warn("[get_diagnostics] headless openEditor crashed for %s: %s — %s", //$NON-NLS-1$
                            file.getFullPath(), e.getClass().getSimpleName(), e.getMessage());
                }
            });
        } catch (RuntimeException e) {
            LOG.warn("[get_diagnostics] syncExec for headless open failed: %s — %s", //$NON-NLS-1$
                    e.getClass().getSimpleName(), e.getMessage());
            return false;
        }
        if (editorRef[0] == null) {
            diagInfo("[get_diagnostics] annotations: headless-open could not resolve a workbench page for %s", file.getFullPath()); //$NON-NLS-1$
            return false;
        }

        // Off UI thread — poll the annotation model until Xtext ValidationJob
        // emits at least one problem-type annotation, or the budget runs out.
        // A fixed Thread.sleep proved unreliable: for cold opens the BSL
        // pipeline (parse + cross-ref resolution + validation) routinely
        // takes longer than the prior 800ms ceiling, leaving the model with
        // only synchronous quickdiff annotations at read time.
        final IEditorPart editor = editorRef[0];
        pollForValidationAnnotations(editor, file);

        // Back on UI thread to read the document + annotation model.
        try {
            Display.getDefault().syncExec(() -> {
                try {
                    extractDocAndModelFromEditorPart(editor, documentRef, modelRef);
                } catch (RuntimeException e) {
                    LOG.warn("[get_diagnostics] headless extract failed: %s — %s", //$NON-NLS-1$
                            e.getClass().getSimpleName(), e.getMessage());
                }
            });
        } catch (RuntimeException e) {
            LOG.warn("[get_diagnostics] syncExec for headless extract failed: %s — %s", //$NON-NLS-1$
                    e.getClass().getSimpleName(), e.getMessage());
            return false;
        }
        return documentRef[0] != null && modelRef[0] != null;
    }

    /**
     * Polls the editor's annotation model off the UI thread until a problem-type
     * annotation appears (Xtext ValidationJob finished and emitted issues) or
     * the {@link #HEADLESS_OPEN_VALIDATION_MAX_WAIT_MS} budget is exhausted.
     * Logs the final state so we can tell timeouts apart from clean files.
     */
    private void pollForValidationAnnotations(IEditorPart editor, IFile file) {
        final long deadline = System.currentTimeMillis() + HEADLESS_OPEN_VALIDATION_MAX_WAIT_MS;
        final long startMs = System.currentTimeMillis();
        int polls = 0;
        while (true) {
            try {
                Thread.sleep(HEADLESS_OPEN_VALIDATION_POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                diagInfo("[get_diagnostics] annotations: poll interrupted after %dms (polls=%d)", //$NON-NLS-1$
                        System.currentTimeMillis() - startMs, polls);
                return;
            }
            polls++;
            final boolean[] hasProblem = {false};
            final int[] totalSeen = {0};
            final Map<String, Integer> typeHistogram = new HashMap<>();
            try {
                Display.getDefault().syncExec(() -> {
                    IDocument[] d = new IDocument[1];
                    IAnnotationModel[] m = new IAnnotationModel[1];
                    extractDocAndModelFromEditorPart(editor, d, m);
                    if (m[0] == null) {
                        return;
                    }
                    Iterator<?> it = m[0].getAnnotationIterator();
                    while (it.hasNext()) {
                        Object o = it.next();
                        if (!(o instanceof Annotation a)) {
                            continue;
                        }
                        totalSeen[0]++;
                        String t = a.getType();
                        if (t == null) {
                            continue;
                        }
                        typeHistogram.merge(t, 1, Integer::sum);
                        if (isProblemAnnotation(t)) {
                            hasProblem[0] = true;
                        }
                    }
                });
            } catch (RuntimeException e) {
                LOG.warn("[get_diagnostics] annotations: poll syncExec failed: %s — %s", //$NON-NLS-1$
                        e.getClass().getSimpleName(), e.getMessage());
            }
            if (hasProblem[0]) {
                diagInfo("[get_diagnostics] annotations: validation populated after %dms (polls=%d, totalAnn=%d, types=%s) %s", //$NON-NLS-1$
                        System.currentTimeMillis() - startMs, polls, totalSeen[0], typeHistogram, file.getFullPath());
                return;
            }
            if (System.currentTimeMillis() >= deadline) {
                diagInfo("[get_diagnostics] annotations: validation wait exhausted budget=%dms (polls=%d, totalAnn=%d, types=%s) %s", //$NON-NLS-1$
                        HEADLESS_OPEN_VALIDATION_MAX_WAIT_MS, polls, totalSeen[0], typeHistogram, file.getFullPath());
                return;
            }
        }
    }

    /**
     * Unified document + annotation-model extraction reused by both the
     * existing-editor scan and the headless-open path. Mirrors the
     * MultiPageEditorPart handling in {@link #matchEditorForFile}.
     */
    private void extractDocAndModelFromEditorPart(
            IEditorPart editor, IDocument[] documentOut, IAnnotationModel[] modelOut) {
        if (editor == null) {
            return;
        }
        if (editor instanceof ITextEditor textEditor) {
            extractDocAndModelFromTextEditor(textEditor, editor.getEditorInput(), documentOut, modelOut);
            return;
        }
        ITextEditor adapted = editor.getAdapter(ITextEditor.class);
        if (adapted != null) {
            if (extractDocAndModelFromTextEditor(adapted, adapted.getEditorInput(), documentOut, modelOut)) {
                return;
            }
        }
        if (editor instanceof org.eclipse.ui.part.MultiPageEditorPart multi) {
            matchTextEditorInsideMultiPage(multi, documentOut, modelOut);
        }
    }

    private void closeHeadlessIfNeeded(IEditorPart openedByUs) {
        if (openedByUs == null) {
            return;
        }
        try {
            Display.getDefault().syncExec(() -> {
                try {
                    if (openedByUs.getSite() == null) {
                        return;
                    }
                    IWorkbenchPage page = openedByUs.getSite().getPage();
                    if (page == null) {
                        return;
                    }
                    page.closeEditor(openedByUs, false); // discard unsaved
                } catch (RuntimeException e) {
                    LOG.warn("[get_diagnostics] headless close failed: %s — %s", //$NON-NLS-1$
                            e.getClass().getSimpleName(), e.getMessage());
                }
            });
        } catch (RuntimeException e) {
            LOG.warn("[get_diagnostics] syncExec for headless close failed: %s — %s", //$NON-NLS-1$
                    e.getClass().getSimpleName(), e.getMessage());
        }
    }

    private boolean matchEditorForFile(IEditorReference ref, IFile target,
                                       IDocument[] documentOut, IAnnotationModel[] modelOut) {
        try {
            IEditorInput input = ref.getEditorInput();
            IFile candidate = resolveFile(input);
            if (candidate == null || !candidate.equals(target)) {
                return false;
            }
            // Force-materialise lazy editor — getEditor(false) returns null for
            // editors that haven't been instantiated yet (e.g. tab open but not
            // focused). The document provider only exists after instantiation.
            IEditorPart editor = ref.getEditor(true);
            if (editor == null) {
                return false;
            }
            // Primary path: plain BSL text editor.
            if (editor instanceof ITextEditor textEditor) {
                return extractDocAndModelFromTextEditor(textEditor, input, documentOut, modelOut);
            }
            // Multi-page editor path: EDT form designer is a MultiPageEditorPart
            // whose BSL module page is a nested ITextEditor. Plain
            // (editor instanceof ITextEditor) returns false on the outer
            // MultiPageEditorPart, so we have to dive in. Try the Eclipse
            // adapter mechanism first (many multi-page editors forward
            // getAdapter(ITextEditor.class) to their active text-editor page);
            // if that returns null (user is on the visual page, BSL page not
            // yet active), reflectively iterate every page and pick the first
            // ITextEditor whose document provider exposes an annotation model.
            ITextEditor adapted = editor.getAdapter(ITextEditor.class);
            if (adapted != null) {
                if (extractDocAndModelFromTextEditor(adapted, adapted.getEditorInput(), documentOut, modelOut)) {
                    return true;
                }
            }
            if (editor instanceof org.eclipse.ui.part.MultiPageEditorPart multi) {
                if (matchTextEditorInsideMultiPage(multi, documentOut, modelOut)) {
                    return true;
                }
            }
            return false;
        } catch (PartInitException e) {
            return false;
        }
    }

    private boolean extractDocAndModelFromTextEditor(ITextEditor textEditor, IEditorInput input,
                                                     IDocument[] documentOut, IAnnotationModel[] modelOut) {
        IDocumentProvider dp = textEditor.getDocumentProvider();
        if (dp == null) {
            return false;
        }
        IDocument doc = dp.getDocument(input);
        IAnnotationModel model = dp.getAnnotationModel(input);
        if (doc == null || model == null) {
            return false;
        }
        documentOut[0] = doc;
        modelOut[0] = model;
        return true;
    }

    private boolean matchTextEditorInsideMultiPage(
            org.eclipse.ui.part.MultiPageEditorPart multi,
            IDocument[] documentOut, IAnnotationModel[] modelOut) {
        try {
            java.lang.reflect.Method getCount = org.eclipse.ui.part.MultiPageEditorPart.class
                    .getDeclaredMethod("getPageCount"); //$NON-NLS-1$
            getCount.setAccessible(true);
            java.lang.reflect.Method getEditor = org.eclipse.ui.part.MultiPageEditorPart.class
                    .getDeclaredMethod("getEditor", int.class); //$NON-NLS-1$
            getEditor.setAccessible(true);
            int pages = (int) getCount.invoke(multi);
            for (int i = 0; i < pages; i++) {
                Object page = getEditor.invoke(multi, i);
                if (page instanceof ITextEditor te) {
                    if (extractDocAndModelFromTextEditor(te, te.getEditorInput(), documentOut, modelOut)) {
                        return true;
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.warn("[get_diagnostics] multi-page editor reflection failed: %s — %s", //$NON-NLS-1$
                    e.getClass().getSimpleName(), e.getMessage());
        }
        return false;
    }

    private IFile resolveFile(IEditorInput input) {
        if (input instanceof FileEditorInput fileInput) {
            return fileInput.getFile();
        }
        Object adapted = input.getAdapter(IFile.class);
        return adapted instanceof IFile ? (IFile) adapted : null;
    }

    private String safeGetMarkerType(IMarker marker) {
        try {
            return marker.getType();
        } catch (CoreException e) {
            return "unknown"; //$NON-NLS-1$
        }
    }

    private static final Pattern LOCATION_LINE_PATTERN =
            Pattern.compile("(?i)\\bline\\s+(\\d+)\\b"); //$NON-NLS-1$

    private static int parseLineFromLocation(String locationText) {
        if (locationText == null || locationText.isBlank()) return -1;
        Matcher m = LOCATION_LINE_PATTERN.matcher(locationText);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return -1;
    }

    private int safeGetLineOfOffset(IDocument doc, int offset) {
        if (doc == null || offset < 0) return -1;
        try {
            return doc.getLineOfOffset(offset) + 1; // 1-based
        } catch (BadLocationException e) {
            return -1;
        }
    }

    private String getSnippetFromFile(IFile file, int line, int charStart, int charEnd) {
        // For markers, we don't have easy access to file content without opening it
        // Return null - the diagnostic message is usually sufficient
        return null;
    }

    private String getSnippetFromDocument(IDocument document, int offset, int length) {
        if (document == null || offset < 0) return null;
        try {
            int lineNum = document.getLineOfOffset(offset);
            int lineStart = document.getLineOffset(lineNum);
            int lineLength = document.getLineLength(lineNum);
            String line = document.get(lineStart, Math.min(lineLength, MAX_SNIPPET_LENGTH));
            return line.stripTrailing();
        } catch (BadLocationException e) {
            return null;
        }
    }

    private record ResolvedFileContext(
            String requestedPath,
            String resolvedPath,
            IProject project,
            IFile file,
            List<String> pathHints,
            List<String> matchTokens,
            int tokenThreshold) {
    }

    private record CheckMetadata(
            String title,
            String description,
            String issueType,
            String issueSeverity) {
    }
}
