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
 *   <li><b>#4 addendum</b> (infra {@code 29d43ab5}) — the surfaced Designer PID must NOT be reported under
 *       a name that implies the tool killed it (it did not; the process stays alive and keeps working).
 *       The detail key is {@code designer_pids_still_holding} and the message points at
 *       {@code kill_agent_mode=true} as the actual-kill lever. Covered via
 *       {@link EdtUpdateInfobaseTool#processTimeoutDetails}.</li>
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
        assertTrue("must point at kill_agent_mode=true as the actual-kill lever on retry (infra 29d43ab5)", //$NON-NLS-1$
                msg.contains("kill_agent_mode=true")); //$NON-NLS-1$
        assertTrue("must say the tool did NOT kill them, so a caller doesn't race a live process", //$NON-NLS-1$
                msg.toLowerCase(java.util.Locale.ROOT).contains("did not terminate")); //$NON-NLS-1$
    }

    // -- #4 addendum (infra 29d43ab5): the PID detail key must not imply a kill ----------------------

    @Test
    public void timeoutDetailsUseNonKillImplyingKey() {
        java.util.Map<String, String> details =
                EdtUpdateInfobaseTool.processTimeoutDetails(600L, List.of(34560L, 34999L));
        assertFalse("the aborted_* key wrongly implied the Designer was killed — must be gone", //$NON-NLS-1$
                details.containsKey("aborted_designer_pids")); //$NON-NLS-1$
        assertTrue("surfaces the still-holding pids under an accurate, non-kill-implying key", //$NON-NLS-1$
                details.containsKey("designer_pids_still_holding")); //$NON-NLS-1$
        assertEquals("34560,34999", details.get("designer_pids_still_holding")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void timeoutDetailsOmitPidKeyWhenNoDesignerFound() {
        java.util.Map<String, String> details =
                EdtUpdateInfobaseTool.processTimeoutDetails(300L, List.of());
        assertFalse("no pid key at all when the scan found nothing", //$NON-NLS-1$
                details.containsKey("designer_pids_still_holding")); //$NON-NLS-1$
        assertEquals("300", details.get("update_timeout_s")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * An empty scan has to SAY it was empty. Omitting the key was the only signal, and it reads exactly
     * like a build predating the feature — which is how the 2026-07-29 retest reported the PID as "still
     * absent from the payload" for a build that had been surfacing it since {@code 3ca38df}. One of the
     * two claims had to be wrong and the payload gave no way to tell which.
     */
    @Test
    public void anEmptyDesignerScanSaysSoInsteadOfGoingSilent() {
        java.util.Map<String, String> details =
                EdtUpdateInfobaseTool.processTimeoutDetails(300L, List.of());
        assertEquals("no_designer_bound_to_this_infobase", details.get("designer_scan")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("and a non-empty scan must not claim an empty one", //$NON-NLS-1$
                EdtUpdateInfobaseTool.processTimeoutDetails(300L, List.of(Long.valueOf(4242L)))
                        .containsKey("designer_scan")); //$NON-NLS-1$
    }

    @Test
    public void anEmptyScanMessageDoesNotSendTheCallerHuntingAPhantom() {
        String message = EdtUpdateInfobaseTool.processTimeoutMessage(300L, List.of());
        assertTrue("the message must state that nothing was found", //$NON-NLS-1$
                message.contains("found NO still-running Designer")); //$NON-NLS-1$
        assertTrue("and admit that an unreadable command line cannot be attributed at all", //$NON-NLS-1$
                message.contains("command line cannot be read")); //$NON-NLS-1$
    }

    // -- 2026-07-29 §3: dynamic_only must not ride along with updated:true -----------------------

    /**
     * The contract change owner-ruled 2026-07-29. A dynamic apply commits the stored configuration but
     * defers the physical restructure, and the payload used to answer {@code updated:true} beside
     * {@code dynamic_only:true} — so the flag every caller gates on said "done" for an infobase whose
     * schema was not live. Measured on a 1.51 GB file infobase.
     */
    @Test
    public void aDynamicApplyIsNotReportedAsUpdated() {
        com.google.gson.JsonObject result = new com.google.gson.JsonObject();
        result.addProperty("status", "updated"); //$NON-NLS-1$ //$NON-NLS-2$
        EdtUpdateInfobaseTool.fillAppliedOutcome(result, true, true);
        assertFalse("the flag callers gate on must not say done for a deferred restructure", //$NON-NLS-1$
                result.get("updated").getAsBoolean()); //$NON-NLS-1$
        assertFalse(result.get("schema_applied").getAsBoolean()); //$NON-NLS-1$
        assertEquals("and the status must name the outcome, not borrow the happy path's", //$NON-NLS-1$
                "partial", result.get("status").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void anExclusiveApplyReportsBothTrueAndKeepsItsStatus() {
        com.google.gson.JsonObject result = new com.google.gson.JsonObject();
        result.addProperty("status", "updated"); //$NON-NLS-1$ //$NON-NLS-2$
        EdtUpdateInfobaseTool.fillAppliedOutcome(result, true, false);
        assertTrue(result.get("updated").getAsBoolean()); //$NON-NLS-1$
        assertTrue(result.get("schema_applied").getAsBoolean()); //$NON-NLS-1$
        assertEquals("updated", result.get("status").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aFailedApplyIsNotDowngradedToPartial() {
        com.google.gson.JsonObject result = new com.google.gson.JsonObject();
        result.addProperty("status", "updated"); //$NON-NLS-1$ //$NON-NLS-2$
        EdtUpdateInfobaseTool.fillAppliedOutcome(result, false, true);
        assertFalse(result.get("updated").getAsBoolean()); //$NON-NLS-1$
        assertFalse(result.get("schema_applied").getAsBoolean()); //$NON-NLS-1$
        assertEquals("\"partial\" claims something landed; nothing did", //$NON-NLS-1$
                "updated", result.get("status").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The one outcome where the two fields legitimately disagree, and the reason {@code schema_applied}
     * cannot be read off {@code updated}: an EQUAL-skip applies nothing while the schema IS live.
     */
    @Test
    public void anEqualSkipAppliesNothingYetTheSchemaIsLive() {
        com.google.gson.JsonObject result = new com.google.gson.JsonObject();
        EdtUpdateInfobaseTool.fillSkippedEqual(result);
        assertFalse(result.get("updated").getAsBoolean()); //$NON-NLS-1$
        assertTrue("the infobase already equals the project — the schema is live", //$NON-NLS-1$
                result.get("schema_applied").getAsBoolean()); //$NON-NLS-1$
        assertEquals("skipped", result.get("status").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
