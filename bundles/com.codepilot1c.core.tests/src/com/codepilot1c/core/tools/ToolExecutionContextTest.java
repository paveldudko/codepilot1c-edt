/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Set;
import java.util.function.Predicate;

import org.junit.After;
import org.junit.Test;

/** Unit tests for the request-scoped endpoint visibility context. */
public class ToolExecutionContextTest {

    @After
    public void tearDown() {
        ToolExecutionContext.clearEndpointToolVisibility();
    }

    @Test
    public void noContextByDefault() {
        assertNull(ToolExecutionContext.endpointToolVisibility());
    }

    @Test
    public void setExposesTheEndpointPredicate() {
        Set<String> allowed = Set.of("qa_run", "get_diagnostics"); //$NON-NLS-1$ //$NON-NLS-2$
        ToolExecutionContext.setEndpointToolVisibility(allowed::contains);
        Predicate<String> visible = ToolExecutionContext.endpointToolVisibility();
        assertTrue(visible.test("qa_run")); //$NON-NLS-1$
        assertFalse(visible.test("create_metadata")); //$NON-NLS-1$
    }

    @Test
    public void clearRemovesTheContext() {
        ToolExecutionContext.setEndpointToolVisibility(name -> true);
        ToolExecutionContext.clearEndpointToolVisibility();
        assertNull(ToolExecutionContext.endpointToolVisibility());
    }
}
