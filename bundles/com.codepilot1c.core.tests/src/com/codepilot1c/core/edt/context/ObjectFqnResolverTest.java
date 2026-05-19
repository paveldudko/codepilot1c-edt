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

import java.util.List;
import java.util.Optional;

import org.junit.Test;

import com.codepilot1c.core.edt.context.ObjectFqnResolver.ObjectKind;
import com.codepilot1c.core.edt.context.ObjectFqnResolver.ParsedFqn;

/**
 * Tests for the pure-Java FQN parser + module-path resolver used by
 * {@code bsl_object_context}.
 *
 * <p>The parser knows the EDT metadata-object kinds (Document, Catalog,
 * CommonModule, etc.) and the BSL module file conventions per kind
 * (ObjectModule, ManagerModule, RecordSetModule, Module). It is
 * independent of any EDT runtime so it can be exercised in plain JUnit.</p>
 */
public class ObjectFqnResolverTest {

    private final ObjectFqnResolver resolver = new ObjectFqnResolver();

    // --- parse ---------------------------------------------------------------

    @Test
    public void parsesPlainEnglishDocumentFqn() {
        Optional<ParsedFqn> p = resolver.parse("Document.SalesOrder");
        assertTrue(p.isPresent());
        assertEquals(ObjectKind.DOCUMENT, p.get().kind());
        assertEquals("SalesOrder", p.get().name());
    }

    @Test
    public void parsesCyrillicCommonModuleFqn() {
        Optional<ParsedFqn> p = resolver.parse("CommonModule.ОбщегоНазначения");
        assertTrue(p.isPresent());
        assertEquals(ObjectKind.COMMON_MODULE, p.get().kind());
        assertEquals("ОбщегоНазначения", p.get().name());
    }

    @Test
    public void parsesEveryKnownKind() {
        assertEquals(ObjectKind.CATALOG,                resolver.parse("Catalog.X").get().kind());
        assertEquals(ObjectKind.DOCUMENT,               resolver.parse("Document.X").get().kind());
        assertEquals(ObjectKind.COMMON_MODULE,          resolver.parse("CommonModule.X").get().kind());
        assertEquals(ObjectKind.INFORMATION_REGISTER,   resolver.parse("InformationRegister.X").get().kind());
        assertEquals(ObjectKind.ACCUMULATION_REGISTER,  resolver.parse("AccumulationRegister.X").get().kind());
        assertEquals(ObjectKind.REPORT,                 resolver.parse("Report.X").get().kind());
        assertEquals(ObjectKind.DATA_PROCESSOR,         resolver.parse("DataProcessor.X").get().kind());
        assertEquals(ObjectKind.ENUM,                   resolver.parse("Enum.X").get().kind());
        assertEquals(ObjectKind.CONSTANT,               resolver.parse("Constant.X").get().kind());
    }

    @Test
    public void kindMatchingIsCaseInsensitive() {
        assertEquals(ObjectKind.DOCUMENT, resolver.parse("DOCUMENT.X").get().kind());
        assertEquals(ObjectKind.DOCUMENT, resolver.parse("document.X").get().kind());
    }

    @Test
    public void parseReturnsEmptyOnUnknownKind() {
        assertFalse(resolver.parse("Widget.X").isPresent());
    }

    @Test
    public void parseReturnsEmptyOnMalformedFqn() {
        assertFalse(resolver.parse("").isPresent());
        assertFalse(resolver.parse(null).isPresent());
        assertFalse(resolver.parse("NoDot").isPresent());
        assertFalse(resolver.parse(".Bare").isPresent());
        assertFalse(resolver.parse("Document.").isPresent());
    }

    @Test
    public void parseTrimsLeadingTrailingSpace() {
        Optional<ParsedFqn> p = resolver.parse("  Document.X  ");
        assertTrue(p.isPresent());
        assertEquals(ObjectKind.DOCUMENT, p.get().kind());
        assertEquals("X", p.get().name());
    }

    // --- folderName / moduleFiles --------------------------------------------

    @Test
    public void folderNameMatchesEdtPluralConvention() {
        // EDT lays out src/Documents/<Name>/, src/CommonModules/<Name>/, etc.
        assertEquals("Documents",            ObjectKind.DOCUMENT.folderName());
        assertEquals("Catalogs",             ObjectKind.CATALOG.folderName());
        assertEquals("CommonModules",        ObjectKind.COMMON_MODULE.folderName());
        assertEquals("InformationRegisters", ObjectKind.INFORMATION_REGISTER.folderName());
        assertEquals("AccumulationRegisters",ObjectKind.ACCUMULATION_REGISTER.folderName());
        assertEquals("Reports",              ObjectKind.REPORT.folderName());
        assertEquals("DataProcessors",       ObjectKind.DATA_PROCESSOR.folderName());
        assertEquals("Enums",                ObjectKind.ENUM.folderName());
        assertEquals("Constants",            ObjectKind.CONSTANT.folderName());
    }

    @Test
    public void documentHasObjectAndManagerModule() {
        ParsedFqn p = resolver.parse("Document.SalesOrder").get();
        List<String> modules = resolver.standardModulePaths(p);
        assertTrue("must include ObjectModule.bsl", //$NON-NLS-1$
                modules.contains("Documents/SalesOrder/Ext/ObjectModule.bsl"));
        assertTrue("must include ManagerModule.bsl", //$NON-NLS-1$
                modules.contains("Documents/SalesOrder/Ext/ManagerModule.bsl"));
    }

    @Test
    public void commonModuleHasOnlyModule() {
        ParsedFqn p = resolver.parse("CommonModule.GeneralPurpose").get();
        List<String> modules = resolver.standardModulePaths(p);
        assertEquals(1, modules.size());
        assertEquals("CommonModules/GeneralPurpose/Ext/Module.bsl", modules.get(0));
    }

    @Test
    public void catalogHasObjectAndManagerModule() {
        ParsedFqn p = resolver.parse("Catalog.Nomenclature").get();
        List<String> modules = resolver.standardModulePaths(p);
        assertTrue(modules.contains("Catalogs/Nomenclature/Ext/ObjectModule.bsl"));
        assertTrue(modules.contains("Catalogs/Nomenclature/Ext/ManagerModule.bsl"));
    }

    @Test
    public void informationRegisterHasRecordSetAndManagerModule() {
        ParsedFqn p = resolver.parse("InformationRegister.Prices").get();
        List<String> modules = resolver.standardModulePaths(p);
        assertTrue("must include RecordSetModule.bsl", //$NON-NLS-1$
                modules.contains("InformationRegisters/Prices/Ext/RecordSetModule.bsl"));
        assertTrue("must include ManagerModule.bsl", //$NON-NLS-1$
                modules.contains("InformationRegisters/Prices/Ext/ManagerModule.bsl"));
    }

    @Test
    public void constantHasValueManagerAndManagerModule() {
        ParsedFqn p = resolver.parse("Constant.OrgName").get();
        List<String> modules = resolver.standardModulePaths(p);
        assertTrue("must include ValueManagerModule.bsl", //$NON-NLS-1$
                modules.contains("Constants/OrgName/Ext/ValueManagerModule.bsl"));
        assertTrue("must include ManagerModule.bsl", //$NON-NLS-1$
                modules.contains("Constants/OrgName/Ext/ManagerModule.bsl"));
    }

    @Test
    public void cyrillicNameSurvivesPathBuild() {
        ParsedFqn p = resolver.parse("Document.ВыпискаДенежныхСредств").get();
        List<String> modules = resolver.standardModulePaths(p);
        assertTrue(modules.contains("Documents/ВыпискаДенежныхСредств/Ext/ObjectModule.bsl"));
    }

    @Test
    public void enumAndReportHaveNoStandardModulesInConvention() {
        // Enums don't carry BSL modules in standard configuration.
        // Reports do: ObjectModule + ManagerModule.
        assertTrue("Report must have ObjectModule", //$NON-NLS-1$
                resolver.standardModulePaths(resolver.parse("Report.X").get())
                        .contains("Reports/X/Ext/ObjectModule.bsl"));
        assertTrue("Enum should yield empty module list", //$NON-NLS-1$
                resolver.standardModulePaths(resolver.parse("Enum.Status").get()).isEmpty());
    }

    @Test
    public void formModulePathIsBuiltFromFormName() {
        ParsedFqn p = resolver.parse("Document.SalesOrder").get();
        String formModule = resolver.formModulePath(p, "DocumentForm");
        assertNotNull(formModule);
        assertEquals("Documents/SalesOrder/Forms/DocumentForm/Ext/Form/Module.bsl", formModule);
    }

    @Test
    public void formModulePathRejectsEnumKindGracefully() {
        ParsedFqn p = resolver.parse("Enum.Status").get();
        // Enums cannot host forms with BSL modules in standard convention.
        assertNull(resolver.formModulePath(p, "Form"));
    }
}
