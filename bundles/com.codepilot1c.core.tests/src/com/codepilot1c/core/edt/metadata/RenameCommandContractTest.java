package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

/**
 * Source-contract test for the {@code rename_command} operation of
 * {@code mutate_form_model}.
 *
 * <p>Like {@link FormSerializationDefaultsContractTest}, the behavior cannot be
 * exercised through plain Maven test bundles because {@code Form},
 * {@code FormCommand}, {@code Button}, {@code FormFactory} etc. are only
 * resolvable inside the OSGi/EMF runtime. We pin the structure instead: the
 * test reads the source files as strings and asserts the required dispatch
 * case, helper methods and tool-schema wiring are present.</p>
 *
 * <p>Backs feedback {@code 2026-06-26-mutate-form-model-command-rename-limitations.md}
 * (BF-12562): there was no API path to rename a form command, because
 * {@code commandName} on buttons is a reference property ({@code set_item}
 * rejects it) and formCommands are not addressable in the {@code set_item} /
 * {@code remove_item} item tree.</p>
 */
public class RenameCommandContractTest {

    private static final String SERVICE_PATH =
            "bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/metadata/EdtMetadataService.java"; //$NON-NLS-1$
    private static final String TOOL_PATH =
            "bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/forms/MutateFormModelTool.java"; //$NON-NLS-1$

    @Test
    public void dispatchHasRenameCommandCase() throws Exception {
        String source = readSource(SERVICE_PATH);
        assertTrue("applyFormModelOperations must handle a rename_command op", //$NON-NLS-1$
                source.contains("case \"renamecommand\"")); //$NON-NLS-1$
    }

    @Test
    public void renameResolvesCommandByNameOrId() throws Exception {
        String source = readSource(SERVICE_PATH);
        // The command is located by command_name OR command_id (the id surfaced
        // by get_form_rendering, which set_item cannot address — Issue 2).
        assertTrue("rename_command must declare resolveRequiredFormCommand", //$NON-NLS-1$
                source.contains("private FormCommand resolveRequiredFormCommand(")); //$NON-NLS-1$
        assertTrue("resolveRequiredFormCommand must accept command_id", //$NON-NLS-1$
                source.contains("\"command_id\"")); //$NON-NLS-1$
        assertTrue("resolveRequiredFormCommand must accept command_name", //$NON-NLS-1$
                source.contains("\"command_name\"")); //$NON-NLS-1$
        assertTrue("rename_command must declare findFormCommand(id, name)", //$NON-NLS-1$
                source.contains("private FormCommand findFormCommand(")); //$NON-NLS-1$
    }

    @Test
    public void renameRejectsCollisionWithDifferentCommand() throws Exception {
        String source = readSource(SERVICE_PATH);
        // A case-only rename of the same command is allowed (target excluded via
        // `existing != command`); a clash with a different command is rejected.
        assertTrue("rename_command must exclude the target when checking name collisions", //$NON-NLS-1$
                source.contains("existing != command")); //$NON-NLS-1$
        assertTrue("rename_command must validate the new name", //$NON-NLS-1$
                source.contains("MetadataNameValidator.isValidName(newName)")); //$NON-NLS-1$
    }

    @Test
    public void renameRebindsReferencingButtons() throws Exception {
        String source = readSource(SERVICE_PATH);
        // Button.commandName is an EMF object reference, so referencing buttons
        // (incl. those inside autoCommandBars / context menus, reachable via
        // eAllContents) stay consistent across the rename — Issue 1.
        assertTrue("rename_command must declare rebindButtonsToCommand", //$NON-NLS-1$
                source.contains("private int rebindButtonsToCommand(")); //$NON-NLS-1$
        assertTrue("rebindButtonsToCommand must walk eAllContents() to reach every button", //$NON-NLS-1$
                source.contains("formModel.eAllContents()")); //$NON-NLS-1$
        assertTrue("rebindButtonsToCommand must re-set the command reference on matching buttons", //$NON-NLS-1$
                source.contains("button.setCommandName(command)")); //$NON-NLS-1$
    }

    @Test
    public void renameCanRebindActionHandler() throws Exception {
        String source = readSource(SERVICE_PATH);
        // Optional new_action rebinds the BSL handler procedure (Issue 5: set_item
        // could not set `action` on a formCommand).
        assertTrue("rename_command must declare applyCommandActionHandler", //$NON-NLS-1$
                source.contains("private void applyCommandActionHandler(")); //$NON-NLS-1$
        assertTrue("applyCommandActionHandler must set the handler name", //$NON-NLS-1$
                source.contains("handler.setName(actionName)")); //$NON-NLS-1$
        assertTrue("rename_command must accept a new_action override", //$NON-NLS-1$
                source.contains("\"new_action\"")); //$NON-NLS-1$
    }

    @Test
    public void toolSchemaAdvertisesRenameCommand() throws Exception {
        String tool = readSource(TOOL_PATH);
        assertTrue("mutate_form_model op enum must list rename_command", //$NON-NLS-1$
                tool.contains("rename_command")); //$NON-NLS-1$
    }

    @Test
    public void earlyValidationHintListsRenameCommand() throws Exception {
        String source = readSource(SERVICE_PATH);
        // The "op field required" guidance enumerates valid ops — keep it in sync.
        assertTrue("validateFormOperationParams hint must mention rename_command", //$NON-NLS-1$
                source.contains("move_item, rename_command")); //$NON-NLS-1$
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
