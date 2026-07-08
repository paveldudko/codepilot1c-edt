package com.codepilot1c.core.tools.metadata;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.AbstractTool;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.metadata.EdtMetadataService;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;
import com.codepilot1c.core.edt.metadata.MetadataOperationResult;
import com.codepilot1c.core.edt.metadata.RightsManageRequest;
import com.codepilot1c.core.edt.validation.MetadataRequestValidationService;
import com.codepilot1c.core.edt.validation.ValidationOperation;
import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.logging.VibeLogger;

/**
 * Tool for mutating a Role's object-level rights grants via the EDT BM rights model.
 */
@ToolMeta(name = "rights_manage", category = "metadata", mutating = true, requiresValidationToken = true, tags = {"workspace", "edt"})
public class RightsManageTool extends AbstractTool {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(RightsManageTool.class);

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "project": {
                  "type": "string",
                  "description": "Имя EDT проекта, содержащего роль."
                },
                "role": {
                  "type": "string",
                  "description": "Имя роли или FQN ('ИмяРоли' либо 'Role.ИмяРоли')."
                },
                "grants": {
                  "type": "array",
                  "minItems": 1,
                  "items": {
                    "type": "object",
                    "properties": {
                      "object_fqn": {"type": "string", "description": "FQN объекта (или под-объекта), для которого задаются права, напр. 'Catalog.OutcomePaymentsTypes'."},
                      "right": {"type": "string", "description": "Имя права для типа объекта: Read/Update/Delete/View/Edit/DeletionMark/Insert/... (как в платформе)."},
                      "value": {"type": "string", "description": "set | unset | provided. По умолчанию set (выдать право). unset — снять, provided — наследуемое значение."}
                    },
                    "required": ["object_fqn", "right"],
                    "additionalProperties": true
                  },
                  "description": "Список грантов прав: {object_fqn, right, value:set|unset|provided}."
                },
                "validation_token": {
                  "type": "string",
                  "description": "Одноразовый токен из edt_validate_request for this exact rights_manage request."
                }
              },
              "required": ["project", "role", "grants", "validation_token"]
            }
            """; //$NON-NLS-1$

    private final EdtMetadataService metadataService;
    private final MetadataRequestValidationService validationService;

    public RightsManageTool() {
        this(new EdtMetadataService(), new MetadataRequestValidationService());
    }

    RightsManageTool(EdtMetadataService metadataService, MetadataRequestValidationService validationService) {
        this.metadataService = metadataService;
        this.validationService = validationService;
    }

    @Override
    public String getDescription() {
        return "Changes a role's rights on metadata objects (a right×object matrix) via the EDT BM model."; //$NON-NLS-1$
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
            String opId = LogSanitizer.newId("rights-manage"); //$NON-NLS-1$
            long startedAt = System.currentTimeMillis();
            LOG.info("[%s] START rights_manage", opId); //$NON-NLS-1$
            LOG.debug("[%s] Raw parameters: %s", opId, // $NON-NLS-1$
                    LogSanitizer.truncate(LogSanitizer.redactSecrets(String.valueOf(parameters)), 4000));
            try {
                String projectName = getString(parameters, "project"); //$NON-NLS-1$
                String role = getString(parameters, "role"); //$NON-NLS-1$
                List<Map<String, Object>> grants = asListOfMaps(parameters.get("grants")); //$NON-NLS-1$
                String validationToken = getString(parameters, "validation_token"); //$NON-NLS-1$

                Map<String, Object> normalizedPayload = validationService.normalizeRightsManagePayload(
                        projectName, role, grants);
                Map<String, Object> validatedPayload = validationService.consumeToken(
                        validationToken,
                        ValidationOperation.RIGHTS_MANAGE,
                        projectName);

                if (!validatedPayload.equals(normalizedPayload)) {
                    LOG.warn("[%s] Input payload differs from validated payload, applying validated payload from token", opId); //$NON-NLS-1$
                }

                String validatedRole = asRequiredString(validatedPayload, "role"); //$NON-NLS-1$
                List<RightsManageRequest.RightGrant> parsedGrants = RightsManageRequest.parseGrants(
                        validatedPayload.get("grants"), "grants"); //$NON-NLS-1$ //$NON-NLS-2$

                RightsManageRequest request = new RightsManageRequest(projectName, validatedRole, parsedGrants);
                MetadataOperationResult result = metadataService.manageRights(request);
                LOG.info("[%s] SUCCESS in %s, role=%s", opId, // $NON-NLS-1$
                        LogSanitizer.formatDuration(System.currentTimeMillis() - startedAt),
                        result.fqn());
                return ToolResult.success(result.formatForLlm());
            } catch (MetadataOperationException e) {
                LOG.warn("[%s] FAILED in %s: %s (%s)", opId, // $NON-NLS-1$
                        LogSanitizer.formatDuration(System.currentTimeMillis() - startedAt),
                        e.getMessage(),
                        e.getCode());
                return ToolResult.failure("[" + e.getCode() + "] " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            } catch (Exception e) {
                LOG.error("[" + opId + "] rights_manage failed", e); //$NON-NLS-1$ //$NON-NLS-2$
                return ToolResult.failure("Ошибка rights_manage: " + e.getMessage()); //$NON-NLS-1$
            }
        });
    }

    private String getString(Map<String, Object> parameters, String key) {
        Object value = parameters.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private String asRequiredString(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value == null) {
            throw new MetadataOperationException(
                    com.codepilot1c.core.edt.metadata.MetadataOperationCode.INVALID_METADATA_NAME,
                    "Required field missing in validated payload: " + key, //$NON-NLS-1$
                    false);
        }
        return String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> asListOfMaps(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                result.add((Map<String, Object>) map);
            }
        }
        return result;
    }
}
