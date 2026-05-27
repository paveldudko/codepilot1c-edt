/*
 * Copyright (c) 2026 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.ui.tools;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.diagnostics.CheckInfoResolver;
import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.tools.ITool;
import com.codepilot1c.core.tools.ToolResult;

/**
 * Standalone MCP tool that returns the rich EDT check description (Markdown)
 * for one or more {@code check_id}s.
 *
 * <p>This complements {@code get_diagnostics}: a typical two-step flow is to
 * call {@code get_diagnostics} first to surface failing checks, then
 * {@code get_diagnostics_details} on the subset of check IDs the model
 * wants guidance on. Use the {@code include_check_help} parameter on
 * {@code get_diagnostics} instead when you want all descriptions inlined in a
 * single round trip.</p>
 */
public class GetDiagnosticsDetailsTool implements ITool {

    private static final VibeLogger.CategoryLogger LOG =
            VibeLogger.forClass(GetDiagnosticsDetailsTool.class);

    private static final String SCHEMA = """
            {
                "type": "object",
                "properties": {
                    "check_ids": {
                        "oneOf": [
                            {
                                "type": "string",
                                "description": "Single EDT check identifier, e.g. 'manager-module-named-self-reference'."
                            },
                            {
                                "type": "array",
                                "items": { "type": "string" },
                                "description": "List of EDT check identifiers to fetch descriptions for. Order is preserved; duplicates and blanks are ignored."
                            }
                        ],
                        "description": "Required. Either a single check_id string or an array of strings."
                    },
                    "locale": {
                        "type": "string",
                        "enum": ["en", "ru"],
                        "description": "Preferred locale for the description. Defaults to 'en' (matches the project's English-only codebase). Falls back to English when the requested locale has no localized HTML."
                    }
                },
                "required": ["check_ids"]
            }
            """; //$NON-NLS-1$

    @Override
    public String getName() {
        return "get_diagnostics_details"; //$NON-NLS-1$
    }

    @Override
    public String getDescription() {
        return "Returns the rich EDT 'Check Info' description (Markdown) for one or more check_id values. " //$NON-NLS-1$
                + "Use this after get_diagnostics when you need guidance on how to fix an issue — Noncompliant/Compliant " //$NON-NLS-1$
                + "code examples, category, rationale. Accepts either a single check_id string or an array of strings. " //$NON-NLS-1$
                + "Checks whose contributor bundle ships no HTML description are listed under 'Not available'; for " //$NON-NLS-1$
                + "those, fall back to the short blurb shown on the 'details:' line of get_diagnostics output."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
        List<String> checkIds = parseCheckIds(parameters.get("check_ids")); //$NON-NLS-1$
        if (checkIds.isEmpty()) {
            return CompletableFuture.completedFuture(
                    ToolResult.failure("Parameter 'check_ids' is required and must contain at least one non-blank string.")); //$NON-NLS-1$
        }
        String locale = (String) parameters.getOrDefault("locale", "en"); //$NON-NLS-1$ //$NON-NLS-2$

        return CompletableFuture.supplyAsync(() -> {
            try {
                CheckInfoResolver resolver = CheckInfoResolver.getInstance();
                StringBuilder sb = new StringBuilder();
                List<String> notAvailable = new ArrayList<>();
                int rendered = 0;

                for (String id : checkIds) {
                    Optional<String> md = resolver.findMarkdown(id, locale);
                    if (md.isEmpty()) {
                        notAvailable.add(id);
                        continue;
                    }
                    if (rendered > 0) {
                        sb.append("\n---\n\n"); //$NON-NLS-1$
                    }
                    sb.append("## ").append(id).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
                    String body = md.get();
                    sb.append(body);
                    if (!body.endsWith("\n")) { //$NON-NLS-1$
                        sb.append("\n"); //$NON-NLS-1$
                    }
                    rendered++;
                }

                if (rendered == 0) {
                    return ToolResult.success(
                            "No bundled check descriptions found for: " + String.join(", ", notAvailable) //$NON-NLS-1$ //$NON-NLS-2$
                                    + ".\nFall back to the check_description field from get_diagnostics, " //$NON-NLS-1$
                                    + "or read the check source in the contributing bundle."); //$NON-NLS-1$
                }

                if (!notAvailable.isEmpty()) {
                    sb.append("\n---\n\n## Not available\n\n"); //$NON-NLS-1$
                    sb.append("No bundled HTML description for: ") //$NON-NLS-1$
                            .append(String.join(", ", notAvailable)).append(".\n"); //$NON-NLS-1$ //$NON-NLS-2$
                }

                return ToolResult.success(sb.toString());
            } catch (Exception e) {
                LOG.error("get_diagnostics_details failed: %s", e.getMessage()); //$NON-NLS-1$
                return ToolResult.failure("Failed to resolve check details: " + e.getMessage()); //$NON-NLS-1$
            }
        });
    }

    private List<String> parseCheckIds(Object raw) {
        if (raw == null) {
            return List.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (raw instanceof String s) {
            addIfNotBlank(out, s);
        } else if (raw instanceof Collection<?> collection) {
            for (Object item : collection) {
                if (item != null) {
                    addIfNotBlank(out, String.valueOf(item));
                }
            }
        } else if (raw.getClass().isArray()) {
            for (Object item : (Object[]) raw) {
                if (item != null) {
                    addIfNotBlank(out, String.valueOf(item));
                }
            }
        } else {
            addIfNotBlank(out, String.valueOf(raw));
        }
        return List.copyOf(out);
    }

    private void addIfNotBlank(LinkedHashSet<String> sink, String value) {
        if (value == null) {
            return;
        }
        String trimmed = value.trim();
        if (!trimmed.isEmpty()) {
            sink.add(trimmed);
        }
    }
}
