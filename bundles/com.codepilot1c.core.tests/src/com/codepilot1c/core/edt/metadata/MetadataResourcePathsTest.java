/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Behaviour of the owner-path guard that create_form's materialization wait depends on.
 *
 * <p>The case that matters is the FQN-shaped string: it is the value the project-relative
 * conversion really returns for a base-configuration top object, and letting it through as a
 * "resolved path" is what made create_form report FORM_MATERIALIZATION_TIMEOUT on a successful
 * mutation (live 2026-07-29).</p>
 */
public class MetadataResourcePathsTest {

    @Test
    public void fqnShapedStringIsNotAnOwnerPath() {
        assertNull("an FQN is not a file path and must not suppress the caller's fallback", //$NON-NLS-1$
                MetadataResourcePaths.asOwnerMdoPath("Catalog.Catalog")); //$NON-NLS-1$
        assertNull(MetadataResourcePaths.asOwnerMdoPath("Document.Invoice")); //$NON-NLS-1$
        assertNull(MetadataResourcePaths.asOwnerMdoPath("InformationRegister.Rates.Form.RecordForm")); //$NON-NLS-1$
    }

    @Test
    public void realOwnerMdoPathIsAccepted() {
        assertEquals("src/Catalogs/Catalog/Catalog.mdo", //$NON-NLS-1$
                MetadataResourcePaths.asOwnerMdoPath("src/Catalogs/Catalog/Catalog.mdo")); //$NON-NLS-1$
    }

    @Test
    public void ownerPathIsReturnedVerbatimNotRewritten() {
        String path = "src/Documents/Invoice/Invoice.MDO"; //$NON-NLS-1$
        assertEquals("case is honoured on the way out — the workspace lookup uses this string", //$NON-NLS-1$
                path, MetadataResourcePaths.asOwnerMdoPath(path));
    }

    @Test
    public void leadingSlashAndBackslashesStillResolve() {
        assertTrue(MetadataResourcePaths.isUsableMetadataResourcePath("/src/Catalogs/Catalog/Catalog.mdo")); //$NON-NLS-1$
        assertTrue(MetadataResourcePaths.isUsableMetadataResourcePath("src\\Catalogs\\Catalog\\Catalog.mdo")); //$NON-NLS-1$
        assertEquals("src\\Catalogs\\Catalog\\Catalog.mdo", //$NON-NLS-1$
                MetadataResourcePaths.asOwnerMdoPath("src\\Catalogs\\Catalog\\Catalog.mdo")); //$NON-NLS-1$
    }

    @Test
    public void formFileIsUsableButIsNotAnOwnerMdo() {
        String formPath = "src/Catalogs/Catalog/Forms/ListForm/Form.form"; //$NON-NLS-1$
        assertTrue("a .form file is a real metadata resource", //$NON-NLS-1$
                MetadataResourcePaths.isUsableMetadataResourcePath(formPath));
        assertNull("but the owner side of a form lives in the owner's .mdo", //$NON-NLS-1$
                MetadataResourcePaths.asOwnerMdoPath(formPath));
    }

    @Test
    public void pathsOutsideSrcAreRejected() {
        assertFalse(MetadataResourcePaths.isUsableMetadataResourcePath("bin/Catalogs/Catalog/Catalog.mdo")); //$NON-NLS-1$
        assertFalse(MetadataResourcePaths.isUsableMetadataResourcePath("Catalog.mdo")); //$NON-NLS-1$
        assertNull(MetadataResourcePaths.asOwnerMdoPath("bin/Catalogs/Catalog/Catalog.mdo")); //$NON-NLS-1$
    }

    @Test
    public void blankAndNullAreRejectedWithoutThrowing() {
        assertFalse(MetadataResourcePaths.isUsableMetadataResourcePath(null));
        assertFalse(MetadataResourcePaths.isUsableMetadataResourcePath("")); //$NON-NLS-1$
        assertFalse(MetadataResourcePaths.isUsableMetadataResourcePath("   ")); //$NON-NLS-1$
        assertNull(MetadataResourcePaths.asOwnerMdoPath(null));
        assertNull(MetadataResourcePaths.asOwnerMdoPath("")); //$NON-NLS-1$
    }

    @Test
    public void extensionMustBeTheWholeSuffixNotSubstring() {
        assertFalse("a directory that merely contains .mdo in its name is not a file", //$NON-NLS-1$
                MetadataResourcePaths.isUsableMetadataResourcePath("src/Catalogs/Catalog.mdo/nested")); //$NON-NLS-1$
    }
}
