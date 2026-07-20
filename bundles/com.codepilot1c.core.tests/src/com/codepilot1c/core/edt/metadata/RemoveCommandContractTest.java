package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

/**
 * Source-contract test for the {@code remove_command} operation of
 * {@code mutate_form_model} (BF-12936 / BF-12562 Issue 3).
 *
 * <p>Like {@link RenameCommandContractTest}, the behavior cannot run through plain
 * Maven test bundles because {@code Form}, {@code FormCommand}, {@code Button},
 * {@code FormFactory} etc. resolve only inside the OSGi/EMF runtime. We pin the
 * structure instead by reading the source as strings.</p>
 *
 * <p>Gap: form-local commands live in {@code Form.getFormCommands()}, not the UI
 * item tree, so {@code remove_item} ("Cannot remove root form container item") could
 * never delete them. Relocating form-local commands to register-owned commands then
 * left the old form commands undeletable ("finishes only with dead metadata"), and
 * dropping their BSL handler in isolation raised "handler not found". {@code
 * remove_command} deletes the form command (its contained action/handler subtree goes
 * with it, atomically), refusing when buttons still reference it unless {@code
 * remove_referencing_buttons=true} drops those buttons too.</p>
 */
public class RemoveCommandContractTest {

    private static final String SERVICE_PATH =
            "bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/metadata/EdtMetadataService.java"; //$NON-NLS-1$
    private static final String TOOL_PATH =
            "bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/forms/MutateFormModelTool.java"; //$NON-NLS-1$

    @Test
    public void dispatchHasRemoveCommandCase() throws Exception {
        String source = readSource(SERVICE_PATH);
        assertTrue("applyFormModelOperations must handle a remove_command op", //$NON-NLS-1$
                source.contains("case \"removecommand\"")); //$NON-NLS-1$
    }

    @Test
    public void removeResolvesCommandByNameOrId() throws Exception {
        String source = readSource(SERVICE_PATH);
        // Reuses the same resolver as rename_command (command_name OR command_id).
        assertTrue("remove_command must resolve via resolveRequiredFormCommand", //$NON-NLS-1$
                source.contains("resolveRequiredFormCommand(formModel, operation)")); //$NON-NLS-1$
    }

    @Test
    public void removeCollectsReferencingButtons() throws Exception {
        String source = readSource(SERVICE_PATH);
        assertTrue("remove_command must declare collectReferencingButtons", //$NON-NLS-1$
                source.contains("private List<Button> collectReferencingButtons(")); //$NON-NLS-1$
        assertTrue("button collection must walk eAllContents() to reach every button", //$NON-NLS-1$
                source.contains("formModel.eAllContents()")); //$NON-NLS-1$
    }

    @Test
    public void removeRefusesWhenReferencedWithoutFlag() throws Exception {
        String source = readSource(SERVICE_PATH);
        // Default: a still-referenced command is a delete conflict, not a silent orphaning.
        assertTrue("remove_command must raise METADATA_DELETE_CONFLICT when referenced", //$NON-NLS-1$
                source.contains("MetadataOperationCode.METADATA_DELETE_CONFLICT")); //$NON-NLS-1$
        assertTrue("refusal message must point at the remove_referencing_buttons escape hatch", //$NON-NLS-1$
                source.contains("remove_referencing_buttons=true")); //$NON-NLS-1$
    }

    @Test
    public void removeHonorsRemoveReferencingButtonsFlag() throws Exception {
        String source = readSource(SERVICE_PATH);
        assertTrue("remove_command must read the remove_referencing_buttons flag", //$NON-NLS-1$
                source.contains("\"remove_referencing_buttons\"")); //$NON-NLS-1$
        // Referencing buttons are detached from their parent container before the drop.
        assertTrue("flagged removal must detach buttons from their parent container", //$NON-NLS-1$
                source.contains("parent.getItems().remove(b)")); //$NON-NLS-1$
    }

    @Test
    public void removeDropsTheFormCommand() throws Exception {
        String source = readSource(SERVICE_PATH);
        assertTrue("remove_command must drop the command from getFormCommands()", //$NON-NLS-1$
                source.contains("formModel.getFormCommands().remove(command)")); //$NON-NLS-1$
    }

    @Test
    public void toolSchemaAdvertisesRemoveCommand() throws Exception {
        String tool = readSource(TOOL_PATH);
        assertTrue("mutate_form_model op enum must list remove_command", //$NON-NLS-1$
                tool.contains("remove_command")); //$NON-NLS-1$
    }

    @Test
    public void earlyValidationHintListsRemoveCommand() throws Exception {
        String source = readSource(SERVICE_PATH);
        assertTrue("validateFormOperationParams hint must mention remove_command", //$NON-NLS-1$
                source.contains("remove_command, set_form_props")); //$NON-NLS-1$
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
