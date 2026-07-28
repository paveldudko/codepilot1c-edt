package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

/**
 * Source-contract test for the {@code DynamicListExtInfo} authoring path (BF-13330).
 *
 * <p>Like {@link RemoveCommandContractTest} / {@link AddFormParameterContractTest}, the
 * behavior cannot run through a plain Maven test bundle: {@code Form},
 * {@code FormFactory}, {@code DynamicListExtInfo} and the BM transactions resolve only
 * inside the OSGi/EMF runtime. The structure is pinned by reading the source instead.</p>
 *
 * <p>Gap: a dynamic list's {@code customQuery} / {@code queryText} / {@code mainTable}
 * live on the attribute's {@code extInfo}, not on {@code FormAttribute}, and the generic
 * feature resolver only ever inspects {@code target.eClass()} — so a flat
 * {@code set:{customQuery:true}} was rejected as {@code Unknown form property} even
 * though the property exists one level deeper. {@code set_item} could not help either:
 * it addresses form ITEMS, whose ids are a different id space than form attributes.</p>
 */
public class DynamicListExtInfoContractTest {

    private static final String SERVICE_PATH =
            "bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/metadata/EdtMetadataService.java"; //$NON-NLS-1$
    private static final String RULES_PATH =
            "bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/metadata/DynamicListExtInfoRules.java"; //$NON-NLS-1$
    private static final String VALIDATION_PATH =
            "bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/validation/MetadataRequestValidationService.java"; //$NON-NLS-1$
    private static final String MANIFEST_PATH =
            "bundles/com.codepilot1c.core/META-INF/MANIFEST.MF"; //$NON-NLS-1$
    private static final String MUTATE_TOOL_PATH =
            "bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/forms/MutateFormModelTool.java"; //$NON-NLS-1$
    private static final String RECIPE_TOOL_PATH =
            "bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/forms/ApplyFormRecipeTool.java"; //$NON-NLS-1$
    private static final String KNOWLEDGE_PATH =
            "bundles/com.codepilot1c.core/resources/knowledge/managed-forms.md"; //$NON-NLS-1$

    // --- materialization ----------------------------------------------------

    @Test
    public void extInfoIsMaterializedInsteadOfRefused() throws Exception {
        String source = readSource(SERVICE_PATH);
        assertTrue("a missing DynamicListExtInfo must be created, not rejected", //$NON-NLS-1$
                source.contains("FormFactory.eINSTANCE.createDynamicListExtInfo()")); //$NON-NLS-1$
        assertTrue("materialization must go through a dedicated ensure* helper", //$NON-NLS-1$
                source.contains("private DynamicListExtInfo ensureDynamicListExtInfo(")); //$NON-NLS-1$
        assertTrue("the old blanket refusal must be gone", //$NON-NLS-1$
                !source.contains("Attribute extInfo is not initialized for patch")); //$NON-NLS-1$
    }

    @Test
    public void nonDynamicListAttributeFailsLoud() throws Exception {
        String source = readSource(SERVICE_PATH);
        assertTrue("a non-DynamicList attribute must be rejected as INVALID_METADATA_CHANGE", //$NON-NLS-1$
                source.contains("are DynamicList-only")); //$NON-NLS-1$
        assertTrue("the refusal must name the attribute's real valueType", //$NON-NLS-1$
                source.contains("describeFormAttributeValueType(attribute)")); //$NON-NLS-1$
    }

    // --- hoist ordering ------------------------------------------------------

    @Test
    public void flatKeysAreHoistedBeforeTheGenericPropertySet() throws Exception {
        String source = readSource(SERVICE_PATH);
        int hoist = source.indexOf("DynamicListExtInfoRules.hoist(set)"); //$NON-NLS-1$
        int genericSet = source.indexOf("applyFormPropertySet(attribute, set, configuration, notes)"); //$NON-NLS-1$
        assertTrue("applyFormAttributePatch must hoist the flat DynamicList keys", hoist > 0); //$NON-NLS-1$
        assertTrue("the attribute-level property set must still run", genericSet > 0); //$NON-NLS-1$
        assertTrue("the hoist must run BEFORE applyFormPropertySet(attribute, set) — otherwise the" //$NON-NLS-1$
                + " generic resolver rejects the key first", hoist < genericSet); //$NON-NLS-1$
    }

    @Test
    public void explicitExtInfoBlockWinsOverFlatKeys() throws Exception {
        String source = readSource(SERVICE_PATH);
        int hoisted = source.indexOf("mergedExtInfoSet.putAll(hoistedExtInfoSet)"); //$NON-NLS-1$
        int explicit = source.indexOf("mergedExtInfoSet.putAll(explicitExtInfoSet)"); //$NON-NLS-1$
        assertTrue("flat keys must be merged into the extInfo patch", hoisted > 0); //$NON-NLS-1$
        assertTrue("the explicit extInfo:{...} block must be merged too", explicit > 0); //$NON-NLS-1$
        assertTrue("the explicit block must be applied LAST so it wins on conflict", //$NON-NLS-1$
                hoisted < explicit);
        assertTrue("both sides must be canonicalized so an alias cannot slip past the override", //$NON-NLS-1$
                source.contains("DynamicListExtInfoRules.canonicalize(asMap(extInfoPatch))")); //$NON-NLS-1$
    }

    @Test
    public void legacyDynamicDataReadBranchIsKeptButNotAppliedTwice() throws Exception {
        String source = readSource(SERVICE_PATH);
        assertTrue("the legacy flat dynamicDataRead branch must stay for backward compatibility", //$NON-NLS-1$
                source.contains("\"dynamicDataRead expects boolean value\"")); //$NON-NLS-1$
        assertTrue("it must feed the merged extInfo patch instead of writing extInfo directly", //$NON-NLS-1$
                source.contains("legacyExtInfoSet.put(\"dynamicDataRead\", parsed)")); //$NON-NLS-1$
        assertEquals("setDynamicDataRead must no longer be called directly (single application point)", //$NON-NLS-1$
                -1, source.indexOf("extInfo.setDynamicDataRead(")); //$NON-NLS-1$
    }

    // --- customQuery semantics ---------------------------------------------

    @Test
    public void customQueryTrueGeneratesQueryTextOnlyInTheTrueBranch() throws Exception {
        String source = readSource(SERVICE_PATH);
        int trueBranch = source.indexOf("if (Boolean.TRUE.equals(customQuery)) {"); //$NON-NLS-1$
        int falseBranch = source.indexOf("} else if (Boolean.FALSE.equals(customQuery)) {"); //$NON-NLS-1$
        int createQueryText = source.indexOf("DynamicListAttributeService.createQueryText("); //$NON-NLS-1$
        assertTrue("customQuery=true branch must exist", trueBranch > 0); //$NON-NLS-1$
        assertTrue("customQuery=false branch must exist", falseBranch > trueBranch); //$NON-NLS-1$
        assertTrue("query-text generation must use the EDT service the form editor uses", //$NON-NLS-1$
                createQueryText > 0);
        assertTrue("generation must live in the customQuery=true branch only", //$NON-NLS-1$
                createQueryText > trueBranch && createQueryText < falseBranch);
        assertEquals("exactly one generation site", //$NON-NLS-1$
                createQueryText, source.lastIndexOf("DynamicListAttributeService.createQueryText(")); //$NON-NLS-1$
    }

    @Test
    public void generatedQueryTextIsReportedBackToTheCaller() throws Exception {
        String source = readSource(SERVICE_PATH);
        // The owner's call: generate automatically, but never silently — the caller must be
        // able to see what was actually written.
        assertTrue("the generated query text must be surfaced through the operation notes", //$NON-NLS-1$
                source.contains("generated queryText from mainTable")); //$NON-NLS-1$
        assertTrue("notes must reach the operation summary", //$NON-NLS-1$
                source.contains("formatOperationNotes(notes)")); //$NON-NLS-1$
    }

    @Test
    public void customQueryFalseClearsDerivedCollectionsOnly() throws Exception {
        String source = readSource(SERVICE_PATH);
        int falseBranch = source.indexOf("} else if (Boolean.FALSE.equals(customQuery)) {"); //$NON-NLS-1$
        int clearFields = source.indexOf("extInfo.getFields().clear()"); //$NON-NLS-1$
        int nextBranch = source.indexOf("} else if (queryText != null) {"); //$NON-NLS-1$
        assertTrue("customQuery=false must clear the derived DCS fields", clearFields > 0); //$NON-NLS-1$
        assertTrue("clearing must happen in the customQuery=false branch, nowhere else", //$NON-NLS-1$
                clearFields > falseBranch && clearFields < nextBranch);
        assertEquals("exactly one getFields().clear() site", //$NON-NLS-1$
                clearFields, source.lastIndexOf("extInfo.getFields().clear()")); //$NON-NLS-1$
        assertTrue("calculatedFields must be cleared too", //$NON-NLS-1$
                source.contains("extInfo.getCalculatedFields().clear()")); //$NON-NLS-1$
        assertTrue("parameters must be cleared too", //$NON-NLS-1$
                source.contains("extInfo.getParameters().clear()")); //$NON-NLS-1$
        assertTrue("queryText must be dropped", //$NON-NLS-1$
                source.contains("extInfo.setQueryText(null)")); //$NON-NLS-1$
    }

    @Test
    public void customQueryFalseWithQueryTextIsRejected() throws Exception {
        String source = readSource(SERVICE_PATH);
        assertTrue("a contradictory customQuery=false + queryText patch must fail loud", //$NON-NLS-1$
                source.contains("Contradictory patch: customQuery=false clears queryText")); //$NON-NLS-1$
    }

    @Test
    public void mainTableIsNeverReassignedImplicitly() throws Exception {
        String source = readSource(SERVICE_PATH);
        int setMainTable = source.indexOf("extInfo.setMainTable("); //$NON-NLS-1$
        assertTrue("mainTable must be settable", setMainTable > 0); //$NON-NLS-1$
        assertEquals("mainTable must be assigned from exactly ONE place (the explicit binder) so" //$NON-NLS-1$
                + " flipping customQuery in either direction preserves it", //$NON-NLS-1$
                setMainTable, source.lastIndexOf("extInfo.setMainTable(")); //$NON-NLS-1$
        int binder = source.indexOf("private void applyDynamicListMainTable("); //$NON-NLS-1$
        assertTrue("the single assignment must live in applyDynamicListMainTable", //$NON-NLS-1$
                binder > 0 && setMainTable > binder);
    }

    @Test
    public void mainTableIsResolvedThroughDbViewDefsReflectively() throws Exception {
        String source = readSource(SERVICE_PATH);
        assertTrue("mainTable must resolve the metadata object by FQN", //$NON-NLS-1$
                source.contains("resolveByFqn(configuration, fqn.trim())")); //$NON-NLS-1$
        assertTrue("dbViewDefs must be read reflectively (dozens of typed *DbViewDefs otherwise)", //$NON-NLS-1$
                source.contains("readFeatureValue(resolved, \"dbViewDefs\")")); //$NON-NLS-1$
        assertTrue("mainView must be read reflectively too", //$NON-NLS-1$
                source.contains("readFeatureValue(defs, \"mainView\")")); //$NON-NLS-1$
        assertTrue("a non-queryable object must be rejected with an actionable message", //$NON-NLS-1$
                source.contains("is not a queryable table (no main database view)")); //$NON-NLS-1$
    }

    // --- refused DCS collections -------------------------------------------

    @Test
    public void dcsContainmentCollectionsAreRefusedNotIgnored() throws Exception {
        String service = readSource(SERVICE_PATH);
        String rules = readSource(RULES_PATH);
        assertTrue("the refused features must be declared in the rules table", //$NON-NLS-1$
                rules.contains("UNSUPPORTED_CONTAINMENT_FEATURES")); //$NON-NLS-1$
        assertTrue("the applier must check them before mutating anything", //$NON-NLS-1$
                service.contains("DynamicListExtInfoRules.UNSUPPORTED_CONTAINMENT_FEATURES")); //$NON-NLS-1$
        assertTrue("the refusal must be explicit, with the autoFillAvailableFields workaround", //$NON-NLS-1$
                service.contains("is a DCS containment collection and is not")); //$NON-NLS-1$
        assertTrue("the refusal must point at autoFillAvailableFields", //$NON-NLS-1$
                service.contains("Keep autoFillAvailableFields=true")); //$NON-NLS-1$
    }

    // --- type:"DynamicList" trap -------------------------------------------

    @Test
    public void dynamicListTypeRequestIsRejectedInBothValidators() throws Exception {
        String service = readSource(SERVICE_PATH);
        String validation = readSource(VALIDATION_PATH);
        assertTrue("validateFormAttributeType must early-reject type:\"DynamicList\"", //$NON-NLS-1$
                service.contains("\"dynamiclist\".equals(normalized)")); //$NON-NLS-1$
        assertTrue("the mirror check must live in MetadataRequestValidationService too", //$NON-NLS-1$
                validation.contains("\"dynamiclist\".equals(normalized)")); //$NON-NLS-1$
        assertTrue("the message must redirect to customQuery/queryText", //$NON-NLS-1$
                service.contains("DynamicList is not a requestable form attribute type")); //$NON-NLS-1$
        assertTrue("the mirror message must redirect too", //$NON-NLS-1$
                validation.contains("DynamicList is not a requestable form attribute type")); //$NON-NLS-1$
    }

    // --- set_item hint ------------------------------------------------------

    @Test
    public void wrongTargetGetsTheIdSpaceExplanation() throws Exception {
        String source = readSource(SERVICE_PATH);
        assertTrue("the generic resolver must recognize a DynamicList-only key", //$NON-NLS-1$
                source.contains("DynamicListExtInfoRules.isDynamicListOnlyKey(fieldName)")); //$NON-NLS-1$
        assertTrue("the hint must explain the id-space split that makes set_item a dead end", //$NON-NLS-1$
                source.contains("set_item addresses form ITEMS, which have their own id space")); //$NON-NLS-1$
        assertTrue("the hint must name the working verb", //$NON-NLS-1$
                source.contains("set_attribute_props")); //$NON-NLS-1$
    }

    // --- set_attribute_props op --------------------------------------------

    @Test
    public void setAttributePropsOpIsWired() throws Exception {
        String source = readSource(SERVICE_PATH);
        assertTrue("applyFormModelOperations must handle set_attribute_props", //$NON-NLS-1$
                source.contains("case \"setattributeprops\"")); //$NON-NLS-1$
        assertTrue("aliases set_attribute / update_attribute must be accepted", //$NON-NLS-1$
                source.contains("\"setattribute\", \"updateattribute\"")); //$NON-NLS-1$
        assertTrue("it must resolve the attribute with the shared resolver", //$NON-NLS-1$
                source.contains("resolveRequiredFormAttribute(formModel, operation)")); //$NON-NLS-1$
        assertTrue("the op-required hint must advertise it", //$NON-NLS-1$
                source.contains("remove_command, set_form_props, set_attribute_props")); //$NON-NLS-1$
    }

    // --- packaging + docs ---------------------------------------------------

    @Test
    public void manifestImportsTheDbViewPackage() throws Exception {
        String manifest = readSource(MANIFEST_PATH);
        assertTrue("mainTable is a DbViewDef — the package must be imported", //$NON-NLS-1$
                manifest.contains("com._1c.g5.v8.dt.metadata.dbview")); //$NON-NLS-1$
    }

    @Test
    public void toolSchemasDocumentTheDynamicListRoute() throws Exception {
        String mutate = readSource(MUTATE_TOOL_PATH);
        String recipe = readSource(RECIPE_TOOL_PATH);
        assertTrue("mutate_form_model must advertise set_attribute_props", //$NON-NLS-1$
                mutate.contains("set_attribute_props")); //$NON-NLS-1$
        assertTrue("mutate_form_model must say set_item targets ITEMS only", //$NON-NLS-1$
                mutate.contains("set_item resolves form ITEMS ONLY")); //$NON-NLS-1$
        assertTrue("mutate_form_model must mention customQuery", //$NON-NLS-1$
                mutate.contains("customQuery")); //$NON-NLS-1$
        assertTrue("apply_form_recipe attributes must mention extInfo", //$NON-NLS-1$
                recipe.contains("extInfo")); //$NON-NLS-1$
        assertTrue("apply_form_recipe attributes must mention the flat DynamicList keys", //$NON-NLS-1$
                recipe.contains("customQuery")); //$NON-NLS-1$
    }

    @Test
    public void knowledgeDocHasTheAutoToCustomQueryRecipe() throws Exception {
        String knowledge = readSource(KNOWLEDGE_PATH);
        assertTrue("knowledge/managed-forms.md must carry the recipe section", //$NON-NLS-1$
                knowledge.contains("Dynamic list: auto")); //$NON-NLS-1$
        assertTrue("the doc must warn about autoFillAvailableFields / fields", //$NON-NLS-1$
                knowledge.contains("autoFillAvailableFields")); //$NON-NLS-1$
        assertTrue("the doc must show the set_attribute_props call", //$NON-NLS-1$
                knowledge.contains("set_attribute_props")); //$NON-NLS-1$
    }

    // --- Helpers ------------------------------------------------------------

    private String readSource(String relativePath) throws Exception {
        Path repoRoot = findRepoRoot();
        return Files.readString(repoRoot.resolve(relativePath), StandardCharsets.UTF_8);
    }

    private Path findRepoRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize(); //$NON-NLS-1$
        while (current != null) {
            if (Files.isDirectory(current.resolve("bundles")) //$NON-NLS-1$
                    && Files.isRegularFile(current.resolve("pom.xml")) //$NON-NLS-1$
                    && Files.isRegularFile(current.resolve("LICENSE"))) { //$NON-NLS-1$
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Cannot locate repository root"); //$NON-NLS-1$
    }
}
