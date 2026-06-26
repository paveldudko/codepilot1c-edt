/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.mcp.host;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.codepilot1c.core.tools.surface.ToolGroupVisibility;

/**
 * Unit tests for the multi-endpoint profile model: seeded defaults and the
 * per-profile tool-set gate derived from {@link ToolGroupVisibility}. Pure logic
 * — no EDT/EMF/preferences runtime needed.
 */
public class ProfileEndpointTest {

    @Test
    public void fullDefaultAnnouncesEverything() {
        ProfileEndpoint full = ProfileEndpoint.fullDefault(8765, "tok", "*"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("full", full.getName()); //$NON-NLS-1$
        assertEquals(8765, full.getPort());
        assertEquals("tok", full.getBearerToken()); //$NON-NLS-1$
        assertTrue(full.isEnabled());
        assertFalse("full's gate is inactive (everything announced)", //$NON-NLS-1$
                full.toGroupVisibility().isActive());
        assertTrue(full.toGroupVisibility().isToolVisible("create_metadata", "metadata.write")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void fullDefaultPreservesLegacyNameFilterAndDefaultsBlankToStar() {
        assertEquals("*,-edit_file", //$NON-NLS-1$
                ProfileEndpoint.fullDefault(1, "t", "*,-edit_file").getExposedToolsFilter()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("*", ProfileEndpoint.fullDefault(1, "t", null).getExposedToolsFilter()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("*", ProfileEndpoint.fullDefault(1, "t", "  ").getExposedToolsFilter()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void seededDefaultsAreFourDisabledNamedProfilesWithTokens() {
        List<ProfileEndpoint> seeds = ProfileEndpoint.seededDefaults(8765);
        assertEquals(4, seeds.size());
        assertEquals(List.of("orchestrator", "dev", "qa", "infra"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                seeds.stream().map(ProfileEndpoint::getName).toList());
        assertEquals(List.of(8766, 8767, 8768, 8769),
                seeds.stream().map(p -> Integer.valueOf(p.getPort())).toList());
        for (ProfileEndpoint p : seeds) {
            assertFalse("seeds are disabled by default: " + p.getName(), p.isEnabled()); //$NON-NLS-1$
            assertNotNull("seed has a token: " + p.getName(), p.getBearerToken()); //$NON-NLS-1$
            assertFalse("seed token is non-blank: " + p.getName(), p.getBearerToken().isBlank()); //$NON-NLS-1$
        }
    }

    @Test
    public void orchestratorProfileIsReadGrounding() {
        ToolGroupVisibility v = profile("orchestrator").toGroupVisibility(); //$NON-NLS-1$
        assertTrue(v.isAllowlistMode());
        assertTrue(v.isToolVisible("scan_metadata_index", "metadata.read")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(v.isToolVisible("create_metadata", "metadata.write")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(v.isToolVisible("inspect_form_layout", "forms.read")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(v.isToolVisible("mutate_form_model", "forms.write")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(v.isToolVisible("read_file", "files.read")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(v.isToolVisible("write_file", "files.write")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(v.isToolVisible("bsl_list_methods", "bsl")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(v.isToolVisible("get_diagnostics", "diagnostics")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(v.isToolVisible("qa_run", "qa")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void devProfileHasFullMutationButDropsConnectInfobase() {
        ToolGroupVisibility v = profile("dev").toGroupVisibility(); //$NON-NLS-1$
        assertTrue(v.isToolVisible("create_metadata", "metadata.write")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(v.isToolVisible("mutate_form_model", "forms.write")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(v.isToolVisible("dcs_manage", "dcs")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(v.isToolVisible("extension_manage", "extensions")); //$NON-NLS-1$ //$NON-NLS-2$
        // connect_infobase sits in workspace.read (mutating=false) but is dropped per-tool.
        assertFalse(v.isToolVisible("connect_infobase", "workspace.read")); //$NON-NLS-1$ //$NON-NLS-2$
        // ...while other workspace.read tools stay visible.
        assertTrue(v.isToolVisible("git_inspect", "workspace.read")); //$NON-NLS-1$ //$NON-NLS-2$
        // dev is read-grounded on workspace: no workspace.write.
        assertFalse(v.isToolVisible("git_mutate", "workspace.write")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void qaProfileCarriesQaAndReadOnlyForms() {
        ToolGroupVisibility v = profile("qa").toGroupVisibility(); //$NON-NLS-1$
        assertTrue(v.isToolVisible("qa_run", "qa")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(v.isToolVisible("get_diagnostics", "diagnostics")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(v.isToolVisible("inspect_form_layout", "forms.read")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(v.isToolVisible("mutate_form_model", "forms.write")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(v.isToolVisible("write_file", "files.write")); //$NON-NLS-1$ //$NON-NLS-2$ (files.* both facets)
        assertFalse(v.isToolVisible("create_metadata", "metadata.write")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(v.isToolVisible("bsl_list_methods", "bsl")); //$NON-NLS-1$ //$NON-NLS-2$
        // discover_tools is the always-on lazy-reveal floor regardless of profile.
        assertTrue(v.isToolVisible("discover_tools", "meta")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void infraProfileIsWorkspaceAndFilesOnly() {
        ToolGroupVisibility v = profile("infra").toGroupVisibility(); //$NON-NLS-1$
        assertTrue(v.isToolVisible("git_mutate", "workspace.write")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(v.isToolVisible("git_inspect", "workspace.read")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(v.isToolVisible("write_file", "files.write")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(v.isToolVisible("create_metadata", "metadata.write")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(v.isToolVisible("qa_run", "qa")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(v.isToolVisible("bsl_list_methods", "bsl")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void toolsSummaryShowsStarForEverythingAndGroupsOtherwise() {
        assertEquals("*", ProfileEndpoint.fullDefault(1, "t", "*").toolsSummary()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(ProfileEndpoint.fullDefault(1, "t", "*").announcesEverything()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("diagnostics,qa,forms.read,files,workspace.read,meta", //$NON-NLS-1$
                profile("qa").toolsSummary()); //$NON-NLS-1$
        assertFalse(profile("qa").announcesEverything()); //$NON-NLS-1$
        // dev's per-tool disable shows as a -override.
        assertEquals("diagnostics,bsl,metadata,forms,dcs,extensions,files,workspace.read,meta -connect_infobase", //$NON-NLS-1$
                profile("dev").toolsSummary()); //$NON-NLS-1$
    }

    @Test
    public void suggestUniqueNameAvoidsCollisionsCaseInsensitively() {
        assertEquals("endpoint", //$NON-NLS-1$
                ProfileEndpoint.suggestUniqueName("endpoint", List.of("full", "qa"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("qa-2", //$NON-NLS-1$
                ProfileEndpoint.suggestUniqueName("qa", List.of("full", "qa"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("qa-3", //$NON-NLS-1$
                ProfileEndpoint.suggestUniqueName("qa", List.of("QA", "qa-2"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("endpoint", //$NON-NLS-1$
                ProfileEndpoint.suggestUniqueName("  ", List.of())); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static ProfileEndpoint profile(String name) {
        return ProfileEndpoint.seededDefaults(8765).stream()
                .filter(p -> p.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
