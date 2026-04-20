/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.codepilot1c.core.edit.BslProcedureMatcher;

/**
 * Tests for {@link BslProcedureMatcher}, which is what
 * {@code YaxunitAuthoringTool} uses to detect existing test procedures.
 *
 * <p>Regression guard for the RU-only parser that silently missed
 * EN-written tests and could cause duplicate generation.</p>
 */
public class YaxunitAuthoringToolPatternTest {

    @Test
    public void detectsRussianProcedure() {
        String region = "Процедура ТестОдин() Экспорт\n    // body\nКонецПроцедуры\n"; //$NON-NLS-1$
        assertEquals(List.of("ТестОдин"), BslProcedureMatcher.procedureNames(region)); //$NON-NLS-1$
    }

    @Test
    public void detectsEnglishProcedure() {
        String region = "Procedure TestOne() Export\n    // body\nEndProcedure\n"; //$NON-NLS-1$
        assertEquals(List.of("TestOne"), BslProcedureMatcher.procedureNames(region)); //$NON-NLS-1$
    }

    @Test
    public void detectsMixedSyntaxInSameRegion() {
        String region = "Процедура ТестА() Экспорт\n    // ru\nКонецПроцедуры\n\n" //$NON-NLS-1$
                + "Procedure TestB() Export\n    // en\nEndProcedure\n"; //$NON-NLS-1$
        assertEquals(List.of("ТестА", "TestB"), BslProcedureMatcher.procedureNames(region)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void detectsMultipleSameLanguage() {
        String region = "Процедура А()\nКонецПроцедуры\n\n" //$NON-NLS-1$
                + "Процедура Б()\nКонецПроцедуры\n"; //$NON-NLS-1$
        assertEquals(List.of("А", "Б"), BslProcedureMatcher.procedureNames(region)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void emptyRegionYieldsNoMatches() {
        assertTrue(BslProcedureMatcher.procedureNames("").isEmpty()); //$NON-NLS-1$
        assertTrue(BslProcedureMatcher.procedureNames(null).isEmpty());
    }

    @Test
    public void procedureNameWithDigitsAndUnderscores() {
        String region = "Procedure Test_42_v2() Export\nEndProcedure\n"; //$NON-NLS-1$
        assertEquals(List.of("Test_42_v2"), BslProcedureMatcher.procedureNames(region)); //$NON-NLS-1$
    }
}
