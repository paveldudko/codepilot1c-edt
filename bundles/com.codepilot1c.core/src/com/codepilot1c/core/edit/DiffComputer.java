/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Pure-Java line-diff utility used by {@code edit_file} dry-run /
 * {@code return_diff} and by other tooling that needs a unified-diff
 * representation of a before/after pair.
 *
 * <p>LCS-based when both inputs stay below {@link #LCS_LINE_LIMIT}; for
 * larger inputs a 1:1 paired fallback is used so the worst case stays
 * O(max(n, m)).</p>
 */
public class DiffComputer {

    private static final int LCS_LINE_LIMIT = 1500;

    public enum DiffType { EQUAL, ADDED, REMOVED }

    /**
     * One line of the diff result.
     *
     * @param type      EQUAL / ADDED / REMOVED
     * @param text      line text without the trailing newline
     * @param prevLine  1-based line number on the "before" side, or 0 if the
     *                  line is not present there (ADDED)
     * @param currLine  1-based line number on the "after" side, or 0 if the
     *                  line is not present there (REMOVED)
     */
    public record DiffLine(DiffType type, String text, int prevLine, int currLine) {}

    /**
     * One unified-diff hunk.
     *
     * @param previousRange "from-to" line range on the "before" side, or null
     *                      if the hunk has no EQUAL/REMOVED lines
     * @param currentRange  "from-to" line range on the "after" side, or null
     *                      if the hunk has no EQUAL/ADDED lines
     * @param text          unified-diff text with leading ' ', '-', '+' markers
     */
    public record Hunk(String previousRange, String currentRange, String text) {}

    /** Summary statistics derived from a before/after diff. */
    public record DiffSummary(int added, int removed) {
        public boolean hasChanges() {
            return added != 0 || removed != 0;
        }
    }

    /**
     * Unified diff bundle: ordered hunks + per-file summary.
     */
    public record UnifiedDiff(List<Hunk> hunks, DiffSummary summary) {}

    /**
     * Computes a list of {@link DiffLine}s describing the transformation from
     * {@code before} to {@code after}.
     */
    public List<DiffLine> computeLines(String before, String after) {
        String[] beforeLines = splitLines(before);
        String[] afterLines = splitLines(after);
        if (beforeLines.length > LCS_LINE_LIMIT || afterLines.length > LCS_LINE_LIMIT) {
            return fallbackPairedDiff(beforeLines, afterLines);
        }
        return lcsDiff(beforeLines, afterLines);
    }

    /**
     * Builds a unified-diff representation with the given context size around
     * each change.
     */
    public UnifiedDiff unifiedDiff(String before, String after, int contextLines) {
        if (contextLines < 0) {
            contextLines = 0;
        }
        List<DiffLine> lines = computeLines(before, after);
        List<Hunk> hunks = new ArrayList<>();
        List<DiffLine> currentHunk = new ArrayList<>();
        int lastChangeIndex = -1;
        boolean hunkHasChange = false;

        for (int i = 0; i < lines.size(); i++) {
            DiffLine line = lines.get(i);
            if (line.type() != DiffType.EQUAL) {
                if (hunkHasChange && i - lastChangeIndex > contextLines * 2 + 1) {
                    hunks.add(formatHunk(currentHunk));
                    currentHunk = new ArrayList<>();
                    hunkHasChange = false;
                    int leadingFrom = Math.max(0, i - contextLines);
                    for (int j = leadingFrom; j < i; j++) {
                        currentHunk.add(lines.get(j));
                    }
                }
                currentHunk.add(line);
                lastChangeIndex = i;
                hunkHasChange = true;
            } else if (hunkHasChange && i - lastChangeIndex <= contextLines) {
                // Trailing context after the most recent change.
                currentHunk.add(line);
            } else if (!hunkHasChange) {
                // Look ahead — if a change is within `contextLines` we want to
                // hold this EQUAL line as leading context for an upcoming hunk.
                boolean hasUpcomingChange = false;
                int lookEnd = Math.min(i + contextLines + 1, lines.size());
                for (int j = i + 1; j < lookEnd; j++) {
                    if (lines.get(j).type() != DiffType.EQUAL) {
                        hasUpcomingChange = true;
                        break;
                    }
                }
                if (hasUpcomingChange) {
                    currentHunk.add(line);
                }
            }
            // else: hunkHasChange=true and we're past the trailing-context
            //       window — drop until either the next change or end-of-input.
        }
        if (hunkHasChange) {
            hunks.add(formatHunk(currentHunk));
        }

        int added = 0;
        int removed = 0;
        for (DiffLine line : lines) {
            if (line.type() == DiffType.ADDED) {
                added++;
            } else if (line.type() == DiffType.REMOVED) {
                removed++;
            }
        }
        return new UnifiedDiff(hunks, new DiffSummary(added, removed));
    }

    /** Returns just the added/removed counts without building full hunk text. */
    public DiffSummary summary(String before, String after) {
        List<DiffLine> lines = computeLines(before, after);
        int added = 0;
        int removed = 0;
        for (DiffLine line : lines) {
            if (line.type() == DiffType.ADDED) {
                added++;
            } else if (line.type() == DiffType.REMOVED) {
                removed++;
            }
        }
        return new DiffSummary(added, removed);
    }

    // --- internals -----------------------------------------------------------

    private static String[] splitLines(String text) {
        if (text == null || text.isEmpty()) {
            return new String[0];
        }
        // Normalize line endings then split — keeping empty trailing entries
        // so a file ending in '\n' yields one extra empty line.
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.indexOf('\n') < 0) {
            return new String[] { normalized };
        }
        return normalized.split("\n", -1);
    }

    private static List<DiffLine> lcsDiff(String[] before, String[] after) {
        int n = before.length;
        int m = after.length;
        int[][] dp = new int[n + 1][m + 1];
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                if (before[i - 1].equals(after[j - 1])) {
                    dp[i][j] = dp[i - 1][j - 1] + 1;
                } else {
                    dp[i][j] = Math.max(dp[i - 1][j], dp[i][j - 1]);
                }
            }
        }
        List<DiffLine> reversed = new ArrayList<>();
        int i = n;
        int j = m;
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && before[i - 1].equals(after[j - 1])) {
                reversed.add(new DiffLine(DiffType.EQUAL, before[i - 1], i, j));
                i--;
                j--;
            } else if (j > 0 && (i == 0 || dp[i][j - 1] >= dp[i - 1][j])) {
                reversed.add(new DiffLine(DiffType.ADDED, after[j - 1], 0, j));
                j--;
            } else if (i > 0) {
                reversed.add(new DiffLine(DiffType.REMOVED, before[i - 1], i, 0));
                i--;
            }
        }
        Collections.reverse(reversed);
        return reversed;
    }

    private static List<DiffLine> fallbackPairedDiff(String[] before, String[] after) {
        List<DiffLine> out = new ArrayList<>();
        int common = Math.min(before.length, after.length);
        for (int k = 0; k < common; k++) {
            if (before[k].equals(after[k])) {
                out.add(new DiffLine(DiffType.EQUAL, before[k], k + 1, k + 1));
            } else {
                out.add(new DiffLine(DiffType.REMOVED, before[k], k + 1, 0));
                out.add(new DiffLine(DiffType.ADDED, after[k], 0, k + 1));
            }
        }
        for (int k = common; k < before.length; k++) {
            out.add(new DiffLine(DiffType.REMOVED, before[k], k + 1, 0));
        }
        for (int k = common; k < after.length; k++) {
            out.add(new DiffLine(DiffType.ADDED, after[k], 0, k + 1));
        }
        return out;
    }

    private static Hunk formatHunk(List<DiffLine> hunk) {
        StringBuilder sb = new StringBuilder();
        int prevFrom = Integer.MAX_VALUE;
        int prevTo = 0;
        int currFrom = Integer.MAX_VALUE;
        int currTo = 0;
        for (DiffLine line : hunk) {
            switch (line.type()) {
                case EQUAL -> sb.append(' ').append(line.text()).append('\n');
                case REMOVED -> sb.append('-').append(line.text()).append('\n');
                case ADDED -> sb.append('+').append(line.text()).append('\n');
            }
            if (line.prevLine() > 0) {
                prevFrom = Math.min(prevFrom, line.prevLine());
                prevTo = Math.max(prevTo, line.prevLine());
            }
            if (line.currLine() > 0) {
                currFrom = Math.min(currFrom, line.currLine());
                currTo = Math.max(currTo, line.currLine());
            }
        }
        String prevRange = prevFrom == Integer.MAX_VALUE ? null : prevFrom + "-" + prevTo;
        String currRange = currFrom == Integer.MAX_VALUE ? null : currFrom + "-" + currTo;
        return new Hunk(prevRange, currRange, sb.toString());
    }
}
