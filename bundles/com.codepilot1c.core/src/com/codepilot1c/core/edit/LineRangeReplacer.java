/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edit;

/**
 * Pure-Java line-range splicing helper used by {@code edit_file}
 * {@code mode=replaceLines} / {@code mode=replaceMethod}.
 *
 * <p>Pulled out of {@code EditFileTool} so the trickiest part of the new
 * modes — line-ending preservation and the "trailing-newline already
 * present in payload" edge case — can be exercised in plain JUnit
 * without an Eclipse workspace.</p>
 */
public final class LineRangeReplacer {

    private LineRangeReplacer() {
        // static utility
    }

    /**
     * Replaces the 1-based inclusive line range {@code [lineFrom..lineTo]}
     * inside {@code content} with {@code newText}, normalising payload line
     * endings to {@code lineSeparator}.
     *
     * @throws IllegalArgumentException for out-of-range or malformed inputs
     */
    public static String replaceLines(
            String content,
            int lineFrom,
            int lineTo,
            String newText,
            String lineSeparator) {
        if (lineFrom < 1) {
            throw new IllegalArgumentException("line_from must be >= 1, got " + lineFrom);
        }
        if (lineTo < lineFrom) {
            throw new IllegalArgumentException(
                    "line_to (" + lineTo + ") must be >= line_from (" + lineFrom + ")");
        }
        if (newText == null) {
            newText = "";
        }
        if (lineSeparator == null || lineSeparator.isEmpty()) {
            lineSeparator = "\n";
        }
        String[] lines = (content == null ? "" : content).split("\\r\\n|\\r|\\n", -1);
        if (lineTo > lines.length) {
            throw new IllegalArgumentException(
                    "line_to=" + lineTo + " is past end of file (" + lines.length + " lines)");
        }

        String normalisedPayload = normaliseLineEndings(newText, lineSeparator);
        boolean payloadEndsWithSep = normalisedPayload.endsWith(lineSeparator);

        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lineFrom - 1; i++) {
            out.append(lines[i]);
            if (i < lines.length - 1) {
                out.append(lineSeparator);
            }
        }
        out.append(normalisedPayload);
        if (lineTo < lines.length && !payloadEndsWithSep) {
            out.append(lineSeparator);
        }
        for (int i = lineTo; i < lines.length; i++) {
            out.append(lines[i]);
            if (i < lines.length - 1) {
                out.append(lineSeparator);
            }
        }
        return out.toString();
    }

    private static String normaliseLineEndings(String text, String lineSeparator) {
        if (text.isEmpty()) {
            return text;
        }
        String unified = text.replace("\r\n", "\n").replace('\r', '\n');
        if ("\n".equals(lineSeparator)) {
            return unified;
        }
        return unified.replace("\n", lineSeparator);
    }
}
