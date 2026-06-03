/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.ui.tools;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.tools.ITool;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.ui.diagnostics.EdtDiagnostic.Severity;
import com.codepilot1c.ui.diagnostics.EdtDiagnosticsCollector;
import com.codepilot1c.ui.diagnostics.EdtDiagnosticsCollector.DiagnosticsQuery;
import com.codepilot1c.ui.diagnostics.EdtDiagnosticsCollector.DiagnosticsResult;

/**
 * Tool for getting EDT diagnostics (errors, warnings) for project, file or active editor.
 *
 * <p>This tool allows the LLM to retrieve compiler/checker diagnostics from 1C EDT,
 * enabling intelligent auto-fix workflows.</p>
 *
 * <p>Example usage by LLM:</p>
 * <ul>
 * <li>"get_diagnostics" → get errors from project</li>
 * <li>"get_diagnostics(severity='warning')" → get errors and warnings</li>
 * <li>"get_diagnostics(path='/Project/Module.bsl')" → get diagnostics for specific file</li>
 * </ul>
 */
public class GetDiagnosticsTool implements ITool {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(GetDiagnosticsTool.class);

    private static final String SCHEMA = """
            {
                "type": "object",
                "properties": {
                    "scope": {
                        "type": "string",
                        "enum": ["project", "file", "active_editor"],
                        "description": "Scope of live UI diagnostics: project, file, or active_editor. Use edt_diagnostics:metadata_smoke when UI is unavailable."
                    },
                    "path": {
                        "type": "string",
                        "description": "Workspace-relative file path for scope=file."
                    },
                    "project_name": {
                        "type": "string",
                        "description": "EDT project name for scope=project. If omitted, default project or workspace diagnostics are used."
                    },
                    "severity": {
                        "type": "string",
                        "enum": ["error", "warning", "info"],
                        "description": "Minimum severity level: error (errors only), warning (errors and warnings), info (all). Default: info"
                    },
                    "max_items": {
                        "type": "integer",
                        "description": "Maximum number of diagnostics. 0 means unlimited. Default: 0"
                    },
                    "wait_ms": {
                        "type": "integer",
                        "description": "Time to wait for diagnostics to be recalculated before reading, in ms (0-5000, values above are clamped to 5000). Default: 0. Use ~3000 right after a metadata/code mutation so EDT/BSL-LS markers catch up, or when a previous call returned a suspiciously empty/partial snapshot."
                    },
                    "include_runtime_markers": {
                        "type": "boolean",
                        "description": "Include diagnostics from the EDT runtime marker manager (project-wide validation results: metadata/object checks). Default true — keep it on for scope=project to get the full picture. Set false to restrict to workspace-attached markers only (rarely needed). Note: for scope=file on an OPEN module the live diagnostics come from the editor's annotations, so toggling this has little visible effect there; it mainly matters for scope=project."
                    },
                    "line_from": {
                        "type": "integer",
                        "description": "Lower bound of the line range (1-based, inclusive). 0 = no limit. USE this together with line_to when editing/inspecting a specific method or fragment: only diagnostics within the range are returned, which sharply shrinks the response on large modules (with hundreds of warnings). Example: editing a function on lines 40-75 -> line_from=40, line_to=75."
                    },
                    "line_to": {
                        "type": "integer",
                        "description": "Upper bound of the line range (1-based, inclusive). 0 = no limit. Set together with line_from to focus on a method/selection. Diagnostics with no precise line (lineNumber<=0) are filtered out when a range is set."
                    },
                    "include_check_help": {
                        "type": "boolean",
                        "description": "RECOMMENDED whenever you intend to FIX or explain diagnostics: appends a 'Check details' section with the FULL official rule explanation + fix guidance (Markdown, the same content as EDT's Check Info view) for each unique rule in the result. This is the authoritative source on how to resolve a diagnostic — prefer it over guessing from the message. The section is DEDUPLICATED: each rule is explained exactly once even if dozens of diagnostics share it, so it stays compact on repeated warnings (cost scales with the number of distinct rules, not diagnostics). Works on its own — it resolves rule ids internally, you do NOT need include_check_id. Default false. Use help_locale to choose language. Rules whose bundle ships no description are silently omitted. Leaner alternative for one-off lookups: leave this off and call get_diagnostics_details for just the rules you care about."
                    },
                    "help_locale": {
                        "type": "string",
                        "enum": ["en", "ru"],
                        "description": "Language for the rule explanations when include_check_help=true: 'en' (default) or 'ru'. Falls back to English if the requested locale has no localized description."
                    }
                },
                "required": []
            }
            """; //$NON-NLS-1$

    @Override
    public String getName() {
        return "get_diagnostics"; //$NON-NLS-1$
    }

    @Override
    public String getDescription() {
        return "Returns live EDT diagnostics from the UI workbench for a project, file, or the active editor. "  //$NON-NLS-1$
                + "TOKEN-SAVING TIP: on large modules pass line_from/line_to to get diagnostics for just the method/fragment " //$NON-NLS-1$
                + "you care about instead of the whole file; also filter with severity/max_items. " //$NON-NLS-1$
                + "Output is grouped into per-severity sections (### Errors/Warnings/Info). Each diagnostic carries its stable " //$NON-NLS-1$
                + "rule code in brackets (e.g. [export-procedure-missing-comment], the same id used at v8std.ru). Repeated " //$NON-NLS-1$
                + "diagnostics of the same rule collapse into a block: '<rule> ×N — lines: 41(×2), 88, …' (per-line counts) " //$NON-NLS-1$
                + "plus up to 3 distinct sample messages and '(+K more variants)'; singletons stay as '<line>: <message> [rule]' " //$NON-NLS-1$
                + "with a code snippet. Line numbers are 1-based; there are no byte offsets. " //$NON-NLS-1$
                + "When you intend to FIX or explain diagnostics, set include_check_help=true: it appends the official " //$NON-NLS-1$
                + "rule explanation + fix per unique rule (deduplicated) — the authoritative way to learn how to resolve a diagnostic. " //$NON-NLS-1$
                + "Example — review and fix one module: get_diagnostics(scope=file, path=\"/Proj/src/CommonModules/X/Module.bsl\", include_check_help=true). " //$NON-NLS-1$
                + "CAUTION: a sudden drop to 0 diagnostics on a file you expected to be dirty usually means EDT is still " //$NON-NLS-1$
                + "recalculating markers (cold start, or right after an edit/save) — it does NOT prove the file is clean. " //$NON-NLS-1$
                + "Re-run after a few seconds, or pass wait_ms (up to 5000) to let the recompute settle. " //$NON-NLS-1$
                + "For .dcs (scope=file) it additionally checks a curated set of elements invalid in the DCS schema " //$NON-NLS-1$
                + "(e.g. <editFormat>) that the EDT importer silently drops together with the data set. This is a targeted " //$NON-NLS-1$
                + "check, NOT full schema validation: .dcs is a platform format parsed by EDT's lenient BM importer, " //$NON-NLS-1$
                + "so a strict parser is unavailable. To verify DCS structure correctness, still open the schema in the designer."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
        // Parse parameters
        String scope = (String) parameters.getOrDefault("scope", ""); //$NON-NLS-1$ //$NON-NLS-2$
        String path = (String) parameters.get("path"); //$NON-NLS-1$
        String projectName = (String) parameters.get("project_name"); //$NON-NLS-1$
        String severityStr = (String) parameters.getOrDefault("severity", "info"); //$NON-NLS-1$ //$NON-NLS-2$
        int maxItems = getIntParam(parameters, "max_items", 0); //$NON-NLS-1$
        long waitMs = getIntParam(parameters, "wait_ms", 0); //$NON-NLS-1$
        // Runtime markers default to true for ALL scopes — EDT places the
        // bulk of BSL diagnostics (syntax, type checks, BSL-checks) in the
        // runtime marker manager rather than as workspace-attached markers
        // on the .bsl IFile. With scope=file/active_editor the prior
        // default (false) made the call silently return 0/0/0 — see
        // 2026-05-19-diagnostics-space-in-project-name.md.
        //
        // The "cross-module noise" concern from issue #24 is mitigated by
        // the strict ALL-tokens filter in markerMatchesContext (fixed by
        // RelativePathCandidates project-prefix stripping so the surviving
        // tokens are precise module discriminators). Callers can still
        // pass include_runtime_markers:false explicitly when they want
        // workspace markers only.
        boolean includeRuntimeMarkers = getBooleanParam(parameters, "include_runtime_markers", true); //$NON-NLS-1$
        int lineFrom = getIntParam(parameters, "line_from", 0); //$NON-NLS-1$
        int lineTo = getIntParam(parameters, "line_to", 0); //$NON-NLS-1$
        boolean includeCheckHelp = getBooleanParam(parameters, "include_check_help", false); //$NON-NLS-1$
        String helpLocale = (String) parameters.getOrDefault("help_locale", "en"); //$NON-NLS-1$ //$NON-NLS-2$

        // Validate parameters
        if (maxItems < 0) maxItems = 0;
        if (waitMs < 0) waitMs = 0;
        if (waitMs > 5000) waitMs = 5000;
        int[] range = com.codepilot1c.core.diagnostics.DiagnosticsLineFilter.normalize(lineFrom, lineTo);
        lineFrom = range[0];
        lineTo = range[1];

        // Parse severity
        Severity minSeverity = parseSeverity(severityStr);

        DiagnosticsQuery query = new DiagnosticsQuery(
                minSeverity, maxItems, true, waitMs, includeRuntimeMarkers, lineFrom, lineTo,
                includeCheckHelp, helpLocale);
        EdtDiagnosticsCollector collector = EdtDiagnosticsCollector.getInstance();

        String normalizedScope = normalizeScope(scope, path, projectName);
        boolean collectWorkspaceDiagnostics = false;
        if ("project".equals(normalizedScope) && (projectName == null || projectName.isBlank())) { //$NON-NLS-1$
            projectName = collector.resolveDefaultProjectName();
            if (projectName == null || projectName.isBlank()) {
                collectWorkspaceDiagnostics = true;
            }
        }
        CompletableFuture<DiagnosticsResult> resultFuture;

        resultFuture = switch (normalizedScope) {
            case "project" -> collectWorkspaceDiagnostics
                    ? collector.collectFromWorkspace(query)
                    : collector.collectFromProject(projectName, query); //$NON-NLS-1$
            case "file" -> collector.collectFromFile(path, query); //$NON-NLS-1$
            default -> collector.collectFromActiveEditor(query);
        };

        return resultFuture.thenApply(result -> {
            String formatted = result.formatForLlm();
            return ToolResult.success(formatted);
        }).exceptionally(e -> {
            LOG.error("get_diagnostics failed: %s", e.getMessage()); //$NON-NLS-1$
            return ToolResult.failure("Failed to get diagnostics: " + e.getMessage()); //$NON-NLS-1$
        });
    }

    private Severity parseSeverity(String str) {
        if (str == null) return Severity.INFO;
        return switch (str.toLowerCase()) {
            case "warning", "warn" -> Severity.WARNING; //$NON-NLS-1$ //$NON-NLS-2$
            case "info", "all" -> Severity.INFO; //$NON-NLS-1$ //$NON-NLS-2$
            default -> Severity.INFO;
        };
    }

    private int getIntParam(Map<String, Object> params, String key, int defaultValue) {
        Object value = params.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Number num) {
            return num.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private boolean getBooleanParam(Map<String, Object> params, String key, boolean defaultValue) {
        Object value = params.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        String str = String.valueOf(value).trim();
        if (str.isEmpty()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(str);
    }

    private String normalizeScope(String scope, String path, String projectName) {
        if (scope != null && !scope.isBlank()) {
            String normalized = scope.trim().toLowerCase();
            if ("project".equals(normalized) || "file".equals(normalized) || "active_editor".equals(normalized)) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                if ("file".equals(normalized) && (path == null || path.isBlank())) { //$NON-NLS-1$
                    return "project"; //$NON-NLS-1$
                }
                return normalized;
            }
        }
        if (path != null && !path.isBlank()) {
            return "file"; //$NON-NLS-1$
        }
        return "project"; //$NON-NLS-1$
    }
}
