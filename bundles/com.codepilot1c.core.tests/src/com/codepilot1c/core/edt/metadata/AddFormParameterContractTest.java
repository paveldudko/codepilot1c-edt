package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

/**
 * Source-contract test for the {@code add_form_parameter} operation of
 * {@code mutate_form_model}.
 *
 * <p>Like {@link RenameCommandContractTest}, the behavior cannot be exercised through plain Maven
 * test bundles because {@code Form}, {@code FormParameter}, {@code FormFactory} etc. only resolve
 * inside the OSGi/EMF runtime. We pin the structure instead: the test reads the source files as
 * strings and asserts the dispatch case, the create+attach+type sequence, and the tool-schema
 * wiring are present.</p>
 *
 * <p>Backs feedback {@code 2026-07-14-bf12839-mutate-form-model-no-way-to-declare-form-parameter.md}:
 * there was no tool path to declare a form-level Parameter — {@code set_form_props} rejects
 * {@code parameters} as a reference collection and {@code add_metadata_child} has no
 * {@code Parameter} child_kind, so a correct BSL {@code Parameters.Property("X")} still tripped the
 * cosmetic {@code unknown-form-parameter-access} diagnostic.</p>
 */
public class AddFormParameterContractTest {

    private static final String SERVICE_PATH =
            "bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/metadata/EdtMetadataService.java"; //$NON-NLS-1$
    private static final String TOOL_PATH =
            "bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/forms/MutateFormModelTool.java"; //$NON-NLS-1$

    @Test
    public void dispatchHasAddFormParameterCase() throws Exception {
        String source = readSource(SERVICE_PATH);
        assertTrue("applyFormModelOperations must handle an add_form_parameter op", //$NON-NLS-1$
                source.contains("case \"addformparameter\"")); //$NON-NLS-1$
    }

    @Test
    public void createsAndAttachesTheParameter() throws Exception {
        String source = readSource(SERVICE_PATH);
        assertTrue("must create a FormParameter via the factory", //$NON-NLS-1$
                source.contains("FormFactory.eINSTANCE.createFormParameter()")); //$NON-NLS-1$
        assertTrue("must attach it to the form's parameters collection", //$NON-NLS-1$
                source.contains("formModel.getParameters().add(parameter)")); //$NON-NLS-1$
        // Attach must precede type resolution (xtext scoping needs form/config context) —
        // mirror the add_field attribute ordering.
        int attachAt = source.indexOf("formModel.getParameters().add(parameter)"); //$NON-NLS-1$
        int typeAt = source.indexOf("applyFormAttributeType(parameter,"); //$NON-NLS-1$
        assertTrue("the parameter must be attached before its type is resolved", //$NON-NLS-1$
                attachAt > 0 && typeAt > 0 && attachAt < typeAt);
    }

    @Test
    public void reusesTheAttributeTypeResolver() throws Exception {
        String source = readSource(SERVICE_PATH);
        assertTrue("type must be applied through the shared applyFormAttributeType path", //$NON-NLS-1$
                source.contains("applyFormAttributeType(parameter,")); //$NON-NLS-1$
        // The shared resolver was generalized from AbstractFormAttribute to EObject so a
        // FormParameter can flow through it.
        assertTrue("applyFormAttributeType must accept an EObject holder", //$NON-NLS-1$
                source.contains("EObject attribute")); //$NON-NLS-1$
        // The value type lives on a FormParameter-specific EReference.
        assertTrue("type-provider route must pick the FormParameter valueType reference", //$NON-NLS-1$
                source.contains("getFormParameter_ValueType()")); //$NON-NLS-1$
        // setTypeDescriptionOnEObject must be able to resolve the generic "valueType" feature.
        assertTrue("setTypeDescriptionOnEObject must resolve the valueType feature", //$NON-NLS-1$
                source.contains("resolveStructuralFeatureIgnoreCase(target, \"valueType\")")); //$NON-NLS-1$
    }

    @Test
    public void rejectsDuplicateParameterName() throws Exception {
        String source = readSource(SERVICE_PATH);
        assertTrue("must reject a duplicate parameter name", //$NON-NLS-1$
                source.contains("Form parameter already exists: ")); //$NON-NLS-1$
        assertTrue("must validate the parameter name", //$NON-NLS-1$
                source.contains("Invalid form parameter name: ")); //$NON-NLS-1$
    }

    @Test
    public void toolSchemaAdvertisesAddFormParameter() throws Exception {
        String tool = readSource(TOOL_PATH);
        assertTrue("mutate_form_model op enum must list add_form_parameter", //$NON-NLS-1$
                tool.contains("add_form_parameter")); //$NON-NLS-1$
    }

    @Test
    public void earlyValidationHintListsAddFormParameter() throws Exception {
        String source = readSource(SERVICE_PATH);
        assertTrue("validateFormOperationParams hint must mention add_form_parameter", //$NON-NLS-1$
                source.contains("add_form_parameter")); //$NON-NLS-1$
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
