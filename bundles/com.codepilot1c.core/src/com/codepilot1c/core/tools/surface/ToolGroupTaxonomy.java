/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.surface;

import java.util.List;
import java.util.Set;

import com.codepilot1c.core.tools.ITool;

/**
 * Maps a tool to an operator-facing <em>group</em> name for the configurable
 * announce surface (see {@link ToolGroupVisibility}).
 *
 * <p>Group names are layered on top of the existing {@code discover_tools}
 * category vocabulary ({@code bsl / metadata / forms / extensions / dcs / qa /
 * diagnostics / workspace}) plus the always-on base groups {@code files} and
 * {@code meta}. A read/write facet is appended for the categories where roles
 * split along that line — {@code files / metadata / forms / workspace} —
 * producing e.g. {@code metadata.read} / {@code metadata.write}. Categories
 * without a meaningful read/write split ({@code bsl}, {@code dcs}, {@code qa},
 * {@code extensions}, {@code diagnostics}) stay single groups.</p>
 *
 * <p>A bare category token (e.g. {@code metadata}) matches every facet of that
 * category; a faceted token (e.g. {@code metadata.write}) targets only that
 * facet — see {@link ToolGroupVisibility#matchesGroupToken}.</p>
 *
 * <p>Backs feedback {@code 2026-06-26-toggleable-tool-groups.md}.</p>
 */
public final class ToolGroupTaxonomy {

    /**
     * Canonical announce-surface group tokens in a stable display order, for the
     * endpoint-profile tool-set editor (one checkbox per token). Faceted tokens
     * ({@code metadata.read}/{@code metadata.write}) are listed separately; a bare
     * token (e.g. {@code metadata}) used in config matches both facets.
     */
    public static final List<String> ANNOUNCEABLE_GROUPS = List.of(
            "diagnostics", //$NON-NLS-1$
            "bsl", //$NON-NLS-1$
            "metadata.read", //$NON-NLS-1$
            "metadata.write", //$NON-NLS-1$
            "forms.read", //$NON-NLS-1$
            "forms.write", //$NON-NLS-1$
            "files.read", //$NON-NLS-1$
            "files.write", //$NON-NLS-1$
            "workspace.read", //$NON-NLS-1$
            "workspace.write", //$NON-NLS-1$
            "dcs", //$NON-NLS-1$
            "extensions", //$NON-NLS-1$
            "qa", //$NON-NLS-1$
            "meta"); //$NON-NLS-1$

    /** Tools that belong to the {@code meta} group regardless of their category. */
    public static final Set<String> META_TOOLS = Set.of(
            "task", //$NON-NLS-1$
            "skill", //$NON-NLS-1$
            "discover_tools", //$NON-NLS-1$
            "delegate_to_agent", //$NON-NLS-1$
            "remember_fact"); //$NON-NLS-1$

    private ToolGroupTaxonomy() {
    }

    /**
     * Resolve the group of a live tool (meta name-override first, then the
     * surface category + mutating facet).
     */
    public static String groupOf(ITool tool) {
        if (tool == null) {
            return "dynamic"; //$NON-NLS-1$
        }
        return groupOf(tool.getName(), BuiltinToolTaxonomy.categoryOf(tool), tool.isMutating());
    }

    /**
     * Pure resolver: {@code (toolName, category, mutating) -> group}. Kept free
     * of the {@link com.codepilot1c.core.tools.ToolRegistry} so it is unit-testable.
     */
    public static String groupOf(String toolName, ToolCategory category, boolean mutating) {
        if (toolName != null && META_TOOLS.contains(toolName)) {
            return "meta"; //$NON-NLS-1$
        }
        return groupName(category, mutating);
    }

    /** Group name for a {@link ToolCategory} + mutating facet, ignoring meta tools. */
    public static String groupName(ToolCategory category, boolean mutating) {
        if (category == null) {
            return "dynamic"; //$NON-NLS-1$
        }
        return switch (category) {
            case FILES_READ_SEARCH -> "files.read"; //$NON-NLS-1$
            case FILES_WRITE_EDIT -> "files.write"; //$NON-NLS-1$
            case WORKSPACE_GIT_IMPORT -> mutating ? "workspace.write" : "workspace.read"; //$NON-NLS-1$ //$NON-NLS-2$
            case EDT_SEMANTIC_READ -> "bsl"; //$NON-NLS-1$
            case METADATA_MUTATION -> mutating ? "metadata.write" : "metadata.read"; //$NON-NLS-1$ //$NON-NLS-2$
            case FORMS -> mutating ? "forms.write" : "forms.read"; //$NON-NLS-1$ //$NON-NLS-2$
            case EXTENSIONS_EXTERNALS -> "extensions"; //$NON-NLS-1$
            case DCS -> "dcs"; //$NON-NLS-1$
            case QA -> "qa"; //$NON-NLS-1$
            case SMOKE_RUNTIME_RECOVERY -> "diagnostics"; //$NON-NLS-1$
            case DYNAMIC -> "dynamic"; //$NON-NLS-1$
        };
    }

    /** The base category of a group token, i.e. the part before any {@code .read}/{@code .write}. */
    public static String baseGroup(String group) {
        if (group == null) {
            return ""; //$NON-NLS-1$
        }
        int dot = group.indexOf('.');
        return dot < 0 ? group : group.substring(0, dot);
    }
}
