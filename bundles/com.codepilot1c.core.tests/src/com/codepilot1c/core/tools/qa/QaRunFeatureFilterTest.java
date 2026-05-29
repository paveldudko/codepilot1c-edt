package com.codepilot1c.core.tools.qa;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/**
 * Guards {@link QaRunTool#asStringList(Object)} — the parser behind the {@code features} /
 * {@code tags_include} / {@code tags_exclude} filters. Regression target:
 * codepilot1c-feedback/2026-05-29-qa-run-filter-bypass.md, where a {@code features} value that did
 * not arrive as a {@code List} parsed to an empty filter and qa_run silently ran ALL features.
 * Now List, Object[] and (comma-separated) String are all accepted; only genuinely absent/blank
 * values yield an empty filter.
 */
public class QaRunFeatureFilterTest {

    @Test
    public void acceptsList() {
        assertEquals(List.of("a/x.feature", "b/y.feature"), //$NON-NLS-1$ //$NON-NLS-2$
                QaRunTool.asStringList(List.of("a/x.feature", "b/y.feature"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void acceptsObjectArray() {
        Object[] arr = {"bf-12591/main-selection.feature"}; //$NON-NLS-1$
        assertEquals(List.of("bf-12591/main-selection.feature"), QaRunTool.asStringList(arr)); //$NON-NLS-1$
    }

    @Test
    public void acceptsCommaSeparatedAndSingleString() {
        assertEquals(List.of("a.feature", "b.feature"), QaRunTool.asStringList("a.feature, b.feature")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(List.of("only.feature"), QaRunTool.asStringList("only.feature")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void blankAndNullYieldEmpty() {
        assertTrue(QaRunTool.asStringList(null).isEmpty());
        assertTrue(QaRunTool.asStringList("").isEmpty()); //$NON-NLS-1$
        assertTrue(QaRunTool.asStringList("   ").isEmpty()); //$NON-NLS-1$
        // List/array entries that are null or blank are skipped, not added as empty strings.
        assertEquals(List.of("keep.feature"), //$NON-NLS-1$
                QaRunTool.asStringList(Arrays.asList("keep.feature", null, "  "))); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
