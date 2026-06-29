/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.surface;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

/**
 * Unit tests for {@link ToolSelectionModel}: translating a per-tool checkbox
 * selection into the {@code enableGroups}/{@code disableTools} facets. Pure logic.
 */
public class ToolSelectionModelTest {

    private static Map<String, List<String>> sampleGroups() {
        Map<String, List<String>> g = new LinkedHashMap<>();
        g.put("metadata.read", List.of("scan_metadata_index", "find_metadata")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        g.put("bsl", List.of("bsl_list_methods", "bsl_get_method_body", "bsl_analyze_method")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        g.put("qa", List.of("qa_run")); //$NON-NLS-1$ //$NON-NLS-2$
        return g;
    }

    @Test
    public void fullyCheckedGroupAddsGroupWithNoExceptions() {
        ToolSelectionModel m = ToolSelectionModel.fromSelection(sampleGroups(),
                Set.of("scan_metadata_index", "find_metadata")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("metadata.read", m.getEnableGroups()); //$NON-NLS-1$
        assertEquals("", m.getDisableTools()); //$NON-NLS-1$
        assertFalse(m.isEmpty());
    }

    @Test
    public void partiallyCheckedGroupKeepsGroupAndDisablesTheRest() {
        ToolSelectionModel m = ToolSelectionModel.fromSelection(sampleGroups(),
                Set.of("scan_metadata_index", "bsl_list_methods")); //$NON-NLS-1$ //$NON-NLS-2$
        // Both touched groups are on (allowlist), and the unchecked tools become exceptions.
        assertEquals("metadata.read,bsl", m.getEnableGroups()); //$NON-NLS-1$
        assertEquals("find_metadata,bsl_get_method_body,bsl_analyze_method", m.getDisableTools()); //$NON-NLS-1$
    }

    @Test
    public void unselectedGroupIsAbsent() {
        ToolSelectionModel m = ToolSelectionModel.fromSelection(sampleGroups(),
                Set.of("qa_run")); //$NON-NLS-1$
        assertEquals("qa", m.getEnableGroups()); //$NON-NLS-1$
        assertEquals("", m.getDisableTools()); //$NON-NLS-1$
    }

    @Test
    public void emptySelectionIsEmpty() {
        ToolSelectionModel m = ToolSelectionModel.fromSelection(sampleGroups(), Set.of());
        assertTrue(m.isEmpty());
        assertEquals("", m.getEnableGroups()); //$NON-NLS-1$
        assertEquals("", m.getDisableTools()); //$NON-NLS-1$
    }

    @Test
    public void selectionRoundTripsThroughGroupVisibility() {
        // A partial selection must announce exactly the selected tools (no more, no less).
        ToolSelectionModel m = ToolSelectionModel.fromSelection(sampleGroups(),
                Set.of("scan_metadata_index", "bsl_list_methods", "bsl_get_method_body")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        ToolGroupVisibility v = ToolGroupVisibility.of(null, m.getDisableTools(), m.getEnableGroups(), null);
        assertTrue(v.isToolVisible("scan_metadata_index", "metadata.read")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(v.isToolVisible("find_metadata", "metadata.read")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(v.isToolVisible("bsl_list_methods", "bsl")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(v.isToolVisible("bsl_get_method_body", "bsl")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(v.isToolVisible("bsl_analyze_method", "bsl")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(v.isToolVisible("qa_run", "qa")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
