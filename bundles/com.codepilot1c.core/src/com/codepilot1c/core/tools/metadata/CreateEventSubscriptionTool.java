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

import com.codepilot1c.core.edt.metadata.CreateEventSubscriptionRequest;
import com.codepilot1c.core.edt.metadata.EdtMetadataService;
import com.codepilot1c.core.edt.metadata.MetadataOperationCode;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;
import com.codepilot1c.core.edt.metadata.MetadataOperationResult;
import com.codepilot1c.core.edt.validation.MetadataRequestValidationService;
import com.codepilot1c.core.edt.validation.ValidationOperation;
import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.logging.VibeLogger;

/**
 * Compound tool: creates an EventSubscription with source types and handler in one call.
 */
@ToolMeta(name = "create_event_subscription", category = "metadata", mutating = true, requiresValidationToken = true, tags = {"workspace", "edt"})
public class CreateEventSubscriptionTool extends AbstractTool {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(CreateEventSubscriptionTool.class);

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
                  "description": "Имя новой подписки на событие (ПодпискаНаСобытие)."
                },
                "handler": {
                  "type": "string",
                  "description": "Обработчик: ИмяОбщегоМодуля.ИмяПроцедуры (например ПодпискиНаСобытия.ПередЗаписьюДокумента)."
                },
                "source_types": {
                  "type": "array",
                  "items": {"type": "string"},
                  "description": "Типы-источники события. Например: ['CatalogRef.Counterparties', 'DocumentRef.Invoice']. Оставьте пустым для подписки на все объекты."
                },
                "event": {
                  "type": "string",
                  "description": "Имя события (необязательно, если закодировано в имени обработчика). Например: BeforeWrite, OnWrite."
                },
                "synonym": {
                  "type": "string",
                  "description": "Синоним подписки."
                },
                "comment": {
                  "type": "string",
                  "description": "Комментарий."
                },
                "validation_token": {
                  "type": "string",
                  "description": "Одноразовый токен из edt_validate_request."
                }
              },
              "required": ["project", "name", "handler", "validation_token"]
            }
            """; //$NON-NLS-1$

    private final EdtMetadataService metadataService;
    private final MetadataRequestValidationService validationService;

    public CreateEventSubscriptionTool() {
        this(new EdtMetadataService(), new MetadataRequestValidationService());
    }

    CreateEventSubscriptionTool(EdtMetadataService metadataService, MetadataRequestValidationService validationService) {
        this.metadataService = metadataService;
        this.validationService = validationService;
    }

    @Override
    public String getDescription() {
        return "Creates an EventSubscription with sources and a handler in one call via the EDT BM API. " //$NON-NLS-1$
                + "Replaces hand-editing the .mdo file."; //$NON-NLS-1$
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
            String opId = LogSanitizer.newId("create-evtsub"); //$NON-NLS-1$
            long startedAt = System.currentTimeMillis();
            LOG.info("[%s] START create_event_subscription", opId); //$NON-NLS-1$
            try {
                String projectName = getString(parameters, "project"); //$NON-NLS-1$
                String name = getString(parameters, "name"); //$NON-NLS-1$
                String handler = getString(parameters, "handler"); //$NON-NLS-1$
                String event = getOptionalString(parameters, "event"); //$NON-NLS-1$
                List<String> sourceTypes = asStringList(parameters.get("source_types")); //$NON-NLS-1$
                String synonym = getOptionalString(parameters, "synonym"); //$NON-NLS-1$
                String comment = getOptionalString(parameters, "comment"); //$NON-NLS-1$
                String validationToken = getString(parameters, "validation_token"); //$NON-NLS-1$

                Map<String, Object> normalizedPayload = validationService.normalizeCreateEventSubscriptionPayload(
                        projectName, name, synonym, comment, sourceTypes, event, handler);
                Map<String, Object> validatedPayload = validationService.consumeToken(
                        validationToken,
                        ValidationOperation.CREATE_EVENT_SUBSCRIPTION,
                        projectName);
                if (!validatedPayload.equals(normalizedPayload)) {
                    LOG.warn("[%s] Input payload differs from validated, using validated payload", opId); //$NON-NLS-1$
                }

                String validatedName = asRequiredString(validatedPayload, "name"); //$NON-NLS-1$
                String validatedHandler = asRequiredString(validatedPayload, "handler"); //$NON-NLS-1$
                String validatedEvent = asOptionalString(validatedPayload, "event"); //$NON-NLS-1$
                List<String> validatedSourceTypes = asStringList(validatedPayload.get("source_types")); //$NON-NLS-1$
                String validatedSynonym = asOptionalString(validatedPayload, "synonym"); //$NON-NLS-1$
                String validatedComment = asOptionalString(validatedPayload, "comment"); //$NON-NLS-1$

                CreateEventSubscriptionRequest request = new CreateEventSubscriptionRequest(
                        projectName, validatedName, validatedSynonym, validatedComment,
                        validatedSourceTypes, validatedEvent, validatedHandler);
                MetadataOperationResult result = metadataService.createEventSubscription(request);
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
                LOG.error("[" + opId + "] create_event_subscription failed", e); //$NON-NLS-1$ //$NON-NLS-2$
                return ToolResult.failure("Ошибка create_event_subscription: " + e.getMessage()); //$NON-NLS-1$
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

    private List<String> asStringList(Object value) {
        List<String> result = new ArrayList<>();
        if (value == null) {
            return result;
        }
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    String str = String.valueOf(item).trim();
                    if (!str.isBlank()) {
                        result.add(str);
                    }
                }
            }
            return result;
        }
        String str = String.valueOf(value).trim();
        if (!str.isBlank()) {
            result.add(str);
        }
        return result;
    }
}
