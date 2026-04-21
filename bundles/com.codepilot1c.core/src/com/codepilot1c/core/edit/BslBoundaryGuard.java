/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edit;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates that a BSL edit does not break the balance of
 * Процедура/Функция ↔ КонецПроцедуры/КонецФункции.
 *
 * <p>Kept dependency-free (pure Java, no Eclipse imports) so it can be
 * unit-tested with plain JUnit.</p>
 */
public final class BslBoundaryGuard {

    // (?U) enables UNICODE_CHARACTER_CLASS so \b treats Cyrillic as word chars.
    // Without it, \b after "процедура" fails because Java's default ASCII-only
    // \w sees Cyrillic 'а' as non-word, and the trailing space is also non-word.
    private static final Pattern METHOD_OPEN = Pattern.compile(
            "(?imU)^\\s*(?:&[\\p{L}_][\\p{L}\\d_]*\\s*(?:\\([^)]*\\))?\\s*)?(Процедура|Функция|Procedure|Function)\\b"); //$NON-NLS-1$

    private static final Pattern METHOD_CLOSE = Pattern.compile(
            "(?imU)^\\s*(КонецПроцедуры|КонецФункции|EndProcedure|EndFunction)\\b"); //$NON-NLS-1$

    private BslBoundaryGuard() {
    }

    /**
     * Checks whether replacing {@code before} with {@code after} preserves
     * the balance of BSL method boundaries.
     *
     * @param fileName name of the file being edited (used to short-circuit for non-BSL files)
     * @param before   full file content before the edit
     * @param after    full file content after the edit
     * @return an error message to surface to the caller, or {@code null} if the edit is safe
     *         (including the case where the file is not a .bsl file or any argument is null)
     */
    public static String validate(String fileName, String before, String after) {
        if (before == null || after == null) {
            return null;
        }
        if (fileName == null || !fileName.toLowerCase(Locale.ROOT).endsWith(".bsl")) { //$NON-NLS-1$
            return null;
        }

        int openBefore = countMatches(METHOD_OPEN, before);
        int closeBefore = countMatches(METHOD_CLOSE, before);
        int openAfter = countMatches(METHOD_OPEN, after);
        int closeAfter = countMatches(METHOD_CLOSE, after);

        int deltaOpen = openAfter - openBefore;
        int deltaClose = closeAfter - closeBefore;

        if (deltaOpen != deltaClose) {
            return String.format(
                    "❌ BSL boundary guard: edit отклонён — нарушен баланс границ методов.%n" //$NON-NLS-1$
                            + "  Процедура/Функция: %+d (было %d → стало %d)%n" //$NON-NLS-1$
                            + "  КонецПроцедуры/КонецФункции: %+d (было %d → стало %d)%n" //$NON-NLS-1$
                            + "Скорее всего, fuzzy-поиск зацепил соседний метод. " //$NON-NLS-1$
                            + "Попробуйте более уникальный old_text (добавьте строку сигнатуры), " //$NON-NLS-1$
                            + "предпросмотр через dry_run=true, " //$NON-NLS-1$
                            + "либо обход: skip_bsl_boundary_guard=true (опасно) или write_module_source.", //$NON-NLS-1$
                    deltaOpen, openBefore, openAfter, deltaClose, closeBefore, closeAfter);
        }
        if (openAfter != closeAfter) {
            return String.format(
                    "❌ BSL boundary guard: после edit-а %d объявлений методов vs %d закрытий. Edit отклонён.", //$NON-NLS-1$
                    openAfter, closeAfter);
        }
        return null;
    }

    /** Counts Процедура/Функция/Procedure/Function openings in the given text. */
    public static int countOpenings(String text) {
        return text == null ? 0 : countMatches(METHOD_OPEN, text);
    }

    /** Counts КонецПроцедуры/КонецФункции/EndProcedure/EndFunction closings in the given text. */
    public static int countClosings(String text) {
        return text == null ? 0 : countMatches(METHOD_CLOSE, text);
    }

    private static int countMatches(Pattern pattern, String text) {
        int count = 0;
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            count++;
        }
        return count;
    }
}
