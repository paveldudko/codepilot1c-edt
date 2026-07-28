package com.codepilot1c.core.tools.metadata;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.AbstractTool;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.metadata.CreateMetadataOutcome;
import com.codepilot1c.core.edt.metadata.CreateMetadataRequest;
import com.codepilot1c.core.edt.metadata.EdtMetadataService;
import com.codepilot1c.core.edt.metadata.MetadataKind;
import com.codepilot1c.core.edt.metadata.MetadataOperationCode;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;
import com.codepilot1c.core.edt.validation.MetadataRequestValidationService;
import com.codepilot1c.core.edt.validation.ValidationOperation;
import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.logging.VibeLogger;

/**
 * Tool for creating top-level EDT metadata objects.
 */
@ToolMeta(name = "create_metadata", category = "metadata", mutating = true, requiresValidationToken = true, tags = {"workspace", "edt"})
public class CreateMetadataTool extends AbstractTool {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(CreateMetadataTool.class);

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "project": {
                  "type": "string",
                  "description": "Имя EDT проекта, в котором создается новый top-level объект."
                },
                "kind": {
                  "type": "string",
                  "enum": [
                    "Catalog",
                    "Document",
                    "InformationRegister",
                    "AccumulationRegister",
                    "AccountingRegister",
                    "CalculationRegister",
                    "CommonModule",
                    "CommonAttribute",
                    "Enum",
                    "Report",
                    "DataProcessor",
                    "Constant",
                    "CommandGroup",
                    "Interface",
                    "Language",
                    "Style",
                    "StyleItem",
                    "SessionParameter",
                    "SettingsStorage",
                    "XDTOPackage",
                    "WSReference",
                    "Role",
                    "Subsystem",
                    "ExchangePlan",
                    "ChartOfAccounts",
                    "ChartOfCharacteristicTypes",
                    "ChartOfCalculationTypes",
                    "BusinessProcess",
                    "Task",
                    "CommonForm",
                    "CommonCommand",
                    "CommonTemplate",
                    "CommonPicture",
                    "ScheduledJob",
                    "FilterCriterion",
                    "DefinedType",
                    "Sequence",
                    "DocumentJournal",
                    "DocumentNumerator",
                    "EventSubscription",
                    "FunctionalOption",
                    "FunctionalOptionsParameter",
                    "WebService",
                    "HTTPService",
                    "ExternalDataSource",
                    "IntegrationService",
                    "Bot",
                    "WebSocketClient"
                  ],
                  "description": "Тип нового top-level объекта метаданных. Не используйте для child objects вроде Attribute или Form."
                },
                "name": {
                  "type": "string",
                  "description": "Имя нового top-level объекта. Не используйте reserved names платформы для конфликтующих сценариев."
                },
                "synonym": {
                  "type": "string",
                  "description": "Пользовательский синоним объекта."
                },
                "comment": {
                  "type": "string",
                  "description": "Комментарий объекта."
                },
                "properties": {
                  "type": "object",
                  "description": "Дополнительные свойства нового объекта. Используйте update_metadata, если объект уже существует."
                },
                "adopt_existing": {
                  "type": "boolean",
                  "description": "Default false. Set true ONLY after the tool refused with 'already registered as a BM top object': the .mdo exists and is loaded but Configuration.mdo does not list it, and adopt registers it into the typed collection. Adoption does not modify the object, so pass no 'properties' with it (use update_metadata afterwards). Must be passed in the edt_validate_request payload too, otherwise the token is issued without it. Alias: adopt."
                },
                "validation_token": {
                  "type": "string",
                  "description": "Одноразовый токен из edt_validate_request. Обязателен для mutation path."
                }
              },
              "required": ["project", "kind", "name", "validation_token"]
            }
            """; //$NON-NLS-1$

    private final EdtMetadataService metadataService;
    private final MetadataRequestValidationService validationService;

    public CreateMetadataTool() {
        this(new EdtMetadataService(), new MetadataRequestValidationService());
    }

    CreateMetadataTool(EdtMetadataService metadataService, MetadataRequestValidationService validationService) {
        this.metadataService = metadataService;
        this.validationService = validationService;
    }

    @Override
    public String getDescription() {
        return "Creates a new top-level 1C metadata object in an EDT project via the BM API."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    public boolean requiresConfirmation() {
        return true;
    }

    @Override
    public boolean isDestructive() {
        return true;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Object> parameters = params.getRaw();
            String opId = LogSanitizer.newId("create-md"); //$NON-NLS-1$
            long startedAt = System.currentTimeMillis();
            LOG.info("[%s] START create_metadata", opId); //$NON-NLS-1$
            LOG.debug("[%s] Raw parameters: %s", opId, // $NON-NLS-1$
                    LogSanitizer.truncate(LogSanitizer.redactSecrets(String.valueOf(parameters)), 4000));
            try {
                String projectName = getString(parameters, "project"); //$NON-NLS-1$
                String kindValue = getString(parameters, "kind"); //$NON-NLS-1$
                String name = getString(parameters, "name"); //$NON-NLS-1$
                String synonym = getOptionalString(parameters, "synonym"); //$NON-NLS-1$
                String comment = getOptionalString(parameters, "comment"); //$NON-NLS-1$
                Map<String, Object> properties = parameterMap(parameters.get("properties")); //$NON-NLS-1$
                Boolean adoptExisting = optionalBoolean(
                        parameters, "adopt_existing", "adoptExisting", "adopt"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                String validationToken = getString(parameters, "validation_token"); //$NON-NLS-1$

                Map<String, Object> normalizedPayload = validationService.normalizeCreatePayload(
                        projectName, kindValue, name, synonym, comment, properties, adoptExisting);
                LOG.debug("[%s] Normalized payload: %s", opId, // $NON-NLS-1$
                        LogSanitizer.truncate(LogSanitizer.redactSecrets(String.valueOf(normalizedPayload)), 4000));
                Map<String, Object> validatedPayload = validationService.consumeToken(
                        validationToken,
                        ValidationOperation.CREATE_METADATA,
                        projectName);
                LOG.debug("[%s] Validation token consumed successfully", opId); //$NON-NLS-1$
                LOG.debug("[%s] Validated payload from token: %s", opId, // $NON-NLS-1$
                        LogSanitizer.truncate(LogSanitizer.redactSecrets(String.valueOf(validatedPayload)), 4000));

                if (!validatedPayload.equals(normalizedPayload)) {
                    LOG.warn("[%s] Input payload differs from validated payload, applying validated payload from token", opId); //$NON-NLS-1$
                }
                MetadataKind kind = MetadataKind.fromString(asRequiredString(validatedPayload, "kind")); //$NON-NLS-1$
                String validatedName = asRequiredString(validatedPayload, "name"); //$NON-NLS-1$
                String validatedSynonym = asOptionalString(validatedPayload, "synonym"); //$NON-NLS-1$
                String validatedComment = asOptionalString(validatedPayload, "comment"); //$NON-NLS-1$
                Map<String, Object> validatedProperties = parameterMap(validatedPayload.get("properties")); //$NON-NLS-1$
                // The token payload is authoritative for every field; adopt_existing is a
                // behaviour switch, so a token issued without it must not be widened here.
                boolean validatedAdopt = Boolean.TRUE.equals(validatedPayload.get("adopt_existing")); //$NON-NLS-1$
                if (Boolean.TRUE.equals(adoptExisting) && !validatedAdopt) {
                    throw new MetadataOperationException(
                            MetadataOperationCode.INVALID_VALIDATION_TOKEN,
                            "adopt_existing=true was passed to create_metadata, but the validation token" //$NON-NLS-1$
                                    + " was issued without it. Re-run edt_validate_request with" //$NON-NLS-1$
                                    + " payload.adopt_existing=true and use the fresh token.", //$NON-NLS-1$
                            false);
                }
                CreateMetadataRequest request = new CreateMetadataRequest(
                        projectName,
                        kind,
                        validatedName,
                        validatedSynonym,
                        validatedComment,
                        validatedProperties,
                        Boolean.valueOf(validatedAdopt));
                LOG.info("[%s] Calling EdtMetadataService.createMetadata(project=%s, kind=%s, name=%s, adopt=%s)", // $NON-NLS-1$
                        opId, projectName, kind, validatedName, validatedAdopt);
                CreateMetadataOutcome outcome = metadataService.createMetadataDetailed(request);
                LOG.info("[%s] SUCCESS in %s, fqn=%s, adopted=%s", opId, // $NON-NLS-1$
                        LogSanitizer.formatDuration(System.currentTimeMillis() - startedAt),
                        outcome.result().fqn(),
                        outcome.adopted());
                return ToolResult.success(outcome.formatForLlm());
            } catch (MetadataOperationException e) {
                LOG.warn("[%s] FAILED in %s: %s (%s)", opId, // $NON-NLS-1$
                        LogSanitizer.formatDuration(System.currentTimeMillis() - startedAt),
                        e.getMessage(),
                        e.getCode());
                if (e.getCode() == com.codepilot1c.core.edt.metadata.MetadataOperationCode.EDT_TRANSACTION_FAILED) {
                    LOG.error("[" + opId + "] create_metadata EDT transaction error details", e); //$NON-NLS-1$ //$NON-NLS-2$
                } else if (e.getCode() == com.codepilot1c.core.edt.metadata.MetadataOperationCode.PROJECT_NOT_READY) {
                    LOG.error("[" + opId + "] create_metadata readiness error details", e); //$NON-NLS-1$ //$NON-NLS-2$
                }
                return ToolResult.failure("[" + e.getCode() + "] " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            } catch (Exception e) {
                LOG.error("[" + opId + "] create_metadata failed", e); //$NON-NLS-1$ //$NON-NLS-2$
                return ToolResult.failure("Ошибка create_metadata: " + e.getMessage()); //$NON-NLS-1$
            }
        });
    }

    private String getString(Map<String, Object> parameters, String key) {
        Object value = parameters.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private String getOptionalString(Map<String, Object> parameters, String key) {
        String value = getString(parameters, key);
        return value == null || value.isBlank() ? null : value;
    }

    /** Reads the first present key as a boolean; JSON clients send either a literal or a string. */
    private Boolean optionalBoolean(Map<String, Object> parameters, String... keys) {
        for (String key : keys) {
            Object value = parameters.get(key);
            if (value == null) {
                continue;
            }
            if (value instanceof Boolean bool) {
                return bool;
            }
            String text = String.valueOf(value).trim();
            if (text.isBlank()) {
                continue;
            }
            if ("true".equalsIgnoreCase(text) || "1".equals(text)) { //$NON-NLS-1$ //$NON-NLS-2$
                return Boolean.TRUE;
            }
            if ("false".equalsIgnoreCase(text) || "0".equals(text)) { //$NON-NLS-1$ //$NON-NLS-2$
                return Boolean.FALSE;
            }
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_PROPERTY_VALUE,
                    "Parameter '" + key + "' must be a boolean, got: " + text, false); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return null;
    }

    private String asRequiredString(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_METADATA_NAME,
                    "Required field missing in validated payload: " + key, //$NON-NLS-1$
                    false);
        }
        return String.valueOf(value);
    }

    private String asOptionalString(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parameterMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Collections.emptyMap();
    }
}
