package com.codepilot1c.core.edt.ast;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Guards the {@code edt_metadata_details} miss message.
 *
 * <p>The tool resolves only a top-level {@code <Type>.<Name>} FQN, but it used to read that pair out of
 * a LONGER dotted FQN and return the object it found — so asking for
 * {@code Subsystem.<Parent>.<Child>} answered with the PARENT's properties under the requested path
 * (observed live 2026-07-28). A silent wrong answer is worse than a false negative: the caller has no
 * signal at all. The miss is now explicit and names the supported form.</p>
 */
public class MetadataDetailsFqnRejectionTest {

    @Test
    public void aPlainTopLevelMissStaysTerse() {
        assertEquals("Object not found", //$NON-NLS-1$
                EdtMetadataInspectorService.notFoundMessage("Catalog.NoSuchThing")); //$NON-NLS-1$
    }

    @Test
    public void aNestedDottedFqnIsToldWhyItCannotResolve() {
        String message = EdtMetadataInspectorService.notFoundMessage("Subsystem.Parent.Child"); //$NON-NLS-1$
        assertTrue("the caller must learn the extra segments are the problem", //$NON-NLS-1$
                message.contains("extra segments")); //$NON-NLS-1$
        assertTrue("the flat subsystem form is the whole point of the hint", //$NON-NLS-1$
                message.contains("Subsystem.<Name>")); //$NON-NLS-1$
    }

    @Test
    public void aChildObjectFqnIsRejectedTheSameWay() {
        String message = EdtMetadataInspectorService.notFoundMessage("Catalog.Goods.Attribute.Price"); //$NON-NLS-1$
        assertTrue("child objects are out of scope for this tool — say so", //$NON-NLS-1$
                message.contains("not addressable")); //$NON-NLS-1$
    }

    @Test
    public void aDegenerateFqnDoesNotBlowUp() {
        assertEquals("Object not found", EdtMetadataInspectorService.notFoundMessage(null)); //$NON-NLS-1$
        assertEquals("Object not found", EdtMetadataInspectorService.notFoundMessage("Configuration")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
