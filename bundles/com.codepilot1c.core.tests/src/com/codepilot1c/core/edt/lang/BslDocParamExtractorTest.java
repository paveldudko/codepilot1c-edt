/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edt.lang;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

/**
 * Tests for {@link BslDocParamExtractor}.
 */
public class BslDocParamExtractorTest {

    @Test
    public void singleEnglishParamType() {
        String src = ""
                + "// Description\n"
                + "//\n"
                + "// Parameters:\n"
                + "//   Date - Date - the alert date\n"
                + "//   Text - String - message body\n"
                + "//\n"
                + "Procedure WriteAlert(Date, Text) Export\n"
                + "EndProcedure\n";
        assertEquals(List.of("Date"), BslDocParamExtractor.findParamTypes(src, 7, "Date")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(List.of("String"), BslDocParamExtractor.findParamTypes(src, 7, "Text")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void russianParamsHeaderAndNames() {
        // NB: "Параметры" header, Cyrillic param name.
        String src = ""
                + "// \u041E\u043F\u0438\u0441\u0430\u043D\u0438\u0435\n"
                + "//\n"
                + "// \u041F\u0430\u0440\u0430\u043C\u0435\u0442\u0440\u044B:\n"
                + "//   \u0414\u0430\u0442\u0430 - \u0414\u0430\u0442\u0430 - \u0434\u0430\u0442\u0430\n"
                + "//\n"
                + "\u041F\u0440\u043E\u0446\u0435\u0434\u0443\u0440\u0430 \u0422\u0435\u0441\u0442(\u0414\u0430\u0442\u0430)\n"
                + "\u041A\u043E\u043D\u0435\u0446\u041F\u0440\u043E\u0446\u0435\u0434\u0443\u0440\u044B\n";
        List<String> types = BslDocParamExtractor.findParamTypes(src, 6,
                "\u0414\u0430\u0442\u0430"); //$NON-NLS-1$
        assertEquals(List.of("\u0414\u0430\u0442\u0430"), types); //$NON-NLS-1$
    }

    @Test
    public void unionOfTypesSplitByComma() {
        String src = ""
                + "// Parameters:\n"
                + "//   Value - String, Number, Undefined - any of these\n"
                + "//\n"
                + "Procedure P(Value)\n"
                + "EndProcedure\n";
        assertEquals(List.of("String", "Number", "Undefined"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                BslDocParamExtractor.findParamTypes(src, 4, "Value")); //$NON-NLS-1$
    }

    @Test
    public void qualifiedPlatformType() {
        String src = ""
                + "// Parameters:\n"
                + "//   Ref - CatalogRef.Users - user reference\n"
                + "Procedure P(Ref)\n"
                + "EndProcedure\n";
        assertEquals(List.of("CatalogRef.Users"), //$NON-NLS-1$
                BslDocParamExtractor.findParamTypes(src, 3, "Ref")); //$NON-NLS-1$
    }

    @Test
    public void paramNameLookupIsCaseInsensitive() {
        String src = ""
                + "// Parameters:\n"
                + "//   DATE - Date - foo\n"
                + "Procedure P(DATE)\n"
                + "EndProcedure\n";
        assertEquals(List.of("Date"), //$NON-NLS-1$
                BslDocParamExtractor.findParamTypes(src, 3, "date")); //$NON-NLS-1$
    }

    @Test
    public void stopsAtReturnsSection() {
        String src = ""
                + "// Parameters:\n"
                + "//   X - Number - input\n"
                + "// Returns:\n"
                + "//   Y - Number - should NOT match as a parameter\n"
                + "Function F(X)\n"
                + "EndFunction\n";
        assertTrue("Y must not be treated as a parameter", //$NON-NLS-1$
                BslDocParamExtractor.findParamTypes(src, 5, "Y").isEmpty()); //$NON-NLS-1$
        assertEquals(List.of("Number"), //$NON-NLS-1$
                BslDocParamExtractor.findParamTypes(src, 5, "X")); //$NON-NLS-1$
    }

    @Test
    public void stopsAtBlankLineAfterParamBlock() {
        String src = ""
                + "// Parameters:\n"
                + "//   X - Number - input\n"
                + "//\n"
                + "//   Y - String - should be outside the block\n"
                + "Function F(X, Y)\n"
                + "EndFunction\n";
        assertEquals(List.of("Number"), //$NON-NLS-1$
                BslDocParamExtractor.findParamTypes(src, 5, "X")); //$NON-NLS-1$
        assertTrue("Y after blank line is outside Parameters block", //$NON-NLS-1$
                BslDocParamExtractor.findParamTypes(src, 5, "Y").isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void missingParametersSectionYieldsEmpty() {
        String src = ""
                + "// Just a description.\n"
                + "Procedure P(X)\n"
                + "EndProcedure\n";
        assertTrue(BslDocParamExtractor.findParamTypes(src, 2, "X").isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void paramNotListedYieldsEmpty() {
        String src = ""
                + "// Parameters:\n"
                + "//   X - Number - input\n"
                + "Procedure P(X, Y)\n"
                + "EndProcedure\n";
        assertTrue(BslDocParamExtractor.findParamTypes(src, 3, "Y").isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void nullAndEmptyInputsReturnEmpty() {
        assertTrue(BslDocParamExtractor.findParamTypes(null, 1, "X").isEmpty()); //$NON-NLS-1$
        assertTrue(BslDocParamExtractor.findParamTypes("// Parameters:\n", 1, null).isEmpty()); //$NON-NLS-1$
        assertTrue(BslDocParamExtractor.findParamTypes("// Parameters:\n", 1, "").isEmpty()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void typeWithoutDescriptionDash() {
        // "Name - Type" — no trailing " - description"
        String src = ""
                + "// Parameters:\n"
                + "//   X - Number\n"
                + "Procedure P(X)\n"
                + "EndProcedure\n";
        assertEquals(List.of("Number"), //$NON-NLS-1$
                BslDocParamExtractor.findParamTypes(src, 3, "X")); //$NON-NLS-1$
    }
}
