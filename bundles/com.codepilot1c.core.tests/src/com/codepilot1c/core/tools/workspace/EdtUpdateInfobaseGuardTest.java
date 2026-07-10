package com.codepilot1c.core.tools.workspace;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for the {@code edt_update_infobase} double-fire guard: a second concurrent (schema)
 * update of the SAME project must be refused while one is in flight, because EDT applies updates
 * through a single-connection Designer session and a re-fired update on the same infobase collides
 * ("Infobase … is already connected") and can wedge the platform. Live finding BF-12705 (2026-07-10):
 * a caller re-fired an async update while the first was still RUNNING; the two Designer sessions
 * contended and the update hung ~51 min.
 *
 * <p>The guard is a pure static reservation ({@code tryAcquireUpdate}/{@code releaseUpdate}) so it is
 * exercised here directly, with no EDT/Eclipse runtime.</p>
 */
public class EdtUpdateInfobaseGuardTest {

    private static final String PROJECT = "Accounting management"; //$NON-NLS-1$

    @Before
    public void reset() {
        EdtUpdateInfobaseTool.clearInFlightUpdatesForTest();
    }

    @After
    public void tearDown() {
        EdtUpdateInfobaseTool.clearInFlightUpdatesForTest();
    }

    @Test
    public void firstAcquireSucceeds_secondConcurrentIsRejectedWithHolderId() {
        AtomicReference<String> first = new AtomicReference<>("job-1"); //$NON-NLS-1$
        assertNull("first acquire must reserve the slot", //$NON-NLS-1$
                EdtUpdateInfobaseTool.tryAcquireUpdate(PROJECT, first));
        assertTrue(EdtUpdateInfobaseTool.hasInFlightUpdate(PROJECT));

        AtomicReference<String> second = new AtomicReference<>("job-2"); //$NON-NLS-1$
        assertEquals("second concurrent acquire must be refused and see the holder's job id", //$NON-NLS-1$
                "job-1", EdtUpdateInfobaseTool.tryAcquireUpdate(PROJECT, second)); //$NON-NLS-1$
    }

    @Test
    public void releaseFreesTheSlotForTheNextUpdate() {
        AtomicReference<String> first = new AtomicReference<>("job-1"); //$NON-NLS-1$
        assertNull(EdtUpdateInfobaseTool.tryAcquireUpdate(PROJECT, first));

        EdtUpdateInfobaseTool.releaseUpdate(PROJECT, first);
        assertFalse(EdtUpdateInfobaseTool.hasInFlightUpdate(PROJECT));

        AtomicReference<String> next = new AtomicReference<>("job-2"); //$NON-NLS-1$
        assertNull("after release the next update must acquire", //$NON-NLS-1$
                EdtUpdateInfobaseTool.tryAcquireUpdate(PROJECT, next));
    }

    @Test
    public void releaseIsIdentityChecked_foreignSlotCannotEvictTheHolder() {
        AtomicReference<String> holder = new AtomicReference<>("job-1"); //$NON-NLS-1$
        assertNull(EdtUpdateInfobaseTool.tryAcquireUpdate(PROJECT, holder));

        // A different reference (e.g. a rejected second caller) must not be able to release the slot.
        AtomicReference<String> foreign = new AtomicReference<>("job-2"); //$NON-NLS-1$
        EdtUpdateInfobaseTool.releaseUpdate(PROJECT, foreign);
        assertTrue("foreign release must not evict the real holder", //$NON-NLS-1$
                EdtUpdateInfobaseTool.hasInFlightUpdate(PROJECT));

        EdtUpdateInfobaseTool.releaseUpdate(PROJECT, holder);
        assertFalse(EdtUpdateInfobaseTool.hasInFlightUpdate(PROJECT));
    }

    @Test
    public void distinctProjectsDoNotBlockEachOther() {
        AtomicReference<String> a = new AtomicReference<>("job-a"); //$NON-NLS-1$
        AtomicReference<String> b = new AtomicReference<>("job-b"); //$NON-NLS-1$
        assertNull(EdtUpdateInfobaseTool.tryAcquireUpdate("Project A", a)); //$NON-NLS-1$
        assertNull("a second, different project must acquire independently", //$NON-NLS-1$
                EdtUpdateInfobaseTool.tryAcquireUpdate("Project B", b)); //$NON-NLS-1$
    }

    @Test
    public void projectKeyIsTrimmed_soWhitespaceVariantsCollide() {
        String padded = EdtUpdateInfobaseTool.updateKey("  " + PROJECT + "  "); //$NON-NLS-1$ //$NON-NLS-2$
        String plain = EdtUpdateInfobaseTool.updateKey(PROJECT);
        assertEquals("surrounding whitespace must normalize to the same key", plain, padded); //$NON-NLS-1$

        AtomicReference<String> first = new AtomicReference<>("job-1"); //$NON-NLS-1$
        assertNull(EdtUpdateInfobaseTool.tryAcquireUpdate(padded, first));
        AtomicReference<String> second = new AtomicReference<>("job-2"); //$NON-NLS-1$
        assertEquals("the same name with surrounding whitespace must map to the same slot", //$NON-NLS-1$
                "job-1", EdtUpdateInfobaseTool.tryAcquireUpdate(plain, second)); //$NON-NLS-1$
    }
}
