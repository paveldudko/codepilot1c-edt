/*******************************************************************************
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 * Copyright (C) 2026 codepilot1c-edt contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License v3.0 as published by the
 * Free Software Foundation.
 ******************************************************************************/
package com.codepilot1c.core.edt.runtime.lease;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Decision table of the pool-exclusivity guard: hard opt-in (no lease dir → OFF, zero behavior
 * change), branch-keyed conflicts, same-infobase-under-another-branch conflicts, auto-take of a
 * free lease, and the non-leasable contexts (detached HEAD / non-git).
 */
public class InfobaseLeaseGuardTest {

    private static final String IB_TASK_C = "File=\"C:\\stacks\\db\\Branches\\task-C\";"; //$NON-NLS-1$
    /** Same physical infobase as {@link #IB_TASK_C}, cosmetically different (case, slashes, trailing). */
    private static final String IB_TASK_C_COSMETIC = "File=\"c:/stacks/db/branches/task-c/\";"; //$NON-NLS-1$

    private Path dir;
    private InfobaseLeaseGuard stack3;
    private InfobaseLeaseGuard stack4;

    @Before
    public void setUp() throws IOException {
        dir = Files.createTempDirectory("lease-guard-test"); //$NON-NLS-1$
        stack3 = new InfobaseLeaseGuard(dir, "stack-3", "C:\\stacks\\stack-3\\workspace"); //$NON-NLS-1$ //$NON-NLS-2$
        stack4 = new InfobaseLeaseGuard(dir, "stack-4", "C:\\stacks\\stack-4\\workspace"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @After
    public void tearDown() throws IOException {
        if (dir != null && Files.exists(dir)) {
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // best-effort cleanup
                    }
                });
            }
        }
    }

    @Test
    public void unconfiguredGuardIsOffAndTouchesNothing() {
        InfobaseLeaseGuard off = new InfobaseLeaseGuard(null, "stack-3", null); //$NON-NLS-1$
        assertFalse(off.isEnabled());
        assertNull(off.store());
        assertEquals("REGRESSION: without CODEPILOT1C_LEASE_DIR the guard must be a strict no-op " //$NON-NLS-1$
                + "— ordinary single-instance users must see zero behavior change", //$NON-NLS-1$
                InfobaseLeaseGuard.Outcome.OFF,
                off.checkOrAcquire("task-C", null, IB_TASK_C, null).outcome()); //$NON-NLS-1$
    }

    @Test
    public void nonBranchContextIsNotLeasable() {
        assertEquals(InfobaseLeaseGuard.Outcome.OFF,
                stack3.checkOrAcquire(null, null, IB_TASK_C, null).outcome());
        assertEquals(InfobaseLeaseGuard.Outcome.OFF,
                stack3.checkOrAcquire("  ", null, IB_TASK_C, null).outcome()); //$NON-NLS-1$
    }

    @Test
    public void freeLeaseIsAutoTaken() {
        InfobaseLeaseGuard.Decision decision =
                stack3.checkOrAcquire("task-C", "C:\\db", IB_TASK_C, "op-7"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(InfobaseLeaseGuard.Outcome.ALLOWED, decision.outcome());
        InfobaseLease stored = stack3.store().find("task-C").orElseThrow(); //$NON-NLS-1$
        assertTrue("the bind must claim the lease for THIS stack", stored.isHeldBy("stack-3")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(IB_TASK_C, stored.ibIdentity());
        assertEquals("op-7", stored.acquiredByOp()); //$NON-NLS-1$
    }

    @Test
    public void ownLeaseIsAllowed() {
        stack3.checkOrAcquire("task-C", null, IB_TASK_C, null); //$NON-NLS-1$
        assertEquals(InfobaseLeaseGuard.Outcome.ALLOWED,
                stack3.checkOrAcquire("task-C", null, IB_TASK_C, null).outcome()); //$NON-NLS-1$
    }

    @Test
    public void foreignBranchLeaseIsDenied() {
        stack3.checkOrAcquire("task-C", null, IB_TASK_C, null); //$NON-NLS-1$
        InfobaseLeaseGuard.Decision decision = stack4.checkOrAcquire("task-C", null, IB_TASK_C, null); //$NON-NLS-1$
        assertEquals(InfobaseLeaseGuard.Outcome.DENIED, decision.outcome());
        assertEquals("stack-3", decision.lease().stackId()); //$NON-NLS-1$
    }

    @Test
    public void sameInfobaseUnderAnotherBranchIsDenied() {
        stack3.checkOrAcquire("task-C", null, IB_TASK_C, null); //$NON-NLS-1$
        // stack-4 asks for a DIFFERENT branch whose infobase is (cosmetically) the same physical
        // folder — convention drift is still "two EDT into one IB" and must be refused.
        InfobaseLeaseGuard.Decision decision =
                stack4.checkOrAcquire("task-D", null, IB_TASK_C_COSMETIC, null); //$NON-NLS-1$
        assertEquals("REGRESSION: conflicts must be detected by canonical infobase identity too, " //$NON-NLS-1$
                + "not only by branch name", //$NON-NLS-1$
                InfobaseLeaseGuard.Outcome.DENIED, decision.outcome());
        assertEquals("task-C", decision.lease().branch()); //$NON-NLS-1$
        assertTrue("the denied caller must NOT have claimed its branch as a side effect", //$NON-NLS-1$
                stack4.store().find("task-D").isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void differentInfobasesOnDifferentBranchesCoexist() {
        stack3.checkOrAcquire("task-C", null, IB_TASK_C, null); //$NON-NLS-1$
        InfobaseLeaseGuard.Decision decision = stack4.checkOrAcquire("task-D", null, //$NON-NLS-1$
                "File=\"C:\\stacks\\db\\Branches\\task-D\";", null); //$NON-NLS-1$
        assertEquals(InfobaseLeaseGuard.Outcome.ALLOWED, decision.outcome());
    }

    @Test
    public void ownLeaseIsRefreshedWhenTheInfobaseMoved() {
        stack3.checkOrAcquire("task-C", "old", IB_TASK_C, null); //$NON-NLS-1$ //$NON-NLS-2$
        String movedIb = "File=\"C:\\stacks\\db\\Branches\\task-C-v2\";"; //$NON-NLS-1$
        InfobaseLeaseGuard.Decision decision =
                stack3.checkOrAcquire("task-C", "new", movedIb, null); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(InfobaseLeaseGuard.Outcome.ALLOWED, decision.outcome());
        assertEquals("an own lease must be refreshed in place when the branch's infobase moved", //$NON-NLS-1$
                movedIb, stack3.store().find("task-C").orElseThrow().ibIdentity()); //$NON-NLS-1$
    }

    @Test
    public void branchFromContextParsesOnlyBranchRefs() {
        assertEquals("task-C", InfobaseLeaseGuard.branchFromContext("refs/heads/task-C")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("feature/x", InfobaseLeaseGuard.branchFromContext("refs/heads/feature/x")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull("a detached-HEAD commit hash is not leasable", //$NON-NLS-1$
                InfobaseLeaseGuard.branchFromContext("4dbf89f0aa3c5d2e")); //$NON-NLS-1$
        assertNull(InfobaseLeaseGuard.branchFromContext("refs/tags/v1")); //$NON-NLS-1$
        assertNull(InfobaseLeaseGuard.branchFromContext(null));
        assertNull(InfobaseLeaseGuard.branchFromContext("refs/heads/")); //$NON-NLS-1$
    }
}
