package com.codepilot1c.core.edt.dcs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

/**
 * Source-contract test for {@code dcs_manage command=create_schema} persistence.
 *
 * <p>The defect: {@code DcsFactory.createDataCompositionSchema()} was assigned straight into
 * {@code template.setTemplate(schema)}. {@code BasicTemplate.template} is a <b>transient,
 * non-containment</b> reference (same flags as {@code Role.rights} / {@code BasicForm.form}) and the
 * schema is a SEPARATE top-object written to {@code Templates/&lt;name&gt;/Template.dcs} — so the
 * orphan was never attached, never exported, and the tool reported success with no file on disk.</p>
 *
 * <p>Neither {@code attachTopObject} nor "the .dcs appeared" can be exercised outside the OSGi/EMF
 * runtime, so this test pins the STRUCTURE that the live fix depends on, exactly like
 * {@code RemoveCommandContractTest} / {@code DynamicListExtInfoContractTest} do for the form model.
 * The ordering assertions matter as much as the presence ones: generating the external FQN before
 * the template is inside the owner's {@code templates} list raises a raw
 * {@code AssertionFailedException} instead of anything actionable.</p>
 */
public class DcsMainSchemaPersistenceContractTest {

    private static final String SERVICE_PATH =
            "bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/dcs/EdtDcsService.java"; //$NON-NLS-1$
    private static final String EXPORT_PATH =
            "bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/dcs/DcsExportSupport.java"; //$NON-NLS-1$
    private static final String TOOL_PATH =
            "bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/dcs/DcsManageTool.java"; //$NON-NLS-1$
    private static final String VALIDATION_PATH =
            "bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/validation/MetadataRequestValidationService.java"; //$NON-NLS-1$

    // --- the attach itself ---------------------------------------------------

    @Test
    public void schemaIsAttachedAsATopObject() throws Exception {
        String source = readSource(SERVICE_PATH);
        assertTrue("the DCS schema must be attached to the BM as an external top object", //$NON-NLS-1$
                source.contains("transaction.attachTopObject(namespace, createdBm, externalFqn)")); //$NON-NLS-1$
    }

    @Test
    public void externalFqnComesFromTheBasicTemplateTemplateReference() throws Exception {
        String source = readSource(SERVICE_PATH);
        assertTrue("the external FQN must be generated for BASIC_TEMPLATE__TEMPLATE", //$NON-NLS-1$
                source.contains("MdClassPackage.Literals.BASIC_TEMPLATE__TEMPLATE")); //$NON-NLS-1$
    }

    // --- operation ORDER -----------------------------------------------------

    @Test
    public void templateJoinsTheOwnerListBeforeItsFqnIsGenerated() throws Exception {
        String source = readSource(SERVICE_PATH);
        int add = source.indexOf("templates.templates().add(target)"); //$NON-NLS-1$
        int generate = source.indexOf("generateSchemaExternalFqn(target"); //$NON-NLS-1$
        assertTrue("the template must be added to the owner's templates list", add > 0); //$NON-NLS-1$
        assertTrue("the external FQN must be generated", generate > 0); //$NON-NLS-1$
        assertTrue("containment must precede FQN generation (the FQN is derived from the container chain)", //$NON-NLS-1$
                add < generate);
    }

    @Test
    public void nameAndTypeAreSetBeforeTheTemplateIsAdded() throws Exception {
        String source = readSource(SERVICE_PATH);
        int setName = source.indexOf("target.setName(requestedName)"); //$NON-NLS-1$
        int setType = source.indexOf("target.setTemplateType(TemplateType.DATA_COMPOSITION_SCHEMA)"); //$NON-NLS-1$
        int add = source.indexOf("templates.templates().add(target)"); //$NON-NLS-1$
        assertTrue("the template name must be set", setName > 0); //$NON-NLS-1$
        assertTrue("the template type must be DATA_COMPOSITION_SCHEMA", setType > 0); //$NON-NLS-1$
        assertTrue("setName must precede the list insertion", setName < add); //$NON-NLS-1$
        assertTrue("setTemplateType must precede the list insertion", setType < add); //$NON-NLS-1$
    }

    @Test
    public void attachHappensAfterTheTemplateIsAdded() throws Exception {
        String source = readSource(SERVICE_PATH);
        int add = source.indexOf("templates.templates().add(target)"); //$NON-NLS-1$
        int attach = source.indexOf("transaction.attachTopObject("); //$NON-NLS-1$
        assertTrue("attachTopObject must be called", attach > 0); //$NON-NLS-1$
        assertTrue("attachTopObject must come after the template joins the owner's list", add < attach); //$NON-NLS-1$
    }

    @Test
    public void defensiveReuseProbeRunsBeforeTheAttach() throws Exception {
        String source = readSource(SERVICE_PATH);
        int probe = source.indexOf("Object preexisting = transaction.getTopObjectByFqn(namespace, externalFqn)"); //$NON-NLS-1$
        int attach = source.indexOf("transaction.attachTopObject("); //$NON-NLS-1$
        assertTrue("a getTopObjectByFqn reuse probe must precede the attach", probe > 0 && probe < attach); //$NON-NLS-1$
    }

    @Test
    public void onlyTheReReadTransactionObjectIsWrittenIntoTheReference() throws Exception {
        String source = readSource(SERVICE_PATH);
        int reread = source.indexOf("Object attached = transaction.getTopObjectByFqn(namespace, externalFqn)"); //$NON-NLS-1$
        int bind = source.indexOf("target.setTemplate(schema)"); //$NON-NLS-1$
        assertTrue("the attached object must be re-read from the transaction", reread > 0); //$NON-NLS-1$
        assertTrue("the reference must be written after the re-read", bind > reread); //$NON-NLS-1$
        // The pre-fix bug in one line: binding the freshly created, unattached instance.
        assertFalse("the unattached factory instance must never be written into the reference", //$NON-NLS-1$
                source.contains("setTemplate(created)")); //$NON-NLS-1$
    }

    // --- name-based idempotency ---------------------------------------------

    @Test
    public void nameSlotIsClassifiedBeforeATemplateIsCreated() throws Exception {
        String source = readSource(SERVICE_PATH);
        int lookup = source.indexOf("findTemplateByName(templates.templates(), requestedName)"); //$NON-NLS-1$
        int classify = source.indexOf("DcsSchemaSupport.classifyNameSlot("); //$NON-NLS-1$
        int create = source.indexOf("MdClassFactory.eINSTANCE.createTemplate()"); //$NON-NLS-1$
        assertTrue("an existing template with the same name must be looked up", lookup > 0); //$NON-NLS-1$
        assertTrue("the name slot must be classified", classify > 0); //$NON-NLS-1$
        assertTrue("the name lookup must precede template creation", lookup < create); //$NON-NLS-1$
        assertTrue("the classification must precede template creation", classify < create); //$NON-NLS-1$
    }

    @Test
    public void forceReplaceResetsContentInsteadOfAppending() throws Exception {
        String source = readSource(SERVICE_PATH);
        assertTrue("a reused schema must have its content reset", //$NON-NLS-1$
                source.contains("private void resetSchemaContent(DataCompositionSchema schema)")); //$NON-NLS-1$
        assertTrue("resetSchemaContent must clear the data sets", //$NON-NLS-1$
                source.contains("schema.getDataSets().clear()")); //$NON-NLS-1$
        assertTrue("resetSchemaContent must clear the parameters", //$NON-NLS-1$
                source.contains("schema.getParameters().clear()")); //$NON-NLS-1$
        assertTrue("resetSchemaContent must be applied to the reused schema", //$NON-NLS-1$
                source.contains("resetSchemaContent(schema)")); //$NON-NLS-1$
    }

    @Test
    public void anOccupiedNameOfAnotherTypeIsRefusedWithAPointerToForceReplace() throws Exception {
        String source = readSource(SERVICE_PATH);
        assertTrue("a name held by a non-DCS template must raise METADATA_ALREADY_EXISTS", //$NON-NLS-1$
                source.contains("MetadataOperationCode.METADATA_ALREADY_EXISTS")); //$NON-NLS-1$
        assertTrue("the refusal must point at the force_replace escape hatch", //$NON-NLS-1$
                source.contains("Pass force_replace=true")); //$NON-NLS-1$
    }

    // --- default data source -------------------------------------------------

    @Test
    public void aDefaultDataSourceIsSeeded() throws Exception {
        String source = readSource(SERVICE_PATH);
        assertTrue("the default data source must be named DataSource1", //$NON-NLS-1$
                source.contains("DEFAULT_DATA_SOURCE_NAME = \"DataSource1\"")); //$NON-NLS-1$
        // dataSourceType is a plain String in the DCS model, not an enum.
        assertTrue("the default data source type must be the Local string", //$NON-NLS-1$
                source.contains("DEFAULT_DATA_SOURCE_TYPE = \"Local\"")); //$NON-NLS-1$
        assertTrue("the schema must get a data source", //$NON-NLS-1$
                source.contains("ensureDefaultDataSource(schema)")); //$NON-NLS-1$
    }

    // --- force export with the extra FQN ------------------------------------

    @Test
    public void createSchemaForceExportsTheSchemaFragmentAlongsideTheOwner() throws Exception {
        String source = readSource(SERVICE_PATH);
        assertTrue("create_schema must force-export the owner AND the schema external FQN", //$NON-NLS-1$
                source.contains("exportSupport.forceExport(project, ownerTopLevelFqn, state.externalFqn, opId)")); //$NON-NLS-1$
    }

    @Test
    public void everyDcsMutatorForceExportsWithTheSchemaFqn() throws Exception {
        String source = readSource(SERVICE_PATH);
        // upsert_dataset / upsert_param / upsert_field — all three used to commit without any export.
        assertEquals("all three upsert mutators must force-export with the schema FQN as extraFqn", //$NON-NLS-1$
                3, countOccurrences(source,
                        "exportSupport.forceExport(project, ownerTopLevelFqn, schemaFqn.value, opId)")); //$NON-NLS-1$
        assertEquals("each upsert must capture the schema's BM FQN inside the transaction", //$NON-NLS-1$
                3, countOccurrences(source, "schemaFqn.value = bmFqnOf(schema)")); //$NON-NLS-1$
    }

    @Test
    public void theExtraFqnReachesTheExportTargetList() throws Exception {
        String source = readSource(EXPORT_PATH);
        assertTrue("buildExportTargets must accept the extra external-property FQN", //$NON-NLS-1$
                source.contains("List<String> buildExportTargets(String fqn, String extraFqn)")); //$NON-NLS-1$
        assertTrue("the extra FQN must be added to the target set", //$NON-NLS-1$
                source.contains("targets.add(extraFqn)")); //$NON-NLS-1$
        assertTrue("Configuration must stay in the target set", //$NON-NLS-1$
                source.contains("targets.add(\"Configuration\")")); //$NON-NLS-1$
    }

    // --- EOL guard -----------------------------------------------------------

    @Test
    public void everyDcsMutatorIsWrappedInAnEolGuard() throws Exception {
        String source = readSource(SERVICE_PATH);
        assertEquals("create_schema + 3 upserts must each snapshot EOL before mutating", //$NON-NLS-1$
                4, countOccurrences(source, "exportSupport.beginEolGuard(")); //$NON-NLS-1$
        assertEquals("each guard must be restored after the export pipeline", //$NON-NLS-1$
                4, countOccurrences(source, "eolGuard.restore()")); //$NON-NLS-1$
    }

    @Test
    public void eolGuardIsOpenedBeforeTheTransaction() throws Exception {
        String source = readSource(SERVICE_PATH);
        int guard = source.indexOf("DcsExportSupport.EolGuard eolGuard = exportSupport.beginEolGuard("); //$NON-NLS-1$
        int write = source.indexOf("executeWrite(project, transaction -> {"); //$NON-NLS-1$
        assertTrue("the EOL snapshot must be taken before the mutating transaction", //$NON-NLS-1$
                guard > 0 && guard < write);
    }

    @Test
    public void theDcsExtensionIsEolManaged() throws Exception {
        String source = readSource(EXPORT_PATH);
        assertTrue("the .dcs artifact must take part in EOL preservation", //$NON-NLS-1$
                source.contains("\"dcs\"")); //$NON-NLS-1$
    }

    // --- honest post-write state --------------------------------------------

    @Test
    public void theResultReportsTheRealOnDiskState() throws Exception {
        String source = readSource(SERVICE_PATH);
        assertTrue("the schema file must be probed on disk", //$NON-NLS-1$
                source.contains("probeSchemaFile(project, relativePath)")); //$NON-NLS-1$
        assertTrue("the probe must target the separate Template.dcs artifact", //$NON-NLS-1$
                source.contains("DcsSchemaSupport.schemaFileRelativePath(ownerFqn, state.templateName)")); //$NON-NLS-1$
        assertTrue("a missing artifact must produce an explicit warning, not a silent success", //$NON-NLS-1$
                source.contains("was NOT found on disk after export")); //$NON-NLS-1$
    }

    @Test
    public void aMissingArtifactTriggersARepairExport() throws Exception {
        String source = readSource(SERVICE_PATH);
        assertTrue("an existing-in-model / missing-on-disk schema must be re-exported", //$NON-NLS-1$
                source.contains("state.mutated() || (relativePath != null && !present)")); //$NON-NLS-1$
    }

    // --- diagnostics for the live build round -------------------------------

    @Test
    public void dcsDiagnosticsCoverFqnLookupAndExportTargets() throws Exception {
        String source = readSource(SERVICE_PATH);
        assertTrue("the generated external FQN must be logged against its expected shape", //$NON-NLS-1$
                source.contains("schema externalFqn=%s expected=%s match=%s")); //$NON-NLS-1$
        assertTrue("the getTopObjectByFqn result must be logged", //$NON-NLS-1$
                source.contains("getTopObjectByFqn(%s) -> %s")); //$NON-NLS-1$
        assertTrue("the on-disk probe result must be logged", //$NON-NLS-1$
                source.contains("schema-file probe %s present=%s")); //$NON-NLS-1$
        String export = readSource(EXPORT_PATH);
        assertTrue("the export target list must be logged", //$NON-NLS-1$
                export.contains("forceExport targets=%s")); //$NON-NLS-1$
    }

    // --- template_name provenance -------------------------------------------

    @Test
    public void theStartLineSaysWhereTheTemplateNameCameFrom() throws Exception {
        String source = readSource(SERVICE_PATH);
        // template=MainDataCompositionSchema alone cannot tell an explicit request from the default.
        assertTrue("the START line must log the provenance of the template name", //$NON-NLS-1$
                source.contains("templateSource=%s")); //$NON-NLS-1$
        assertTrue("the provenance must be derived from the request, not guessed", //$NON-NLS-1$
                source.contains("request.hasExplicitTemplateName() ? \"caller\" : \"default\"")); //$NON-NLS-1$
    }

    @Test
    public void forceReplaceWarnsOnlyWhenAnExplicitNameIsIgnored() throws Exception {
        String source = readSource(SERVICE_PATH);
        assertTrue("dropping a caller-supplied name must be a warning", //$NON-NLS-1$
                source.contains("IGNORES the explicitly requested")); //$NON-NLS-1$
        assertTrue("dropping the default is routine, not a warning", //$NON-NLS-1$
                source.contains("instead of creating the default")); //$NON-NLS-1$
    }

    @Test
    public void theValidatedPayloadDoesNotMaterializeTheDefaultName() throws Exception {
        String source = readSource(VALIDATION_PATH);
        int guard = source.indexOf("if (request.hasExplicitTemplateName()) {"); //$NON-NLS-1$
        int put = source.indexOf("payload.put(\"template_name\", request.effectiveTemplateName())"); //$NON-NLS-1$
        assertTrue("the payload must be guarded by an explicitness check", guard > 0); //$NON-NLS-1$
        assertTrue("template_name must be written only inside that guard", put > guard); //$NON-NLS-1$
        assertEquals("exactly one template_name write, otherwise the guard can be bypassed", //$NON-NLS-1$
                1, countOccurrences(source, "payload.put(\"template_name\"")); //$NON-NLS-1$
    }

    // --- tool contract -------------------------------------------------------

    @Test
    public void toolSchemaDocumentsTheReplaceSemanticsOfForceReplace() throws Exception {
        String tool = readSource(TOOL_PATH);
        assertTrue("force_replace must be documented as a content replacement, not an append", //$NON-NLS-1$
                tool.contains("resets its content and rebinds it")); //$NON-NLS-1$
        assertTrue("template_name must document the case-insensitive match", //$NON-NLS-1$
                tool.contains("Matched case-insensitively against existing templates")); //$NON-NLS-1$
    }

    // --- Helpers ------------------------------------------------------------

    private int countOccurrences(String source, String needle) {
        int count = 0;
        int from = source.indexOf(needle);
        while (from >= 0) {
            count++;
            from = source.indexOf(needle, from + needle.length());
        }
        return count;
    }

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
