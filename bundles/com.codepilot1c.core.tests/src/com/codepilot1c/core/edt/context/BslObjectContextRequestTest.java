/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edt.context;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import com.codepilot1c.core.edt.context.BslObjectContextRequest.MethodInclusion;
import com.codepilot1c.core.edt.context.BslObjectContextRequest.FormLayoutInclusion;

/**
 * Tests for the parameter-parsing surface of {@code bsl_object_context}.
 *
 * <p>The Request is constructed from a free-form {@code Map<String, Object>}
 * (the deferred-tool argument shape) and must apply the documented
 * defaults: methods=signatures, exports_only=false, form_layout=none,
 * callers=disabled. The defaults exist because the caller pays per
 * include section — a request with no flags should still produce a
 * useful skeletal response.</p>
 */
public class BslObjectContextRequestTest {

    // --- happy path ----------------------------------------------------------

    @Test
    public void parsesProjectAndObjectFqn() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("project_name", "AM");
        raw.put("object_fqn", "Document.SalesOrder");
        BslObjectContextRequest req = BslObjectContextRequest.fromParameters(raw);
        assertEquals("AM", req.projectName());
        assertEquals("Document.SalesOrder", req.objectFqn());
    }

    @Test
    public void defaultsApplyWhenIncludeBlockOmitted() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("project_name", "AM");
        raw.put("object_fqn", "Document.X");
        BslObjectContextRequest req = BslObjectContextRequest.fromParameters(raw);
        // Defaults: methods=signatures (cheap baseline), no exports filter,
        // no attributes/tabular/form_layout/callers, both module kinds included.
        assertEquals(MethodInclusion.SIGNATURES, req.methods());
        assertFalse(req.exportsOnly());
        assertFalse(req.attributes());
        assertFalse(req.tabularSections());
        assertEquals(FormLayoutInclusion.NONE, req.formLayout());
        assertFalse(req.callers());
        assertTrue(req.managerModuleMethods());
        assertTrue(req.objectModuleMethods());
        assertFalse(req.moduleDirectives());
    }

    @Test
    public void parsesNestedIncludeBlock() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("project_name", "AM");
        raw.put("object_fqn", "CommonModule.X");
        Map<String, Object> include = new HashMap<>();
        include.put("methods", "bodies");
        include.put("exports_only", Boolean.TRUE);
        include.put("attributes", Boolean.TRUE);
        include.put("tabular_sections", Boolean.TRUE);
        include.put("form_layout", "shallow");
        include.put("manager_module_methods", Boolean.FALSE);
        include.put("object_module_methods", Boolean.FALSE);
        include.put("module_directives", Boolean.TRUE);
        Map<String, Object> callers = new HashMap<>();
        callers.put("exports_only", Boolean.TRUE);
        callers.put("max_per_method", 7);
        include.put("callers", callers);
        raw.put("include", include);

        BslObjectContextRequest req = BslObjectContextRequest.fromParameters(raw);
        assertEquals(MethodInclusion.BODIES, req.methods());
        assertTrue(req.exportsOnly());
        assertTrue(req.attributes());
        assertTrue(req.tabularSections());
        assertEquals(FormLayoutInclusion.SHALLOW, req.formLayout());
        assertFalse(req.managerModuleMethods());
        assertFalse(req.objectModuleMethods());
        assertTrue(req.moduleDirectives());
        assertTrue(req.callers());
        assertTrue(req.callersExportsOnly());
        assertEquals(7, req.callersMaxPerMethod());
    }

    // --- enum coercion -------------------------------------------------------

    @Test
    public void methodsEnumIsCaseInsensitive() {
        BslObjectContextRequest req = build("Document.X", Map.of("methods", "BODIES"));
        assertEquals(MethodInclusion.BODIES, req.methods());
    }

    @Test
    public void methodsEnumNoneTurnsOffMethodSection() {
        BslObjectContextRequest req = build("Document.X", Map.of("methods", "none"));
        assertEquals(MethodInclusion.NONE, req.methods());
    }

    @Test
    public void formLayoutEnumIsCaseInsensitive() {
        BslObjectContextRequest req = build("Document.X", Map.of("form_layout", "FULL"));
        assertEquals(FormLayoutInclusion.FULL, req.formLayout());
    }

    @Test
    public void callersFalseDisablesSection() {
        BslObjectContextRequest req = build("Document.X", Map.of("callers", Boolean.FALSE));
        assertFalse(req.callers());
    }

    @Test
    public void callersAbsentDisablesSection() {
        BslObjectContextRequest req = build("Document.X", Map.of());
        assertFalse(req.callers());
        // max_per_method default is exposed so tools can render a useful
        // placeholder without an NPE.
        assertEquals(5, req.callersMaxPerMethod());
    }

    // --- error handling ------------------------------------------------------

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMissingObjectFqn() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("project_name", "AM");
        BslObjectContextRequest.fromParameters(raw);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMissingProjectName() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("object_fqn", "Document.X");
        BslObjectContextRequest.fromParameters(raw);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnknownMethodsEnum() {
        build("Document.X", Map.of("methods", "verbose"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnknownFormLayoutEnum() {
        build("Document.X", Map.of("form_layout", "deep"));
    }

    // --- helper --------------------------------------------------------------

    private BslObjectContextRequest build(String objectFqn, Map<String, Object> include) {
        Map<String, Object> raw = new HashMap<>();
        raw.put("project_name", "AM");
        raw.put("object_fqn", objectFqn);
        raw.put("include", include);
        return BslObjectContextRequest.fromParameters(raw);
    }
}
