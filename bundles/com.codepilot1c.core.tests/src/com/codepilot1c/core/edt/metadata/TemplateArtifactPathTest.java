package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

/**
 * Behavioural test for template artifact file names.
 *
 * <p>What was broken: every artifact path was hard-coded to {@code Template.mxl}, a name EDT uses
 * for no template type at all. Live 2026-07-29 against the real Accounting management configuration
 * (680 template artifacts): 270 spreadsheets are stored as {@code Template.mxlx}, zero as
 * {@code Template.mxl}, and {@code inspect_template} on a real one answered "(файл не найден)".</p>
 *
 * <p>The expected values are EDT's own, read out of the constant pool of
 * {@code com._1c.g5.v8.dt.ide.QualifiedNameFilePathConverter} (2025.2.3). Every {@code TemplateType}
 * constant is asserted here, by its EMF constant name and by its {@code .mdo} literal, so a typo in
 * either spelling fails rather than silently resolving to no extension.</p>
 */
public class TemplateArtifactPathTest {

    // --- the defect itself ---------------------------------------------------

    @Test
    public void aSpreadsheetArtifactIsMxlxNotMxl() {
        assertEquals("Template.mxlx", TemplateArtifactPath.fileNameFor("SPREADSHEET_DOCUMENT")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Template.mxlx", TemplateArtifactPath.fileNameFor("SpreadsheetDocument")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aSpreadsheetLookupStillFindsTheLegacyNameWeUsedToWrite() {
        // Projects that already received a Template.mxl from an earlier build must keep resolving.
        assertEquals(List.of("Template.mxlx", "Template.mxl"), //$NON-NLS-1$ //$NON-NLS-2$
                TemplateArtifactPath.candidateFileNames("SPREADSHEET_DOCUMENT")); //$NON-NLS-1$
    }

    @Test
    public void noOtherTypeOffersTheLegacySpreadsheetName() {
        for (String type : List.of("TEXT_DOCUMENT", "BINARY_DATA", "HTML_DOCUMENT", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "ACTIVE_DOCUMENT", "GRAPHICAL_SCHEMA", "GEOGRAPHICAL_SCHEMA", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "DATA_COMPOSITION_SCHEMA", "DATA_COMPOSITION_APPEARANCE_TEMPLATE", "ADD_IN")) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            assertEquals(type, 1, TemplateArtifactPath.candidateFileNames(type).size());
        }
    }

    // --- the whole table, both spellings -------------------------------------

    @Test
    public void everyTemplateTypeResolvesByItsEmfConstantName() {
        assertEquals("mxlx", TemplateArtifactPath.extensionFor("SPREADSHEET_DOCUMENT")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("txt", TemplateArtifactPath.extensionFor("TEXT_DOCUMENT")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("bin", TemplateArtifactPath.extensionFor("BINARY_DATA")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("htmldoc", TemplateArtifactPath.extensionFor("HTML_DOCUMENT")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("axdt", TemplateArtifactPath.extensionFor("ACTIVE_DOCUMENT")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("scheme", TemplateArtifactPath.extensionFor("GRAPHICAL_SCHEMA")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("geos", TemplateArtifactPath.extensionFor("GEOGRAPHICAL_SCHEMA")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("dcs", TemplateArtifactPath.extensionFor("DATA_COMPOSITION_SCHEMA")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("dcsat", //$NON-NLS-1$
                TemplateArtifactPath.extensionFor("DATA_COMPOSITION_APPEARANCE_TEMPLATE")); //$NON-NLS-1$
        assertEquals("addin", TemplateArtifactPath.extensionFor("ADD_IN")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void everyTemplateTypeResolvesByItsMdoLiteral() {
        // The literals as they appear in <templateType> in real .mdo files.
        assertEquals("mxlx", TemplateArtifactPath.extensionFor("SpreadsheetDocument")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("txt", TemplateArtifactPath.extensionFor("TextDocument")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("bin", TemplateArtifactPath.extensionFor("BinaryData")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("htmldoc", TemplateArtifactPath.extensionFor("HTMLDocument")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("axdt", TemplateArtifactPath.extensionFor("ActiveDocument")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("geos", TemplateArtifactPath.extensionFor("GeographicalSchema")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("dcs", TemplateArtifactPath.extensionFor("DataCompositionSchema")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("dcsat", //$NON-NLS-1$
                TemplateArtifactPath.extensionFor("DataCompositionAppearanceTemplate")); //$NON-NLS-1$
        assertEquals("addin", TemplateArtifactPath.extensionFor("AddIn")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void theGraphicalOneResolvesUnderBothSpellings() {
        // EDT spells it GraphicalScheme, the EMF constant is GRAPHICAL_SCHEMA — they normalise
        // differently, so both have to answer.
        assertEquals("scheme", TemplateArtifactPath.extensionFor("GraphicalScheme")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("scheme", TemplateArtifactPath.extensionFor("GraphicalSchema")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("scheme", TemplateArtifactPath.extensionFor("GRAPHICAL_SCHEMA")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // --- an unknown type must not be guessed at ------------------------------

    @Test
    public void anUnknownTypeYieldsNothingRatherThanADefault() {
        assertNull(TemplateArtifactPath.extensionFor("NotATemplateType")); //$NON-NLS-1$
        assertNull(TemplateArtifactPath.fileNameFor("NotATemplateType")); //$NON-NLS-1$
        assertTrue(TemplateArtifactPath.candidateFileNames("NotATemplateType").isEmpty()); //$NON-NLS-1$
        assertNull(TemplateArtifactPath.extensionFor(null));
        assertNull(TemplateArtifactPath.extensionFor("")); //$NON-NLS-1$
        assertNull(TemplateArtifactPath.extensionFor("   ")); //$NON-NLS-1$
    }

    // --- the two routing questions the service asks ---------------------------

    @Test
    public void onlyTheSpreadsheetTypeIsRecognisedAsASpreadsheet() {
        assertTrue(TemplateArtifactPath.isSpreadsheet("SPREADSHEET_DOCUMENT")); //$NON-NLS-1$
        assertTrue(TemplateArtifactPath.isSpreadsheet("SpreadsheetDocument")); //$NON-NLS-1$
        assertFalse(TemplateArtifactPath.isSpreadsheet("TEXT_DOCUMENT")); //$NON-NLS-1$
        assertFalse(TemplateArtifactPath.isSpreadsheet("DataCompositionSchema")); //$NON-NLS-1$
        assertFalse(TemplateArtifactPath.isSpreadsheet(null));
    }

    @Test
    public void bothDataCompositionTypesRouteToTheDcsService() {
        assertTrue(TemplateArtifactPath.isDataCompositionManaged("DATA_COMPOSITION_SCHEMA")); //$NON-NLS-1$
        assertTrue(TemplateArtifactPath.isDataCompositionManaged("DataCompositionSchema")); //$NON-NLS-1$
        assertTrue(TemplateArtifactPath.isDataCompositionManaged( //
                "DATA_COMPOSITION_APPEARANCE_TEMPLATE")); //$NON-NLS-1$
        assertTrue(TemplateArtifactPath.isDataCompositionManaged( //
                "DataCompositionAppearanceTemplate")); //$NON-NLS-1$
        assertFalse(TemplateArtifactPath.isDataCompositionManaged("SPREADSHEET_DOCUMENT")); //$NON-NLS-1$
        assertFalse(TemplateArtifactPath.isDataCompositionManaged(null));
    }
}
