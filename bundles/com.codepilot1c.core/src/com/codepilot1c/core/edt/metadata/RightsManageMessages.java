package com.codepilot1c.core.edt.metadata;

import java.util.List;

/**
 * Pure (EDT-runtime-free) result-message helpers for {@code rights_manage}.
 *
 * <p>Extracted so the honest-reporting contract can be unit-tested headless. Background:
 * codepilot1c-feedback {@code 2026-07-16-rights-manage-reports-success-but-does-not-persist} —
 * the tool reported unconditional success (listing every grant as "applied") even when every grant
 * was a no-op and nothing was serialized. The message must instead reflect what actually changed.</p>
 */
final class RightsManageMessages {

    private RightsManageMessages() {
    }

    /** Renders one grant line, honestly marking a real change vs an already-at-value no-op. */
    static String formatGrantSummary(int index, String objectFqn, String rightName,
            String newValueName, String currentValueName, boolean changed) {
        String base = "grant[" + index + "]: " + objectFqn + "." + rightName + "=" + newValueName; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        return changed
                ? base + " (changed from " + currentValueName + ")" //$NON-NLS-1$ //$NON-NLS-2$
                : base + " (unchanged — already " + currentValueName + ")"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Renders one {@code value:remove} grant line, honestly marking a real removal vs a no-op when the
     * object carried no such explicit right entry. Removal drops the entry entirely (and prunes the
     * emptied {@code <object>} block), which {@code unset}/{@code provided} do not.
     */
    static String formatGrantRemoval(int index, String objectFqn, String rightName, boolean removed) {
        String base = "grant[" + index + "]: " + objectFqn + "." + rightName + "=remove"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        return removed
                ? base + " (removed)" //$NON-NLS-1$
                : base + " (unchanged — no such explicit grant)"; //$NON-NLS-1$
    }

    /**
     * Builds the rights_manage result message. When NOTHING changed, says so plainly instead of
     * claiming an update — the old unconditional "Role rights updated: …" wrongly implied a write
     * even when every grant was a no-op (and thus nothing was serialized to disk).
     *
     * @param changedCount   grants that actually mutated the model
     * @param summaries      per-grant lines (see {@link #formatGrantSummary})
     * @param rightsFileState optional on-disk advisory appended verbatim (may be {@code null}/blank)
     */
    static String buildRightsMessage(int changedCount, List<String> summaries, String rightsFileState) {
        int total = summaries == null ? 0 : summaries.size();
        String joined = summaries == null ? "" : String.join("; ", summaries); //$NON-NLS-1$ //$NON-NLS-2$
        StringBuilder sb = new StringBuilder();
        if (changedCount <= 0) {
            sb.append("No rights changed: all ").append(total) //$NON-NLS-1$
                    .append(" grant(s) were already at the requested values, so nothing was written. ") //$NON-NLS-1$
                    .append(joined);
        } else {
            sb.append("Role rights updated (").append(changedCount).append(" of ").append(total) //$NON-NLS-1$ //$NON-NLS-2$
                    .append(" grant(s) changed): ").append(joined); //$NON-NLS-1$
        }
        if (rightsFileState != null && !rightsFileState.isBlank()) {
            sb.append(' ').append(rightsFileState);
        }
        return sb.toString();
    }
}
