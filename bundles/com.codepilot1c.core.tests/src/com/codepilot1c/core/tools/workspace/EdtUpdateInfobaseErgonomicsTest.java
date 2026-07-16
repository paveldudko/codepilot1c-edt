package com.codepilot1c.core.tools.workspace;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

/**
 * Unit tests for the {@code edt_update_infobase} ergonomics fixes from the 2026-07-16 BF-12936 feedback:
 *
 * <ul>
 *   <li><b>#3</b> — the in-flight guard's {@code "sync"}/{@code "starting"} sentinels are NOT pollable
 *       job ids, so the rejection payload must not advise polling them (the source of the
 *       "Unknown job: sync" dead end). Covered via {@link EdtUpdateInfobaseTool#isPollableJobId}.</li>
 *   <li><b>#4</b> — the 300s update ceiling is now a caller-overridable {@code timeout_s} (clamped), and
 *       the {@code PROCESS_TIMEOUT} message names both plausible causes plus the still-alive Designer
 *       PID(s). Covered via {@link EdtUpdateInfobaseTool#clampTimeoutSeconds},
 *       {@link EdtUpdateInfobaseTool#asInt} and {@link EdtUpdateInfobaseTool#processTimeoutMessage}.</li>
 * </ul>
 *
 * <p>All the methods under test are pure (no EDT/Eclipse runtime), so they run headless.</p>
 */
public class EdtUpdateInfobaseErgonomicsTest {

    // -- #3: sentinel vs real job id -------------------------------------------------------------

    @Test
    public void realJobIdIsPollable() {
        assertTrue(EdtUpdateInfobaseTool.isPollableJobId("f670423c-e7bb-4bb0-8997-7705717350a5")); //$NON-NLS-1$
    }

    @Test
    public void syncSentinelIsNotPollable() {
        assertFalse("\"sync\" is an in-flight sentinel, not a registry job", //$NON-NLS-1$
                EdtUpdateInfobaseTool.isPollableJobId("sync")); //$NON-NLS-1$
    }

    @Test
    public void startingSentinelIsNotPollable() {
        assertFalse("\"starting\" is the transient async-registration sentinel", //$NON-NLS-1$
                EdtUpdateInfobaseTool.isPollableJobId("starting")); //$NON-NLS-1$
    }

    @Test
    public void nullOrBlankSlotIsNotPollable() {
        assertFalse(EdtUpdateInfobaseTool.isPollableJobId(null));
        assertFalse(EdtUpdateInfobaseTool.isPollableJobId("")); //$NON-NLS-1$
        assertFalse(EdtUpdateInfobaseTool.isPollableJobId("   ")); //$NON-NLS-1$
    }

    // -- #4: timeout clamp ------------------------------------------------------------------------

    @Test
    public void defaultTimeoutPassesThroughUnclamped() {
        assertEquals(300, EdtUpdateInfobaseTool.clampTimeoutSeconds(300));
    }

    @Test
    public void inRangeTimeoutPassesThrough() {
        assertEquals(900, EdtUpdateInfobaseTool.clampTimeoutSeconds(900));
    }

    @Test
    public void belowMinIsClampedUp() {
        assertEquals("a too-small timeout clamps up to the 60s floor", //$NON-NLS-1$
                60, EdtUpdateInfobaseTool.clampTimeoutSeconds(5));
        assertEquals(60, EdtUpdateInfobaseTool.clampTimeoutSeconds(0));
        assertEquals(60, EdtUpdateInfobaseTool.clampTimeoutSeconds(-100));
    }

    @Test
    public void aboveMaxIsClampedDown() {
        assertEquals("a too-large timeout clamps down to the 1800s ceiling", //$NON-NLS-1$
                1800, EdtUpdateInfobaseTool.clampTimeoutSeconds(9999));
    }

    // -- #4: timeout_s parsing (dispatcher may ship it as a string) ------------------------------

    @Test
    public void asIntAcceptsNumberAndString() {
        assertEquals(600, EdtUpdateInfobaseTool.asInt(Integer.valueOf(600), 300));
        assertEquals(600, EdtUpdateInfobaseTool.asInt("600", 300)); //$NON-NLS-1$
        assertEquals(600, EdtUpdateInfobaseTool.asInt(Double.valueOf(600.0d), 300));
    }

    @Test
    public void asIntFallsBackOnNullBlankOrGarbage() {
        assertEquals(300, EdtUpdateInfobaseTool.asInt(null, 300));
        assertEquals(300, EdtUpdateInfobaseTool.asInt("", 300)); //$NON-NLS-1$
        assertEquals(300, EdtUpdateInfobaseTool.asInt("   ", 300)); //$NON-NLS-1$
        assertEquals(300, EdtUpdateInfobaseTool.asInt("abc", 300)); //$NON-NLS-1$
    }

    // -- #4: PROCESS_TIMEOUT message --------------------------------------------------------------

    @Test
    public void timeoutMessageNamesSecondsAndBothCauses() {
        String msg = EdtUpdateInfobaseTool.processTimeoutMessage(300L, List.of());
        assertTrue("must state the elapsed timeout", msg.contains("300s")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must point at timeout_s as a lever", msg.contains("timeout_s")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must still name the holder cause", //$NON-NLS-1$
                msg.toLowerCase(java.util.Locale.ROOT).contains("held by another process")); //$NON-NLS-1$
    }

    @Test
    public void timeoutMessageOmitsPidClauseWhenNoDesignerFound() {
        String msg = EdtUpdateInfobaseTool.processTimeoutMessage(300L, List.of());
        assertFalse("no pid clause when the scan found nothing", msg.contains("pid ")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void timeoutMessageSurfacesDesignerPids() {
        String msg = EdtUpdateInfobaseTool.processTimeoutMessage(600L, List.of(34560L, 34999L));
        assertTrue("must state the (raised) elapsed timeout", msg.contains("600s")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must name the still-alive Designer pids", msg.contains("34560,34999")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
