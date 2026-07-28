package com.codepilot1c.core.tools.workspace;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.codepilot1c.core.tools.workspace.EdtUpdateInfobaseTool.UpdateSlot;

/**
 * Unit tests for the {@code edt_update_infobase} double-fire guard: a second concurrent (schema)
 * update of the SAME infobase must be refused while one is in flight, because EDT applies updates
 * through a single-connection Designer session and a re-fired update on the same infobase collides
 * ("Infobase … is already connected") and can wedge the platform. Live finding BF-12705 (2026-07-10):
 * a caller re-fired an async update while the first was still RUNNING; the two Designer sessions
 * contended and the update hung ~51 min.
 *
 * <p>The guard is a pure static reservation ({@code tryAcquireUpdate}/{@code releaseUpdate}) so it is
 * exercised here directly, with no EDT/Eclipse runtime. The key is now derived from the INFOBASE
 * identity (covered by {@link EdtUpdateInfobaseSharedInfobaseTest}); the cases below use the
 * name-based fallback key — what a project with an unresolvable infobase falls back to.</p>
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

    private static UpdateSlot slot(String jobId) {
        return new UpdateSlot(PROJECT, jobId);
    }

    @Test
    public void firstAcquireSucceeds_secondConcurrentIsRejectedWithHolderId() {
        UpdateSlot first = slot("job-1"); //$NON-NLS-1$
        assertNull("first acquire must reserve the slot", //$NON-NLS-1$
                EdtUpdateInfobaseTool.tryAcquireUpdate(PROJECT, first));
        assertTrue(EdtUpdateInfobaseTool.hasInFlightUpdate(PROJECT));

        UpdateSlot second = slot("job-2"); //$NON-NLS-1$
        UpdateSlot held = EdtUpdateInfobaseTool.tryAcquireUpdate(PROJECT, second);
        assertNotNull("second concurrent acquire must be refused", held); //$NON-NLS-1$
        assertEquals("the refusal must expose the holder's job id", //$NON-NLS-1$
                "job-1", held.jobId()); //$NON-NLS-1$
        assertEquals("…and the holding project", PROJECT, held.project()); //$NON-NLS-1$
    }

    @Test
    public void releaseFreesTheSlotForTheNextUpdate() {
        UpdateSlot first = slot("job-1"); //$NON-NLS-1$
        assertNull(EdtUpdateInfobaseTool.tryAcquireUpdate(PROJECT, first));

        EdtUpdateInfobaseTool.releaseUpdate(PROJECT, first);
        assertFalse(EdtUpdateInfobaseTool.hasInFlightUpdate(PROJECT));

        UpdateSlot next = slot("job-2"); //$NON-NLS-1$
        assertNull("after release the next update must acquire", //$NON-NLS-1$
                EdtUpdateInfobaseTool.tryAcquireUpdate(PROJECT, next));
    }

    @Test
    public void releaseIsIdentityChecked_foreignSlotCannotEvictTheHolder() {
        UpdateSlot holder = slot("job-1"); //$NON-NLS-1$
        assertNull(EdtUpdateInfobaseTool.tryAcquireUpdate(PROJECT, holder));

        // A different reference (e.g. a rejected second caller) must not be able to release the slot.
        UpdateSlot foreign = slot("job-2"); //$NON-NLS-1$
        EdtUpdateInfobaseTool.releaseUpdate(PROJECT, foreign);
        assertTrue("foreign release must not evict the real holder", //$NON-NLS-1$
                EdtUpdateInfobaseTool.hasInFlightUpdate(PROJECT));

        EdtUpdateInfobaseTool.releaseUpdate(PROJECT, holder);
        assertFalse(EdtUpdateInfobaseTool.hasInFlightUpdate(PROJECT));
    }

    @Test
    public void distinctKeysDoNotBlockEachOther() {
        UpdateSlot a = new UpdateSlot("Project A", "job-a"); //$NON-NLS-1$ //$NON-NLS-2$
        UpdateSlot b = new UpdateSlot("Project B", "job-b"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(EdtUpdateInfobaseTool.tryAcquireUpdate("Project A", a)); //$NON-NLS-1$
        assertNull("a second, different key must acquire independently", //$NON-NLS-1$
                EdtUpdateInfobaseTool.tryAcquireUpdate("Project B", b)); //$NON-NLS-1$
    }

    @Test
    public void projectKeyIsTrimmed_soWhitespaceVariantsCollide() {
        String padded = EdtUpdateInfobaseTool.updateKey("  " + PROJECT + "  "); //$NON-NLS-1$ //$NON-NLS-2$
        String plain = EdtUpdateInfobaseTool.updateKey(PROJECT);
        assertEquals("surrounding whitespace must normalize to the same key", plain, padded); //$NON-NLS-1$

        UpdateSlot first = slot("job-1"); //$NON-NLS-1$
        assertNull(EdtUpdateInfobaseTool.tryAcquireUpdate(padded, first));
        UpdateSlot second = slot("job-2"); //$NON-NLS-1$
        UpdateSlot held = EdtUpdateInfobaseTool.tryAcquireUpdate(plain, second);
        assertNotNull("the same name with surrounding whitespace must map to the same slot", held); //$NON-NLS-1$
        assertEquals("job-1", held.jobId()); //$NON-NLS-1$
    }
}
