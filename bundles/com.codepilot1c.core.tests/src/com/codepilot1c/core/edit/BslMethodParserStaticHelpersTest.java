/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

/**
 * Tests for the static utility methods on {@link BslMethodParser} that the
 * {@code grep} tool relies on for {@code match_kind} filtering and
 * enclosing-method backscan. Splits these off into JUnit so the predicates
 * are exercised independently of the workspace-aware GrepTool plumbing.
 */
public class BslMethodParserStaticHelpersTest {

    // --- passesMatchKindFilter -----------------------------------------------

    @Test
    public void matchKindAnyPassesEverything() {
        assertTrue(BslMethodParser.passesMatchKindFilter("Процедура X()", "any"));
        assertTrue(BslMethodParser.passesMatchKindFilter("    Foo();", "any"));
        assertTrue(BslMethodParser.passesMatchKindFilter("// comment", "any"));
        assertTrue(BslMethodParser.passesMatchKindFilter("", "any"));
    }

    @Test
    public void matchKindNullDefaultsToAny() {
        assertTrue(BslMethodParser.passesMatchKindFilter("Процедура X()", null));
    }

    @Test
    public void matchKindDefinitionKeepsRussianHeaders() {
        assertTrue(BslMethodParser.passesMatchKindFilter("Процедура X()", "definition"));
        assertTrue(BslMethodParser.passesMatchKindFilter("Функция Y() Экспорт", "definition"));
        assertTrue(BslMethodParser.passesMatchKindFilter("  Процедура Padded()", "definition"));
    }

    @Test
    public void matchKindDefinitionKeepsEnglishHeaders() {
        assertTrue(BslMethodParser.passesMatchKindFilter("Procedure X()", "definition"));
        assertTrue(BslMethodParser.passesMatchKindFilter("Function Y() Export", "definition"));
    }

    @Test
    public void matchKindDefinitionDropsCallSites() {
        assertFalse(BslMethodParser.passesMatchKindFilter("    DoStuff();", "definition"));
        assertFalse(BslMethodParser.passesMatchKindFilter("    A = СформироватьУведомление();", "definition"));
    }

    @Test
    public void matchKindDefinitionDropsComments() {
        assertFalse(BslMethodParser.passesMatchKindFilter("// Procedure X()", "definition"));
    }

    @Test
    public void matchKindCallDropsHeaders() {
        assertFalse(BslMethodParser.passesMatchKindFilter("Процедура X()", "call"));
        assertFalse(BslMethodParser.passesMatchKindFilter("Function Y()", "call"));
    }

    @Test
    public void matchKindCallDropsBslLineComments() {
        assertFalse(BslMethodParser.passesMatchKindFilter("// some comment", "call"));
        assertFalse(BslMethodParser.passesMatchKindFilter("    // indented comment", "call"));
    }

    @Test
    public void matchKindCallKeepsActualCallSites() {
        assertTrue(BslMethodParser.passesMatchKindFilter("    DoStuff();", "call"));
        assertTrue(BslMethodParser.passesMatchKindFilter("    A = B();", "call"));
        assertTrue(BslMethodParser.passesMatchKindFilter("    Возврат СформироватьУведомление(Параметры);", "call"));
    }

    // --- findEnclosingMethodName ---------------------------------------------

    @Test
    public void enclosingMethodInsideProcedureReturnsName() {
        List<String> lines = List.of(
                "Процедура Outer()",
                "    A = 1;",
                "    B = 2;",
                "КонецПроцедуры");
        assertEquals("Outer", BslMethodParser.findEnclosingMethodName(lines, 1));
        assertEquals("Outer", BslMethodParser.findEnclosingMethodName(lines, 2));
    }

    @Test
    public void enclosingMethodOnHeaderLineReturnsItself() {
        List<String> lines = List.of(
                "Процедура Outer()",
                "КонецПроцедуры");
        assertEquals("Outer", BslMethodParser.findEnclosingMethodName(lines, 0));
    }

    @Test
    public void enclosingMethodBetweenMethodsReturnsNull() {
        List<String> lines = List.of(
                "Процедура First()",
                "КонецПроцедуры",
                "",                  // index 2 — between methods
                "Процедура Second()",
                "КонецПроцедуры");
        assertNull(BslMethodParser.findEnclosingMethodName(lines, 2));
    }

    @Test
    public void enclosingMethodBeforeAnyMethodReturnsNull() {
        List<String> lines = List.of(
                "// File-level comment",
                "",
                "Процедура First()",
                "КонецПроцедуры");
        assertNull(BslMethodParser.findEnclosingMethodName(lines, 0));
        assertNull(BslMethodParser.findEnclosingMethodName(lines, 1));
    }

    @Test
    public void enclosingMethodEnglishKeyword() {
        List<String> lines = List.of(
                "Function GetValue() Export",
                "    Return 1;",
                "EndFunction");
        assertEquals("GetValue", BslMethodParser.findEnclosingMethodName(lines, 1));
    }

    @Test
    public void enclosingMethodSecondOfTwoReturnsSecond() {
        List<String> lines = List.of(
                "Процедура First()",
                "КонецПроцедуры",
                "",
                "Процедура Second()",
                "    DoSomething();",
                "КонецПроцедуры");
        assertEquals("Second", BslMethodParser.findEnclosingMethodName(lines, 4));
    }
}
