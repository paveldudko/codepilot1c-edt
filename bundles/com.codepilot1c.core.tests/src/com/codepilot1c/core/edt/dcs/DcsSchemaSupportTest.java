package com.codepilot1c.core.edt.dcs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.codepilot1c.core.edt.dcs.DcsSchemaSupport.MutationPlan;
import com.codepilot1c.core.edt.dcs.DcsSchemaSupport.NameSlotState;

/**
 * Unit tests for the pure parts of the DCS main-schema path.
 *
 * <p>These are the pieces of the fix that can run outside the OSGi/EMF runtime: the name-slot
 * idempotency predicate, the create/reuse/repair decision, the external-FQN shape and the on-disk
 * artifact path. The BM operations themselves ({@code attachTopObject}, the {@code Template.dcs}
 * actually appearing) are live-only — see {@link DcsMainSchemaPersistenceContractTest} for the
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

    // --- planMutation (create / reuse / repair / refuse) ---------------------

    /**
     * The state the defect leaves behind, and the one the bug report arrived in: the owner's
     * {@code .mdo} carries a {@code <templates>} entry typed {@code DataCompositionSchema} while
     * {@code Templates/<name>/Template.dcs} does not exist, because the schema was never attached as
     * a top-object. Reading the type alone as "already done" made the tool unable to repair itself —
     * it answered no-op, the disk probe kept reporting the file missing, and the only escape was
     * {@code force_replace=true}, which also wipes content.
     */
    @Test
    public void aDcsTemplateWhoseSchemaDoesNotResolveIsRepairedNotReportedAsDone() {
        assertEquals(MutationPlan.REBIND_SAME_NAME,
                DcsSchemaSupport.planMutation(NameSlotState.REUSABLE_DCS, false, false, true, false));
    }

    @Test
    public void aBoundSchemaUnderTheRequestedNameIsAnIdempotentNoOp() {
        assertEquals(MutationPlan.NO_OP,
                DcsSchemaSupport.planMutation(NameSlotState.REUSABLE_DCS, true, true, true, false));
    }

    @Test
    public void aFreeNameOnAnOwnerWithoutASchemaCreatesATemplate() {
        assertEquals(MutationPlan.CREATE,
                DcsSchemaSupport.planMutation(NameSlotState.FREE, false, false, false, false));
    }

    @Test
    public void aFreeNameIsANoOpWhenTheOwnerAlreadyCarriesABoundSchema() {
        // The owner's main schema lives under another name — creating a second one is not a fix.
        assertEquals(MutationPlan.NO_OP,
                DcsSchemaSupport.planMutation(NameSlotState.FREE, false, true, true, false));
    }

    @Test
    public void aDanglingSchemaUnderAnotherNameIsLeftAloneWhenANewNameIsRequested() {
        // Repairing a template the caller did not name would be a bigger surprise than a new one.
        assertEquals(MutationPlan.CREATE,
                DcsSchemaSupport.planMutation(NameSlotState.FREE, false, false, true, false));
    }

    @Test
    public void aNameHeldByAnotherTemplateTypeIsRefusedWithoutForceReplace() {
        assertEquals(MutationPlan.REFUSE_NAME_OCCUPIED,
                DcsSchemaSupport.planMutation(NameSlotState.OCCUPIED_OTHER_TYPE, false, false, false, false));
        // Even when the owner has a perfectly good schema elsewhere the name conflict still decides.
        assertEquals(MutationPlan.REFUSE_NAME_OCCUPIED,
                DcsSchemaSupport.planMutation(NameSlotState.OCCUPIED_OTHER_TYPE, false, true, true, false));
    }

    @Test
    public void forceReplaceConvertsTheSameNamedTemplateOfAnotherType() {
        assertEquals(MutationPlan.REBIND_SAME_NAME,
                DcsSchemaSupport.planMutation(NameSlotState.OCCUPIED_OTHER_TYPE, false, false, false, true));
    }

    @Test
    public void forceReplaceNeverAppendsASecondTemplateUnderTheSameName() {
        // The pre-fix behaviour: force_replace only ever ADDED, so one name could end up twice.
        assertEquals(MutationPlan.REBIND_SAME_NAME,
                DcsSchemaSupport.planMutation(NameSlotState.REUSABLE_DCS, true, true, true, true));
    }

    @Test
    public void forceReplaceWithAFreeNameRebindsTheOwnersExistingDcsTemplate() {
        assertEquals(MutationPlan.REBIND_OWNER_TEMPLATE,
                DcsSchemaSupport.planMutation(NameSlotState.FREE, false, true, true, true));
        // Also when that template is the dangling one — it is still the template to reuse.
        assertEquals(MutationPlan.REBIND_OWNER_TEMPLATE,
                DcsSchemaSupport.planMutation(NameSlotState.FREE, false, false, true, true));
    }

    @Test
    public void forceReplaceOnAnOwnerWithNoDcsTemplateAtAllCreatesOne() {
        assertEquals(MutationPlan.CREATE,
                DcsSchemaSupport.planMutation(NameSlotState.FREE, false, false, false, true));
    }

    /**
     * The invariant the defect broke, swept over every input combination: the tool may answer
     * "already done" ONLY when some schema actually resolves. A no-op with nothing bound is a
     * success report with no artifact — precisely what was reported.
     */
    @Test
    public void noOpIsNeverAnsweredWhileNoSchemaResolvesAnywhere() {
        forEachInput((slot, sameNameBound, ownerBound, hasDcsTemplate, force) -> {
            MutationPlan plan = DcsSchemaSupport.planMutation(
                    slot, sameNameBound, ownerBound, hasDcsTemplate, force);
            String where = slot + " sameNameBound=" + sameNameBound + " ownerBound=" + ownerBound //$NON-NLS-1$ //$NON-NLS-2$
                    + " hasDcs=" + hasDcsTemplate + " force=" + force; //$NON-NLS-1$ //$NON-NLS-2$
            if (plan == MutationPlan.NO_OP) {
                assertTrue("no-op with nothing bound: " + where, sameNameBound || ownerBound); //$NON-NLS-1$
            }
            assertFalse("force_replace must always mutate: " + where, //$NON-NLS-1$
                    force && plan == MutationPlan.NO_OP);
        });
    }

    /**
     * A name held by a template of another type is a refusal for every other input — the caller must
     * hear about the collision instead of getting a silent conversion or a second entry.
     */
    @Test
    public void anOccupiedNameOfAnotherTypeIsAlwaysRefusedUnlessForced() {
        forEachInput((slot, sameNameBound, ownerBound, hasDcsTemplate, force) -> {
            if (slot != NameSlotState.OCCUPIED_OTHER_TYPE || force) {
                return;
            }
            assertEquals(MutationPlan.REFUSE_NAME_OCCUPIED,
                    DcsSchemaSupport.planMutation(slot, sameNameBound, ownerBound, hasDcsTemplate, false));
        });
    }

    private void forEachInput(PlanCase body) {
        for (NameSlotState slot : NameSlotState.values()) {
            for (boolean sameNameBound : BOOLEANS) {
                for (boolean ownerBound : BOOLEANS) {
                    for (boolean hasDcsTemplate : BOOLEANS) {
                        for (boolean force : BOOLEANS) {
                            body.check(slot, sameNameBound, ownerBound, hasDcsTemplate, force);
                        }
                    }
                }
            }
        }
    }

    private static final boolean[] BOOLEANS = {false, true};

    @FunctionalInterface
    private interface PlanCase {
        void check(NameSlotState slot, boolean sameNameBound, boolean ownerBound,
                boolean hasDcsTemplate, boolean force);
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
