/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.codepilot1c.core.edit.BslBoundaryGuard;

/**
 * Tests for the BSL boundary guard in {@link EditFileTool}.
 *
 * <p>The guard rejects edits that break the balance of
 * Процедура/Функция ↔ КонецПроцедуры/КонецФункции in .bsl files — the class
 * of errors that caused BF-11510 (silent overwriting of EndProcedure by a
 * fuzzy-match replacement).</p>
 */
public class EditFileToolBoundaryGuardTest {

    private static final String MODULE_BSL =
            "Процедура Первая()\n" //$NON-NLS-1$
                    + "    Сообщить(\"первая\");\n" //$NON-NLS-1$
                    + "КонецПроцедуры\n" //$NON-NLS-1$
                    + "\n" //$NON-NLS-1$
                    + "Процедура Вторая()\n" //$NON-NLS-1$
                    + "    Сообщить(\"вторая\");\n" //$NON-NLS-1$
                    + "КонецПроцедуры\n"; //$NON-NLS-1$

    @Test
    public void nonBslFileReturnsNullEvenIfUnbalanced() {
        String result = BslBoundaryGuard.validate(
                "Form.xml", //$NON-NLS-1$
                "Процедура A() КонецПроцедуры", //$NON-NLS-1$
                "Процедура A()"); // intentionally missing EndProcedure //$NON-NLS-1$
        assertNull("Non-BSL files must bypass the guard", result); //$NON-NLS-1$
    }

    @Test
    public void nullFileNameBypassesGuard() {
        assertNull(BslBoundaryGuard.validate(
                null, MODULE_BSL, "Процедура X()")); //$NON-NLS-1$
    }

    @Test
    public void balancedEditInsideBodyPasses() {
        String edited = MODULE_BSL.replace("Сообщить(\"первая\");", //$NON-NLS-1$
                "Сообщить(\"первая - обновлено\");"); //$NON-NLS-1$
        assertNull("Edit inside method body must not trigger the guard", //$NON-NLS-1$
                BslBoundaryGuard.validate("ManagerModule.bsl", MODULE_BSL, edited)); //$NON-NLS-1$
    }

    @Test
    public void deletingWholeMethodPassesWhenBalanced() {
        String edited = "Процедура Первая()\n" //$NON-NLS-1$
                + "    Сообщить(\"первая\");\n" //$NON-NLS-1$
                + "КонецПроцедуры\n"; //$NON-NLS-1$
        assertNull("Removing a full Procedure/EndProcedure pair keeps the balance", //$NON-NLS-1$
                BslBoundaryGuard.validate("ManagerModule.bsl", MODULE_BSL, edited)); //$NON-NLS-1$
    }

    @Test
    public void missingEndProcedureIsRejected() {
        // This is the BF-11510 scenario: fuzzy-match swallowed КонецПроцедуры.
        String corrupted = MODULE_BSL.replace("    Сообщить(\"первая\");\n" //$NON-NLS-1$
                + "КонецПроцедуры\n", //$NON-NLS-1$
                "    Сообщить(\"первая - обновлено\");\n"); // no EndProcedure //$NON-NLS-1$
        String err = BslBoundaryGuard.validate(
                "ManagerModule.bsl", MODULE_BSL, corrupted); //$NON-NLS-1$
        assertNotNull("Guard must reject edit that drops EndProcedure", err); //$NON-NLS-1$
        assertTrue("Error must mention boundary balance", //$NON-NLS-1$
                err.contains("баланс") || err.contains("boundary")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void extraEndProcedureIsRejected() {
        String corrupted = MODULE_BSL + "КонецПроцедуры\n"; //$NON-NLS-1$
        String err = BslBoundaryGuard.validate(
                "ManagerModule.bsl", MODULE_BSL, corrupted); //$NON-NLS-1$
        assertNotNull("Guard must reject stray EndProcedure", err); //$NON-NLS-1$
    }

    @Test
    public void englishKeywordsAreRecognized() {
        String before = "Procedure A()\nEndProcedure\n"; //$NON-NLS-1$
        String afterUnbalanced = "Procedure A()\n"; //$NON-NLS-1$
        assertNotNull("English Procedure/EndProcedure must be counted", //$NON-NLS-1$
                BslBoundaryGuard.validate("Module.bsl", before, afterUnbalanced)); //$NON-NLS-1$
    }

    @Test
    public void functionKeywordsAreRecognized() {
        String before = "Функция Сумма(А, Б)\n    Возврат А + Б;\nКонецФункции\n"; //$NON-NLS-1$
        String afterUnbalanced = "Функция Сумма(А, Б)\n    Возврат А + Б;\n"; //$NON-NLS-1$
        assertNotNull("Функция/КонецФункции must be counted", //$NON-NLS-1$
                BslBoundaryGuard.validate("Module.bsl", before, afterUnbalanced)); //$NON-NLS-1$
    }

    @Test
    public void caseInsensitiveMatching() {
        String before = "процедура low()\nконецпроцедуры\n"; //$NON-NLS-1$
        String afterUnbalanced = "процедура low()\n"; //$NON-NLS-1$
        assertNotNull("Lowercase keywords must be recognized", //$NON-NLS-1$
                BslBoundaryGuard.validate("Module.bsl", before, afterUnbalanced)); //$NON-NLS-1$
    }

    @Test
    public void annotatedMethodCountedOnce() {
        // &НаСервере annotation on a separate line must not cause double-counting.
        String before = "&НаСервере\nПроцедура A()\nКонецПроцедуры\n"; //$NON-NLS-1$
        String edited = "&НаСервере\nПроцедура A()\n    Сообщить(1);\nКонецПроцедуры\n"; //$NON-NLS-1$
        assertNull(BslBoundaryGuard.validate("Module.bsl", before, edited)); //$NON-NLS-1$
    }

    @Test
    public void commentMentioningKeywordDoesNotTriggerGuard() {
        // Comment that talks about "КонецПроцедуры" must not be counted — regex
        // anchors at line start, comments start with //.
        String before = "Процедура A()\n    // тут КонецПроцедуры упомянут в комментарии\nКонецПроцедуры\n"; //$NON-NLS-1$
        String edited = "Процедура A()\n    // тут КонецПроцедуры упомянут в комментарии\n    Сообщить(1);\nКонецПроцедуры\n"; //$NON-NLS-1$
        assertNull(BslBoundaryGuard.validate("Module.bsl", before, edited)); //$NON-NLS-1$
    }

    @Test
    public void nullBeforeOrAfterReturnsNull() {
        assertNull(BslBoundaryGuard.validate("Module.bsl", null, "Процедура A()")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(BslBoundaryGuard.validate("Module.bsl", "Процедура A()", null)); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
