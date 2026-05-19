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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.Test;

import com.codepilot1c.core.edit.BslMethodParser.MethodInfo;

/**
 * Tests for the regex-based BSL method parser used by {@code edit_file}
 * {@code mode=replaceMethod} and by the {@code definition} match-kind in
 * {@code grep}. Independent of any EDT runtime so it can run as a pure-Java
 * unit test.
 *
 * <p>Pins behaviour: bilingual recognition (Russian/English keywords),
 * directive line stays outside the method range, exported flag detection,
 * doc-comment block recognition, nested constructs do not confuse the
 * scanner.</p>
 */
public class BslMethodParserTest {

    private final BslMethodParser parser = new BslMethodParser();

    // --- parseAll -------------------------------------------------------------

    @Test
    public void parsesSinglePlainProcedure() {
        String src = ""
                + "Процедура DoStuff()\n"
                + "    A = 1;\n"
                + "КонецПроцедуры\n";
        List<MethodInfo> methods = parser.parseAll(src);
        assertEquals(1, methods.size());
        MethodInfo m = methods.get(0);
        assertEquals("DoStuff", m.name());
        assertEquals(MethodInfo.Kind.PROCEDURE, m.kind());
        assertEquals(1, m.headerLine());
        assertEquals(3, m.endLine());
        assertFalse(m.isExport());
        assertNull(m.directiveLine() == null ? null : m.directiveLine());
        assertTrue(m.docCommentLines().isEmpty());
    }

    @Test
    public void parsesEnglishKeywordFunctionWithExport() {
        String src = ""
                + "Function GetValue() Export\n"
                + "    Return 1;\n"
                + "EndFunction\n";
        List<MethodInfo> methods = parser.parseAll(src);
        assertEquals(1, methods.size());
        MethodInfo m = methods.get(0);
        assertEquals("GetValue", m.name());
        assertEquals(MethodInfo.Kind.FUNCTION, m.kind());
        assertTrue(m.isExport());
    }

    @Test
    public void parsesRussianFunctionWithExport() {
        String src = ""
                + "Функция Сумма(А, Б) Экспорт\n"
                + "    Возврат А + Б;\n"
                + "КонецФункции\n";
        List<MethodInfo> methods = parser.parseAll(src);
        assertEquals(1, methods.size());
        MethodInfo m = methods.get(0);
        assertEquals("Сумма", m.name());
        assertEquals(MethodInfo.Kind.FUNCTION, m.kind());
        assertTrue(m.isExport());
    }

    @Test
    public void parsesMultipleMethodsSeparatedByBlankLines() {
        String src = ""
                + "Процедура One()\n"
                + "КонецПроцедуры\n"
                + "\n"
                + "Процедура Two()\n"
                + "КонецПроцедуры\n";
        List<MethodInfo> methods = parser.parseAll(src);
        assertEquals(2, methods.size());
        assertEquals("One", methods.get(0).name());
        assertEquals("Two", methods.get(1).name());
        assertEquals(1, methods.get(0).headerLine());
        assertEquals(4, methods.get(1).headerLine());
    }

    @Test
    public void directiveLineIsCapturedSeparatelyFromHeader() {
        String src = ""
                + "&НаСервере\n"
                + "Процедура Calc()\n"
                + "КонецПроцедуры\n";
        List<MethodInfo> methods = parser.parseAll(src);
        assertEquals(1, methods.size());
        MethodInfo m = methods.get(0);
        assertEquals(Integer.valueOf(1), m.directiveLine());
        assertEquals(2, m.headerLine());
        assertEquals(3, m.endLine());
        assertEquals(1, m.replaceFromLine());
        assertEquals(3, m.replaceToLine());
    }

    @Test
    public void docCommentBlockAttachesToFollowingMethod() {
        String src = ""
                + "// Описание процедуры\n"
                + "// Параметры:\n"
                + "//   None\n"
                + "Процедура Doc()\n"
                + "КонецПроцедуры\n";
        List<MethodInfo> methods = parser.parseAll(src);
        assertEquals(1, methods.size());
        MethodInfo m = methods.get(0);
        assertEquals(3, m.docCommentLines().size());
        assertEquals(1, (int) m.docCommentLines().get(0));
        assertEquals(2, (int) m.docCommentLines().get(1));
        assertEquals(3, (int) m.docCommentLines().get(2));
        assertEquals("with doc-comment, replaceFromLine() points at first comment line",
                1, m.replaceFromLine());
        assertEquals(5, m.replaceToLine());
    }

    @Test
    public void docCommentBlockWithDirectiveAndExport() {
        String src = ""
                + "// Doc\n"
                + "&AtServer\n"
                + "Function F() Export\n"
                + "    Return 1;\n"
                + "EndFunction\n";
        List<MethodInfo> methods = parser.parseAll(src);
        MethodInfo m = methods.get(0);
        assertTrue(m.isExport());
        assertEquals(Integer.valueOf(2), m.directiveLine());
        assertEquals(3, m.headerLine());
        assertEquals(5, m.endLine());
        assertEquals(1, m.replaceFromLine());
        assertEquals(5, m.replaceToLine());
    }

    @Test
    public void blankLineBetweenDocCommentAndHeaderBreaksAttachment() {
        // Doc-comment separated from header by a blank line is NOT part of the method
        // (convention: tightly-coupled comment touches the header / directive).
        String src = ""
                + "// Standalone comment\n"
                + "\n"
                + "Процедура NoDoc()\n"
                + "КонецПроцедуры\n";
        List<MethodInfo> methods = parser.parseAll(src);
        MethodInfo m = methods.get(0);
        assertTrue("doc-comment must NOT attach across a blank line",
                m.docCommentLines().isEmpty());
        assertEquals(3, m.replaceFromLine());
        assertEquals(4, m.replaceToLine());
    }

    @Test
    public void unclosedMethodIsSkippedRatherThanThrown() {
        // Robustness — half-written code in playgrounds shouldn't blow up.
        String src = ""
                + "Процедура Broken()\n"
                + "    A = 1;\n";
        List<MethodInfo> methods = parser.parseAll(src);
        assertTrue("unterminated method must not produce a MethodInfo",
                methods.isEmpty());
    }

    // --- findByName -----------------------------------------------------------

    @Test
    public void findByNameIsCaseInsensitive() {
        String src = ""
                + "Процедура AlphaOne()\n"
                + "КонецПроцедуры\n"
                + "\n"
                + "Процедура BetaTwo()\n"
                + "КонецПроцедуры\n";
        Optional<MethodInfo> hit = parser.findByName(src, "alphaone");
        assertTrue(hit.isPresent());
        assertEquals("AlphaOne", hit.get().name());
    }

    @Test
    public void findByNameReturnsEmptyWhenMissing() {
        String src = "Процедура X()\nКонецПроцедуры\n";
        assertFalse(parser.findByName(src, "DoesNotExist").isPresent());
    }

    @Test
    public void findByNameReturnsFirstMatchIfDuplicated() {
        // Duplicate-name in BSL is a syntax error but the parser should not crash
        // and should return the first occurrence so downstream apply can warn.
        String src = ""
                + "Процедура Dup()\n"
                + "КонецПроцедуры\n"
                + "\n"
                + "Процедура Dup()\n"
                + "КонецПроцедуры\n";
        Optional<MethodInfo> hit = parser.findByName(src, "Dup");
        assertTrue(hit.isPresent());
        assertEquals(1, hit.get().headerLine());
    }
}
