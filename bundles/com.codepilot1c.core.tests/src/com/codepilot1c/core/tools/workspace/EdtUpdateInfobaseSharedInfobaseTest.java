package com.codepilot1c.core.tools.workspace;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.codepilot1c.core.edt.runtime.InfobaseSiblingResolver;
import com.codepilot1c.core.edt.runtime.InfobaseSiblingResolver.Sibling;
import com.google.gson.JsonObject;

/**
 * Hermetic tests for the shared-infobase behaviour of {@code edt_update_infobase}:
 *
 * <ul>
 *   <li><b>sibling annotation</b> — a payload that says "skipped, because EQUAL" is the strongest
 *       false-green on a shared infobase (this project matches, a sibling extension may not), so the
 *       skip payload must name the diverged sibling(s).</li>
 *   <li><b>in-flight guard keyed by infobase</b> — EDT's Designer connection is single-per-INFOBASE, so
 *       two DIFFERENT projects on one infobase must collide on one slot; two projects on different
 *       infobases must not.</li>
 *   <li><b>damaged target DB classifier</b> — must win over the {@code xml.zip}-based IB_LOCKED
 *       heuristic, which a damaged database's failed config export also trips.</li>
 * </ul>
 *
 * <p>Everything under test is pure (no EDT/Eclipse runtime).</p>
 */
public class EdtUpdateInfobaseSharedInfobaseTest {

    private static final String IB_A = "File=\"c:/1C/db/am\";"; //$NON-NLS-1$
    /** The same infobase as {@link #IB_A}: other slash direction, case and a trailing separator. */
    private static final String IB_A_COSMETIC = "File=\"C:\\1C\\DB\\AM\\\";"; //$NON-NLS-1$
    private static final String IB_B = "File=\"c:/1C/db/other\";"; //$NON-NLS-1$

    @Before
    public void reset() {
        EdtUpdateInfobaseTool.clearInFlightUpdatesForTest();
    }

    @After
    public void tearDown() {
        EdtUpdateInfobaseTool.clearInFlightUpdatesForTest();
    }

    private static Sibling stale(String project) {
        return new Sibling(project, InfobaseSiblingResolver.RELATION_EXTENSION_OF, "NOT_EQUAL", true); //$NON-NLS-1$
    }

    private static Sibling green(String project) {
        return new Sibling(project, InfobaseSiblingResolver.RELATION_EXTENSION_OF, "EQUAL", false); //$NON-NLS-1$
    }

    // -- F3: skip-because-EQUAL + sibling annotation -----------------------------------------------

    @Test
    public void skippedEqualPayloadNamesTheDivergedSibling() {
        JsonObject payload = new JsonObject();
        EdtUpdateInfobaseTool.fillSkippedEqual(payload);
        EdtUpdateInfobaseTool.annotateSiblings(payload, List.of(stale("AM_Ext")), true); //$NON-NLS-1$

        assertEquals("skipped", payload.get("status").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(payload.get("skipped").getAsBoolean()); //$NON-NLS-1$
        assertFalse(payload.get("updated").getAsBoolean()); //$NON-NLS-1$
        assertEquals(1, payload.getAsJsonArray("sibling_projects_stale").size()); //$NON-NLS-1$
        assertEquals("AM_Ext", //$NON-NLS-1$
                payload.getAsJsonArray("sibling_projects_stale").get(0).getAsString()); //$NON-NLS-1$
        String warning = payload.get("sibling_warning").getAsString(); //$NON-NLS-1$
        assertTrue("the skip variant must say the skip does NOT mean the IB is current", //$NON-NLS-1$
                warning.contains("does NOT mean the infobase is current")); //$NON-NLS-1$
        assertTrue(warning.contains("AM_Ext")); //$NON-NLS-1$
    }

    @Test
    public void sharedButConvergedInfobaseEmitsAnEmptyStaleListAndNoWarning() {
        JsonObject payload = new JsonObject();
        EdtUpdateInfobaseTool.annotateSiblings(payload, List.of(green("AM_Ext")), false); //$NON-NLS-1$
        assertTrue("the empty array is the proof the check ran", //$NON-NLS-1$
                payload.has("sibling_projects_stale")); //$NON-NLS-1$
        assertEquals(0, payload.getAsJsonArray("sibling_projects_stale").size()); //$NON-NLS-1$
        assertFalse(payload.has("sibling_warning")); //$NON-NLS-1$
    }

    @Test
    public void unsharedInfobaseAddsNothing() {
        JsonObject payload = new JsonObject();
        EdtUpdateInfobaseTool.annotateSiblings(payload, List.of(), false);
        EdtUpdateInfobaseTool.annotateSiblings(payload, null, true);
        assertEquals("no noise for a single-project infobase", 0, payload.size()); //$NON-NLS-1$
    }

    // -- post-update equality: emitted unconditionally, unlike the opt-in pre-check ----------------

    @Test
    public void postUpdateEqualityIsReportedWithoutTheOptInPreCheck() {
        JsonObject payload = new JsonObject();
        EdtUpdateInfobaseTool.annotatePostUpdateEquality(payload, "EQUAL", true, false); //$NON-NLS-1$
        assertEquals("EQUAL", payload.get("equality_state_after").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("a converged update needs no warning", //$NON-NLS-1$
                payload.has("equality_state_after_warning")); //$NON-NLS-1$
        assertFalse("the PRE-check field stays opt-in — this must not fake it", //$NON-NLS-1$
                payload.has("equality_state")); //$NON-NLS-1$
    }

    @Test
    public void successfulUpdateThatDidNotConvergeSaysSoAndForbidsARetryLoop() {
        JsonObject payload = new JsonObject();
        EdtUpdateInfobaseTool.annotatePostUpdateEquality(payload, "NOT_EQUAL", true, false); //$NON-NLS-1$
        assertEquals("NOT_EQUAL", payload.get("equality_state_after").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        String warning = payload.get("equality_state_after_warning").getAsString(); //$NON-NLS-1$
        assertTrue("the non-convergence mode must be named, not left to a second call", //$NON-NLS-1$
                warning.contains("still differs")); //$NON-NLS-1$
        assertTrue("re-running the same update never converges — say it", //$NON-NLS-1$
                warning.contains("will not converge")); //$NON-NLS-1$
    }

    @Test
    public void dynamicOnlyUpdateLeavesTheWarningToItsOwnRicherAnnotation() {
        JsonObject payload = new JsonObject();
        EdtUpdateInfobaseTool.annotatePostUpdateEquality(payload, "NOT_EQUAL", true, true); //$NON-NLS-1$
        assertEquals("NOT_EQUAL", payload.get("equality_state_after").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("dynamic_only_forward_warning already covers this — do not double-warn", //$NON-NLS-1$
                payload.has("equality_state_after_warning")); //$NON-NLS-1$
    }

    @Test
    public void anUndeterminableStateAddsNothingRatherThanAFalseVerdict() {
        JsonObject payload = new JsonObject();
        EdtUpdateInfobaseTool.annotatePostUpdateEquality(payload, null, true, false);
        EdtUpdateInfobaseTool.annotatePostUpdateEquality(payload, "  ", true, false); //$NON-NLS-1$
        assertEquals("an unreadable state is silence, never a verdict", 0, payload.size()); //$NON-NLS-1$
    }

    @Test
    public void siblingWarningAlwaysPointsAtThePerProjectUpdate() {
        String proceeding = EdtUpdateInfobaseTool.siblingWarning(List.of("A", "B"), false); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(proceeding.contains("A, B")); //$NON-NLS-1$
        assertTrue(proceeding.contains("update_infobase")); //$NON-NLS-1$
        assertFalse("the proceeding variant must not claim the update was skipped", //$NON-NLS-1$
                proceeding.contains("was skipped")); //$NON-NLS-1$
    }

    // -- F4: in-flight guard keyed by canonical infobase identity ----------------------------------

    @Test
    public void twoProjectsOnOneInfobaseShareTheGuardSlot() {
        String keyA = EdtUpdateInfobaseTool.updateKey("Config", IB_A); //$NON-NLS-1$
        String keyB = EdtUpdateInfobaseTool.updateKey("Extension", IB_A_COSMETIC); //$NON-NLS-1$
        assertEquals("the same physical infobase must produce one key", keyA, keyB); //$NON-NLS-1$

        EdtUpdateInfobaseTool.UpdateSlot first = new EdtUpdateInfobaseTool.UpdateSlot("Config", "job-1"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(EdtUpdateInfobaseTool.tryAcquireUpdate(keyA, first));

        EdtUpdateInfobaseTool.UpdateSlot second = new EdtUpdateInfobaseTool.UpdateSlot("Extension", "job-2"); //$NON-NLS-1$ //$NON-NLS-2$
        EdtUpdateInfobaseTool.UpdateSlot held = EdtUpdateInfobaseTool.tryAcquireUpdate(keyB, second);
        assertNotNull("a second update on the SAME infobase must be refused", held); //$NON-NLS-1$
        assertEquals("job-1", held.jobId()); //$NON-NLS-1$
        assertEquals("the rejection must name the project that actually holds the infobase", //$NON-NLS-1$
                "Config", held.project()); //$NON-NLS-1$
    }

    @Test
    public void projectsOnDifferentInfobasesDoNotBlockEachOther() {
        EdtUpdateInfobaseTool.UpdateSlot a = new EdtUpdateInfobaseTool.UpdateSlot("Config", "job-a"); //$NON-NLS-1$ //$NON-NLS-2$
        EdtUpdateInfobaseTool.UpdateSlot b = new EdtUpdateInfobaseTool.UpdateSlot("Other", "job-b"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(EdtUpdateInfobaseTool.tryAcquireUpdate(
                EdtUpdateInfobaseTool.updateKey("Config", IB_A), a)); //$NON-NLS-1$
        assertNull("different infobases keep independent slots", //$NON-NLS-1$
                EdtUpdateInfobaseTool.tryAcquireUpdate(EdtUpdateInfobaseTool.updateKey("Other", IB_B), b)); //$NON-NLS-1$
    }

    @Test
    public void unresolvedInfobaseFallsBackToTheProjectNameKey() {
        assertEquals("Accounting management", //$NON-NLS-1$
                EdtUpdateInfobaseTool.updateKey("  Accounting management  ", null)); //$NON-NLS-1$
        assertEquals("Accounting management", //$NON-NLS-1$
                EdtUpdateInfobaseTool.updateKey("Accounting management", "   ")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void slotJobIdIsUpgradedFromTheStartingSentinel() {
        EdtUpdateInfobaseTool.UpdateSlot slot = new EdtUpdateInfobaseTool.UpdateSlot("Config", "starting"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(EdtUpdateInfobaseTool.isPollableJobId(slot.jobId()));
        slot.setJobId("f670423c"); //$NON-NLS-1$
        assertTrue(EdtUpdateInfobaseTool.isPollableJobId(slot.jobId()));
    }

    // -- F5: damaged target database ---------------------------------------------------------------

    @Test
    public void damagedTargetDbIsDetectedInEnglish() {
        assertTrue(EdtUpdateInfobaseTool.isTargetDbDamaged(new IllegalStateException(
                "Error executing operation: the integrity of configuration structure is violated"))); //$NON-NLS-1$
    }

    @Test
    public void damagedTargetDbIsDetectedInRussian() {
        assertTrue(EdtUpdateInfobaseTool.isTargetDbDamaged(new IllegalStateException(
                "Ошибка: нарушена целостность структуры конфигурации"))); //$NON-NLS-1$
    }

    @Test
    public void damagedTargetDbIsDetectedDeepInTheCauseChain() {
        Throwable root = new IOException("integrity of configuration structure is violated"); //$NON-NLS-1$
        assertTrue(EdtUpdateInfobaseTool.isTargetDbDamaged(
                new IllegalStateException("update failed", new RuntimeException("designer apply", root)))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void anOrdinaryFailureIsNotClassifiedAsDamaged() {
        assertFalse(EdtUpdateInfobaseTool.isTargetDbDamaged(
                new IllegalStateException("File not found: c:/temp/config.xml.zip"))); //$NON-NLS-1$
        assertFalse(EdtUpdateInfobaseTool.isTargetDbDamaged(new IllegalStateException((String) null)));
        assertFalse(EdtUpdateInfobaseTool.isTargetDbDamaged(null));
    }

    /**
     * The ordering guard: {@code isBlockedByLockedIB} fires on a bare "xml.zip" substring, and a damaged
     * database fails its config export with exactly that message — a chain carrying BOTH must classify as
     * TARGET_INFOBASE_DAMAGED, never as IB_LOCKED.
     */
    @Test
    public void damagedTargetDbWinsOverTheLockedIbHeuristic() {
        Throwable both = new IllegalStateException(
                "Config export failed: c:/temp/1c/config.xml.zip", //$NON-NLS-1$
                new IllegalStateException("нарушена целостность структуры конфигурации")); //$NON-NLS-1$
        assertTrue("the damaged-DB classifier must match this chain", //$NON-NLS-1$
                EdtUpdateInfobaseTool.isTargetDbDamaged(both));
    }

    @Test
    public void selfReferencingCauseChainTerminates() {
        RuntimeException loop = new RuntimeException("boom") { //$NON-NLS-1$
            private static final long serialVersionUID = 1L;

            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };
        assertFalse(EdtUpdateInfobaseTool.isTargetDbDamaged(loop));
        assertTrue(EdtUpdateInfobaseTool.causeChain(loop, 200).contains("boom")); //$NON-NLS-1$
    }

    @Test
    public void causeChainFlattensAndTruncates() {
        String chain = EdtUpdateInfobaseTool.causeChain(
                new IllegalStateException("outer", new IOException("inner")), 0); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(chain.contains("outer")); //$NON-NLS-1$
        assertTrue("the raw chain must survive to the payload/log — it is the only live evidence " //$NON-NLS-1$
                + "of the platform's real wording", chain.contains("inner")); //$NON-NLS-1$
        assertTrue(chain.contains("<-")); //$NON-NLS-1$
        assertEquals("", EdtUpdateInfobaseTool.causeChain(null, 100)); //$NON-NLS-1$

        String truncated = EdtUpdateInfobaseTool.causeChain(new IllegalStateException("x".repeat(500)), 50); //$NON-NLS-1$
        assertTrue(truncated.endsWith("(truncated)")); //$NON-NLS-1$
    }

    @Test
    public void repairCommandTargetsTheDatabaseFile() {
        assertEquals("chdbfl.exe -s \"c:\\1C\\db\\am\\1Cv8.1CD\"", //$NON-NLS-1$
                EdtUpdateInfobaseTool.repairCommand("c:\\1C\\db\\am\\")); //$NON-NLS-1$
        assertEquals("chdbfl.exe -s \"c:\\1C\\db\\am\\1Cv8.1CD\"", //$NON-NLS-1$
                EdtUpdateInfobaseTool.repairCommand(" c:\\1C\\db\\am ")); //$NON-NLS-1$
    }
}
