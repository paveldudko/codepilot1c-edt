/*******************************************************************************
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 * Copyright (C) 2026 codepilot1c-edt contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License v3.0 as published by the
 * Free Software Foundation.
 ******************************************************************************/
package com.codepilot1c.core.edt.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAssociationContext;
import com.codepilot1c.core.edt.runtime.lease.InfobaseLeaseGuard;

/**
 * Context synthesis contract of manage_associations: the synthesized context must be
 * byte-for-byte what EDT's git provider produces — {@code InfobaseAssociationContext.of(fullRef)}
 * with a single {@code refs/heads/<branch>} segment (verified against the 2025.2.x bytecode of
 * {@code GitRepositoryAssociationContextManager}) — otherwise bindings land in a context EDT
 * never reads.
 */
public class EdtInfobaseAssociationServiceTest {

    @Test
    public void synthesizedContextMatchesTheGitProviderShape() {
        InfobaseAssociationContext expected = InfobaseAssociationContext.of("refs/heads/task-C"); //$NON-NLS-1$
        assertEquals("REGRESSION: the synthesized context must equal what the git provider builds " //$NON-NLS-1$
                + "(single segment, full ref) — a mismatch writes bindings EDT never reads", //$NON-NLS-1$
                expected, EdtInfobaseAssociationService.contextForBranch("task-C")); //$NON-NLS-1$
        assertEquals("refs/heads/task-C", //$NON-NLS-1$
                EdtInfobaseAssociationService.contextForBranch("task-C").getContext().orElseThrow()); //$NON-NLS-1$
    }

    @Test
    public void fullRefIsAcceptedAndNotDoubled() {
        assertEquals("refs/heads/task-C", //$NON-NLS-1$
                EdtInfobaseAssociationService.contextForBranch("refs/heads/task-C") //$NON-NLS-1$
                        .getContext().orElseThrow());
    }

    @Test
    public void slashBranchesKeepTheirFullName() {
        assertEquals("refs/heads/feature/x", //$NON-NLS-1$
                EdtInfobaseAssociationService.contextForBranch("feature/x").getContext().orElseThrow()); //$NON-NLS-1$
    }

    @Test
    public void blankBranchIsInvalidArgument() {
        try {
            EdtInfobaseAssociationService.contextForBranch("  "); //$NON-NLS-1$
            fail("expected INVALID_ARGUMENT for a blank branch"); //$NON-NLS-1$
        } catch (EdtToolException expected) {
            assertEquals(EdtToolErrorCode.INVALID_ARGUMENT, expected.getCode());
        }
    }

    @Test
    public void registryRowLookupRequiresASelector() {
        EdtInfobaseAssociationService service = new EdtInfobaseAssociationService(
                new EdtRuntimeGateway(), new InfobaseLeaseGuard(null, "stack-x", null)); //$NON-NLS-1$
        try {
            service.resolveRegistryRow(null, null);
            fail("expected INVALID_ARGUMENT when neither infobase_name nor database_path is given"); //$NON-NLS-1$
        } catch (EdtToolException expected) {
            assertEquals(EdtToolErrorCode.INVALID_ARGUMENT, expected.getCode());
        }
    }
}
