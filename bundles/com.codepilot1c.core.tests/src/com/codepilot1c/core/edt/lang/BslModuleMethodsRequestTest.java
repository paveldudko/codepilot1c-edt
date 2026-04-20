/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edt.lang;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import com.codepilot1c.core.edt.ast.EdtAstException;

/**
 * Tests for {@link BslModuleMethodsRequest#fromParameters(Map)} and
 * {@code validate()}, covering the pagination / compact-mode parameters
 * the {@code bsl_list_methods} tool exposes.
 */
public class BslModuleMethodsRequestTest {

    private static Map<String, Object> baseParams() {
        Map<String, Object> p = new HashMap<>();
        p.put("projectName", "Demo"); //$NON-NLS-1$ //$NON-NLS-2$
        p.put("filePath", "CommonModules/Mod/Module.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
        return p;
    }

    @Test
    public void defaultsWhenOptionalParamsAbsent() {
        BslModuleMethodsRequest req = BslModuleMethodsRequest.fromParameters(baseParams());
        assertEquals("Demo", req.getProjectName()); //$NON-NLS-1$
        assertEquals(100, req.getLimit());
        assertEquals(0, req.getOffset());
        assertEquals("any", req.normalizedKind()); //$NON-NLS-1$
        assertEquals("", req.normalizedNameContains()); //$NON-NLS-1$
        assertFalse("compact defaults to false (full output)", req.isCompact()); //$NON-NLS-1$
    }

    @Test
    public void limitAndOffsetParsedFromNumber() {
        Map<String, Object> p = baseParams();
        p.put("limit", 25); //$NON-NLS-1$
        p.put("offset", 50); //$NON-NLS-1$
        BslModuleMethodsRequest req = BslModuleMethodsRequest.fromParameters(p);
        assertEquals(25, req.getLimit());
        assertEquals(50, req.getOffset());
    }

    @Test
    public void limitAndOffsetParsedFromString() {
        // JSON clients may pass numeric strings — tolerated.
        Map<String, Object> p = baseParams();
        p.put("limit", "30"); //$NON-NLS-1$ //$NON-NLS-2$
        p.put("offset", "15"); //$NON-NLS-1$ //$NON-NLS-2$
        BslModuleMethodsRequest req = BslModuleMethodsRequest.fromParameters(p);
        assertEquals(30, req.getLimit());
        assertEquals(15, req.getOffset());
    }

    @Test
    public void compactParsedFromBoolean() {
        Map<String, Object> p = baseParams();
        p.put("compact", true); //$NON-NLS-1$
        assertTrue(BslModuleMethodsRequest.fromParameters(p).isCompact());
    }

    @Test
    public void compactParsedFromString() {
        Map<String, Object> p = baseParams();
        p.put("compact", "true"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(BslModuleMethodsRequest.fromParameters(p).isCompact());
        p.put("compact", "false"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(BslModuleMethodsRequest.fromParameters(p).isCompact());
    }

    @Test
    public void nameContainsNormalizedToLowercase() {
        Map<String, Object> p = baseParams();
        p.put("name_contains", "  LoadFROMFile  "); //$NON-NLS-1$ //$NON-NLS-2$
        BslModuleMethodsRequest req = BslModuleMethodsRequest.fromParameters(p);
        assertEquals("loadfromfile", req.normalizedNameContains()); //$NON-NLS-1$
    }

    @Test
    public void validateRejectsEmptyProject() {
        Map<String, Object> p = baseParams();
        p.put("projectName", ""); //$NON-NLS-1$ //$NON-NLS-2$
        BslModuleMethodsRequest req = BslModuleMethodsRequest.fromParameters(p);
        assertThrows(EdtAstException.class, req::validate);
    }

    @Test
    public void validateRejectsLimitOutOfBounds() {
        Map<String, Object> p = baseParams();
        p.put("limit", 0); //$NON-NLS-1$
        assertThrows(EdtAstException.class,
                () -> BslModuleMethodsRequest.fromParameters(p).validate());
        p.put("limit", 501); //$NON-NLS-1$
        assertThrows(EdtAstException.class,
                () -> BslModuleMethodsRequest.fromParameters(p).validate());
    }

    @Test
    public void validateRejectsNegativeOffset() {
        Map<String, Object> p = baseParams();
        p.put("offset", -1); //$NON-NLS-1$
        assertThrows(EdtAstException.class,
                () -> BslModuleMethodsRequest.fromParameters(p).validate());
    }

    @Test
    public void validateRejectsUnknownKind() {
        Map<String, Object> p = baseParams();
        p.put("kind", "method"); //$NON-NLS-1$ //$NON-NLS-2$
        assertThrows(EdtAstException.class,
                () -> BslModuleMethodsRequest.fromParameters(p).validate());
    }

    @Test
    public void validateAcceptsBoundaryValues() {
        Map<String, Object> p = baseParams();
        p.put("limit", 500); //$NON-NLS-1$
        p.put("offset", 0); //$NON-NLS-1$
        BslModuleMethodsRequest req = BslModuleMethodsRequest.fromParameters(p);
        req.validate(); // must not throw
    }
}
