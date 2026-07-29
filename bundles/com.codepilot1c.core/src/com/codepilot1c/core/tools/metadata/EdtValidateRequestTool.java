package com.codepilot1c.core.tools.metadata;
import com.codepilot1c.core.tools.CompositeCommandKeyGuard;
import com.codepilot1c.core.tools.SchemaKeyGuard;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.AbstractTool;

import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.metadata.MetadataOperationException;
import com.codepilot1c.core.edt.validation.MetadataRequestValidationService;
import com.codepilot1c.core.edt.validation.ValidationOperation;
import com.codepilot1c.core.edt.validation.ValidationRequest;
import com.codepilot1c.core.edt.validation.ValidationResult;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * Validates metadata mutation request and returns a short-lived validation token.
 */
@ToolMeta(name = "edt_validate_request", category = "metadata", mutating = false, tags = {"workspace", "edt"})
public class EdtValidateRequestTool extends AbstractTool {

    private static final Gson GSON = new Gson();

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "project": {
                  "type": "string",
                  "description": "Имя EDT проекта, в котором будет выполнена следующая mutating-операция"
                },
                "operation": {
                  "type": "string",
                  "enum": ["create_metadata", "create_form", "apply_form_recipe", "external_manage", "external_create_report", "external_create_processing", "extension_manage", "extension_create_project", "extension_adopt_object", "extension_set_property_state", "dcs_manage", "dcs_create_main_schema", "dcs_upsert_query_dataset", "dcs_upsert_parameter", "dcs_upsert_calculated_field", "add_metadata_child", "ensure_module_artifact", "update_metadata", "delete_metadata", "mutate_form_model", "rights_manage", "render_template", "create_event_subscription", "create_information_register"],
                  "description": "Имя mutating tool; для composite tools external_manage, extension_manage и dcs_manage передай точный payload.command"
                },
                "payload": {
                  "type": "object",
                  "description": "Те же аргументы, которые потом будут переданы в мутационный tool без validation_token; для composite tools должен включать command. Top-level keys are checked against the target tool's own schema and an unknown one is REFUSED (not dropped): put per-object values such as 'type' / 'length' inside the nested 'properties' object, not at the top level."
                }
              },
              "required": ["project", "operation", "payload"],
              "additionalProperties": false
            }
            """; //$NON-NLS-1$

    private final MetadataRequestValidationService service;

    public EdtValidateRequestTool() {
        this(new MetadataRequestValidationService());
    }

    EdtValidateRequestTool(MetadataRequestValidationService service) {
        this.service = service;
    }

    @Override
    public String getDescription() {
        return "Validates a metadata-change request and issues a one-time validation_token. Required before metadata/forms/DCS/extension/external mutations. Not for read-only tools."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Object> parameters = params.getRaw();
            try {
                String project = stringParam(parameters, "project"); //$NON-NLS-1$
                String requestedOperation = normalizeOperationName(stringParam(parameters, "operation")); //$NON-NLS-1$
                Object payloadObject = parameters.get("payload"); //$NON-NLS-1$
                if (!(payloadObject instanceof Map<?, ?> payloadMap)) {
                    return ToolResult.failure(errorJson("KNOWLEDGE_REQUIRED", "payload must be an object", false)); //$NON-NLS-1$ //$NON-NLS-2$
                }
                ValidationOperation operation = ValidationOperation.resolve(requestedOperation, (Map<String, Object>) payloadMap);

                ToolResult refusal = refuseUnknownPayloadKeys(requestedOperation, operation, payloadMap);
                if (refusal != null) {
                    return refusal;
                }
                ToolResult foreignKeyRefusal = refuseForeignCommandPayloadKeys(operation, payloadMap);
                if (foreignKeyRefusal != null) {
                    return foreignKeyRefusal;
                }

                ValidationRequest request = new ValidationRequest(project, operation, (Map<String, Object>) payloadMap);
                ValidationResult result = service.validateAndIssueToken(request);
                result = new ValidationResult(
                        result.valid(),
                        result.project(),
                        requestedOperation,
                        result.checks(),
                        result.normalizedPayload(),
                        result.validationToken(),
                        result.expiresAtEpochMs());
                return ToolResult.success(GSON.toJson(result));
            } catch (MetadataOperationException e) {
                return ToolResult.failure(errorJson(e.getCode().name(), e.getMessage(), e.isRecoverable()));
            } catch (Exception e) {
                return ToolResult.failure(errorJson("INTERNAL_ERROR", e.getMessage(), false)); //$NON-NLS-1$
            }
        });
    }

    /**
     * Refuses a payload whose top-level keys the target mutating tool does not accept, instead of
     * letting the normalizer drop them and issuing a token anyway.
     *
     * <p>This is the layer the live defect needed (2026-07-29): {@code add_metadata_child} was called
     * with {@code type} at the top level instead of inside {@code properties}. Validation answered
     * {@code valid:true} with a {@code normalizedPayload} that no longer mentioned {@code type},
     * handed out a token, and the mutation then took its "no type requested" branch and wrote the
     * default {@code String(150)} to the .mdo while reporting success. Catching a bad request before
     * a mutation is precisely this tool's mandate, so a payload whose keys would be discarded must
     * not receive a token.</p>
     *
     * <p>Top level only — {@code properties}, {@code changes} and the operation descriptors are
     * legitimately open-ended and are never judged. Fail-open by construction: an unobtainable or
     * non-enumerating schema yields a clean report (see {@link SchemaKeyGuard}).</p>
     *
     * @return the refusal, or {@code null} when the payload's keys are all accounted for
     */
    private ToolResult refuseUnknownPayloadKeys(
            String requestedOperation,
            ValidationOperation operation,
            Map<?, ?> payload
    ) {
        if (ValidationPayloadKeyContract.isExempt(operation)) {
            return null;
        }
        SchemaKeyGuard.Report report = SchemaKeyGuard.inspect(
                ValidationPayloadKeyContract.targetToolSchema(operation),
                payload.keySet(),
                ValidationPayloadKeyContract.extraAcceptedKeys(operation));
        if (report.isClean()) {
            return null;
        }
        // validation_token is a real parameter of the target tool but never part of the payload
        // (the payload is what gets passed WITHOUT it), so it is accepted silently yet not advertised.
        String message = SchemaKeyGuard.refusalMessage(
                requestedOperation,
                report.unknownKeys(),
                SchemaKeyGuard.forDisplay(report.acceptedKeys(), Set.of("validation_token"))); //$NON-NLS-1$
        return ToolResult.failure(errorJson("KNOWLEDGE_REQUIRED", message, false)); //$NON-NLS-1$
    }

    /**
     * Refuses a payload that carries a key belonging to a DIFFERENT command of the same composite tool.
     *
     * <p>The gap {@link SchemaKeyGuard} cannot see: {@code dcs_manage}, {@code external_manage} and
     * {@code extension_manage} advertise the UNION of every command's parameters, so
     * {@code dataset_name} passed with {@code command:"upsert_param"} is a declared key — and still
     * read by nobody. That is the same silent drop plus success report the top-level guard exists to
     * end, so it is refused here too, before the token.</p>
     *
     * <p>Fail-open in every direction (see {@link CompositeCommandKeyGuard}): a non-composite
     * operation, an unresolvable command, or an untagged schema all pass through untouched, and an
     * untagged key is common to every command and can never be refused.</p>
     *
     * @return the refusal, or {@code null} when every key belongs to the dispatched command
     */
    private ToolResult refuseForeignCommandPayloadKeys(ValidationOperation operation, Map<?, ?> payload) {
        if (ValidationPayloadKeyContract.isExempt(operation)) {
            return null;
        }
        String toolName = ValidationPayloadKeyContract.compositeToolName(operation);
        if (toolName == null) {
            return null;
        }
        CompositeCommandKeyGuard.Report report = CompositeCommandKeyGuard.inspect(
                ValidationPayloadKeyContract.targetToolSchema(operation),
                ValidationPayloadKeyContract.compositeCommand(operation, payload),
                payload.keySet());
        if (report.isClean()) {
            return null;
        }
        String message = CompositeCommandKeyGuard.refusalMessage(
                toolName,
                report.command(),
                report.foreignKeys(),
                SchemaKeyGuard.forDisplay(report.acceptedKeys(), Set.of("validation_token"))); //$NON-NLS-1$
        return ToolResult.failure(errorJson("KNOWLEDGE_REQUIRED", message, false)); //$NON-NLS-1$
    }

    private String stringParam(Map<String, Object> params, String key) {
        Object value = params.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private String normalizeOperationName(String operation) {
        return operation == null ? null : operation.trim().toLowerCase(Locale.ROOT);
    }

    private String errorJson(String code, String message, boolean recoverable) {
        JsonObject obj = new JsonObject();
        obj.addProperty("error", code); //$NON-NLS-1$
        obj.addProperty("message", message); //$NON-NLS-1$
        obj.addProperty("recoverable", recoverable); //$NON-NLS-1$
        return GSON.toJson(obj);
    }
}
