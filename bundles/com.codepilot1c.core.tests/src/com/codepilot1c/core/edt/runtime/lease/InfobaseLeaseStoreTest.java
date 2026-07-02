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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The lease store is the pool's coordination primitive: the claim must be atomic under
 * concurrency (exactly one winner), a steal must report whom it stole from, a release must not
 * drop someone else's lease, and an unreadable payload must read as HELD — never as free.
 */
public class InfobaseLeaseStoreTest {

    private Path dir;
    private InfobaseLeaseStore store;

    @Before
    public void setUp() throws IOException {
        dir = Files.createTempDirectory("lease-store-test"); //$NON-NLS-1$
        store = new InfobaseLeaseStore(dir);
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

    private static InfobaseLease lease(String branch, String stackId) {
        return new InfobaseLease(branch, "C:\\stacks\\db\\Branches\\" + branch, //$NON-NLS-1$
                "File=\"C:\\stacks\\db\\Branches\\" + branch + "\";", //$NON-NLS-1$ //$NON-NLS-2$
                stackId, "C:\\stacks\\" + stackId + "\\workspace", "host-1", 4242L, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "2026-07-02T18:00:00Z", "op-1"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void concurrentTakeHasExactlyOneWinner() throws Exception {
        int contenders = 16;
        CyclicBarrier start = new CyclicBarrier(contenders);
        CountDownLatch done = new CountDownLatch(contenders);
        AtomicInteger winners = new AtomicInteger();
        for (int i = 0; i < contenders; i++) {
            String stackId = "stack-" + i; //$NON-NLS-1$
            new Thread(() -> {
                try {
                    start.await();
                    if (store.take(lease("task-race", stackId)).taken()) { //$NON-NLS-1$
                        winners.incrementAndGet();
                    }
                } catch (Exception ignored) {
                    // a failed contender is not a winner
                } finally {
                    done.countDown();
                }
            }).start();
        }
        assertTrue("contenders did not finish", done.await(30, java.util.concurrent.TimeUnit.SECONDS)); //$NON-NLS-1$
        assertEquals("the atomic claim must have EXACTLY one winner — more means two stacks " //$NON-NLS-1$
                + "would open the same infobase", 1, winners.get()); //$NON-NLS-1$
        Optional<InfobaseLease> current = store.find("task-race"); //$NON-NLS-1$
        assertTrue(current.isPresent());
        assertNotEquals(InfobaseLease.UNKNOWN_HOLDER, current.get().stackId());
    }

    @Test
    public void takeThenFindRoundtripsThePayload() {
        assertTrue(store.take(lease("task-C", "stack-3")).taken()); //$NON-NLS-1$ //$NON-NLS-2$
        InfobaseLease read = store.find("task-C").orElseThrow(); //$NON-NLS-1$
        assertEquals("task-C", read.branch()); //$NON-NLS-1$
        assertEquals("stack-3", read.stackId()); //$NON-NLS-1$
        assertEquals("host-1", read.host()); //$NON-NLS-1$
        assertEquals(4242L, read.pid());
        assertEquals("2026-07-02T18:00:00Z", read.acquiredAt()); //$NON-NLS-1$
        assertNotNull(read.ibIdentity());
        assertTrue(read.isHeldBy("stack-3")); //$NON-NLS-1$
        assertFalse(read.isHeldBy("stack-4")); //$NON-NLS-1$
    }

    @Test
    public void secondTakeIsRefusedAndReportsTheHolder() {
        assertTrue(store.take(lease("task-C", "stack-3")).taken()); //$NON-NLS-1$ //$NON-NLS-2$
        InfobaseLeaseStore.TakeResult second = store.take(lease("task-C", "stack-4")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(second.taken());
        assertNotNull(second.lease());
        assertEquals("stack-3", second.lease().stackId()); //$NON-NLS-1$
    }

    @Test
    public void forceTakeStealsAndReturnsThePreviousHolder() {
        assertTrue(store.take(lease("task-C", "stack-3")).taken()); //$NON-NLS-1$ //$NON-NLS-2$
        InfobaseLeaseStore.TakeResult stolen = store.forceTake(lease("task-C", "stack-4")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(stolen.taken());
        assertNotNull("the steal must report whom it stole from", stolen.lease()); //$NON-NLS-1$
        assertEquals("stack-3", stolen.lease().stackId()); //$NON-NLS-1$
        assertEquals("stack-4", store.find("task-C").orElseThrow().stackId()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void forceTakeOnFreeLeaseHasNoPreviousHolder() {
        InfobaseLeaseStore.TakeResult taken = store.forceTake(lease("task-F", "stack-4")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(taken.taken());
        assertNull(taken.lease());
    }

    @Test
    public void releaseByOwnerReleases() {
        store.take(lease("task-C", "stack-3")); //$NON-NLS-1$ //$NON-NLS-2$
        InfobaseLeaseStore.ReleaseResult result = store.release("task-C", "stack-3", false); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(InfobaseLeaseStore.ReleaseStatus.RELEASED, result.status());
        assertTrue(store.find("task-C").isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void releaseByOtherIsRefusedWithoutForce() {
        store.take(lease("task-C", "stack-3")); //$NON-NLS-1$ //$NON-NLS-2$
        InfobaseLeaseStore.ReleaseResult refused = store.release("task-C", "stack-4", false); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("REGRESSION: releasing another stack's lease without force would let a " //$NON-NLS-1$
                + "misbehaving stack unlock an infobase someone else is working", //$NON-NLS-1$
                InfobaseLeaseStore.ReleaseStatus.HELD_BY_OTHER, refused.status());
        assertEquals("stack-3", refused.lease().stackId()); //$NON-NLS-1$
        assertTrue(store.find("task-C").isPresent()); //$NON-NLS-1$

        InfobaseLeaseStore.ReleaseResult forced = store.release("task-C", "stack-4", true); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(InfobaseLeaseStore.ReleaseStatus.RELEASED, forced.status());
        assertTrue(store.find("task-C").isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void releaseOfFreeLeaseReportsNotHeld() {
        assertEquals(InfobaseLeaseStore.ReleaseStatus.NOT_HELD,
                store.release("task-none", "stack-3", false).status()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void unreadablePayloadReadsAsHeldByUnknown_neverAsFree() throws IOException {
        Path file = dir.resolve(InfobaseLeaseStore.fileNameFor("task-C")); //$NON-NLS-1$
        Files.write(file, "{not json".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
        InfobaseLease lease = store.find("task-C").orElseThrow(); //$NON-NLS-1$
        assertEquals("REGRESSION: an unreadable lease payload (mid-write race, corruption) must " //$NON-NLS-1$
                + "read as HELD by an unknown holder — reading it as free would let a second " //$NON-NLS-1$
                + "stack claim a lease whose file already exists", //$NON-NLS-1$
                InfobaseLease.UNKNOWN_HOLDER, lease.stackId());
        assertFalse("an unknown holder matches NO stack", lease.isHeldBy("stack-3")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(store.take(lease("task-C", "stack-4")).taken()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void sanitizedBranchNamesCannotCollide() {
        String a = InfobaseLeaseStore.fileNameFor("feature/x"); //$NON-NLS-1$
        String b = InfobaseLeaseStore.fileNameFor("feature_x"); //$NON-NLS-1$
        assertNotEquals("branches 'feature/x' and 'feature_x' must not share one lease file", a, b); //$NON-NLS-1$
        assertFalse("no path separators may survive sanitizing", a.contains("/")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void listReturnsAllLeases() {
        store.take(lease("task-A", "stack-3")); //$NON-NLS-1$ //$NON-NLS-2$
        store.take(lease("task-B", "stack-4")); //$NON-NLS-1$ //$NON-NLS-2$
        List<InfobaseLease> all = store.list();
        assertEquals(2, all.size());
    }

    @Test
    public void listOnMissingDirectoryIsEmpty() {
        InfobaseLeaseStore fresh = new InfobaseLeaseStore(dir.resolve("does-not-exist")); //$NON-NLS-1$
        assertTrue(fresh.list().isEmpty());
        assertTrue(fresh.find("task-C").isEmpty()); //$NON-NLS-1$
    }
}
