/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.grep;

import java.util.List;

/**
 * Output formatters for the {@code grep} tool.
 *
 * <p>Kept dependency-free (pure Java, no Eclipse imports) so the formatting
 * rules can be unit-tested without the workspace runtime.</p>
 */
public final class GrepFormatters {

    /** A single grep hit, fully self-contained for formatting. */
    public record Hit(String path, int lineNumber, int contextStartLine, List<String> contextLines) {
    }

    private GrepFormatters() {
    }

    /**
     * Default markdown format: header, `> NNN | text` lines inside a code fence.
     * Self-documenting but verbose — ~3× the token cost of {@link #compact}.
     */
    public static String markdown(String pattern, List<Hit> matches, int limit) {
        StringBuilder sb = new StringBuilder();
        sb.append("**Search results for:** `").append(pattern).append("`\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("**Found:** ").append(matches.size()); //$NON-NLS-1$
        if (matches.size() >= limit) {
            sb.append("+ (limited)"); //$NON-NLS-1$
        }
        sb.append(" matches\n\n"); //$NON-NLS-1$

        for (Hit hit : matches) {
            sb.append("**").append(hit.path()).append(":").append(hit.lineNumber()).append("**\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            sb.append("```\n"); //$NON-NLS-1$
            for (int j = 0; j < hit.contextLines().size(); j++) {
                int lineNo = hit.contextStartLine() + j;
                String prefix = (lineNo == hit.lineNumber()) ? ">" : " "; //$NON-NLS-1$ //$NON-NLS-2$
                sb.append(String.format("%s%4d | %s%n", prefix, lineNo, hit.contextLines().get(j))); //$NON-NLS-1$
            }
            sb.append("```\n\n"); //$NON-NLS-1$
        }
        return sb.toString();
    }

    /**
     * Compact grep-like format: {@code path:line:text} for matches,
     * {@code path-line-text} for context lines (rg/grep convention).
     * No markdown, no code fences — minimizes tokens.
     *
     * @param pattern       the original search pattern (echoed in the footer)
     * @param matches       hits to render
     * @param limit         cap applied upstream; footer announces truncation
     * @param contextLines  number of context lines requested (used only to
     *                      decide whether to insert blank separators between hits)
     */
    public static String compact(String pattern, List<Hit> matches, int limit, int contextLines) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Hit hit : matches) {
            if (!first && contextLines > 0) {
                sb.append('\n');
            }
            first = false;
            for (int j = 0; j < hit.contextLines().size(); j++) {
                int lineNo = hit.contextStartLine() + j;
                char sep = (lineNo == hit.lineNumber()) ? ':' : '-';
                sb.append(hit.path()).append(sep).append(lineNo).append(sep)
                        .append(hit.contextLines().get(j)).append('\n');
            }
        }
        sb.append('\n').append(matches.size());
        if (matches.size() >= limit) {
            sb.append("+ (limited; pass larger limit)"); //$NON-NLS-1$
        }
        sb.append(" matches for `").append(pattern).append("`"); //$NON-NLS-1$ //$NON-NLS-2$
        return sb.toString();
    }
}
