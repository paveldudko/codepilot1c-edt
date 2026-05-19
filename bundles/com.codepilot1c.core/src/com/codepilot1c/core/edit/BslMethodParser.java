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
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight regex-based parser for BSL procedures/functions. Used by
 * {@code edit_file} {@code mode=replaceMethod} and by the {@code definition}
 * grep match-kind. Independent from any EDT runtime.
 *
 * <p>The parser does not validate BSL syntax. It locates method headers,
 * pairs them with the corresponding terminator, and exposes the inclusive
 * line range plus a "wide" range that captures the preceding directive
 * line and any tightly-coupled doc-comment block.</p>
 */
public class BslMethodParser {

    private static final Pattern HEADER_PATTERN = Pattern.compile(
            "^\\s*(Процедура|Procedure|Функция|Function)\\s+([\\p{L}\\p{N}_]+)\\s*\\(",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern END_PATTERN = Pattern.compile(
            "^\\s*(КонецПроцедуры|EndProcedure|КонецФункции|EndFunction)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern EXPORT_PATTERN = Pattern.compile(
            "\\b(Экспорт|Export)\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern DIRECTIVE_PATTERN = Pattern.compile(
            "^\\s*&[\\p{L}_][\\p{L}\\p{N}_]*");

    /**
     * One parsed method.
     *
     * @param name            method identifier as written in the source
     * @param kind            PROCEDURE or FUNCTION
     * @param isExport        true if the header line contains the Export modifier
     * @param headerLine      1-based line number of the {@code Процедура / Function} line
     * @param endLine         1-based line number of the matching terminator
     * @param directiveLine   1-based line number of an immediately-preceding
     *                        compiler directive ({@code &НаСервере} etc.), or
     *                        {@code null} if no directive
     * @param docCommentLines 1-based line numbers of the contiguous comment
     *                        block immediately preceding the directive/header
     *                        (empty if no doc-comment is attached)
     */
    public record MethodInfo(
            String name,
            Kind kind,
            boolean isExport,
            int headerLine,
            int endLine,
            Integer directiveLine,
            List<Integer> docCommentLines) {

        public enum Kind { PROCEDURE, FUNCTION }

        /**
         * Inclusive lower bound of the line range a "replaceMethod" operation
         * must overwrite to atomically swap doc-comment + directive + header
         * + body + terminator.
         */
        public int replaceFromLine() {
            if (!docCommentLines.isEmpty()) {
                return docCommentLines.get(0);
            }
            if (directiveLine != null) {
                return directiveLine;
            }
            return headerLine;
        }

        /** Inclusive upper bound — always the terminator line. */
        public int replaceToLine() {
            return endLine;
        }
    }

    /** Parses every method in {@code source}. Half-written methods are skipped. */
    public List<MethodInfo> parseAll(String source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyList();
        }
        String[] lines = splitLines(source);
        List<MethodInfo> out = new ArrayList<>();
        int i = 0;
        while (i < lines.length) {
            Matcher header = HEADER_PATTERN.matcher(lines[i]);
            if (!header.find()) {
                i++;
                continue;
            }
            int headerLineIndex = i;
            int endLineIndex = -1;
            int j = i + 1;
            while (j < lines.length) {
                if (END_PATTERN.matcher(lines[j]).find()) {
                    endLineIndex = j;
                    break;
                }
                j++;
            }
            if (endLineIndex < 0) {
                // Unterminated: drop and resume scanning past the header.
                i = headerLineIndex + 1;
                continue;
            }
            String keyword = header.group(1);
            String name = header.group(2);
            MethodInfo.Kind kind = keyword.equalsIgnoreCase("Функция")
                    || keyword.equalsIgnoreCase("Function")
                    ? MethodInfo.Kind.FUNCTION
                    : MethodInfo.Kind.PROCEDURE;
            boolean isExport = EXPORT_PATTERN.matcher(lines[headerLineIndex]).find();

            Integer directiveLine = null;
            int probe = headerLineIndex - 1;
            if (probe >= 0 && DIRECTIVE_PATTERN.matcher(lines[probe]).find()) {
                directiveLine = probe + 1; // 1-based
                probe--;
            }

            List<Integer> docCommentLines = new ArrayList<>();
            while (probe >= 0) {
                String trimmed = lines[probe].trim();
                if (trimmed.startsWith("//")) {
                    docCommentLines.add(probe + 1); // 1-based
                    probe--;
                } else {
                    break;
                }
            }
            Collections.reverse(docCommentLines);

            out.add(new MethodInfo(
                    name,
                    kind,
                    isExport,
                    headerLineIndex + 1,
                    endLineIndex + 1,
                    directiveLine,
                    Collections.unmodifiableList(docCommentLines)));
            i = endLineIndex + 1;
        }
        return out;
    }

    /** Case-insensitive lookup by method name. Returns first occurrence. */
    public Optional<MethodInfo> findByName(String source, String name) {
        if (name == null) {
            return Optional.empty();
        }
        String needle = name.toLowerCase(Locale.ROOT);
        for (MethodInfo m : parseAll(source)) {
            if (m.name().toLowerCase(Locale.ROOT).equals(needle)) {
                return Optional.of(m);
            }
        }
        return Optional.empty();
    }

    /**
     * Returns whether {@code line} looks like a procedure/function header.
     * Cheap predicate used by {@code grep} {@code match_kind=definition}.
     */
    public static boolean isHeaderLine(String line) {
        return line != null && HEADER_PATTERN.matcher(line).find();
    }

    private static String[] splitLines(String text) {
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.indexOf('\n') < 0) {
            return new String[] { normalized };
        }
        return normalized.split("\n", -1);
    }
}
