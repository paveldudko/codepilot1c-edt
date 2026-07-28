package com.codepilot1c.core.edt.dcs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.codepilot1c.core.edt.dcs.DcsSchemaSupport.NameSlotState;

/**
 * Unit tests for the pure parts of the DCS main-schema path.
 *
 * <p>These are the only pieces of the fix that can run outside the OSGi/EMF runtime: the
 * name-slot idempotency predicate, the external-FQN shape and the on-disk artifact path. The
 * BM operations themselves ({@code attachTopObject}, the {@code Template.dcs} actually
 * appearing) are live-only — see {@link DcsMainSchemaPersistenceContractTest} for the
 * source-level pins that guard their ordering.</p>
 */
public class DcsSchemaSupportTest {

    // --- nameMatches ---------------------------------------------------------

    @Test
    public void nameMatchesIsCaseInsensitive() {
        assertTrue(DcsSchemaSupport.nameMatches("MainDataCompositionSchema", "maindatacompositionschema")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(DcsSchemaSupport.nameMatches("Schema", "SCHEMA")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void nameMatchesIgnoresSurroundingWhitespace() {
        assertTrue(DcsSchemaSupport.nameMatches("  Schema ", "Schema")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void nameMatchesRejectsDifferentNamesAndNulls() {
        assertFalse(DcsSchemaSupport.nameMatches("Schema", "OtherSchema")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(DcsSchemaSupport.nameMatches(null, "Schema")); //$NON-NLS-1$
        assertFalse(DcsSchemaSupport.nameMatches("Schema", null)); //$NON-NLS-1$
        assertFalse(DcsSchemaSupport.nameMatches(null, null));
    }

    // --- classifyNameSlot (the idempotency predicate) ------------------------

    @Test
    public void nameSlotIsFreeWhenNoTemplateCarriesTheName() {
        assertEquals(NameSlotState.FREE,
                DcsSchemaSupport.classifyNameSlot(null, false, "MainDataCompositionSchema")); //$NON-NLS-1$
    }

    @Test
    public void nameSlotIsFreeWhenTheFoundTemplateHasAnotherName() {
        assertEquals(NameSlotState.FREE,
                DcsSchemaSupport.classifyNameSlot("OtherTemplate", true, "MainDataCompositionSchema")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void nameSlotIsReusableForACaseInsensitiveDcsMatch() {
        // The pre-fix code only checked "does a schema exist", never "is the NAME taken", so a
        // second <templates> entry with the same name was appended.
        assertEquals(NameSlotState.REUSABLE_DCS,
                DcsSchemaSupport.classifyNameSlot("maindatacompositionschema", true, "MainDataCompositionSchema")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void nameSlotIsOccupiedWhenTheTemplateHasAnotherTemplateType() {
        // e.g. a spreadsheet-document template created under the DCS name.
        assertEquals(NameSlotState.OCCUPIED_OTHER_TYPE,
                DcsSchemaSupport.classifyNameSlot("MainDataCompositionSchema", false, "MainDataCompositionSchema")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // --- FQN shapes ---------------------------------------------------------

    @Test
    public void topLevelFqnCanonicalizesTheOwnerKind() {
        assertEquals("Report.Sales", DcsSchemaSupport.topLevelFqn("Report.Sales")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Report.Sales", DcsSchemaSupport.topLevelFqn("Отчет.Sales")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("DataProcessor.Loader", DcsSchemaSupport.topLevelFqn("Обработка.Loader")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void topLevelFqnIsNullForUnknownKindOrMissingName() {
        assertNull(DcsSchemaSupport.topLevelFqn("Catalog.Goods")); //$NON-NLS-1$
        assertNull(DcsSchemaSupport.topLevelFqn("Report")); //$NON-NLS-1$
        assertNull(DcsSchemaSupport.topLevelFqn(null));
    }

    @Test
    public void templateFqnMatchesTheMdoSerializedForm() {
        // Ground truth: <mainDataCompositionSchema>Report.AccessRights.Template.ParametersTemplate.
        assertEquals("Report.Sales.Template.MainSchema", //$NON-NLS-1$
                DcsSchemaSupport.templateFqn("Report.Sales", "MainSchema")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void externalSchemaFqnAppendsTheCapitalizedReferenceName() {
        // MdTopObjectFqnGeneratorDelegate appends capitalize(reference.getName()) to the owner's
        // qualified name; the reference is BasicTemplate.template.
        assertEquals("Report.Sales.Template.MainSchema.Template", //$NON-NLS-1$
                DcsSchemaSupport.expectedExternalSchemaFqn("Report.Sales", "MainSchema")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void externalSchemaFqnTrimsAndCanonicalizes() {
        assertEquals("Report.Sales.Template.MainSchema.Template", //$NON-NLS-1$
                DcsSchemaSupport.expectedExternalSchemaFqn(" Отчет.Sales ", " MainSchema ")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void externalSchemaFqnIsNullWithoutATemplateName() {
        assertNull(DcsSchemaSupport.expectedExternalSchemaFqn("Report.Sales", "  ")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(DcsSchemaSupport.expectedExternalSchemaFqn("Report.Sales", null)); //$NON-NLS-1$
    }

    // --- on-disk artifact path ----------------------------------------------

    @Test
    public void schemaFileRelativePathPointsAtTheSeparateDcsArtifact() {
        assertEquals("src/Reports/Sales/Templates/MainSchema/Template.dcs", //$NON-NLS-1$
                DcsSchemaSupport.schemaFileRelativePath("Report.Sales", "MainSchema")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("src/DataProcessors/Loader/Templates/Schema/Template.dcs", //$NON-NLS-1$
                DcsSchemaSupport.schemaFileRelativePath("DataProcessor.Loader", "Schema")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void schemaFileRelativePathIsNullForOwnersWithoutASrcTree() {
        assertNull(DcsSchemaSupport.schemaFileRelativePath("ExternalReport.Ad hoc", "Schema")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(DcsSchemaSupport.schemaFileRelativePath("ExternalDataProcessor.Tool", "Schema")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void ownerFolderMapsBothLanguages() {
        assertEquals("Reports", DcsSchemaSupport.ownerFolder("Report")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Reports", DcsSchemaSupport.ownerFolder("отчёт")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("DataProcessors", DcsSchemaSupport.ownerFolder("DataProcessor")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(DcsSchemaSupport.ownerFolder("ExternalReport")); //$NON-NLS-1$
        assertNull(DcsSchemaSupport.ownerFolder(null));
    }

    @Test
    public void canonicalOwnerKindCoversExternalOwners() {
        assertEquals("ExternalReport", DcsSchemaSupport.canonicalOwnerKind("externalreport")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("ExternalDataProcessor", //$NON-NLS-1$
                DcsSchemaSupport.canonicalOwnerKind("ExternalDataProcessor")); //$NON-NLS-1$
        assertNull(DcsSchemaSupport.canonicalOwnerKind("Catalog")); //$NON-NLS-1$
    }
}
