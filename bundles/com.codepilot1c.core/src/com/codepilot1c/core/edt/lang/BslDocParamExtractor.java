/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edt.lang;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts parameter type names from a BSL doc comment following the 1C
 * platform convention:
 *
 * <pre>
 * // Description.
 * //
 * // Parameters:         (or "Параметры:")
 * //   ParamA - Type - description
 * //   ParamB - Type1, Type2, Undefined - description spanning
 * //       continuation lines that are ignored
 * //
 * // Returns:            (or "Возвращаемое значение:")
 * //   Type - description
 * </pre>
 *
 * <p>Used as a last-resort fallback for {@code bsl_type_at_position} on a
 * {@code FormalParam} when EDT's TypesComputer returns no types.</p>
 *
 * <p>Pure Java / no Eclipse imports so it can be unit-tested directly.</p>
 */
public final class BslDocParamExtractor {

    // Name - first non-whitespace token followed by a hyphen. Supports
    // Cyrillic and Latin, digits, underscores. Trailing hyphen is the
    // separator before the type part; we do not require it to be
    // surrounded by spaces (some 1C code uses "Name-Type").
    private static final Pattern PARAM_LINE = Pattern.compile(
            "^\\s*([\\p{L}_][\\p{L}\\p{N}_]*)\\s*-\\s*(.+)$"); //$NON-NLS-1$

    private static final Pattern PARAMETERS_HEADER = Pattern.compile(
            "^(Parameters|\u041F\u0430\u0440\u0430\u043C\u0435\u0442\u0440\u044B)\\s*:\\s*$", //$NON-NLS-1$
            Pattern.CASE_INSENSITIVE);

    // Any other section header stops the Parameters block. RU: Возвращаемое
    // значение, Пример, См. также. EN: Returns, Example, See also.
    private static final Pattern SECTION_BOUNDARY = Pattern.compile(
            "^(Returns|Example|See|\u0412\u043E\u0437\u0432\u0440\u0430\u0449\u0430\u0435\u043C\u043E\u0435|" //$NON-NLS-1$
                    + "\u041F\u0440\u0438\u043C\u0435\u0440|\u0421\u043C\\.)\\b", //$NON-NLS-1$
            Pattern.CASE_INSENSITIVE);

    private BslDocParamExtractor() {
    }

    /**
     * Returns the list of type names declared for {@code paramName} in the
     * doc comment that immediately precedes the method at {@code methodStartLine}.
     *
     * <p>Multiple types are returned when the doc declares a union — e.g.
     * {@code String, Number, Undefined} yields three elements. The original
     * ordering is preserved.</p>
     *
     * @param source          full BSL module source
     * @param methodStartLine 1-based line where the method declaration starts
     * @param paramName       formal parameter name to look up (case-insensitive)
     * @return type names (possibly empty); never {@code null}
     */
    public static List<String> findParamTypes(String source, int methodStartLine, String paramName) {
        if (source == null || paramName == null || paramName.isBlank()) {
            return List.of();
        }
        String doc = BslDocCommentExtractor.extract(source, methodStartLine);
        if (doc == null || doc.isBlank()) {
            return List.of();
        }

        // Strip the "// " / "//" prefix so regexes operate on payload text.
        List<String> cleaned = new ArrayList<>();
        for (String raw : doc.split("\n", -1)) { //$NON-NLS-1$
            String trimmed = raw.trim();
            if (trimmed.startsWith("//")) { //$NON-NLS-1$
                trimmed = trimmed.substring(2);
            }
            cleaned.add(trimmed.trim());
        }

        boolean inParameters = false;
        for (String line : cleaned) {
            if (PARAMETERS_HEADER.matcher(line).matches()) {
                inParameters = true;
                continue;
            }
            if (!inParameters) {
                continue;
            }
            if (line.isEmpty() || SECTION_BOUNDARY.matcher(line).find()) {
                break;
            }
            Matcher m = PARAM_LINE.matcher(line);
            if (!m.matches()) {
                continue;
            }
            String name = m.group(1);
            if (!name.equalsIgnoreCase(paramName)) {
                continue;
            }
            String typeBlob = m.group(2).trim();
            return splitTypes(trimDescriptionTail(typeBlob));
        }
        return List.of();
    }

    /** Drops the description tail ("Type - desc") keeping only the type part. */
    private static String trimDescriptionTail(String blob) {
        int dashIdx = blob.indexOf(" - "); //$NON-NLS-1$
        return dashIdx > 0 ? blob.substring(0, dashIdx).trim() : blob;
    }

    /** Splits a union type blob like "String, Number, Undefined" into items. */
    private static List<String> splitTypes(String blob) {
        List<String> result = new ArrayList<>();
        for (String part : blob.split(",")) { //$NON-NLS-1$
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }
}
