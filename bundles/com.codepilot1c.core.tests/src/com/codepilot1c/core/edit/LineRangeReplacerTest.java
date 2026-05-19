/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edit;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Tests for the pure-Java line-splicing utility used by {@code edit_file}
 * {@code mode=replaceLines} / {@code mode=replaceMethod}. Splits out the
 * trickiest part of the new modes — line-ending preservation and the
 * trailing-newline edge cases — so it can be exercised in plain JUnit
 * without an Eclipse workspace.
 */
public class LineRangeReplacerTest {

    // --- single-line replace (most common case) ------------------------------

    @Test
    public void replaceSingleLineInMiddleLfSeparator() {
        String before = "a\nb\nc\nd";
        String result = LineRangeReplacer.replaceLines(before, 2, 2, "B", "\n");
        assertEquals("a\nB\nc\nd", result);
    }

    @Test
    public void replaceSingleLineInMiddleCrlfSeparator() {
        String before = "a\r\nb\r\nc\r\nd";
        String result = LineRangeReplacer.replaceLines(before, 2, 2, "B", "\r\n");
        assertEquals("a\r\nB\r\nc\r\nd", result);
    }

    @Test
    public void replaceFirstLineKeepsTrailingFile() {
        String before = "a\nb\nc";
        String result = LineRangeReplacer.replaceLines(before, 1, 1, "X", "\n");
        assertEquals("X\nb\nc", result);
    }

    @Test
    public void replaceLastLineKeepsLeadingFile() {
        String before = "a\nb\nc";
        String result = LineRangeReplacer.replaceLines(before, 3, 3, "Z", "\n");
        assertEquals("a\nb\nZ", result);
    }

    // --- multi-line replace --------------------------------------------------

    @Test
    public void replaceMultipleAdjacentLines() {
        String before = "a\nb\nc\nd\ne";
        String result = LineRangeReplacer.replaceLines(before, 2, 4, "X\nY", "\n");
        assertEquals("a\nX\nY\ne", result);
    }

    @Test
    public void replaceMultipleLinesWithSingleLine() {
        // Collapse 3 lines into 1
        String before = "a\nb\nc\nd\ne";
        String result = LineRangeReplacer.replaceLines(before, 2, 4, "ONE", "\n");
        assertEquals("a\nONE\ne", result);
    }

    @Test
    public void replaceSingleLineWithMultipleLines() {
        // Expand 1 line into 3
        String before = "a\nb\nc";
        String result = LineRangeReplacer.replaceLines(before, 2, 2, "x1\nx2\nx3", "\n");
        assertEquals("a\nx1\nx2\nx3\nc", result);
    }

    @Test
    public void replaceEntireFile() {
        String before = "a\nb\nc";
        String result = LineRangeReplacer.replaceLines(before, 1, 3, "fresh", "\n");
        assertEquals("fresh", result);
    }

    // --- newline normalisation in payload -----------------------------------

    @Test
    public void newTextWithLfSeparatorsGetsNormalisedToCrlf() {
        // Payload comes with \n, target file uses \r\n. Splicer must convert.
        String before = "a\r\nb\r\nc";
        String result = LineRangeReplacer.replaceLines(before, 2, 2, "B1\nB2", "\r\n");
        assertEquals("a\r\nB1\r\nB2\r\nc", result);
    }

    @Test
    public void newTextEndingInNewlineDoesNotProduceDoubleSeparator() {
        // If new_text already ends with the line separator, splicer must not
        // append another one when re-attaching the trailing region.
        String before = "a\nb\nc";
        String result = LineRangeReplacer.replaceLines(before, 2, 2, "B\n", "\n");
        assertEquals("a\nB\nc", result);
    }

    // --- edge cases ----------------------------------------------------------

    @Test
    public void replaceWithEmptyPayloadDeletesRange() {
        String before = "a\nb\nc\nd";
        String result = LineRangeReplacer.replaceLines(before, 2, 3, "", "\n");
        assertEquals("a\n\nd", result);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsLineFromZero() {
        LineRangeReplacer.replaceLines("a\nb", 0, 1, "X", "\n");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsLineToBelowLineFrom() {
        LineRangeReplacer.replaceLines("a\nb", 2, 1, "X", "\n");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsLineToPastEndOfFile() {
        LineRangeReplacer.replaceLines("a\nb", 1, 5, "X", "\n");
    }

    @Test
    public void singleLineFileWithoutTrailingSeparator() {
        String before = "only";
        String result = LineRangeReplacer.replaceLines(before, 1, 1, "REPLACED", "\n");
        assertEquals("REPLACED", result);
    }
}
