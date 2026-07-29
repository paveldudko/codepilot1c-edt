/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools;

import java.util.Map;
import java.util.Set;

/**
 * The honest "you passed a key I ignored" note, in one place so that every tool gets it and not
 * only the ones that happen to extend {@link AbstractTool}.
 *
 * <p>This logic used to live as private methods on {@code AbstractTool}, which meant it covered a
 * tool only by inheritance. {@code get_diagnostics} and {@code get_diagnostics_details} implement
 * {@link ITool} directly, so both slipped through — and {@code get_diagnostics} is the very tool
 * the guard's own javadoc cites as motivation ("answered a {@code project=…} call about the default
 * project — both without a word"). Live 2026-07-29: {@code get_diagnostics(project=…, scope=project)}
 * answered in full about a DIFFERENT project than the caller named, with no note at all, because
 * the accepted spelling is {@code project_name}. Hanging the note off a static helper plus
 * {@link AdvisoryToolWrapper} makes coverage a property of registration rather than of a
 * superclass, so a future tool cannot opt out by choosing a different base.</p>
 *
 * @see SchemaKeyGuard for the key comparison itself
 */
public final class ToolAdvisory {

    /**
     * Tools reached only by dispatch, never called directly, so an "unknown parameter" advisory on
     * them would report the dispatcher's own routing rather than a caller mistake.
     *
     * <p>{@code edt_diagnostics}, {@code qa_inspect} and {@code qa_generate} forward the whole
     * argument map to the chosen delegate — {@code command} included, and {@code edt_diagnostics}
     * additionally injects BOTH {@code project} and {@code project_name} via
     * {@code EdtDiagnosticsCommandContract.applyProjectFieldAliases}. The delegates below therefore
     * always see keys their own schemas do not declare. (The three dispatchers themselves need no
     * entry: their schemas state {@code additionalProperties: true}, which already turns the guard
     * off.)</p>
     */
    private static final Set<String> EXEMPT_TOOLS = Set.of(
            "edt_metadata_smoke", "edt_trace_export", "analyze_tool_error", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "edt_update_infobase", "edt_launch_app", //$NON-NLS-1$ //$NON-NLS-2$
            "qa_explain_config", "qa_status", "qa_steps_search", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "qa_init_config", "qa_migrate_config", "qa_compile_feature"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    private ToolAdvisory() {
    }

    /** Whether a tool is reached only by dispatch, so an advisory on it would blame the dispatcher. */
    public static boolean isExempt(String toolName) {
        return toolName != null && EXEMPT_TOOLS.contains(toolName);
    }

    /**
     * Appends an honest advisory when the caller passed a top-level parameter the tool's schema does
     * not declare, so a mistyped or misplaced key stops being a silent no-op.
     *
     * <p>Advisory ONLY: it never fails a call and never changes behaviour, so a tool whose schema
     * under-declares a pass-through key loses nothing but the accuracy of this note. Two kinds of
     * dropped key are covered: one the schema does not declare at all, and — for a composite tool —
     * one it declares for a DIFFERENT {@code command} than the call dispatched.</p>
     *
     * @param toolName   the tool's registered name, used in the note and against the exempt list
     * @param schemaJson the tool's parameter schema
     * @param result     the result to annotate; returned unchanged when there is nothing to say
     * @param parameters the parameters as the caller passed them
     * @return the result, with the note appended when a key was dropped
     */
    public static ToolResult annotate(
            String toolName, String schemaJson, ToolResult result, Map<String, Object> parameters) {
        try {
            if (result == null || parameters == null || parameters.isEmpty() || isExempt(toolName)) {
                return result;
            }
            String advisory = unknownParameterNote(toolName, schemaJson, parameters)
                    + foreignCommandNote(toolName, schemaJson, parameters);
            if (advisory.isEmpty()) {
                return result;
            }
            if (!result.isSuccess()) {
                String error = result.getErrorMessage() == null ? "" : result.getErrorMessage(); //$NON-NLS-1$
                return ToolResult.failure(error + advisory);
            }
            String content = result.getContent() == null ? "" : result.getContent(); //$NON-NLS-1$
            return result.getStructuredData() != null
                    ? ToolResult.success(content + advisory, result.getType(), result.getStructuredData())
                    : ToolResult.success(content + advisory, result.getType());
        } catch (RuntimeException e) {
            // An advisory must never be the reason a call fails.
            return result;
        }
    }

    /** The note for a key the schema does not declare at all, or {@code ""} when there is none. */
    private static String unknownParameterNote(
            String toolName, String schemaJson, Map<String, Object> parameters) {
        SchemaKeyGuard.Report report = SchemaKeyGuard.inspect(schemaJson, parameters.keySet());
        if (report.isClean()) {
            return ""; //$NON-NLS-1$
        }
        return SchemaKeyGuard.advisoryLine(toolName, report.unknownKeys(),
                SchemaKeyGuard.forDisplay(report.acceptedKeys(), null));
    }

    /**
     * The note for a key the schema declares for another {@code command} than the one just
     * dispatched, or {@code ""} when there is none.
     *
     * <p>Generic on purpose: it fires for any tool whose schema declares a {@code command} enum and
     * tags its per-command properties {@code (command) …}. Everything else — the
     * {@code additionalProperties:true} dispatchers included — is unenforceable and stays silent.</p>
     */
    private static String foreignCommandNote(
            String toolName, String schemaJson, Map<String, Object> parameters) {
        Object command = parameters.get("command"); //$NON-NLS-1$
        CompositeCommandKeyGuard.Report report = CompositeCommandKeyGuard.inspect(
                schemaJson,
                command == null ? null : String.valueOf(command),
                parameters.keySet());
        if (report.isClean()) {
            return ""; //$NON-NLS-1$
        }
        return CompositeCommandKeyGuard.advisoryLine(toolName, report.command(), report.foreignKeys(),
                SchemaKeyGuard.forDisplay(report.acceptedKeys(), null));
    }
}
