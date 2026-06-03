/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.ui.diagnostics;

/**
 * Represents a single EDT diagnostic (error, warning, info).
 */
public record EdtDiagnostic(
        String filePath,
        int lineNumber,
        int charStart,
        int charEnd,
        String message,
        Severity severity,
        String markerType,
        String source,
        String codeSnippet,
        String checkId,
        String checkTitle,
        String checkDescription,
        String issueType,
        String issueSeverity,
        String objectPresentation,
        String locationText) {

    /**
     * Diagnostic severity level.
     */
    public enum Severity {
        ERROR(2),
        WARNING(1),
        INFO(0);

        private final int level;

        Severity(int level) {
            this.level = level;
        }

        public int getLevel() {
            return level;
        }

        /**
         * Converts Eclipse IMarker severity to enum.
         */
        public static Severity fromMarkerSeverity(int severity) {
            return switch (severity) {
                case 2 -> ERROR;   // IMarker.SEVERITY_ERROR
                case 1 -> WARNING; // IMarker.SEVERITY_WARNING
                default -> INFO;   // IMarker.SEVERITY_INFO
            };
        }
    }

    /**
     * Creates a diagnostic from marker data.
     */
    public static EdtDiagnostic fromMarker(
            String filePath,
            int lineNumber,
            int charStart,
            int charEnd,
            String message,
            int markerSeverity,
            String markerType,
            String codeSnippet) {
        return new EdtDiagnostic(
                filePath,
                lineNumber,
                charStart,
                charEnd,
                message,
                Severity.fromMarkerSeverity(markerSeverity),
                markerType,
                "marker", //$NON-NLS-1$
                codeSnippet,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    /**
     * Creates a diagnostic from annotation data.
     */
    public static EdtDiagnostic fromAnnotation(
            String filePath,
            int lineNumber,
            int charStart,
            int charEnd,
            String message,
            Severity severity,
            String annotationType,
            String codeSnippet) {
        return fromAnnotation(
                filePath, lineNumber, charStart, charEnd, message, severity,
                annotationType, codeSnippet, null);
    }

    /**
     * Creates a diagnostic from annotation data, carrying an optional
     * {@code checkId} (e.g. the Xtext {@code Issue.getCode()} recovered from an
     * open editor's {@code XtextAnnotation}). Surfaced in the debug-gated
     * branch only — see {@link #formatForLlm(boolean)} — until we confirm these
     * codes match the v8-code-style identifiers.
     */
    public static EdtDiagnostic fromAnnotation(
            String filePath,
            int lineNumber,
            int charStart,
            int charEnd,
            String message,
            Severity severity,
            String annotationType,
            String codeSnippet,
            String checkId) {
        return new EdtDiagnostic(
                filePath,
                lineNumber,
                charStart,
                charEnd,
                message,
                severity,
                annotationType,
                "annotation", //$NON-NLS-1$
                codeSnippet,
                checkId,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    /**
     * Creates a diagnostic from EDT runtime marker manager data.
     */
    public static EdtDiagnostic fromRuntimeMarker(
            String filePath,
            int lineNumber,
            String message,
            Severity severity,
            String markerType,
            String checkId,
            String checkTitle,
            String checkDescription,
            String issueType,
            String issueSeverity,
            String objectPresentation,
            String locationText) {
        return new EdtDiagnostic(
                filePath,
                lineNumber,
                -1,
                -1,
                message,
                severity,
                markerType,
                "marker_manager", //$NON-NLS-1$
                null,
                checkId,
                checkTitle,
                checkDescription,
                issueType,
                issueSeverity,
                objectPresentation,
                locationText);
    }

    /**
     * Formats a single diagnostic for LLM output (no debug provenance).
     */
    public String formatForLlm() {
        return formatForLlm(false);
    }

    /** Back-compat: single diagnostic with its severity prefix. */
    public String formatForLlm(boolean includeDebug) {
        return formatForLlm(includeDebug, true);
    }

    /**
     * Formats a single diagnostic for LLM output. Used for groups of size 1;
     * repeated diagnostics that share a rule are collapsed by
     * {@code DiagnosticsResult.formatForLlm} into a compact grouped line.
     *
     * @param includeDebug when {@code true}, append debug-only {@code source} /
     *        {@code source_type} lines for inspecting marker provenance. Gated
     *        behind the diagnostics-verbose toggle to avoid token noise.
     * @param includeSeverity when {@code true}, prefix the line with the
     *        {@code **SEVERITY**} tag. Pass {@code false} when the caller already
     *        groups diagnostics under a per-severity section header.
     */
    public String formatForLlm(boolean includeDebug, boolean includeSeverity) {
        StringBuilder sb = new StringBuilder();
        sb.append("- "); //$NON-NLS-1$
        if (includeSeverity) {
            sb.append("**").append(severity.name()).append("** "); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (lineNumber >= 0) {
            sb.append("line ").append(lineNumber); //$NON-NLS-1$
            if (charStart >= 0 && charEnd >= 0) {
                sb.append(" (pos ").append(charStart).append("-").append(charEnd).append(")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
            sb.append(": "); //$NON-NLS-1$
        }
        sb.append(message); //$NON-NLS-1$
        // Stable kebab rule code as a trailing tag, e.g. [export-procedure-missing-comment].
        if (checkId != null && !checkId.isBlank()) {
            sb.append("  [").append(checkId).append("]"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (issueType != null && !issueType.isBlank()) {
            sb.append("\n  issue_type: ").append(issueType); //$NON-NLS-1$
        }
        if (issueSeverity != null && !issueSeverity.isBlank()) {
            sb.append("\n  issue_severity: ").append(issueSeverity); //$NON-NLS-1$
        }
        if (locationText != null && !locationText.isBlank()) {
            sb.append("\n  location: ").append(locationText); //$NON-NLS-1$
        }
        if (objectPresentation != null && !objectPresentation.isBlank()) {
            sb.append("\n  object: ").append(objectPresentation); //$NON-NLS-1$
        }
        if (includeDebug) {
            // Debug-only marker provenance: source = collection path,
            // source_type = marker.getSourceType()/annotation type.
            if (source != null && !source.isBlank()) {
                sb.append("\n  source: ").append(source); //$NON-NLS-1$
            }
            if (markerType != null && !markerType.isBlank()) {
                sb.append("\n  source_type: ").append(markerType); //$NON-NLS-1$
            }
        }
        if (codeSnippet != null && !codeSnippet.isBlank()) {
            sb.append("\n  ```\n  ").append(codeSnippet.trim()).append("\n  ```"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return sb.toString();
    }

    /** Stable grouping key: the rule code when known, else the message text. */
    public String groupKey() {
        if (checkId != null && !checkId.isBlank()) {
            return checkId;
        }
        return message != null ? message : ""; //$NON-NLS-1$
    }

    /** Label for a collapsed group: the rule code when known, else the message. */
    public String groupLabel() {
        if (checkId != null && !checkId.isBlank()) {
            return checkId;
        }
        return message != null ? message : ""; //$NON-NLS-1$
    }
}
