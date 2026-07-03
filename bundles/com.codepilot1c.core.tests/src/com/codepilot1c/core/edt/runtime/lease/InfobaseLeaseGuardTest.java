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
 * change), infobase-identity-keyed conflicts (the branch is a payload attribute), branch-name
 * reservations, auto-take of a free lease, the phase-hop refresh, and the only non-leasable
 * request — one with neither an infobase identity nor a branch.
 */
public class InfobaseLeaseGuardTest {

    private static final String IB_TASK_C = "File=\"C:\\stacks\\db\\Branches\\task-C\";"; //$NON-NLS-1$
    /** Same physical infobase as {@link #IB_TASK_C}, cosmetically different (case, slashes, trailing). */
    private static final String IB_TASK_C_COSMETIC = "File=\"c:/stacks/db/branches/task-c/\";"; //$NON-NLS-1$

    private static String key(String ibIdentity, String branch) {
        return InfobaseLeaseStore.resourceKey(ibIdentity, branch);
    }

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
    public void requestWithoutIdentityAndBranchIsNotLeasable() {
        assertEquals("nothing identifies a resource — nothing to protect", //$NON-NLS-1$
                InfobaseLeaseGuard.Outcome.OFF,
                stack3.checkOrAcquire(null, null, null, null).outcome());
        assertEquals(InfobaseLeaseGuard.Outcome.OFF,
                stack3.checkOrAcquire("  ", null, null, null).outcome()); //$NON-NLS-1$
    }

    @Test
    public void detachedHeadWithKnownInfobaseIsStillEnforced() {
        stack3.checkOrAcquire("task-C", null, IB_TASK_C, null); //$NON-NLS-1$
        // A detached-HEAD context yields no branch name — but the infobase identity is the
        // primary key, so the second EDT must still be refused.
        InfobaseLeaseGuard.Decision decision =
                stack4.checkOrAcquire(null, null, IB_TASK_C_COSMETIC, null);
        assertEquals("REGRESSION: a missing branch name (detached HEAD) must not disable the " //$NON-NLS-1$
                + "guard when the physical infobase is known", //$NON-NLS-1$
                InfobaseLeaseGuard.Outcome.DENIED, decision.outcome());
    }

    @Test
    public void freeLeaseIsAutoTaken() {
        InfobaseLeaseGuard.Decision decision =
                stack3.checkOrAcquire("task-C", "C:\\db", IB_TASK_C, "op-7"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(InfobaseLeaseGuard.Outcome.ALLOWED, decision.outcome());
        InfobaseLease stored = stack3.store().find(key(IB_TASK_C, "task-C")).orElseThrow(); //$NON-NLS-1$
        assertTrue("the bind must claim the lease for THIS stack", stored.isHeldBy("stack-3")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(IB_TASK_C, stored.ibIdentity());
        assertEquals("task-C", stored.branch()); //$NON-NLS-1$
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
    public void foreignBranchNameReservationIsDenied() {
        // An identity-less take (manage_leases take before the IB exists) reserves the branch
        // NAME; exercising that branch with a real infobase on another stack must be refused.
        stack3.checkOrAcquire("task-C", null, null, null); //$NON-NLS-1$
        InfobaseLeaseGuard.Decision decision = stack4.checkOrAcquire("task-C", null, IB_TASK_C, null); //$NON-NLS-1$
        assertEquals("REGRESSION: a branch-name reservation must conflict with a later " //$NON-NLS-1$
                + "identity-keyed bind of the same branch on another stack", //$NON-NLS-1$
                InfobaseLeaseGuard.Outcome.DENIED, decision.outcome());
        assertEquals("stack-3", decision.lease().stackId()); //$NON-NLS-1$
    }

    @Test
    public void sameInfobaseUnderAnotherBranchIsDenied() {
        stack3.checkOrAcquire("task-C", null, IB_TASK_C, null); //$NON-NLS-1$
        // stack-4 asks for a DIFFERENT branch whose infobase is (cosmetically) the same physical
        // folder — the infobase identity IS the lease key, so this must be refused.
        InfobaseLeaseGuard.Decision decision =
                stack4.checkOrAcquire("task-D", null, IB_TASK_C_COSMETIC, null); //$NON-NLS-1$
        assertEquals("REGRESSION: the lease claims the physical infobase — another branch over " //$NON-NLS-1$
                + "the same folder is still two EDT into one IB", //$NON-NLS-1$
                InfobaseLeaseGuard.Outcome.DENIED, decision.outcome());
        assertEquals("task-C", decision.lease().branch()); //$NON-NLS-1$
        assertEquals("the denied caller must NOT have claimed anything as a side effect", //$NON-NLS-1$
                1, stack4.store().list().size());
    }

    @Test
    public void differentInfobasesOnDifferentBranchesCoexist() {
        stack3.checkOrAcquire("task-C", null, IB_TASK_C, null); //$NON-NLS-1$
        InfobaseLeaseGuard.Decision decision = stack4.checkOrAcquire("task-D", null, //$NON-NLS-1$
                "File=\"C:\\stacks\\db\\Branches\\task-D\";", null); //$NON-NLS-1$
        assertEquals(InfobaseLeaseGuard.Outcome.ALLOWED, decision.outcome());
    }

    @Test
    public void ownBranchHopOnTheSameInfobaseRefreshesInPlace() {
        stack3.checkOrAcquire("BF-1-phase1", "db", IB_TASK_C, null); //$NON-NLS-1$ //$NON-NLS-2$
        // The holder moves to the next phase branch of the SAME task infobase — one lease file,
        // its branch attribute refreshed, no second claim.
        InfobaseLeaseGuard.Decision decision =
                stack3.checkOrAcquire("BF-1-phase2", "db", IB_TASK_C_COSMETIC, null); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(InfobaseLeaseGuard.Outcome.ALLOWED, decision.outcome());
        assertEquals(1, stack3.store().list().size());
        assertEquals("a branch hop on the same infobase must refresh the lease's branch attribute", //$NON-NLS-1$
                "BF-1-phase2", stack3.store().list().get(0).branch()); //$NON-NLS-1$
    }

    @Test
    public void repointingTheBranchToANewInfobaseClaimsASecondLease() {
        stack3.checkOrAcquire("task-C", "old", IB_TASK_C, null); //$NON-NLS-1$ //$NON-NLS-2$
        String movedIb = "File=\"C:\\stacks\\db\\Branches\\task-C-v2\";"; //$NON-NLS-1$
        InfobaseLeaseGuard.Decision decision =
                stack3.checkOrAcquire("task-C", "new", movedIb, null); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(InfobaseLeaseGuard.Outcome.ALLOWED, decision.outcome());
        assertEquals("the lease claims the INFOBASE: pointing the branch at a new one claims a " //$NON-NLS-1$
                + "second lease — the old infobase stays claimed until explicitly released", //$NON-NLS-1$
                2, stack3.store().list().size());
        assertTrue(stack3.store().find(key(movedIb, "task-C")).orElseThrow().isHeldBy("stack-3")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(stack3.store().find(key(IB_TASK_C, "task-C")).orElseThrow().isHeldBy("stack-3")); //$NON-NLS-1$ //$NON-NLS-2$
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
