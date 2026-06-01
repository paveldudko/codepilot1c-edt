package com.codepilot1c.core.edt.forms;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Tests for {@link FormOwnerStrategy} owner-aware OBJECT-form resolution.
 *
 * <p>Pins the fix for the AM-side feedback note
 * {@code 2026-06-01-create-form-object-informationregister-unavailable.md}: {@code usage=OBJECT} on an
 * InformationRegister must generate a {@code RECORD} form (not the catalog-style {@code OBJECT} form, which
 * NPEs in the EDT generator) and bind it via {@code setDefaultRecordForm} (registers have no
 * {@code setDefaultObjectForm}).</p>
 */
public class FormOwnerStrategyTest {

    private final FormOwnerStrategy strategy = FormOwnerStrategy.defaultStrategy();

    // --- objectFormGeneratorType --------------------------------------------

    @Test
    public void informationRegisterObjectFormIsRecord() {
        assertEquals("RECORD", strategy.objectFormGeneratorType("InformationRegister")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void recordSetRegistersUseRecordSetObjectForm() {
        assertEquals("RECORD_SET", strategy.objectFormGeneratorType("AccumulationRegister")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("RECORD_SET", strategy.objectFormGeneratorType("AccountingRegister")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("RECORD_SET", strategy.objectFormGeneratorType("CalculationRegister")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void objectBearingOwnersKeepGenericObjectForm() {
        assertEquals("OBJECT", strategy.objectFormGeneratorType("Catalog")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("OBJECT", strategy.objectFormGeneratorType("Document")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("OBJECT", strategy.objectFormGeneratorType("DataProcessor")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("OBJECT", strategy.objectFormGeneratorType(null)); //$NON-NLS-1$
    }

    // --- resolveDefaultSetter (owner-aware) ---------------------------------

    @Test
    public void informationRegisterObjectDefaultIsRecordForm() {
        assertEquals("setDefaultRecordForm", //$NON-NLS-1$
                strategy.resolveDefaultSetter(FormUsage.OBJECT, "InformationRegister")); //$NON-NLS-1$
    }

    @Test
    public void recordSetRegistersHaveNoObjectDefaultSlot() {
        assertNull(strategy.resolveDefaultSetter(FormUsage.OBJECT, "AccumulationRegister")); //$NON-NLS-1$
        assertNull(strategy.resolveDefaultSetter(FormUsage.OBJECT, "AccountingRegister")); //$NON-NLS-1$
        assertNull(strategy.resolveDefaultSetter(FormUsage.OBJECT, "CalculationRegister")); //$NON-NLS-1$
    }

    @Test
    public void objectBearingOwnersUseDefaultObjectForm() {
        assertEquals("setDefaultObjectForm", //$NON-NLS-1$
                strategy.resolveDefaultSetter(FormUsage.OBJECT, "Catalog")); //$NON-NLS-1$
    }

    @Test
    public void listAndChoiceSettersAreOwnerIndependent() {
        assertEquals("setDefaultListForm", //$NON-NLS-1$
                strategy.resolveDefaultSetter(FormUsage.LIST, "InformationRegister")); //$NON-NLS-1$
        assertEquals("setDefaultChoiceForm", //$NON-NLS-1$
                strategy.resolveDefaultSetter(FormUsage.CHOICE, "Catalog")); //$NON-NLS-1$
    }

    @Test
    public void auxiliaryAndNullUsageHaveNoDefaultSlot() {
        assertNull(strategy.resolveDefaultSetter(FormUsage.AUXILIARY, "Catalog")); //$NON-NLS-1$
        assertNull(strategy.resolveDefaultSetter(null, "Catalog")); //$NON-NLS-1$
    }
}
