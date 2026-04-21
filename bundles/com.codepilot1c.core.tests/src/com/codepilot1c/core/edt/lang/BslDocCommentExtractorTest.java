/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edt.lang;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Tests for {@link BslDocCommentExtractor}.
 */
public class BslDocCommentExtractorTest {

    @Test
    public void singleLineDocComment() {
        String src =
                "// Описание процедуры\n" //$NON-NLS-1$
              + "Процедура Foo()\n" //$NON-NLS-1$
              + "КонецПроцедуры\n"; //$NON-NLS-1$
        assertEquals("// Описание процедуры", //$NON-NLS-1$
                BslDocCommentExtractor.extract(src, 2));
    }

    @Test
    public void multiLineDocCommentPreservesLeadingWhitespace() {
        String src =
                "    // Параметры:\n" //$NON-NLS-1$
              + "    //   A - Число\n" //$NON-NLS-1$
              + "    //   B - Число\n" //$NON-NLS-1$
              + "    Функция Sum(A, B)\n" //$NON-NLS-1$
              + "    КонецФункции\n"; //$NON-NLS-1$
        String doc = BslDocCommentExtractor.extract(src, 4);
        assertEquals("    // Параметры:\n    //   A - Число\n    //   B - Число", doc); //$NON-NLS-1$
    }

    @Test
    public void skipsAnnotationLineAboveMethod() {
        String src =
                "// Серверная процедура\n" //$NON-NLS-1$
              + "&НаСервере\n" //$NON-NLS-1$
              + "Процедура DoServer()\n" //$NON-NLS-1$
              + "КонецПроцедуры\n"; //$NON-NLS-1$
        assertEquals("// Серверная процедура", //$NON-NLS-1$
                BslDocCommentExtractor.extract(src, 3));
    }

    @Test
    public void skipsMultipleAnnotationLines() {
        String src =
                "// Клиент/сервер\n" //$NON-NLS-1$
              + "&НаКлиентеНаСервереБезКонтекста\n" //$NON-NLS-1$
              + "&НаКлиенте\n" //$NON-NLS-1$
              + "Процедура DoBoth()\n" //$NON-NLS-1$
              + "КонецПроцедуры\n"; //$NON-NLS-1$
        assertEquals("// Клиент/сервер", //$NON-NLS-1$
                BslDocCommentExtractor.extract(src, 4));
    }

    @Test
    public void blankLineSeparatesDocFromPreviousMethod() {
        String src =
                "// doc of Foo\n" //$NON-NLS-1$
              + "Процедура Foo()\n" //$NON-NLS-1$
              + "КонецПроцедуры\n" //$NON-NLS-1$
              + "\n" //$NON-NLS-1$
              + "// doc of Bar\n" //$NON-NLS-1$
              + "Процедура Bar()\n" //$NON-NLS-1$
              + "КонецПроцедуры\n"; //$NON-NLS-1$
        assertEquals("// doc of Bar", BslDocCommentExtractor.extract(src, 6)); //$NON-NLS-1$
    }

    @Test
    public void noDocCommentYieldsEmptyString() {
        String src =
                "Процедура Raw()\n" //$NON-NLS-1$
              + "КонецПроцедуры\n"; //$NON-NLS-1$
        assertEquals("", BslDocCommentExtractor.extract(src, 1)); //$NON-NLS-1$
    }

    @Test
    public void methodOnFirstLineHasNoDoc() {
        assertEquals("", BslDocCommentExtractor.extract("Процедура A()\n", 1)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void crlfLineEndingsNormalized() {
        String src = "// win-style\r\n" //$NON-NLS-1$
                + "Процедура W()\r\n" //$NON-NLS-1$
                + "КонецПроцедуры\r\n"; //$NON-NLS-1$
        assertEquals("// win-style", BslDocCommentExtractor.extract(src, 2)); //$NON-NLS-1$
    }

    @Test
    public void stopsAtNonCommentCode() {
        String src =
                "Перем ГлобальнаяПеременная;\n" //$NON-NLS-1$
              + "// doc\n" //$NON-NLS-1$
              + "Процедура Foo()\n" //$NON-NLS-1$
              + "КонецПроцедуры\n"; //$NON-NLS-1$
        assertEquals("// doc", BslDocCommentExtractor.extract(src, 3)); //$NON-NLS-1$
    }

    @Test
    public void stopsAtOtherMethodDeclaration() {
        // No blank between previous КонецПроцедуры and this method — must still stop.
        String src =
                "Процедура Prev()\n" //$NON-NLS-1$
              + "КонецПроцедуры\n" //$NON-NLS-1$
              + "Процедура Curr()\n" //$NON-NLS-1$
              + "КонецПроцедуры\n"; //$NON-NLS-1$
        assertEquals("", BslDocCommentExtractor.extract(src, 3)); //$NON-NLS-1$
    }

    @Test
    public void englishKeywordsSupported() {
        // Extractor is keyword-agnostic — it just walks comment lines above `methodStartLine`.
        String src =
                "// English-style doc\n" //$NON-NLS-1$
              + "Procedure Foo()\n" //$NON-NLS-1$
              + "EndProcedure\n"; //$NON-NLS-1$
        assertEquals("// English-style doc", //$NON-NLS-1$
                BslDocCommentExtractor.extract(src, 2));
    }

    @Test
    public void nullAndEmptySourceReturnEmpty() {
        assertEquals("", BslDocCommentExtractor.extract(null, 5)); //$NON-NLS-1$
        assertEquals("", BslDocCommentExtractor.extract("", 1)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void invalidLineNumberReturnsEmpty() {
        String src = "// doc\nПроцедура X()\n"; //$NON-NLS-1$
        assertEquals("", BslDocCommentExtractor.extract(src, 0)); //$NON-NLS-1$
        assertEquals("", BslDocCommentExtractor.extract(src, 999)); //$NON-NLS-1$
    }
}
