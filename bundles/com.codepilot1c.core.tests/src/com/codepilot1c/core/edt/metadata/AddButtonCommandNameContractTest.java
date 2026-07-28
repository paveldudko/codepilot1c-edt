package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

/**
 * Source-contract test for {@code add_button}'s command reference (BF-13330, side fix).
 *
 * <p>{@code Form.Command.<Name>} is exactly the notation the BM serializer writes into
 * {@code <commandName>}, so a caller that reads back its own {@code .form} and passes the
 * value straight into {@code add_button} was not hallucinating a format — yet
 * {@code findFormCommandByName} compared the raw string against {@code FormCommand.getName()}
 * (the bare name) and always missed. The prefix is now stripped case-insensitively, and the
 * two references that can never resolve to a form-local command get an explanation instead of
 * a bare "not found".</p>
 *
 * <p>Form/EMF types resolve only inside the OSGi runtime, so the structure is pinned by
 * reading the source, like {@link RemoveCommandContractTest}.</p>
 */
public class AddButtonCommandNameContractTest {

    private static final String SERVICE_PATH =
            "bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/metadata/EdtMetadataService.java"; //$NON-NLS-1$
    private static final String TOOL_PATH =
            "bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/forms/MutateFormModelTool.java"; //$NON-NLS-1$

    @Test
    public void qualifiedPrefixesAreStripped() throws Exception {
        String source = readSource(SERVICE_PATH);
        assertTrue("the accepted prefixes must be declared in one table", //$NON-NLS-1$
                source.contains("FORM_COMMAND_NAME_PREFIXES")); //$NON-NLS-1$
        assertTrue("Form.Command. is the notation a serialized .form carries", //$NON-NLS-1$
                source.contains("\"Form.Command.\"")); //$NON-NLS-1$
        assertTrue("FormCommand. must be accepted too", //$NON-NLS-1$
                source.contains("\"FormCommand.\"")); //$NON-NLS-1$
        assertTrue("bare Command. must be accepted too", //$NON-NLS-1$
                source.contains("\"Command.\"")); //$NON-NLS-1$
        assertTrue("stripping must be case-insensitive (regionMatches with ignoreCase)", //$NON-NLS-1$
                source.contains("value.regionMatches(true, 0, prefix, 0, prefix.length())")); //$NON-NLS-1$
    }

    @Test
    public void lookupGoesThroughTheStripper() throws Exception {
        String source = readSource(SERVICE_PATH);
        assertTrue("findFormCommandByName must normalize before comparing", //$NON-NLS-1$
                source.contains("String localName = stripFormCommandPrefix(name)")); //$NON-NLS-1$
        assertTrue("a bare name must keep working (compared against FormCommand.getName())", //$NON-NLS-1$
                source.contains("localName.equalsIgnoreCase(cmd.getName())")); //$NON-NLS-1$
        assertTrue("add_button must validate the reference through the dedicated resolver", //$NON-NLS-1$
                source.contains("resolveAddButtonCommandName(commandRef)")); //$NON-NLS-1$
    }

    @Test
    public void standardFormCommandsAreRejectedWithAnExplanation() throws Exception {
        String source = readSource(SERVICE_PATH);
        assertTrue("Form.StandardCommand.* must be recognized", //$NON-NLS-1$
                source.contains("\"Form.StandardCommand.\"")); //$NON-NLS-1$
        assertTrue("the refusal must be INVALID_METADATA_CHANGE, not a bare not-found", //$NON-NLS-1$
                source.contains("is a standard form command")); //$NON-NLS-1$
        assertTrue("the refusal must say standard commands are not form-local", //$NON-NLS-1$
                source.contains("cannot be added as a form-local button command")); //$NON-NLS-1$
    }

    @Test
    public void foreignNamespaceFqnIsRejectedWithAnExplanation() throws Exception {
        String source = readSource(SERVICE_PATH);
        assertTrue("an object/common command FQN must be rejected as METADATA_NOT_FOUND", //$NON-NLS-1$
                source.contains("is not a form-local command. add_button resolves only")); //$NON-NLS-1$
        assertTrue("the message must name the supported source collection", //$NON-NLS-1$
                source.contains("commands from Form.getFormCommands()")); //$NON-NLS-1$
        assertTrue("the message must name the unsupported shapes explicitly", //$NON-NLS-1$
                source.contains("Catalog.X.Command.Y")); //$NON-NLS-1$
        assertTrue("the workaround (form command + handler) must be suggested", //$NON-NLS-1$
                source.contains("call the object command from its handler")); //$NON-NLS-1$
    }

    @Test
    public void notFoundListsAvailableFormCommands() throws Exception {
        String source = readSource(SERVICE_PATH);
        // Same courtesy remove_command already extends when it refuses.
        assertTrue("the not-found message must list the available form commands", //$NON-NLS-1$
                source.contains("describeAvailableFormCommands(formModel)")); //$NON-NLS-1$
        assertTrue("the lister must read Form.getFormCommands()", //$NON-NLS-1$
                source.contains("private String describeAvailableFormCommands(")); //$NON-NLS-1$
        assertTrue("the hint must state both accepted formats", //$NON-NLS-1$
                source.contains("a bare name or Form.Command.<Name> is accepted")); //$NON-NLS-1$
    }

    @Test
    public void toolSchemaDocumentsBothFormats() throws Exception {
        String tool = readSource(TOOL_PATH);
        assertTrue("the schema must document that both formats are accepted", //$NON-NLS-1$
                tool.contains("command_name accepts BOTH the bare command name and the qualified")); //$NON-NLS-1$
        assertTrue("the schema must name the qualified notation", //$NON-NLS-1$
                tool.contains("Form.Command.<Name>")); //$NON-NLS-1$
        assertTrue("the schema must say object/common commands are unsupported", //$NON-NLS-1$
                tool.contains("only form-local commands resolve")); //$NON-NLS-1$
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
