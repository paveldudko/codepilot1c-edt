package com.codepilot1c.core.edt.dcs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import com.codepilot1c.core.edt.metadata.MetadataOperationCode;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;

/**
 * Unit tests for the {@code dcs_manage command=create_schema} request contract.
 */
public class DcsCreateMainSchemaRequestTest {

    @Test
    public void templateNameDefaultsToMainDataCompositionSchema() {
        assertEquals(DcsSchemaSupport.DEFAULT_TEMPLATE_NAME, request(null).effectiveTemplateName());
        assertEquals(DcsSchemaSupport.DEFAULT_TEMPLATE_NAME, request("").effectiveTemplateName()); //$NON-NLS-1$
        assertEquals(DcsSchemaSupport.DEFAULT_TEMPLATE_NAME, request("   ").effectiveTemplateName()); //$NON-NLS-1$
    }

    @Test
    public void templateNameIsTrimmedButOtherwiseVerbatim() {
        assertEquals("MySchema", request("  MySchema  ").effectiveTemplateName()); //$NON-NLS-1$ //$NON-NLS-2$
        // Case is preserved: the name-slot match is case-insensitive, the stored name is not folded.
        assertEquals("mySchema", request("mySchema").effectiveTemplateName()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void forceReplaceIsFalseUnlessExplicitlyTrue() {
        assertFalse(new DcsCreateMainSchemaRequest("p", "Report.Sales", null, null).shouldForceReplace()); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(new DcsCreateMainSchemaRequest("p", "Report.Sales", null, Boolean.FALSE) //$NON-NLS-1$ //$NON-NLS-2$
                .shouldForceReplace());
        assertTrue(new DcsCreateMainSchemaRequest("p", "Report.Sales", null, Boolean.TRUE) //$NON-NLS-1$ //$NON-NLS-2$
                .shouldForceReplace());
    }

    @Test
    public void normalizersTrimProjectAndOwner() {
        DcsCreateMainSchemaRequest request =
                new DcsCreateMainSchemaRequest(" proj ", " Report.Sales ", null, null); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("proj", request.normalizedProjectName()); //$NON-NLS-1$
        assertEquals("Report.Sales", request.normalizedOwnerFqn()); //$NON-NLS-1$
        assertNull(new DcsCreateMainSchemaRequest(null, null, null, null).normalizedProjectName());
    }

    @Test
    public void validateRejectsBlankProject() {
        assertRejected(new DcsCreateMainSchemaRequest(null, "Report.Sales", null, null)); //$NON-NLS-1$
        assertRejected(new DcsCreateMainSchemaRequest("  ", "Report.Sales", null, null)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void validateRejectsBlankOwnerFqn() {
        assertRejected(new DcsCreateMainSchemaRequest("proj", null, null, null)); //$NON-NLS-1$
        assertRejected(new DcsCreateMainSchemaRequest("proj", "  ", null, null)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void validateAcceptsAMinimalRequest() {
        new DcsCreateMainSchemaRequest("proj", "Report.Sales", null, null).validate(); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // --- Helpers ------------------------------------------------------------

    private DcsCreateMainSchemaRequest request(String templateName) {
        return new DcsCreateMainSchemaRequest("proj", "Report.Sales", templateName, null); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private void assertRejected(DcsCreateMainSchemaRequest request) {
        try {
            request.validate();
            fail("expected a MetadataOperationException"); //$NON-NLS-1$
        } catch (MetadataOperationException e) {
            assertEquals(MetadataOperationCode.KNOWLEDGE_REQUIRED, e.getCode());
        }
    }
}
