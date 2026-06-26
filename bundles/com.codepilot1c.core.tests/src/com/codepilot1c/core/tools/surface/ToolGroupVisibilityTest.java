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

import org.junit.Test;

/**
 * Unit tests for the operator-configurable tool group/per-tool announce gate.
 * Pure logic — no EDT/EMF runtime needed.
 *
 * <p>Backs feedback {@code 2026-06-26-toggleable-tool-groups.md}.</p>
 */
public class ToolGroupVisibilityTest {

    // --- ToolGroupTaxonomy: category + mutating -> group --------------------

    @Test
    public void groupNameAppliesReadWriteFacetToMutatingCategories() {
        assertEquals("metadata.read", //$NON-NLS-1$
                ToolGroupTaxonomy.groupName(ToolCategory.METADATA_MUTATION, false));
        assertEquals("metadata.write", //$NON-NLS-1$
                ToolGroupTaxonomy.groupName(ToolCategory.METADATA_MUTATION, true));
        assertEquals("forms.read", ToolGroupTaxonomy.groupName(ToolCategory.FORMS, false)); //$NON-NLS-1$
        assertEquals("forms.write", ToolGroupTaxonomy.groupName(ToolCategory.FORMS, true)); //$NON-NLS-1$
        assertEquals("workspace.read", //$NON-NLS-1$
                ToolGroupTaxonomy.groupName(ToolCategory.WORKSPACE_GIT_IMPORT, false));
        assertEquals("workspace.write", //$NON-NLS-1$
                ToolGroupTaxonomy.groupName(ToolCategory.WORKSPACE_GIT_IMPORT, true));
    }

    @Test
    public void groupNameLeavesNonFacetedCategoriesBare() {
        assertEquals("bsl", ToolGroupTaxonomy.groupName(ToolCategory.EDT_SEMANTIC_READ, false)); //$NON-NLS-1$
        assertEquals("dcs", ToolGroupTaxonomy.groupName(ToolCategory.DCS, true)); //$NON-NLS-1$
        assertEquals("qa", ToolGroupTaxonomy.groupName(ToolCategory.QA, true)); //$NON-NLS-1$
        assertEquals("extensions", //$NON-NLS-1$
                ToolGroupTaxonomy.groupName(ToolCategory.EXTENSIONS_EXTERNALS, true));
        assertEquals("diagnostics", //$NON-NLS-1$
                ToolGroupTaxonomy.groupName(ToolCategory.SMOKE_RUNTIME_RECOVERY, false));
    }

    @Test
    public void metaToolsOverrideCategory() {
        // task is category=general -> DYNAMIC, but belongs to the meta group.
        assertEquals("meta", //$NON-NLS-1$
                ToolGroupTaxonomy.groupOf("task", ToolCategory.DYNAMIC, false)); //$NON-NLS-1$
        assertEquals("meta", //$NON-NLS-1$
                ToolGroupTaxonomy.groupOf("discover_tools", ToolCategory.DYNAMIC, false)); //$NON-NLS-1$
    }

    @Test
    public void baseGroupStripsFacet() {
        assertEquals("metadata", ToolGroupTaxonomy.baseGroup("metadata.write")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("bsl", ToolGroupTaxonomy.baseGroup("bsl")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // --- ToolGroupVisibility: decision logic --------------------------------

    @Test
    public void emptyConfigAnnouncesEverything() {
        ToolGroupVisibility v = ToolGroupVisibility.of(null, null, null, null);
        assertFalse("empty config is not active", v.isActive()); //$NON-NLS-1$
        assertTrue(v.isToolVisible("create_metadata", "metadata.write")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(v.isToolVisible("qa_run", "qa")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void disableGroupHidesFacetButNotSibling() {
        ToolGroupVisibility v = ToolGroupVisibility.of("metadata.write", null, null, null); //$NON-NLS-1$
        assertTrue(v.isActive());
        assertFalse(v.isToolVisible("create_metadata", "metadata.write")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(v.isToolVisible("scan_metadata_index", "metadata.read")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void bareGroupTokenMatchesAllFacets() {
        ToolGroupVisibility v = ToolGroupVisibility.of("metadata", null, null, null); //$NON-NLS-1$
        assertFalse(v.isToolVisible("create_metadata", "metadata.write")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(v.isToolVisible("scan_metadata_index", "metadata.read")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void groupTokenIsCaseInsensitive() {
        ToolGroupVisibility v = ToolGroupVisibility.of("METADATA.Write", null, null, null); //$NON-NLS-1$
        assertFalse(v.isToolVisible("create_metadata", "metadata.write")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void perToolDisableWinsOverEnabledGroup() {
        ToolGroupVisibility v = ToolGroupVisibility.of(null, "render_template", null, null); //$NON-NLS-1$
        assertFalse(v.isToolVisible("render_template", "metadata.write")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(v.isToolVisible("create_metadata", "metadata.write")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void allowlistModeCarriesOnlyListedGroups() {
        ToolGroupVisibility v = ToolGroupVisibility.of(null, null, "bsl,meta", null); //$NON-NLS-1$
        assertTrue(v.isAllowlistMode());
        assertTrue(v.isToolVisible("bsl_list_methods", "bsl")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(v.isToolVisible("task", "meta")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(v.isToolVisible("create_metadata", "metadata.write")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(v.isToolVisible("qa_run", "qa")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void perToolEnableWinsInAllowlistMode() {
        ToolGroupVisibility v = ToolGroupVisibility.of(null, null, "bsl", "git_inspect"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(v.isToolVisible("git_mutate", "workspace.write")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(v.isToolVisible("git_inspect", "workspace.read")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void discoverToolsIsAlwaysVisible() {
        // Even when its group (meta) is not enabled and everything else is denied.
        ToolGroupVisibility allowOnlyBsl = ToolGroupVisibility.of(null, null, "bsl", null); //$NON-NLS-1$
        assertTrue(allowOnlyBsl.isToolVisible("discover_tools", "meta")); //$NON-NLS-1$ //$NON-NLS-2$
        ToolGroupVisibility denyMeta = ToolGroupVisibility.of("meta", null, null, null); //$NON-NLS-1$
        assertTrue(denyMeta.isToolVisible("discover_tools", "meta")); //$NON-NLS-1$ //$NON-NLS-2$
        // ...but a per-tool disable cannot kill it either (lazy-reveal floor).
        ToolGroupVisibility denyTool = ToolGroupVisibility.of(null, "discover_tools", null, null); //$NON-NLS-1$
        assertTrue(denyTool.isToolVisible("discover_tools", "meta")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void matchesGroupTokenExactAndBare() {
        assertTrue(ToolGroupVisibility.matchesGroupToken(java.util.Set.of("metadata.write"), "metadata.write")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(ToolGroupVisibility.matchesGroupToken(java.util.Set.of("metadata"), "metadata.write")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(ToolGroupVisibility.matchesGroupToken(java.util.Set.of("metadata.read"), "metadata.write")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
