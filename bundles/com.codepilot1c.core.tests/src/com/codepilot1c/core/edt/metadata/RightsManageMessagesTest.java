package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

/**
 * Pins the honest-reporting contract for {@code rights_manage} result messages
 * ({@link RightsManageMessages}). Background: codepilot1c-feedback
 * {@code 2026-07-16-rights-manage-reports-success-but-does-not-persist} — the tool reported
 * unconditional success (every grant listed as "applied") even when nothing changed / persisted.
 */
public class RightsManageMessagesTest {

    @Test
    public void changedGrantSummaryStatesTheTransition() {
        String s = RightsManageMessages.formatGrantSummary(1,
                "InformationRegister.FinanceVerification", "Read", "SET", "UNSET", true); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertTrue(s.contains("InformationRegister.FinanceVerification.Read=SET")); //$NON-NLS-1$
        assertTrue("a real change must be marked changed", s.contains("changed")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(s.contains("unchanged")); //$NON-NLS-1$
    }

    @Test
    public void noOpGrantSummaryIsMarkedUnchangedNotApplied() {
        String s = RightsManageMessages.formatGrantSummary(2,
                "Enum.FinanceVerificationState", "Use", "SET", "SET", false); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertTrue("a no-op grant must read as unchanged, never a bare applied", //$NON-NLS-1$
                s.contains("unchanged")); //$NON-NLS-1$
    }

    @Test
    public void messageForZeroChangesSaysNothingWasWritten() {
        List<String> summaries = List.of(
                RightsManageMessages.formatGrantSummary(1, "Enum.X", "Read", "SET", "SET", false), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                RightsManageMessages.formatGrantSummary(2, "Enum.X", "Use", "SET", "SET", false)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        String msg = RightsManageMessages.buildRightsMessage(0, summaries, null);
        assertTrue("must say nothing was written when no grant changed", //$NON-NLS-1$
                msg.toLowerCase(java.util.Locale.ROOT).contains("nothing was written")); //$NON-NLS-1$
        assertFalse("must NOT falsely claim an update on a pure no-op", //$NON-NLS-1$
                msg.contains("Role rights updated")); //$NON-NLS-1$
    }

    @Test
    public void messageForRealChangesReportsChangedCount() {
        List<String> summaries = List.of(
                RightsManageMessages.formatGrantSummary(1, "Enum.X", "Read", "SET", "UNSET", true), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                RightsManageMessages.formatGrantSummary(2, "Enum.X", "Use", "SET", "SET", false)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        String msg = RightsManageMessages.buildRightsMessage(1, summaries, null);
        assertTrue("must announce an update when at least one grant changed", //$NON-NLS-1$
                msg.contains("Role rights updated")); //$NON-NLS-1$
        assertTrue("must state the changed-of-total ratio", msg.contains("1 of 2")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void removalSummaryMarksActualRemovalVsNoOp() {
        String removed = RightsManageMessages.formatGrantRemoval(1,
                "Enum.FinanceVerificationState", "Read", true); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(removed.contains("Enum.FinanceVerificationState.Read=remove")); //$NON-NLS-1$
        assertTrue("a real removal must be marked removed", removed.contains("(removed)")); //$NON-NLS-1$ //$NON-NLS-2$

        String noop = RightsManageMessages.formatGrantRemoval(2,
                "Enum.FinanceVerificationState", "Use", false); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("removing an absent grant must read as a no-op, not a removal", //$NON-NLS-1$
                noop.contains("unchanged")); //$NON-NLS-1$
        assertFalse(noop.contains("(removed)")); //$NON-NLS-1$
    }

    @Test
    public void messageAppendsRightsFileStateAdvisory() {
        String advisory = "⚠️ WARNING: expected rights fragment src/Roles/X/Rights.rights was NOT found"; //$NON-NLS-1$
        String msg = RightsManageMessages.buildRightsMessage(1,
                List.of(RightsManageMessages.formatGrantSummary(1, "Enum.X", "Read", "SET", "UNSET", true)), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                advisory);
        assertTrue("on-disk advisory must be surfaced in the message", msg.contains(advisory)); //$NON-NLS-1$
    }
}
