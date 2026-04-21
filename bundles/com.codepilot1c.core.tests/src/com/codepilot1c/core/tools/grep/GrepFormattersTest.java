/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.grep;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

/**
 * Tests for {@link GrepFormatters}.
 *
 * <p>The compact format saves ~20% of chars compared with the default
 * markdown output on representative input (token savings are higher — fewer
 * punctuation tokens). These tests pin the grep-like shape (path:line:text /
 * path-line-text) and make sure the ratio holds.</p>
 */
public class GrepFormattersTest {

    @Test
    public void compactEmitsGrepStyleSingleLinePerMatch() {
        GrepFormatters.Hit hit = new GrepFormatters.Hit(
                "/Demo/CommonModules/Auth/Module.bsl", //$NON-NLS-1$
                42,
                42,
                List.of("    Server.Login(User);")); //$NON-NLS-1$

        String out = GrepFormatters.compact("Login", List.of(hit), 50, 0); //$NON-NLS-1$
        assertTrue("Compact output must contain path:line:text", //$NON-NLS-1$
                out.contains("/Demo/CommonModules/Auth/Module.bsl:42:    Server.Login(User);")); //$NON-NLS-1$
        assertFalse("Compact output must not contain markdown fences", out.contains("```")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("Compact output must not contain bold headers", out.contains("**")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void compactUsesDashForContextLines() {
        GrepFormatters.Hit hit = new GrepFormatters.Hit(
                "/x/Module.bsl", 5, 3, //$NON-NLS-1$
                List.of("before 1", "before 2", "the MATCH", "after 1", "after 2")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        String out = GrepFormatters.compact("MATCH", List.of(hit), 50, 2); //$NON-NLS-1$
        assertTrue(out.contains("/x/Module.bsl-3-before 1")); //$NON-NLS-1$
        assertTrue(out.contains("/x/Module.bsl-4-before 2")); //$NON-NLS-1$
        assertTrue(out.contains("/x/Module.bsl:5:the MATCH")); //$NON-NLS-1$
        assertTrue(out.contains("/x/Module.bsl-6-after 1")); //$NON-NLS-1$
        assertTrue(out.contains("/x/Module.bsl-7-after 2")); //$NON-NLS-1$
    }

    @Test
    public void compactAppendsFooterWithCount() {
        GrepFormatters.Hit hit = new GrepFormatters.Hit(
                "/x.bsl", 1, 1, List.of("hit")); //$NON-NLS-1$ //$NON-NLS-2$
        String out = GrepFormatters.compact("pat", List.of(hit, hit, hit), 50, 0); //$NON-NLS-1$
        assertTrue(out.endsWith("3 matches for `pat`")); //$NON-NLS-1$
    }

    @Test
    public void compactLimitReachedAnnouncesTruncation() {
        GrepFormatters.Hit hit = new GrepFormatters.Hit(
                "/x.bsl", 1, 1, List.of("hit")); //$NON-NLS-1$ //$NON-NLS-2$
        String out = GrepFormatters.compact("pat", List.of(hit, hit), 2, 0); //$NON-NLS-1$
        assertTrue("Footer must warn when limit is hit", out.contains("(limited")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void compactSeparatesHitsWithBlankLineOnlyWhenContextRequested() {
        GrepFormatters.Hit a = new GrepFormatters.Hit("/a", 1, 1, List.of("a")); //$NON-NLS-1$ //$NON-NLS-2$
        GrepFormatters.Hit b = new GrepFormatters.Hit("/b", 2, 2, List.of("b")); //$NON-NLS-1$ //$NON-NLS-2$

        String noCtx = GrepFormatters.compact("x", List.of(a, b), 50, 0); //$NON-NLS-1$
        // No blank separator between hits (they're already one line each).
        assertFalse("contextLines=0 must not insert blank separator", noCtx.contains("\n\n/b")); //$NON-NLS-1$ //$NON-NLS-2$

        String withCtx = GrepFormatters.compact("x", List.of(a, b), 50, 2); //$NON-NLS-1$
        assertTrue("contextLines>0 must separate hits with blank line", withCtx.contains("\n\n/b")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void markdownHeaderAndFenceShape() {
        GrepFormatters.Hit hit = new GrepFormatters.Hit(
                "/x.bsl", 3, 3, List.of("match")); //$NON-NLS-1$ //$NON-NLS-2$
        String out = GrepFormatters.markdown("pat", List.of(hit), 50); //$NON-NLS-1$
        assertTrue(out.contains("**Search results for:** `pat`")); //$NON-NLS-1$
        assertTrue(out.contains("**/x.bsl:3**")); //$NON-NLS-1$
        assertTrue(out.contains("```")); //$NON-NLS-1$
        assertTrue(out.contains(">   3 | match")); //$NON-NLS-1$
    }

    @Test
    public void markdownLimitedBadgeAppended() {
        GrepFormatters.Hit hit = new GrepFormatters.Hit(
                "/x.bsl", 1, 1, List.of("a")); //$NON-NLS-1$ //$NON-NLS-2$
        String out = GrepFormatters.markdown("p", List.of(hit, hit), 2); //$NON-NLS-1$
        assertTrue(out.contains("(limited)")); //$NON-NLS-1$
    }

    @Test
    public void compactIsSubstantiallySmallerThanMarkdownOnRealishInput() {
        // 20 hits, 1 context line each — representative of a BSL search.
        List<GrepFormatters.Hit> hits = new java.util.ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            hits.add(new GrepFormatters.Hit(
                    "/Project/CommonModules/SomeModule/Module.bsl", //$NON-NLS-1$
                    i * 10,
                    i * 10,
                    List.of("    Result = SomeModule.DoWork(Arg" + i + ");"))); //$NON-NLS-1$
        }

        int markdownLen = GrepFormatters.markdown("DoWork", hits, 50).length(); //$NON-NLS-1$
        int compactLen = GrepFormatters.compact("DoWork", hits, 50, 0).length(); //$NON-NLS-1$

        assertTrue("Compact must be smaller", compactLen < markdownLen); //$NON-NLS-1$
        // Expected savings: ~20% on representative BSL input. The compact
        // format uses single-line grep-style `path:line:text` (matches rg /
        // grep -H default) — path is repeated per line, so savings come
        // purely from dropping `**` bold headers, ``` fences and blank
        // separators. Grouping hits under a shared `path` heading (rg
        // --heading) would push savings toward 50–60% but changes the
        // format contract expected by other tests here.
        assertTrue("Compact should save at least 15% of chars (got " //$NON-NLS-1$
                + (100 * (markdownLen - compactLen) / markdownLen) + "%)", //$NON-NLS-1$
                compactLen < markdownLen * 85 / 100);
    }

    @Test
    public void emptyMatchesStillEmitsFooter() {
        String out = GrepFormatters.compact("nothing", List.of(), 50, 0); //$NON-NLS-1$
        assertEquals("\n0 matches for `nothing`", out); //$NON-NLS-1$
    }
}
