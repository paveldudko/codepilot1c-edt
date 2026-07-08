package com.codepilot1c.core.tools.metadata;

import com.codepilot1c.core.tools.AbstractTool;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.metadata.CreateInformationRegisterRequest;
import com.codepilot1c.core.edt.metadata.EdtMetadataService;
import com.codepilot1c.core.edt.metadata.MetadataOperationCode;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;
import com.codepilot1c.core.edt.metadata.MetadataOperationResult;
import com.codepilot1c.core.edt.validation.MetadataRequestValidationService;
import com.codepilot1c.core.edt.validation.ValidationOperation;
import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.logging.VibeLogger;

/**
 * Compound tool: creates an InformationRegister with dimensions and resources in one call.
 */
@ToolMeta(name = "create_information_register", category = "metadata", mutating = true, requiresValidationToken = true, tags = {"workspace", "edt"})
public class CreateInformationRegisterTool extends AbstractTool {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(CreateInformationRegisterTool.class);

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "project": {
                  "type": "string",
                  "description": "Имя EDT проекта."
                },
                "name": {
                  "type": "string",
                  "description": "Имя нового регистра сведений."
                },
                "synonym": {
                  "type": "string",
                  "description": "Синоним регистра."
                },
                "comment": {
                  "type": "string",
                  "description": "Комментарий."
                },
                "periodicity": {
                  "type": "string",
                  "enum": ["NONPERIODICAL", "RECORDER_POSITION", "SECOND", "DAY", "MONTH", "QUARTER", "YEAR"],
                  "description": "Периодичность регистра. По умолчанию NONPERIODICAL."
                },
                "dimensions": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "properties": {
                      "name": {"type": "string"},
                      "synonym": {"type": "string"},
                      "type": {"type": "string", "description": "Тип: String, Number, CatalogRef.Foo и т.п."},
                      "length": {"type": "integer"},
                      "precision": {"type": "integer"},
                      "scale": {"type": "integer"},
                      "fillChecking": {"type": "string"}
                    },
                    "required": ["name"]
                  },
                  "description": "Измерения регистра."
                },
                "resources": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "properties": {
                      "name": {"type": "string"},
                      "synonym": {"type": "string"},
                      "type": {"type": "string", "description": "Тип: String, Number, CatalogRef.Foo и т.п."},
                      "length": {"type": "integer"},
                      "precision": {"type": "integer"},
                      "scale": {"type": "integer"},
                      "fillChecking": {"type": "string"}
                    },
                    "required": ["name"]
                  },
                  "description": "Ресурсы регистра."
                },
                "validation_token": {
                  "type": "string",
                  "description": "Одноразовый токен из edt_validate_request."
                }
              },
              "required": ["project", "name", "validation_token"]
            }
            """; //$NON-NLS-1$

    private final EdtMetadataService metadataService;
    private final MetadataRequestValidationService validationService;

    public CreateInformationRegisterTool() {
        this(new EdtMetadataService(), new MetadataRequestValidationService());
    }

    CreateInformationRegisterTool(EdtMetadataService metadataService, MetadataRequestValidationService validationService) {
        this.metadataService = metadataService;
        this.validationService = validationService;
    }

    @Override
    public String getDescription() {
        return "Creates an InformationRegister with dimensions and resources in one call. " //$NON-NLS-1$
                + "Replaces: create_metadata + N×add_metadata_child + N×update_metadata."; //$NON-NLS-1$
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
    protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Object> parameters = params.getRaw();
            String opId = LogSanitizer.newId("create-inforeg"); //$NON-NLS-1$
            long startedAt = System.currentTimeMillis();
            LOG.info("[%s] START create_information_register", opId); //$NON-NLS-1$
            try {
                String projectName = getString(parameters, "project"); //$NON-NLS-1$
                String name = getString(parameters, "name"); //$NON-NLS-1$
                String synonym = getOptionalString(parameters, "synonym"); //$NON-NLS-1$
                String comment = getOptionalString(parameters, "comment"); //$NON-NLS-1$
                String periodicity = getOptionalString(parameters, "periodicity"); //$NON-NLS-1$
                List<Map<String, Object>> dimensions = asListOfMaps(parameters.get("dimensions")); //$NON-NLS-1$
                List<Map<String, Object>> resources = asListOfMaps(parameters.get("resources")); //$NON-NLS-1$
                String validationToken = getString(parameters, "validation_token"); //$NON-NLS-1$

                Map<String, Object> normalizedPayload = validationService.normalizeCreateInformationRegisterPayload(
                        projectName, name, synonym, comment, periodicity, dimensions, resources);
                Map<String, Object> validatedPayload = validationService.consumeToken(
                        validationToken,
                        ValidationOperation.CREATE_INFORMATION_REGISTER,
                        projectName);
                if (!validatedPayload.equals(normalizedPayload)) {
                    LOG.warn("[%s] Input payload differs from validated, using validated payload", opId); //$NON-NLS-1$
                }

                String validatedName = asRequiredString(validatedPayload, "name"); //$NON-NLS-1$
                String validatedSynonym = asOptionalString(validatedPayload, "synonym"); //$NON-NLS-1$
                String validatedComment = asOptionalString(validatedPayload, "comment"); //$NON-NLS-1$
                String validatedPeriodicity = asOptionalString(validatedPayload, "periodicity"); //$NON-NLS-1$
                List<Map<String, Object>> validatedDimensions = asListOfMaps(validatedPayload.get("dimensions")); //$NON-NLS-1$
                List<Map<String, Object>> validatedResources = asListOfMaps(validatedPayload.get("resources")); //$NON-NLS-1$

                CreateInformationRegisterRequest request = new CreateInformationRegisterRequest(
                        projectName, validatedName, validatedSynonym, validatedComment,
                        validatedPeriodicity, validatedDimensions, validatedResources);
                MetadataOperationResult result = metadataService.createInformationRegister(request);
                LOG.info("[%s] SUCCESS in %s fqn=%s", opId, //$NON-NLS-1$
                        LogSanitizer.formatDuration(System.currentTimeMillis() - startedAt),
                        result.fqn());
                return ToolResult.success(result.formatForLlm());
            } catch (MetadataOperationException e) {
                LOG.warn("[%s] FAILED in %s: %s (%s)", opId, //$NON-NLS-1$
                        LogSanitizer.formatDuration(System.currentTimeMillis() - startedAt),
                        e.getMessage(), e.getCode());
                return ToolResult.failure("[" + e.getCode() + "] " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            } catch (Exception e) {
                LOG.error("[" + opId + "] create_information_register failed", e); //$NON-NLS-1$ //$NON-NLS-2$
                return ToolResult.failure("Ошибка create_information_register: " + e.getMessage()); //$NON-NLS-1$
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

    private String asRequiredString(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_METADATA_NAME,
                    "Required field missing in validated payload: " + key, false); //$NON-NLS-1$
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
    private List<Map<String, Object>> asListOfMaps(Object value) {
        if (!(value instanceof List<?> list)) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                result.add((Map<String, Object>) map);
            }
        }
        return result;
    }
}
