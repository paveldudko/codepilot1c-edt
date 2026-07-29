package com.codepilot1c.core.tools.metadata;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.codepilot1c.core.edt.validation.MetadataRequestValidationService;
import com.codepilot1c.core.edt.validation.ValidationOperation;
import com.codepilot1c.core.tools.SchemaKeyGuard;

/**
 * The audit that had to pass before the {@code edt_validate_request} guard could be made strict:
 * every operation in the advertised {@code operation} enum must reach a target tool whose schema
 * really does enumerate its keys, and every key
 * {@link MetadataRequestValidationService#validateAndIssueToken} reads out of a payload must be an
 * accepted key. A false refusal on a legitimate call would be a worse outcome than the silent-drop
 * bug, so this is the guard against exactly that.
 *
 * <p>The expectation table below is the audit result, one line per operation, transcribed from the
 * {@code normalizePayload} switch. It doubles as the drift alarm: add a key to a normalizer branch
 * without adding it to the tool's schema and this test fails instead of a live caller.</p>
 */
public class ValidationPayloadKeyContractAuditTest {

    /** Every top-level payload key {@code normalizePayload} reads, per operation. */
    private static final Map<ValidationOperation, List<String>> KEYS_THE_VALIDATOR_READS =
            new EnumMap<>(ValidationOperation.class);

    static {
        KEYS_THE_VALIDATOR_READS.put(ValidationOperation.CREATE_METADATA, List.of(
                "project", "kind", "name", "synonym", "comment", "properties",
                "adopt_existing", "adoptExisting", "adopt"));
        KEYS_THE_VALIDATOR_READS.put(ValidationOperation.CREATE_FORM, List.of(
                "project", "owner_fqn", "name", "usage", "managed", "set_as_default",
                "synonym", "comment", "wait_ms"));
        KEYS_THE_VALIDATOR_READS.put(ValidationOperation.APPLY_FORM_RECIPE, List.of(
                "project", "mode", "form_fqn", "owner_fqn", "name", "usage", "managed",
                "set_as_default", "synonym", "comment", "wait_ms", "attributes", "layout"));
        KEYS_THE_VALIDATOR_READS.put(ValidationOperation.EXTERNAL_CREATE_REPORT, List.of(
                "command", "project", "external_project", "name", "project_path", "version",
                "synonym", "comment"));
        KEYS_THE_VALIDATOR_READS.put(ValidationOperation.EXTERNAL_CREATE_PROCESSING, List.of(
                "command", "project", "external_project", "name", "project_path", "version",
                "synonym", "comment"));
        KEYS_THE_VALIDATOR_READS.put(ValidationOperation.EXTENSION_CREATE_PROJECT, List.of(
                "command", "project", "extension_project", "base_project", "project_path",
                "version", "configuration_name", "purpose", "compatibility_mode"));
        KEYS_THE_VALIDATOR_READS.put(ValidationOperation.EXTENSION_ADOPT_OBJECT, List.of(
                "command", "project", "extension_project", "base_project", "source_object_fqn",
                "update_if_exists"));
        KEYS_THE_VALIDATOR_READS.put(ValidationOperation.EXTENSION_SET_PROPERTY_STATE, List.of(
                "command", "project", "extension_project", "base_project", "source_object_fqn",
                "property_name", "state"));
        KEYS_THE_VALIDATOR_READS.put(ValidationOperation.DCS_CREATE_MAIN_SCHEMA, List.of(
                "command", "project", "owner_fqn", "template_name", "force_replace"));
        KEYS_THE_VALIDATOR_READS.put(ValidationOperation.DCS_UPSERT_QUERY_DATASET, List.of(
                "command", "project", "owner_fqn", "dataset_name", "query", "data_source",
                "auto_fill_available_fields", "use_query_group_if_possible"));
        KEYS_THE_VALIDATOR_READS.put(ValidationOperation.DCS_UPSERT_PARAMETER, List.of(
                "command", "project", "owner_fqn", "parameter_name", "expression",
                "available_as_field", "value_list_allowed", "deny_incomplete_values",
                "use_restriction"));
        KEYS_THE_VALIDATOR_READS.put(ValidationOperation.DCS_UPSERT_CALCULATED_FIELD, List.of(
                "command", "project", "owner_fqn", "data_path", "expression",
                "presentation_expression"));
        KEYS_THE_VALIDATOR_READS.put(ValidationOperation.ADD_METADATA_CHILD, List.of(
                "project", "parent_fqn", "child_kind", "name", "synonym", "comment",
                "properties", "template_type"));
        KEYS_THE_VALIDATOR_READS.put(ValidationOperation.ENSURE_MODULE_ARTIFACT, List.of(
                "project", "object_fqn", "objectFqn", "module_kind", "moduleType", "moduleKind",
                "create_if_missing", "createIfMissing", "initial_content", "initialContent"));
        KEYS_THE_VALIDATOR_READS.put(ValidationOperation.UPDATE_METADATA, List.of(
                "project", "target_fqn", "changes"));
        KEYS_THE_VALIDATOR_READS.put(ValidationOperation.DELETE_METADATA, List.of(
                "project", "target_fqn", "recursive", "force"));
        KEYS_THE_VALIDATOR_READS.put(ValidationOperation.MUTATE_FORM_MODEL, List.of(
                "project", "form_fqn", "operations"));
        KEYS_THE_VALIDATOR_READS.put(ValidationOperation.RIGHTS_MANAGE, List.of(
                "project", "role", "role_fqn", "role_name", "grants"));
        KEYS_THE_VALIDATOR_READS.put(ValidationOperation.RENDER_TEMPLATE, List.of(
                "project", "template_fqn", "sections"));
        KEYS_THE_VALIDATOR_READS.put(ValidationOperation.CREATE_EVENT_SUBSCRIPTION, List.of(
                "project", "name", "synonym", "comment", "source_types", "event", "handler"));
        KEYS_THE_VALIDATOR_READS.put(ValidationOperation.CREATE_INFORMATION_REGISTER, List.of(
                "project", "name", "synonym", "comment", "periodicity", "dimensions", "resources"));
    }

    @Test
    public void everyOperationHasAnExpectationLineSoNoneEscapesTheAudit() {
        for (ValidationOperation operation : ValidationOperation.values()) {
            assertTrue("operation " + operation + " was added without auditing its payload keys", //$NON-NLS-1$ //$NON-NLS-2$
                    KEYS_THE_VALIDATOR_READS.containsKey(operation));
        }
    }

    @Test
    public void everyOperationResolvesToAToolWhoseSchemaEnumeratesItsKeys() {
        for (ValidationOperation operation : ValidationOperation.values()) {
            String toolName = ValidationPayloadKeyContract.targetToolName(operation);
            assertNotNull(operation + " resolves to no target tool", toolName); //$NON-NLS-1$
            String schema = ValidationPayloadKeyContract.targetToolSchema(operation);
            assertNotNull(operation + " -> " + toolName + " advertises no schema", schema); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(operation + " -> " + toolName + " schema is not enforceable", //$NON-NLS-1$ //$NON-NLS-2$
                    SchemaKeyGuard.inspect(schema, List.of()).enforceable());
        }
    }

    @Test
    public void everyKeyTheValidatorReadsIsAcceptedForItsOperation() {
        for (ValidationOperation operation : ValidationOperation.values()) {
            List<String> keys = KEYS_THE_VALIDATOR_READS.get(operation);
            SchemaKeyGuard.Report report = SchemaKeyGuard.inspect(
                    ValidationPayloadKeyContract.targetToolSchema(operation),
                    keys,
                    ValidationPayloadKeyContract.extraAcceptedKeys(operation));
            assertTrue(operation + " would falsely refuse " + report.unknownKeys(), //$NON-NLS-1$
                    report.isClean());
        }
    }

    @Test
    public void noOperationNeededAnExemption() {
        // The audit outcome: all 21 operations enumerate their keys, so nothing is skipped. If this
        // ever has to change, the exemption belongs in ValidationPayloadKeyContract with its reason.
        for (ValidationOperation operation : ValidationOperation.values()) {
            assertFalse(operation + " is exempt from the payload-key guard", //$NON-NLS-1$
                    ValidationPayloadKeyContract.isExempt(operation));
        }
    }

    @Test
    public void aStrayKeyIsStillCaughtForEveryOperation() {
        // The mirror of the test above: the guard must be live for all of them, not merely permissive.
        for (ValidationOperation operation : ValidationOperation.values()) {
            List<String> keys = new java.util.ArrayList<>(KEYS_THE_VALIDATOR_READS.get(operation));
            keys.add("definitely_not_a_parameter"); //$NON-NLS-1$
            SchemaKeyGuard.Report report = SchemaKeyGuard.inspect(
                    ValidationPayloadKeyContract.targetToolSchema(operation),
                    keys,
                    ValidationPayloadKeyContract.extraAcceptedKeys(operation));
            assertFalse(operation + " lets a stray key through", report.isClean()); //$NON-NLS-1$
        }
    }
}
