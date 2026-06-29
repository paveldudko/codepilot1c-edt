/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.surface;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure translation between an explicit per-tool selection (the checkbox tree in
 * the endpoint-profile editor) and the {@link ToolGroupVisibility} facet strings
 * stored on a profile ({@code enableGroups} / {@code disableTools}).
 *
 * <p>Encoding rule — a group is <em>on</em> (allowlisted) as soon as at least one
 * of its tools is selected; within an on-group the unselected tools are pushed to
 * {@code disableTools} ("group minus exceptions"), so a tool added to that group
 * in a future release is announced by default. A fully-selected group contributes
 * no exceptions; a fully-unselected group is simply absent. This keeps the stored
 * model identical in <em>which</em> tools it announces to what the tree shows, while
 * staying forward-compatible as new tools land.</p>
 *
 * <p>Kept free of SWT and {@link com.codepilot1c.core.tools.ToolRegistry} so it is
 * unit-testable in isolation. The editor builds the {@code toolsByGroup} map from
 * the live registry via {@link ToolGroupTaxonomy#groupOf}.</p>
 */
public final class ToolSelectionModel {

    private final String enableGroups;
    private final String disableTools;

    private ToolSelectionModel(String enableGroups, String disableTools) {
        this.enableGroups = enableGroups;
        this.disableTools = disableTools;
    }

    /** Comma-separated enabled groups (allowlist), or empty when nothing is selected. */
    public String getEnableGroups() {
        return enableGroups;
    }

    /** Comma-separated per-tool exceptions disabled within otherwise-on groups. */
    public String getDisableTools() {
        return disableTools;
    }

    /**
     * Build the facets from a per-tool selection.
     *
     * @param toolsByGroup group token -&gt; the tool names that resolve to it
     *                     (insertion order preserved for stable output)
     * @param selected     the tool names the operator checked
     */
    public static ToolSelectionModel fromSelection(Map<String, List<String>> toolsByGroup, Set<String> selected) {
        List<String> enableGroups = new ArrayList<>();
        List<String> disableTools = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : toolsByGroup.entrySet()) {
            List<String> tools = entry.getValue();
            if (tools == null || tools.isEmpty()) {
                continue;
            }
            int selectedCount = 0;
            for (String tool : tools) {
                if (selected.contains(tool)) {
                    selectedCount++;
                }
            }
            if (selectedCount == 0) {
                continue; // group entirely off
            }
            enableGroups.add(entry.getKey()); // group on (allowlist)
            if (selectedCount < tools.size()) {
                for (String tool : tools) {
                    if (!selected.contains(tool)) {
                        disableTools.add(tool); // unselected exception within an on-group
                    }
                }
            }
        }
        return new ToolSelectionModel(String.join(",", enableGroups), String.join(",", disableTools)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** {@code true} when the selection announces no group (and so should be rejected by the editor). */
    public boolean isEmpty() {
        return enableGroups.isEmpty();
    }
}
