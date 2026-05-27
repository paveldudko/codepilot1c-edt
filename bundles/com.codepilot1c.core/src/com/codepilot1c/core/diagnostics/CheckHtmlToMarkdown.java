/*
 * Copyright (c) 2026 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.diagnostics;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts the narrow subset of HTML that EDT ships under
 * {@code check.descriptions/*.html} into compact Markdown suitable for LLM
 * consumption.
 *
 * <p>Not a general-purpose HTML→Markdown library — it only knows about the
 * tags v8codestyle check descriptions actually use: headings, paragraphs,
 * {@code <pre><code class="language-bsl">}, {@code <ul>/<li>}, and the empty
 * anchor children that Markdown-generated EDT pages embed for self-links.</p>
 *
 * <p>Empty headings (e.g. the trailing "See" / "См." section that has no body)
 * are dropped; {@code <script>} and {@code <footer>} blocks are stripped.</p>
 */
public final class CheckHtmlToMarkdown {

    private static final Pattern BODY = Pattern.compile(
            "<body[^>]*>(.*?)</body>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE); //$NON-NLS-1$

    private static final Pattern SCRIPT = Pattern.compile(
            "<script[^>]*>.*?</script>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE); //$NON-NLS-1$

    private static final Pattern FOOTER = Pattern.compile(
            "<footer[^>]*>.*?</footer>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE); //$NON-NLS-1$

    private static final Pattern HEADING = Pattern.compile(
            "<h([1-6])[^>]*>(.*?)</h\\1>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE); //$NON-NLS-1$

    private static final Pattern PARAGRAPH = Pattern.compile(
            "<p[^>]*>(.*?)</p>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE); //$NON-NLS-1$

    private static final Pattern CODE_BLOCK = Pattern.compile(
            "<pre[^>]*>\\s*<code([^>]*)>(.*?)</code>\\s*</pre>",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private static final Pattern LIST_BLOCK = Pattern.compile(
            "<ul[^>]*>(.*?)</ul>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE); //$NON-NLS-1$

    private static final Pattern LIST_ITEM = Pattern.compile(
            "<li[^>]*>(.*?)</li>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE); //$NON-NLS-1$

    private static final Pattern LANGUAGE_CLASS = Pattern.compile(
            "class\\s*=\\s*\"language-([a-zA-Z0-9_-]+)\"", Pattern.CASE_INSENSITIVE); //$NON-NLS-1$

    private static final Pattern ANCHOR = Pattern.compile(
            "<a[^>]*>.*?</a>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE); //$NON-NLS-1$

    private static final Pattern INLINE_CODE = Pattern.compile(
            "<code[^>]*>(.*?)</code>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE); //$NON-NLS-1$

    private static final Pattern STRONG = Pattern.compile(
            "<(strong|b)[^>]*>(.*?)</\\1>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE); //$NON-NLS-1$

    private static final Pattern EMPHASIS = Pattern.compile(
            "<(em|i)[^>]*>(.*?)</\\1>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE); //$NON-NLS-1$

    private static final Pattern REMAINING_TAGS = Pattern.compile("<[^>]+>"); //$NON-NLS-1$

    private static final Pattern BLANK_LINES = Pattern.compile("\\n{3,}"); //$NON-NLS-1$

    // Matches a heading whose section body is empty — i.e. the next non-blank
    // line is another heading, or there is no next non-blank line at all.
    // Used to drop the trailing "See" / "См." marker EDT renders even when its
    // contributor leaves the section empty.
    private static final Pattern EMPTY_SECTION = Pattern.compile(
            "(?m)^#+[ \\t]+[^\\n]+\\s*\\n+(?=#+[ \\t]|\\Z)"); //$NON-NLS-1$

    private CheckHtmlToMarkdown() {
        // utility
    }

    /**
     * Converts an EDT check description HTML page to Markdown.
     *
     * @param html raw HTML page contents (may include {@code <head>}, scripts, etc.)
     * @return Markdown body terminated by a single newline; empty string if {@code html} is blank
     */
    public static String convert(String html) {
        if (html == null || html.isBlank()) {
            return ""; //$NON-NLS-1$
        }

        Matcher bodyMatcher = BODY.matcher(html);
        String body = bodyMatcher.find() ? bodyMatcher.group(1) : html;

        body = SCRIPT.matcher(body).replaceAll(""); //$NON-NLS-1$
        body = FOOTER.matcher(body).replaceAll(""); //$NON-NLS-1$

        // Replace fenced code blocks first — their inner text may contain
        // HTML-looking constructs ("<", "&") that later passes must not parse.
        StringBuilder buf = new StringBuilder();
        Matcher cm = CODE_BLOCK.matcher(body);
        int last = 0;
        while (cm.find()) {
            buf.append(body, last, cm.start());
            String attrs = cm.group(1);
            String code = cm.group(2);
            String lang = ""; //$NON-NLS-1$
            Matcher lm = LANGUAGE_CLASS.matcher(attrs);
            if (lm.find()) {
                lang = lm.group(1);
            }
            buf.append("\n\n```").append(lang).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
            buf.append(decodeEntities(code).strip()).append("\n```\n\n"); //$NON-NLS-1$
            last = cm.end();
        }
        buf.append(body, last, body.length());
        body = buf.toString();

        body = HEADING.matcher(body).replaceAll(mr -> {
            int level = Integer.parseInt(mr.group(1));
            String inner = ANCHOR.matcher(mr.group(2)).replaceAll(""); //$NON-NLS-1$
            String text = stripTags(inner).strip();
            if (text.isEmpty()) {
                return ""; // drop empty trailing sections (e.g. "See" / "См.") //$NON-NLS-1$
            }
            return Matcher.quoteReplacement("\n\n" + "#".repeat(level) + " " + text + "\n\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        });

        body = LIST_BLOCK.matcher(body).replaceAll(mr -> {
            StringBuilder items = new StringBuilder("\n\n"); //$NON-NLS-1$
            Matcher im = LIST_ITEM.matcher(mr.group(1));
            while (im.find()) {
                String item = stripTags(im.group(1)).strip();
                if (!item.isEmpty()) {
                    items.append("- ").append(item).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
            items.append("\n"); //$NON-NLS-1$
            return Matcher.quoteReplacement(items.toString());
        });

        body = PARAGRAPH.matcher(body).replaceAll(mr -> {
            String text = stripTags(mr.group(1)).strip();
            if (text.isEmpty()) {
                return ""; //$NON-NLS-1$
            }
            return Matcher.quoteReplacement("\n\n" + text + "\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        });

        body = INLINE_CODE.matcher(body).replaceAll(mr ->
                Matcher.quoteReplacement("`" + mr.group(1) + "`")); //$NON-NLS-1$ //$NON-NLS-2$
        body = STRONG.matcher(body).replaceAll(mr ->
                Matcher.quoteReplacement("**" + mr.group(2) + "**")); //$NON-NLS-1$ //$NON-NLS-2$
        body = EMPHASIS.matcher(body).replaceAll(mr ->
                Matcher.quoteReplacement("*" + mr.group(2) + "*")); //$NON-NLS-1$ //$NON-NLS-2$

        body = REMAINING_TAGS.matcher(body).replaceAll(""); //$NON-NLS-1$
        body = decodeEntities(body);
        body = BLANK_LINES.matcher(body).replaceAll("\n\n"); //$NON-NLS-1$

        // Drop body-less heading sections. Iterates because removing one
        // trailing empty heading can expose another above it (e.g. two stacked
        // empty markers near end-of-doc).
        String previous;
        do {
            previous = body;
            body = EMPTY_SECTION.matcher(body).replaceAll(""); //$NON-NLS-1$
        } while (!body.equals(previous));

        body = BLANK_LINES.matcher(body).replaceAll("\n\n"); //$NON-NLS-1$
        return body.strip() + "\n"; //$NON-NLS-1$
    }

    private static String stripTags(String s) {
        return REMAINING_TAGS.matcher(s).replaceAll(""); //$NON-NLS-1$
    }

    private static String decodeEntities(String s) {
        if (s == null || s.indexOf('&') < 0) {
            return s == null ? "" : s; //$NON-NLS-1$
        }
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '&') {
                int semi = s.indexOf(';', i);
                if (semi > 0 && semi - i <= 8) {
                    String name = s.substring(i + 1, semi);
                    String resolved = resolveEntity(name);
                    if (resolved != null) {
                        out.append(resolved);
                        i = semi + 1;
                        continue;
                    }
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static String resolveEntity(String name) {
        if (name.isEmpty()) {
            return null;
        }
        if (name.charAt(0) == '#') {
            try {
                int code;
                if (name.length() > 2 && (name.charAt(1) == 'x' || name.charAt(1) == 'X')) {
                    code = Integer.parseInt(name.substring(2), 16);
                } else {
                    code = Integer.parseInt(name.substring(1));
                }
                return new String(Character.toChars(code));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return switch (name) {
            case "amp" -> "&"; //$NON-NLS-1$ //$NON-NLS-2$
            case "lt" -> "<"; //$NON-NLS-1$ //$NON-NLS-2$
            case "gt" -> ">"; //$NON-NLS-1$ //$NON-NLS-2$
            case "quot" -> "\""; //$NON-NLS-1$ //$NON-NLS-2$
            case "apos" -> "'"; //$NON-NLS-1$ //$NON-NLS-2$
            case "nbsp" -> " "; //$NON-NLS-1$ //$NON-NLS-2$
            case "ndash" -> "–"; //$NON-NLS-1$ //$NON-NLS-2$
            case "mdash" -> "—"; //$NON-NLS-1$ //$NON-NLS-2$
            case "laquo" -> "«"; //$NON-NLS-1$ //$NON-NLS-2$
            case "raquo" -> "»"; //$NON-NLS-1$ //$NON-NLS-2$
            case "hellip" -> "…"; //$NON-NLS-1$ //$NON-NLS-2$
            case "copy" -> "©"; //$NON-NLS-1$ //$NON-NLS-2$
            case "reg" -> "®"; //$NON-NLS-1$ //$NON-NLS-2$
            case "trade" -> "™"; //$NON-NLS-1$ //$NON-NLS-2$
            default -> null;
        };
    }
}
