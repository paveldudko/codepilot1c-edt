package com.codepilot1c.core.tools.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.codepilot1c.core.edt.validation.MetadataRequestValidationService;
import com.codepilot1c.core.edt.validation.ValidationOperation;
import com.codepilot1c.core.edt.validation.ValidationRequest;
import com.codepilot1c.core.edt.validation.ValidationResult;
import com.codepilot1c.core.tools.CompositeCommandKeyGuard;
import com.codepilot1c.core.tools.ToolResult;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Behavioural tests for the L2 half of the composite-command key guard: {@code edt_validate_request}
 * must REFUSE — before any token exists — a payload carrying a key that belongs to a different command
 * of the same composite tool.
 *
 * <p>The residue left open by the top-level guard (commit {@code 8ed8c67}): {@code dcs_manage},
 * {@code external_manage} and {@code extension_manage} advertise the UNION of all their commands'
 * parameters, so {@code dataset_name} sent with {@code command:"upsert_param"} passes the
 * "is it declared?" test and is then read by nobody. Outcome identical to the closed defect — the
 * value is dropped, the mutation reports success, the caller's intent is gone.</p>
 *
 * <p>Every test drives the tool and asserts on the {@link ToolResult}, plus on whether the validation
 * service was reached at all: a refused request that still issued a token would defeat the whole
 * point. The acceptance tests are the more important half, one per command of all three tools — a
 * false refusal would break working callers, which is worse than the bug being fixed.</p>
 */
public class EdtValidateRequestForeignCommandKeyTest {

    // --- the hole, refused before a token exists -----------------------------

    @Test
    public void aDatasetKeySentWithUpsertParamIsRefusedNamingBothCommands() {
        RecordingValidationService service = new RecordingValidationService();
        ToolResult result = validate(service, "dcs_manage", payload(
                "command", "upsert_param",
                "project", "Sandbox",
                "owner_fqn", "Report.Sales",
                "parameter_name", "Period",
                "dataset_name", "DataSet1"));

        assertFalse("a key upsert_param never reads must not be validated", result.isSuccess());
        JsonObject error = JsonParser.parseString(result.getErrorMessage()).getAsJsonObject();
        assertEquals("KNOWLEDGE_REQUIRED", error.get("error").getAsString());
        String message = error.get("message").getAsString();
        assertTrue(message, message.contains("'dataset_name'"));
        assertTrue("must name the command the key belongs to: " + message,
                message.contains("belongs to 'upsert_dataset'"));
        assertTrue("must name the command that was requested: " + message,
                message.contains("not to 'upsert_param'"));
        assertTrue(message, message.contains("No validation token was issued"));
        assertTrue("must list the keys upsert_param does accept: " + message,
                message.contains("Keys accepted by 'upsert_param'")
                        && message.contains("parameter_name"));
    }

    @Test
    public void aRefusedForeignKeyNeverReachesTheValidationServiceSoNoTokenExists() {
        RecordingValidationService service = new RecordingValidationService();
        ToolResult result = validate(service, "dcs_manage", payload(
                "command", "upsert_param",
                "project", "Sandbox",
                "owner_fqn", "Report.Sales",
                "parameter_name", "Period",
                "dataset_name", "DataSet1"));

        assertFalse(result.isSuccess());
        assertNull("the guard must short-circuit before a token is issued", service.lastRequest);
        assertNull(result.getContent());
    }

    @Test
    public void theResolvedPerCommandOperationNameIsGuardedEvenWithoutAPayloadCommand() {
        // edt_validate_request also accepts the resolved name, and in that spelling the payload need
        // not repeat 'command'. Without the operation -> command table this call would fail open, i.e.
        // the very shape of call that skips the guard would be the unprotected one.
        RecordingValidationService service = new RecordingValidationService();
        ToolResult result = validate(service, "dcs_upsert_parameter", payload(
                "project", "Sandbox",
                "owner_fqn", "Report.Sales",
                "parameter_name", "Period",
                "dataset_name", "DataSet1"));

        assertFalse(result.isSuccess());
        assertNull(service.lastRequest);
        String message = messageOf(result);
        assertTrue(message, message.contains("'dataset_name'"));
        assertTrue(message, message.contains("not to 'upsert_param'"));
    }

    @Test
    public void anExtensionCreateKeySentWithAdoptIsRefused() {
        RecordingValidationService service = new RecordingValidationService();
        ToolResult result = validate(service, "extension_manage", payload(
                "command", "adopt",
                "project", "Sandbox",
                "base_project", "Sandbox",
                "extension_project", "Ext",
                "source_object_fqn", "Catalog.Goods",
                "purpose", "ADD_ON"));

        assertFalse(result.isSuccess());
        assertNull(service.lastRequest);
        String message = messageOf(result);
        assertTrue(message, message.contains("'purpose' (belongs to 'create')"));
        assertTrue(message, message.contains("not to 'adopt'"));
    }

    @Test
    public void aReadCommandKeySentWithExternalCreateReportIsRefused() {
        RecordingValidationService service = new RecordingValidationService();
        ToolResult result = validate(service, "external_manage", payload(
                "command", "create_report",
                "project", "Sandbox",
                "external_project", "ExtReports",
                "name", "SalesReport",
                "type_filter", "Report"));

        assertFalse(result.isSuccess());
        assertNull(service.lastRequest);
        String message = messageOf(result);
        assertTrue(message, message.contains("'type_filter' (belongs to 'list_objects')"));
        assertTrue(message, message.contains("not to 'create_report'"));
    }

    @Test
    public void twoForeignKeysAreBothNamed() {
        RecordingValidationService service = new RecordingValidationService();
        ToolResult result = validate(service, "dcs_manage", payload(
                "command", "create_schema",
                "project", "Sandbox",
                "owner_fqn", "Report.Sales",
                "dataset_name", "DataSet1",
                "data_path", "Field1"));

        assertFalse(result.isSuccess());
        String message = messageOf(result);
        assertTrue(message, message.contains("2 keys that belong to other"));
        assertTrue(message, message.contains("'dataset_name'"));
        assertTrue(message, message.contains("'data_path'"));
    }

    // --- no false refusals: one legitimate call per mutating command ----------

    @Test
    public void aFullDcsCreateSchemaPayloadIsAccepted() {
        assertAccepted("dcs_manage", payload(
                "command", "create_schema",
                "project", "Sandbox",
                "owner_fqn", "Report.Sales",
                "template_name", "MainDataCompositionSchema",
                "force_replace", Boolean.TRUE));
    }

    @Test
    public void aFullDcsUpsertDatasetPayloadIsAccepted() {
        assertAccepted("dcs_manage", payload(
                "command", "upsert_dataset",
                "project", "Sandbox",
                "owner_fqn", "Report.Sales",
                "dataset_name", "DataSet1",
                "query", "SELECT 1",
                "data_source", "DataSource1",
                "auto_fill_available_fields", Boolean.TRUE,
                "use_query_group_if_possible", Boolean.FALSE));
    }

    @Test
    public void aFullDcsUpsertParamPayloadIsAccepted() {
        assertAccepted("dcs_manage", payload(
                "command", "upsert_param",
                "project", "Sandbox",
                "owner_fqn", "Report.Sales",
                "parameter_name", "Period",
                "expression", "&Period",
                "available_as_field", Boolean.TRUE,
                "value_list_allowed", Boolean.FALSE,
                "deny_incomplete_values", Boolean.FALSE,
                "use_restriction", Boolean.FALSE));
    }

    @Test
    public void aFullDcsUpsertFieldPayloadIsAcceptedIncludingTheSharedExpressionKey() {
        // 'expression' is tagged "(upsert_param/upsert_field)": both owners must be honoured, or one of
        // the two commands starts refusing a parameter it really reads.
        assertAccepted("dcs_manage", payload(
                "command", "upsert_field",
                "project", "Sandbox",
                "owner_fqn", "Report.Sales",
                "data_path", "Total",
                "expression", "Amount * 2",
                "presentation_expression", "Total"));
    }

    @Test
    public void aFullExtensionCreatePayloadIsAccepted() {
        assertAccepted("extension_manage", payload(
                "command", "create",
                "project", "Sandbox",
                "base_project", "Sandbox",
                "extension_project", "Ext",
                "project_path", "/tmp/ext",
                "version", "8.3.24",
                "configuration_name", "Ext",
                "purpose", "ADD_ON",
                "compatibility_mode", "8.3.21"));
    }

    @Test
    public void aFullExtensionSetStatePayloadIsAccepted() {
        assertAccepted("extension_manage", payload(
                "command", "set_state",
                "project", "Sandbox",
                "base_project", "Sandbox",
                "extension_project", "Ext",
                "source_object_fqn", "Catalog.Goods",
                "property_name", "Synonym",
                "state", "EXTENDED"));
    }

    @Test
    public void aFullExternalCreateProcessingPayloadIsAccepted() {
        // project_path / version / synonym / comment are tagged "(create)" — a PREFIX of both
        // create_report and create_processing. Prefix resolution has to work or every external
        // creation call breaks.
        assertAccepted("external_manage", payload(
                "command", "create_processing",
                "project", "Sandbox",
                "external_project", "ExtProc",
                "name", "Loader",
                "project_path", "/tmp/x",
                "version", "8.3.24",
                "synonym", "Загрузчик",
                "comment", "c"));
    }

    @Test
    public void aPayloadThatCarriesAValidationTokenKeyIsStillAccepted() {
        // validation_token is tagged "(mutating commands)" — prose, not a command name. Reading it as a
        // command would refuse it for every command there is.
        assertAccepted("dcs_manage", payload(
                "command", "upsert_param",
                "project", "Sandbox",
                "owner_fqn", "Report.Sales",
                "parameter_name", "Period",
                "validation_token", "irrelevant"));
    }

    @Test
    public void aNonCompositeOperationIsUntouchedByThePerCommandGuard() {
        // update_metadata has no commands at all; the per-command guard must not invent any.
        assertAccepted("update_metadata", payload(
                "project", "Sandbox",
                "target_fqn", "Catalog.Goods.Attribute.Owner",
                "changes", Map.of("comment", "x")));
    }

    // --- the operation -> command table -------------------------------------

    @Test
    public void everyCompositeOperationMapsToACommandItsTargetSchemaDeclares() {
        // The table mirrors ValidationOperation.resolve*ManageCommand. A typo there would silently
        // disable the guard for that operation (unknown command -> fail open), so it is pinned here.
        int composites = 0;
        for (ValidationOperation operation : ValidationOperation.values()) {
            String toolName = ValidationPayloadKeyContract.compositeToolName(operation);
            if (toolName == null) {
                continue;
            }
            composites++;
            String command = ValidationPayloadKeyContract.compositeCommand(operation, Map.of());
            assertNotNull(operation + " has no command mapping", command);
            String schema = ValidationPayloadKeyContract.targetToolSchema(operation);
            assertTrue(operation + " maps to '" + command + "', which " + toolName
                    + " does not declare",
                    CompositeCommandKeyGuard.declaredCommands(schema).contains(command));
            assertTrue(operation + " is not per-command enforceable",
                    CompositeCommandKeyGuard.inspect(schema, command, List.of()).enforceable());
        }
        assertEquals("all nine composite operations must be covered", 9, composites);
    }

    @Test
    public void thePayloadCommandWinsOverTheOperationImpliedOne() {
        // The composite tool dispatches on payload.command, so that is what the keys must be judged
        // against; a mismatch with the operation name fails later when the token is consumed.
        assertEquals("upsert_dataset", ValidationPayloadKeyContract.compositeCommand(
                ValidationOperation.DCS_UPSERT_PARAMETER, Map.of("command", "upsert_dataset")));
        assertEquals("upsert_param", ValidationPayloadKeyContract.compositeCommand(
                ValidationOperation.DCS_UPSERT_PARAMETER, Map.of()));
        assertNull(ValidationPayloadKeyContract.compositeCommand(
                ValidationOperation.UPDATE_METADATA, Map.of("command", "whatever")));
    }

    // --- helpers ------------------------------------------------------------

    private void assertAccepted(String operation, Map<String, Object> payload) {
        RecordingValidationService service = new RecordingValidationService();
        ToolResult result = validate(service, operation, payload);
        assertTrue(operation + " falsely refused: " + errorOf(result), result.isSuccess());
        assertNotNull("a validated request must reach the service", service.lastRequest);
    }

    private ToolResult validate(MetadataRequestValidationService service, String operation,
            Map<String, Object> payload) {
        return new EdtValidateRequestTool(service).execute(Map.of(
                "project", "Sandbox",
                "operation", operation,
                "payload", payload)).join();
    }

    private static Map<String, Object> payload(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
            map.put(String.valueOf(keysAndValues[i]), keysAndValues[i + 1]);
        }
        return map;
    }

    private static String messageOf(ToolResult result) {
        return JsonParser.parseString(result.getErrorMessage())
                .getAsJsonObject().get("message").getAsString();
    }

    private static String errorOf(ToolResult result) {
        return result.isSuccess() ? "" : String.valueOf(result.getErrorMessage());
    }

    /** Records the request instead of touching EDT. A refused request must never get here. */
    private static final class RecordingValidationService extends MetadataRequestValidationService {
        private ValidationRequest lastRequest;

        @Override
        public ValidationResult validateAndIssueToken(ValidationRequest request) {
            lastRequest = request;
            return new ValidationResult(
                    true,
                    request.projectName(),
                    request.operation().getToolName(),
                    List.of("ok"),
                    request.payload(),
                    "token-1",
                    123L);
        }
    }
}
