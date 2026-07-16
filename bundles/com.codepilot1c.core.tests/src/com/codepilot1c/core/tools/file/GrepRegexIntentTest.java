/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.file;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Pins the regex-intent heuristic behind {@code grep}'s literal-search hint
 * ({@link GrepTool#looksLikeRegexIntent}). Background: codepilot1c-feedback
 * {@code 2026-07-16-grep-false-negative-on-present-method-names} (and the earlier
 * {@code 2026-07-11}) — a pipe-alternation pattern passed WITHOUT {@code regex:true} is searched as one
 * literal string (pipes included) and returns a clean "0 matches", reading as "not present" even when
 * each term is present. The heuristic drives an actionable hint on that zero-match case.
 *
 * <p>Pure logic — no Eclipse workspace — so it is unit-testable in the plain-Maven test bundle.</p>
 */
public class GrepRegexIntentTest {

    @Test
    public void alternationPipeSignalsRegexIntent() {
        assertTrue(GrepTool.looksLikeRegexIntent("ЮТ_RunExport|ЮТ_LoadReadyMirrorRows")); //$NON-NLS-1$
        assertTrue(GrepTool.looksLikeRegexIntent("A|B|C|D")); //$NON-NLS-1$
    }

    @Test
    public void anchorsQuantifiersClassesAndEscapesSignalRegexIntent() {
        assertTrue("leading ^ anchor", GrepTool.looksLikeRegexIntent("^Procedure ")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("trailing $ anchor", GrepTool.looksLikeRegexIntent("EndProcedure$")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("quantified wildcard", GrepTool.looksLikeRegexIntent("Foo.*Bar")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("char class", GrepTool.looksLikeRegexIntent("//.*[А-Яа-я]")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("\\b word boundary", GrepTool.looksLikeRegexIntent("\\bKey\\s*=")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("\\d digit class", GrepTool.looksLikeRegexIntent("BF-\\d+:")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void plainIdentifiersAndFqnsDoNotSignalRegexIntent() {
        // A bare '.' is ubiquitous in FQNs — must NOT trigger the hint (else it fires on nearly every search).
        assertFalse(GrepTool.looksLikeRegexIntent("ЮТ_RunExport")); //$NON-NLS-1$
        assertFalse(GrepTool.looksLikeRegexIntent("Catalog.OutcomePaymentsTypes")); //$NON-NLS-1$
        assertFalse(GrepTool.looksLikeRegexIntent("ПриЗаписи")); //$NON-NLS-1$
        assertFalse(GrepTool.looksLikeRegexIntent("Функция(")); //$NON-NLS-1$
    }

    @Test
    public void nullOrEmptyIsNotRegexIntent() {
        assertFalse(GrepTool.looksLikeRegexIntent(null));
        assertFalse(GrepTool.looksLikeRegexIntent("")); //$NON-NLS-1$
    }
}
