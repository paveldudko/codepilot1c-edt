package com.codepilot1c.core.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.Test;

import com.codepilot1c.core.tools.dcs.DcsManageTool;
import com.codepilot1c.core.tools.extension.ExtensionManageTool;
import com.codepilot1c.core.tools.external.ExternalManageTool;

/**
 * Behavioural tests for {@link CompositeCommandKeyGuard}: every assertion runs the guard and inspects
 * the verdict it returns, never the source that produced it.
 *
 * <p>The gap being closed: a composite tool advertises the UNION of all its commands' parameters, so
 * {@link SchemaKeyGuard} accepts {@code dataset_name} passed with {@code command:"upsert_param"} — the
 * schema does declare it — while the {@code upsert_param} branch never reads it. Silent drop plus a
 * success report, the same class {@code SchemaKeyGuard} was written to end.</p>
 *
 * <p>The per-command tables below are run against the tools' REAL schemas, so they are simultaneously
 * the expectation and the drift alarm: retag a description and the mismatch shows up here. Their more
 * important half is the negative one — a key listed for a command must NEVER be refused for it,
 * because a false refusal on a working call would be worse than the bug.</p>
 */
public class CompositeCommandKeyGuardTest {

    private static final String DCS_SCHEMA = new DcsManageTool().getParameterSchema();
    private static final String EXTERNAL_SCHEMA = new ExternalManageTool().getParameterSchema();
    private static final String EXTENSION_SCHEMA = new ExtensionManageTool().getParameterSchema();

    /** Untagged keys, accepted by every command of the tool. */
    private static final List<String> DCS_COMMON =
            List.of("command", "project", "owner_fqn", "validation_token");
    private static final List<String> EXTERNAL_COMMON =
            List.of("command", "project", "external_project", "validation_token");
    private static final List<String> EXTENSION_COMMON =
            List.of("command", "project", "base_project", "extension_project", "validation_token");

    // --- the contract really extracted from the real schemas -----------------

    @Test
    public void theCommandUniverseComesFromTheSchemaEnum() {
        assertEquals(Set.of("get_summary", "list_nodes", "create_schema",
                "upsert_dataset", "upsert_param", "upsert_field"),
                new TreeSet<>(CompositeCommandKeyGuard.declaredCommands(DCS_SCHEMA)));
        assertEquals(Set.of("list_projects", "list_objects", "details",
                "create_report", "create_processing"),
                new TreeSet<>(CompositeCommandKeyGuard.declaredCommands(EXTERNAL_SCHEMA)));
        assertEquals(Set.of("list_projects", "list_objects", "create", "adopt", "set_state"),
                new TreeSet<>(CompositeCommandKeyGuard.declaredCommands(EXTENSION_SCHEMA)));
    }

    @Test
    public void dcsManageAcceptsExactlyItsPerCommandKeysPlusTheCommonOnes() {
        assertAccepts(DCS_SCHEMA, "get_summary", DCS_COMMON, List.of());
        assertAccepts(DCS_SCHEMA, "list_nodes", DCS_COMMON,
                List.of("node_kind", "name_contains", "limit", "offset"));
        assertAccepts(DCS_SCHEMA, "create_schema", DCS_COMMON,
                List.of("template_name", "force_replace"));
        assertAccepts(DCS_SCHEMA, "upsert_dataset", DCS_COMMON,
                List.of("dataset_name", "query", "data_source",
                        "auto_fill_available_fields", "use_query_group_if_possible"));
        assertAccepts(DCS_SCHEMA, "upsert_param", DCS_COMMON,
                List.of("parameter_name", "expression", "available_as_field",
                        "value_list_allowed", "deny_incomplete_values", "use_restriction"));
        assertAccepts(DCS_SCHEMA, "upsert_field", DCS_COMMON,
                List.of("data_path", "expression", "presentation_expression"));
    }

    @Test
    public void externalManageAcceptsExactlyItsPerCommandKeysPlusTheCommonOnes() {
        // '(list)' and '(create)' are prefix tags: they must reach both list_ and both create_ commands.
        assertAccepts(EXTERNAL_SCHEMA, "list_projects", EXTERNAL_COMMON,
                List.of("name_contains", "limit", "offset"));
        assertAccepts(EXTERNAL_SCHEMA, "list_objects", EXTERNAL_COMMON,
                List.of("type_filter", "name_contains", "limit", "offset"));
        assertAccepts(EXTERNAL_SCHEMA, "details", EXTERNAL_COMMON, List.of("object_fqn"));
        assertAccepts(EXTERNAL_SCHEMA, "create_report", EXTERNAL_COMMON,
                List.of("name", "project_path", "version", "synonym", "comment"));
        assertAccepts(EXTERNAL_SCHEMA, "create_processing", EXTERNAL_COMMON,
                List.of("name", "project_path", "version", "synonym", "comment"));
    }

    @Test
    public void extensionManageAcceptsExactlyItsPerCommandKeysPlusTheCommonOnes() {
        assertAccepts(EXTENSION_SCHEMA, "list_projects", EXTENSION_COMMON, List.of());
        assertAccepts(EXTENSION_SCHEMA, "list_objects", EXTENSION_COMMON,
                List.of("type_filter", "name_contains", "limit", "offset"));
        assertAccepts(EXTENSION_SCHEMA, "create", EXTENSION_COMMON,
                List.of("project_path", "version", "configuration_name", "purpose",
                        "compatibility_mode"));
        assertAccepts(EXTENSION_SCHEMA, "adopt", EXTENSION_COMMON,
                List.of("source_object_fqn", "update_if_exists"));
        assertAccepts(EXTENSION_SCHEMA, "set_state", EXTENSION_COMMON,
                List.of("source_object_fqn", "property_name", "state"));
    }

    @Test
    public void theValidationTokenIsCommonToEveryCommandBecauseItsTagIsProse() {
        // "(mutating commands)" is prose, not a command name. Treating it as a command would refuse
        // validation_token everywhere and break every mutating call there is.
        for (String command : CompositeCommandKeyGuard.declaredCommands(DCS_SCHEMA)) {
            assertTrue(command + " must still accept validation_token",
                    CompositeCommandKeyGuard.acceptedKeys(DCS_SCHEMA, command).contains("validation_token"));
        }
    }

    // --- the hole itself -----------------------------------------------------

    @Test
    public void aKeyOfAnotherCommandIsReportedWithTheCommandThatOwnsIt() {
        CompositeCommandKeyGuard.Report report = CompositeCommandKeyGuard.inspect(DCS_SCHEMA,
                "upsert_param", List.of("command", "project", "owner_fqn", "dataset_name"));

        assertTrue(report.enforceable());
        assertFalse("dataset_name is not read by upsert_param", report.isClean());
        assertEquals(1, report.foreignKeys().size());
        assertEquals("dataset_name", report.foreignKeys().get(0).key());
        assertEquals(List.of("upsert_dataset"), report.foreignKeys().get(0).owningCommands());
    }

    @Test
    public void aKeySharedByTwoCommandsNamesBothAsItsOwners() {
        CompositeCommandKeyGuard.Report report = CompositeCommandKeyGuard.inspect(DCS_SCHEMA,
                "upsert_dataset", List.of("expression"));

        assertFalse(report.isClean());
        assertEquals(List.of("upsert_param", "upsert_field"),
                report.foreignKeys().get(0).owningCommands());
    }

    @Test
    public void everyForeignKeyIsReportedNotJustTheFirst() {
        CompositeCommandKeyGuard.Report report = CompositeCommandKeyGuard.inspect(DCS_SCHEMA,
                "list_nodes", List.of("limit", "dataset_name", "template_name", "data_path"));

        assertEquals(3, report.foreignKeys().size());
        assertEquals(List.of("dataset_name", "template_name", "data_path"),
                report.foreignKeys().stream().map(CompositeCommandKeyGuard.ForeignKey::key).toList());
    }

    @Test
    public void aKeyTheSchemaDoesNotDeclareAtAllIsLeftToSchemaKeyGuard() {
        // Two guards, two jobs: an undeclared key is SchemaKeyGuard's report, not this one's, so it
        // must not be reported twice with two different explanations.
        CompositeCommandKeyGuard.Report report = CompositeCommandKeyGuard.inspect(DCS_SCHEMA,
                "list_nodes", List.of("definitely_not_a_parameter"));

        assertTrue(report.enforceable());
        assertTrue(report.isClean());
    }

    // --- fail-open ------------------------------------------------------------

    @Test
    public void anAbsentCommandIsNotJudged() {
        CompositeCommandKeyGuard.Report report =
                CompositeCommandKeyGuard.inspect(DCS_SCHEMA, null, List.of("dataset_name"));

        assertFalse(report.enforceable());
        assertTrue(report.isClean());
    }

    @Test
    public void aCommandOutsideTheEnumIsNotJudged() {
        // The tool answers that itself with "Unknown command: …"; guessing a branch here would only
        // add a second, less accurate error.
        CompositeCommandKeyGuard.Report report =
                CompositeCommandKeyGuard.inspect(DCS_SCHEMA, "delete_everything", List.of("dataset_name"));

        assertFalse(report.enforceable());
        assertTrue(report.isClean());
    }

    @Test
    public void aCommandIsMatchedCaseAndPaddingInsensitively() {
        CompositeCommandKeyGuard.Report report =
                CompositeCommandKeyGuard.inspect(DCS_SCHEMA, "  Upsert_Param  ", List.of("dataset_name"));

        assertTrue(report.enforceable());
        assertFalse(report.isClean());
        assertEquals("upsert_param", report.command());
    }

    @Test
    public void aSchemaWithoutACommandEnumIsNotJudged() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "command": {"type": "string", "description": "free-form command"},
                    "limit": {"type": "integer", "description": "(list_nodes) Page size"}
                  }
                }
                """;
        assertFalse(CompositeCommandKeyGuard.inspect(schema, "list_nodes", List.of("limit")).enforceable());
    }

    @Test
    public void anUntaggedSchemaIsNotJudged() {
        // No description carries a command tag, so there is no contract to extract and nothing may be
        // refused — a plain command-dispatching tool must not start erroring because of this guard.
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "command": {"type": "string", "enum": ["a", "b"], "description": "which"},
                    "value": {"type": "string", "description": "a value"}
                  }
                }
                """;
        assertFalse(CompositeCommandKeyGuard.inspect(schema, "a", List.of("value")).enforceable());
        assertTrue(CompositeCommandKeyGuard.commandsByKey(schema).isEmpty());
    }

    @Test
    public void anAdditionalPropertiesSchemaIsNotJudged() {
        // How the dispatchers (edt_diagnostics, qa_inspect, qa_generate) declare that they route keys
        // they do not list.
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "command": {"type": "string", "enum": ["a", "b_thing"], "description": "which"},
                    "value": {"type": "string", "description": "(a) a value"}
                  },
                  "additionalProperties": true
                }
                """;
        assertFalse(CompositeCommandKeyGuard.inspect(schema, "b_thing", List.of("value")).enforceable());
    }

    @Test
    public void anUnparsableSchemaIsNotJudged() {
        assertFalse(CompositeCommandKeyGuard.inspect("{not json", "a", List.of("x")).enforceable());
        assertFalse(CompositeCommandKeyGuard.inspect(null, "a", List.of("x")).enforceable());
        assertFalse(CompositeCommandKeyGuard.inspect("", "a", List.of("x")).enforceable());
    }

    @Test
    public void aTagThatNamesNoCommandLeavesTheKeyCommon() {
        // "(optional)" is prose someone might reasonably write; it must not make the key exclusive to
        // an imaginary "optional" command.
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "command": {"type": "string", "enum": ["read", "write"], "description": "which"},
                    "payload": {"type": "string", "description": "(write) the data"},
                    "note": {"type": "string", "description": "(optional) free text"}
                  }
                }
                """;
        assertTrue(CompositeCommandKeyGuard.inspect(schema, "read", List.of("note")).isClean());
        assertFalse(CompositeCommandKeyGuard.inspect(schema, "read", List.of("payload")).isClean());
    }

    // --- the messages the caller actually reads -------------------------------

    @Test
    public void theRefusalNamesTheKeyTheOwningCommandAndWhatThisCommandAccepts() {
        CompositeCommandKeyGuard.Report report = CompositeCommandKeyGuard.inspect(DCS_SCHEMA,
                "upsert_param", List.of("dataset_name"));
        String message = CompositeCommandKeyGuard.refusalMessage("dcs_manage", report.command(),
                report.foreignKeys(), SchemaKeyGuard.forDisplay(report.acceptedKeys(), null));

        assertTrue(message, message.contains("'dataset_name'"));
        assertTrue("must name the command that owns the key: " + message,
                message.contains("belongs to 'upsert_dataset'"));
        assertTrue("must name the command that was requested: " + message,
                message.contains("not to 'upsert_param'"));
        assertTrue("must say no token was issued: " + message,
                message.contains("No validation token was issued"));
        assertTrue("must list what upsert_param does accept: " + message,
                message.contains("Keys accepted by 'upsert_param'")
                        && message.contains("parameter_name")
                        && message.contains("expression"));
        assertFalse("another command's key must not be listed as accepted",
                report.acceptedKeys().contains("dataset_name"));
    }

    @Test
    public void theAdvisoryNamesTheKeyTheOwningCommandAndWhatThisCommandAccepts() {
        CompositeCommandKeyGuard.Report report = CompositeCommandKeyGuard.inspect(EXTERNAL_SCHEMA,
                "list_objects", List.of("object_fqn"));
        String advisory = CompositeCommandKeyGuard.advisoryLine("external_manage", report.command(),
                report.foreignKeys(), SchemaKeyGuard.forDisplay(report.acceptedKeys(), null));

        assertTrue(advisory, advisory.startsWith("\n\nNote: external_manage ignored"));
        assertTrue(advisory, advisory.contains("'object_fqn' (belongs to 'details')"));
        assertTrue(advisory, advisory.contains("not to 'list_objects'"));
        assertTrue(advisory, advisory.contains("It had no effect on this call."));
        assertTrue(advisory, advisory.contains("Parameters accepted by 'list_objects'")
                && advisory.contains("type_filter"));
    }

    @Test
    public void severalForeignKeysAreCountedInThePlural() {
        CompositeCommandKeyGuard.Report report = CompositeCommandKeyGuard.inspect(EXTENSION_SCHEMA,
                "adopt", List.of("purpose", "state"));
        String message = CompositeCommandKeyGuard.refusalMessage("extension_manage", report.command(),
                report.foreignKeys(), SchemaKeyGuard.forDisplay(report.acceptedKeys(), null));

        assertTrue(message, message.contains("2 keys that belong to other"));
        assertTrue(message, message.contains("'purpose' (belongs to 'create')"));
        assertTrue(message, message.contains("'state' (belongs to 'set_state')"));
    }

    // --- helpers -------------------------------------------------------------

    private static void assertAccepts(String schema, String command, List<String> common,
            List<String> own) {
        Set<String> expected = new TreeSet<>(common);
        expected.addAll(own);
        assertEquals(command + " accepts the wrong key set", expected,
                new TreeSet<>(CompositeCommandKeyGuard.acceptedKeys(schema, command)));

        // The half that guards against a false refusal: each of these keys, passed together, is clean.
        assertTrue(command + " falsely refuses one of its own keys",
                CompositeCommandKeyGuard.inspect(schema, command, expected).isClean());
    }
}
