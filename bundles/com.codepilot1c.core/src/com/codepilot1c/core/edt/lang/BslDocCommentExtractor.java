/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edt.lang;

import java.util.ArrayList;
import java.util.List;

/**
 * Extracts the doc comment that precedes a BSL method declaration.
 *
 * <p>1C/EDT convention: the doc block is the run of contiguous {@code //}
 * lines immediately above the method declaration. Annotation lines
 * ({@code &НаКлиенте}, {@code &НаСервере}, …) may sit between the doc
 * block and the keyword and are skipped during the upward walk.</p>
 *
 * <p>Collection stops at the first blank line or any non-comment /
 * non-annotation line. Leading whitespace inside comment lines is
 * preserved so callers can render the doc block verbatim.</p>
 *
 * <p>Pure Java — no Eclipse imports — so the extractor is unit-tested
 * without the workspace runtime.</p>
 */
public final class BslDocCommentExtractor {

    private BslDocCommentExtractor() {
    }

    /**
     * Extracts the doc comment immediately preceding a method declaration.
     *
     * @param source           full module source
     * @param methodStartLine  1-based line number where
     *                         {@code Процедура}/{@code Функция} /
     *                         {@code Procedure}/{@code Function} begins
     * @return the doc comment with original line separators (LF), or an
     *         empty string if there is no doc block
     */
    public static String extract(String source, int methodStartLine) {
        if (source == null || source.isEmpty() || methodStartLine <= 1) {
            return ""; //$NON-NLS-1$
        }

        String[] lines = source.split("\n", -1); //$NON-NLS-1$
        int idx = methodStartLine - 2; // lines[] is 0-based, scan the line above the method
        if (idx < 0 || idx >= lines.length) {
            return ""; //$NON-NLS-1$
        }

        List<String> collected = new ArrayList<>();
        boolean seenComment = false;

        for (int i = idx; i >= 0; i--) {
            String raw = stripTrailingCr(lines[i]);
            String trimmed = raw.stripLeading();

            if (trimmed.isEmpty()) {
                // Blank line separates the method from anything above it.
                break;
            }
            if (trimmed.startsWith("//")) { //$NON-NLS-1$
                collected.add(0, raw);
                seenComment = true;
                continue;
            }
            if (!seenComment && trimmed.startsWith("&")) { //$NON-NLS-1$
                // Annotation sitting between method and doc block — skip and keep walking.
                continue;
            }
            // Any other code above: doc block ends here.
            break;
        }

        return String.join("\n", collected); //$NON-NLS-1$
    }

    private static String stripTrailingCr(String s) {
        if (s != null && !s.isEmpty() && s.charAt(s.length() - 1) == '\r') {
            return s.substring(0, s.length() - 1);
        }
        return s == null ? "" : s; //$NON-NLS-1$
    }
}
