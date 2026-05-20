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
import com.codepilot1c.core.diagnostics.DiagnosticsLineFilter;
import com.codepilot1c.core.diagnostics.PathMatchTokens;
import com.codepilot1c.core.diagnostics.RelativePathCandidates;
import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.internal.VibeCorePlugin;
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
            int lineTo) {

        public static DiagnosticsQuery defaults() {
            return new DiagnosticsQuery(Severity.INFO, 0, true, 0, true, 0, 0);
        }

        public static DiagnosticsQuery withSeverity(Severity minSeverity) {
            return new DiagnosticsQuery(minSeverity, 0, true, 0, true, 0, 0);
        }
    }

    /**
     * Result of diagnostics collection.
     */
    public record DiagnosticsResult(
            String filePath,
            boolean editorDirty,
            List<EdtDiagnostic> diagnostics,
            int errorCount,
            int warningCount,
            int infoCount) {

        public boolean hasErrors() {
            return errorCount > 0;
        }

        public boolean hasDiagnostics() {
            return !diagnostics.isEmpty();
        }

        /**
         * Formats result for LLM consumption.
         */
        public String formatForLlm() {
            StringBuilder sb = new StringBuilder();
            sb.append("## Диагностики: ").append(filePath).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$

            if (editorDirty) {
                sb.append("⚠️ *Файл не сохранён — диагностики могут быть неполными*\n\n"); //$NON-NLS-1$
            }

            sb.append("**Итого:** "); //$NON-NLS-1$
            sb.append(errorCount).append(" ошибок, "); //$NON-NLS-1$
            sb.append(warningCount).append(" предупреждений, "); //$NON-NLS-1$
            sb.append(infoCount).append(" информационных\n\n"); //$NON-NLS-1$

            if (diagnostics.isEmpty()) {
                sb.append("✅ Диагностик не найдено.\n"); //$NON-NLS-1$
            } else {
                for (EdtDiagnostic diag : diagnostics) {
                    sb.append(diag.formatForLlm()).append("\n"); //$NON-NLS-1$
                }
            }

            return sb.toString();
        }
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
                                "нет активного редактора", false, List.of(), 0, 0, 0)); //$NON-NLS-1$
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
                        collectFromAnnotations(annotationModel, document, filePath, q, diagnostics, seen);
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

                    future.complete(new DiagnosticsResult(filePath, dirty, diagnostics, errors, warnings, infos));

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
                LOG.info("[get_diagnostics] scope=file path='%s' severity=%s maxItems=%d includeRuntime=%s", //$NON-NLS-1$
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

                diagnostics.sort(Comparator
                        .comparing((EdtDiagnostic d) -> d.severity().getLevel()).reversed()
                        .thenComparing(EdtDiagnostic::filePath, Comparator.nullsLast(String::compareTo))
                        .thenComparing(EdtDiagnostic::lineNumber));

                diagnostics = applyLineFilter(diagnostics, query);
                diagnostics = applyResultLimit(diagnostics, query.maxItems());

                int errors = (int) diagnostics.stream().filter(d -> d.severity() == Severity.ERROR).count();
                int warnings = (int) diagnostics.stream().filter(d -> d.severity() == Severity.WARNING).count();
                int infos = diagnostics.size() - errors - warnings;

                LOG.info("[get_diagnostics] result: %d items (errors=%d warnings=%d infos=%d) path='%s'", //$NON-NLS-1$
                        diagnostics.size(), errors, warnings, infos, resultPath);
                return new DiagnosticsResult(resultPath, false, diagnostics, errors, warnings, infos);

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
            LOG.info("[get_diagnostics] runtime-markers: SKIPPED (markerManager=%s project=%s)", //$NON-NLS-1$
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
            LOG.info("[get_diagnostics] runtime-markers: raw=%d matched=%d sevDrop=%d blankDrop=%d emitted=%d tokens=%s threshold=%d hints=%d", //$NON-NLS-1$
                    counters[0], counters[1], counters[2], counters[3],
                    diagnostics.size() - sizeBefore,
                    context.matchTokens(),
                    context.tokenThreshold(),
                    context.pathHints() != null ? context.pathHints().size() : 0);
            if (sampleSink.length() > 0) {
                LOG.info("[get_diagnostics] runtime-markers sample (first %d raw):\n%s", //$NON-NLS-1$
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
                    return new DiagnosticsResult("проект не указан", false, List.of(), 0, 0, 0); //$NON-NLS-1$
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

                return new DiagnosticsResult("/" + projectName, false, diagnostics, errors, warnings, infos); //$NON-NLS-1$

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

                return new DiagnosticsResult("/workspace", false, diagnostics, errors, warnings, infos); //$NON-NLS-1$
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
            LOG.info("[get_diagnostics] file-markers: file=%s rawCount=%d types=%s", //$NON-NLS-1$
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
            LOG.info("[get_diagnostics] file-markers emitted=%d (of %d raw)", //$NON-NLS-1$
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
            DiagnosticsQuery query,
            List<EdtDiagnostic> diagnostics,
            Set<String> seen) {

        Iterator<?> it = model.getAnnotationIterator();
        int count = 0;

        while (it.hasNext()) {
            Object obj = it.next();
            if (!(obj instanceof Annotation ann)) {
                continue;
            }

            String annType = ann.getType();
            if (annType == null || !isProblemAnnotation(annType)) {
                continue;
            }

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

            diagnostics.add(EdtDiagnostic.fromAnnotation(
                    filePath, line, offset, charEnd, text, sev,
                    markerType != null ? markerType : annType, snippet));
            count++;
            if (count >= getSoftScanLimit(query.maxItems(), 2)) {
                break;
            }
        }
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
    private void collectFromOpenEditorAnnotations(
            IFile file, String filePath, DiagnosticsQuery query,
            List<EdtDiagnostic> diagnostics, Set<String> seen) {
        if (file == null) {
            return;
        }
        final IDocument[] documentRef = {null};
        final IAnnotationModel[] modelRef = {null};
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
        if (document == null || annotationModel == null) {
            return; // file not open — live XText hints unavailable
        }
        collectFromAnnotations(annotationModel, document, filePath, query, diagnostics, seen);
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
            if (!(editor instanceof ITextEditor textEditor)) {
                return false;
            }
            IDocumentProvider dp = textEditor.getDocumentProvider();
            if (dp == null) {
                return false;
            }
            documentOut[0] = dp.getDocument(input);
            modelOut[0] = dp.getAnnotationModel(input);
            return documentOut[0] != null && modelOut[0] != null;
        } catch (PartInitException e) {
            return false;
        }
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
