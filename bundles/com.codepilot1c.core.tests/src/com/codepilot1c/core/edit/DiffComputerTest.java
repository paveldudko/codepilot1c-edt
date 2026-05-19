/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.codepilot1c.core.edit.DiffComputer.DiffLine;
import com.codepilot1c.core.edit.DiffComputer.DiffSummary;
import com.codepilot1c.core.edit.DiffComputer.DiffType;
import com.codepilot1c.core.edit.DiffComputer.UnifiedDiff;

/**
 * Tests for the pure-Java diff utility used by {@code edit_file} dry-run /
 * {@code return_diff} support and by future {@code bsl_object_context} diff
 * verification. Independent from any Eclipse runtime so it can run in the
 * plain-Maven test bundle.
 *
 * <p>Pins behaviour we rely on: LCS-based simple diff for small inputs,
 * line-paired fallback for large inputs, hunk grouping with surrounding
 * context, and per-file summary statistics.</p>
 */
public class DiffComputerTest {

    private final DiffComputer diff = new DiffComputer();

    // --- computeLines ---------------------------------------------------------

    @Test
    public void identicalContentProducesAllEqualLines() {
        List<DiffLine> lines = diff.computeLines("a\nb\nc", "a\nb\nc");
        assertEquals(3, lines.size());
        for (DiffLine line : lines) {
            assertEquals(DiffType.EQUAL, line.type());
        }
    }

    @Test
    public void singleLineInsertionIsReportedAsAdded() {
        List<DiffLine> lines = diff.computeLines("a\nc", "a\nb\nc");
        assertEquals(3, lines.size());
        assertEquals(DiffType.EQUAL, lines.get(0).type());
        assertEquals(DiffType.ADDED, lines.get(1).type());
        assertEquals("b", lines.get(1).text());
        assertEquals(DiffType.EQUAL, lines.get(2).type());
    }

    @Test
    public void singleLineDeletionIsReportedAsRemoved() {
        List<DiffLine> lines = diff.computeLines("a\nb\nc", "a\nc");
        assertEquals(3, lines.size());
        assertEquals(DiffType.EQUAL, lines.get(0).type());
        assertEquals(DiffType.REMOVED, lines.get(1).type());
        assertEquals("b", lines.get(1).text());
        assertEquals(DiffType.EQUAL, lines.get(2).type());
    }

    @Test
    public void modificationIsReportedAsRemovedThenAdded() {
        List<DiffLine> lines = diff.computeLines("hello", "world");
        assertEquals(2, lines.size());
        // The LCS variant emits all removals then additions for a non-equal pair.
        boolean sawRemoved = false;
        boolean sawAdded = false;
        for (DiffLine line : lines) {
            if (line.type() == DiffType.REMOVED && "hello".equals(line.text())) {
                sawRemoved = true;
            }
            if (line.type() == DiffType.ADDED && "world".equals(line.text())) {
                sawAdded = true;
            }
        }
        assertTrue("expected REMOVED 'hello'", sawRemoved);
        assertTrue("expected ADDED 'world'", sawAdded);
    }

    @Test
    public void lineNumbersTrackBothSidesIndependently() {
        List<DiffLine> lines = diff.computeLines("a\nb\nc", "a\nx\nc");
        // EQUAL 'a' has both prev=1 and curr=1
        DiffLine first = lines.get(0);
        assertEquals(DiffType.EQUAL, first.type());
        assertEquals(1, first.prevLine());
        assertEquals(1, first.currLine());
        // Find the REMOVED 'b': prev=2, curr=0
        DiffLine removed = lines.stream()
                .filter(l -> l.type() == DiffType.REMOVED && "b".equals(l.text()))
                .findFirst().orElseThrow();
        assertEquals(2, removed.prevLine());
        assertEquals(0, removed.currLine());
        // Find the ADDED 'x': prev=0, curr=2
        DiffLine added = lines.stream()
                .filter(l -> l.type() == DiffType.ADDED && "x".equals(l.text()))
                .findFirst().orElseThrow();
        assertEquals(0, added.prevLine());
        assertEquals(2, added.currLine());
    }

    @Test
    public void largeInputsTriggerFallbackButStayCorrectForPureAppend() {
        // Build a 2000-line "before" and a 2001-line "after" that adds one line at the end.
        // The LCS path would handle this in O(2000*2001) = 4M ops; fallback path pairs
        // 1:1 then emits the trailing addition. Either implementation should report:
        //   2000 EQUAL + 1 ADDED.
        StringBuilder beforeBld = new StringBuilder();
        StringBuilder afterBld = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            beforeBld.append("line ").append(i).append('\n');
            afterBld.append("line ").append(i).append('\n');
        }
        afterBld.append("tail");
        // Drop the trailing newline on beforeBld so the line count is exact 2000.
        String before = beforeBld.substring(0, beforeBld.length() - 1);
        String after = afterBld.toString();

        List<DiffLine> lines = diff.computeLines(before, after);
        long equalCount = lines.stream().filter(l -> l.type() == DiffType.EQUAL).count();
        long addedCount = lines.stream().filter(l -> l.type() == DiffType.ADDED).count();
        long removedCount = lines.stream().filter(l -> l.type() == DiffType.REMOVED).count();
        assertEquals(2000, equalCount);
        assertEquals(1, addedCount);
        assertEquals(0, removedCount);
    }

    // --- unifiedDiff ----------------------------------------------------------

    @Test
    public void unifiedDiffProducesHunksWithRangesAndText() {
        String before = "alpha\nbeta\ngamma\ndelta\nepsilon";
        String after  = "alpha\nBETA\ngamma\ndelta\nepsilon";
        UnifiedDiff ud = diff.unifiedDiff(before, after, 1);
        assertTrue("expected at least one hunk", ud.hunks().size() >= 1);
        DiffComputer.Hunk hunk = ud.hunks().get(0);
        assertNotNull(hunk.text());
        assertTrue("hunk text must include the removed line",
                hunk.text().contains("-beta"));
        assertTrue("hunk text must include the added line",
                hunk.text().contains("+BETA"));
        assertEquals(1, ud.summary().added());
        assertEquals(1, ud.summary().removed());
    }

    @Test
    public void unifiedDiffHasNoHunksForIdenticalInputs() {
        UnifiedDiff ud = diff.unifiedDiff("foo\nbar", "foo\nbar", 3);
        assertTrue(ud.hunks().isEmpty());
        assertFalse(ud.summary().hasChanges());
        assertEquals(0, ud.summary().added());
        assertEquals(0, ud.summary().removed());
    }

    @Test
    public void unifiedDiffMergesNearbyChangesIntoOneHunk() {
        // Two adjacent changed lines should land in the same hunk when context >= 1.
        String before = "a\nb\nc\nd\ne";
        String after  = "a\nB\nC\nd\ne";
        UnifiedDiff ud = diff.unifiedDiff(before, after, 1);
        assertEquals("changes on adjacent lines must coalesce into one hunk",
                1, ud.hunks().size());
        assertEquals(2, ud.summary().added());
        assertEquals(2, ud.summary().removed());
    }

    @Test
    public void unifiedDiffSplitsFarChangesIntoSeparateHunks() {
        // Changes 10 lines apart with context=1 must NOT merge into one hunk.
        StringBuilder beforeBld = new StringBuilder();
        StringBuilder afterBld = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            String prefix = (i == 2 ? "OLD-" : i == 14 ? "OLD2-" : "");
            String prefixAfter = (i == 2 ? "NEW-" : i == 14 ? "NEW2-" : "");
            beforeBld.append(prefix).append("row ").append(i).append('\n');
            afterBld.append(prefixAfter).append("row ").append(i).append('\n');
        }
        UnifiedDiff ud = diff.unifiedDiff(
                beforeBld.substring(0, beforeBld.length() - 1),
                afterBld.substring(0, afterBld.length() - 1),
                1);
        assertEquals("far-apart changes must produce separate hunks",
                2, ud.hunks().size());
    }

    // --- summary -------------------------------------------------------------

    @Test
    public void summaryReportsAddedRemovedAndHasChanges() {
        DiffSummary summary = diff.summary("foo\nbar", "foo\nbar\nbaz");
        assertEquals(1, summary.added());
        assertEquals(0, summary.removed());
        assertTrue(summary.hasChanges());
    }

    @Test
    public void summaryOfIdenticalInputsHasNoChanges() {
        DiffSummary summary = diff.summary("x", "x");
        assertEquals(0, summary.added());
        assertEquals(0, summary.removed());
        assertFalse(summary.hasChanges());
    }
}
