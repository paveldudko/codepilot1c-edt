/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edt.lang;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.google.gson.Gson;

/**
 * Tests that {@link BslMethodInfo} preserves the {@code null} params list
 * so Gson drops the {@code "params"} field in compact mode — the
 * mechanism {@code bsl_list_methods compact=true} relies on.
 */
public class BslMethodInfoCompactTest {

    @Test
    public void compactModeEmitsJsonWithoutParamsField() {
        BslMethodInfo info = new BslMethodInfo(
                "LoadFromFile", "procedure", 10, 42, true, false, false, false, //$NON-NLS-1$ //$NON-NLS-2$
                null, List.of(), null);
        assertNull("Compact info must preserve null params", info.getParams()); //$NON-NLS-1$

        String json = new Gson().toJson(info);
        assertFalse("Gson must drop the null params field", json.contains("\"params\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(json.contains("\"name\":\"LoadFromFile\"")); //$NON-NLS-1$
        assertTrue(json.contains("\"startLine\":10")); //$NON-NLS-1$
    }

    @Test
    public void fullModeIncludesParamsArray() {
        BslMethodInfo info = new BslMethodInfo(
                "Sum", "function", 1, 5, true, false, false, false, //$NON-NLS-1$ //$NON-NLS-2$
                List.of(new BslMethodParamInfo("A", true, null), //$NON-NLS-1$
                        new BslMethodParamInfo("B", true, "0")), //$NON-NLS-1$ //$NON-NLS-2$
                List.of(), null);
        assertNotNull(info.getParams());
        String json = new Gson().toJson(info);
        assertTrue("Full mode must include params", json.contains("\"params\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(json.contains("\"A\"")); //$NON-NLS-1$
        assertTrue(json.contains("\"B\"")); //$NON-NLS-1$
    }

    @Test
    public void fullModeWithZeroParamsStillEmitsEmptyArray() {
        BslMethodInfo info = new BslMethodInfo(
                "Ping", "procedure", 1, 2, false, false, false, false, //$NON-NLS-1$ //$NON-NLS-2$
                List.<BslMethodParamInfo>of(), List.of(), null);
        String json = new Gson().toJson(info);
        assertTrue("Empty list must be serialized as [] (distinguishable from compact)", //$NON-NLS-1$
                json.contains("\"params\":[]")); //$NON-NLS-1$
    }

    @Test
    public void getParamsReturnsUnmodifiableView() {
        BslMethodInfo info = new BslMethodInfo(
                "X", "procedure", 1, 2, false, false, false, false, //$NON-NLS-1$ //$NON-NLS-2$
                List.of(new BslMethodParamInfo("p", true, null)), //$NON-NLS-1$
                List.of(), null);
        try {
            info.getParams().clear();
        } catch (UnsupportedOperationException expected) {
            return;
        }
        throw new AssertionError("getParams() must return an unmodifiable list"); //$NON-NLS-1$
    }
}
