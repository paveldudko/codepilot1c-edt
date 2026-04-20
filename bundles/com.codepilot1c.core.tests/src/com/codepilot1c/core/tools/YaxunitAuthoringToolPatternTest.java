/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;

import org.junit.Test;

/**
 * Tests for {@link YaxunitAuthoringTool#PROCEDURE_PATTERN}.
 *
 * <p>Guarantees the regex detects existing YAxUnit test procedures in both
 * Russian and English BSL syntax. Regression test for RU-only parser that
 * silently missed EN-written tests and could cause duplicate generation.</p>
 */
public class YaxunitAuthoringToolPatternTest {

    private static List<String> extractNames(String region) {
        List<String> names = new ArrayList<>();
        Matcher m = YaxunitAuthoringTool.PROCEDURE_PATTERN.matcher(region);
        while (m.find()) {
            names.add(m.group(1));
        }
        return names;
    }

    @Test
    public void detectsRussianProcedure() {
        String region = "Процедура ТестОдин() Экспорт\n    // body\nКонецПроцедуры\n"; //$NON-NLS-1$
        assertEquals(List.of("ТестОдин"), extractNames(region)); //$NON-NLS-1$
    }

    @Test
    public void detectsEnglishProcedure() {
        String region = "Procedure TestOne() Export\n    // body\nEndProcedure\n"; //$NON-NLS-1$
        assertEquals(List.of("TestOne"), extractNames(region)); //$NON-NLS-1$
    }

    @Test
    public void detectsMixedSyntaxInSameRegion() {
        String region = "Процедура ТестА() Экспорт\n    // ru\nКонецПроцедуры\n\n" //$NON-NLS-1$
                + "Procedure TestB() Export\n    // en\nEndProcedure\n"; //$NON-NLS-1$
        assertEquals(List.of("ТестА", "TestB"), extractNames(region)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void detectsMultipleSameLanguage() {
        String region = "Процедура А()\nКонецПроцедуры\n\n" //$NON-NLS-1$
                + "Процедура Б()\nКонецПроцедуры\n"; //$NON-NLS-1$
        assertEquals(List.of("А", "Б"), extractNames(region)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void emptyRegionYieldsNoMatches() {
        assertTrue(extractNames("").isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void procedureNameWithDigitsAndUnderscores() {
        String region = "Procedure Test_42_v2() Export\nEndProcedure\n"; //$NON-NLS-1$
        assertEquals(List.of("Test_42_v2"), extractNames(region)); //$NON-NLS-1$
    }
}
