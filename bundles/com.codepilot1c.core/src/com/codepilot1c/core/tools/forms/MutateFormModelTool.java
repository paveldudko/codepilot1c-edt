package com.codepilot1c.core.tools.forms;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.AbstractTool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.forms.EdtFormService;
import com.codepilot1c.core.edt.forms.UpdateFormModelRequest;
import com.codepilot1c.core.edt.forms.UpdateFormModelResult;
import com.codepilot1c.core.edt.metadata.MetadataOperationCode;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;
import com.codepilot1c.core.edt.validation.MetadataRequestValidationService;
import com.codepilot1c.core.edt.validation.ValidationOperation;
import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.logging.VibeLogger;

/**
 * Tool for headless form model mutation via EDT BM API.
 */
@ToolMeta(name = "mutate_form_model", category = "forms", mutating = true, requiresValidationToken = true, tags = {"workspace", "edt"})
public class MutateFormModelTool extends AbstractTool {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(MutateFormModelTool.class);

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "project": {
                  "type": "string",
                  "description": "Имя EDT проекта, containing an already existing managed form."
                },
                "form_fqn": {
                  "type": "string",
                  "description": "FQN of an existing managed form to modify. Use create_form when the form does not exist yet."
                },
                "operations": {
                  "type": "array",
                  "minItems": 1,
                  "items": {
                    "type": "object",
                    "properties": {
                      "op": {
                        "type": "string",
                        "description": "Тип операции: set_form_props/add_group/add_field/add_command/add_button/add_form_parameter/set_item/remove_item/move_item/rename_command"
                      }
                    },
                    "required": ["op"],
                    "additionalProperties": true
                  },
                  "description": "Список операций: set_form_props/add_group/add_field/add_command/add_button/set_item/remove_item/move_item. ALWAYS prefer these over direct .form XML editing — BM API applies designer defaults (e.g. showInHeader=true) raw XML skips, and keeps ids/cross-refs consistent. add_command: name+action (handler); optional picture (CommonPicture.<Name>). add_form_parameter: declare a form-level Parameter (name + type, e.g. Boolean/String/Number/Date or a config-object ref; optional key_parameter bool, comment) — this is the ONLY way to declare a Parameter (set_form_props rejects 'parameters' as a reference collection); needed so OpenForm(..., New Structure(\\"X\\", ...)) callers and Parameters.Property(\\"X\\") clear the unknown-form-parameter-access diagnostic. add_button: name+command_name+parent_item_id; optional picture (CommonPicture.<Name>) + representation (Auto/Text/Picture/PictureAndText). rename_command: rename a form command (command_name OR command_id from get_form_rendering; new_name) — atomically updates the formCommand name and every referencing button's commandName (object reference, kept consistent); optional new_action rebinds the BSL handler procedure, optional new_title updates the display title. This is the ONLY way to rename a command: commandName on buttons is a reference property (not settable via set_item) and formCommands are not in the set_item/remove_item item tree. EVENT HANDLERS bind via set_item set:{handlers:[{event:'OnChange', handler:'MyHandler'}, ...]} on the item or form (merge-safe — leaves untouched events alone; register write-events like BeforeWrite resolve to the form's extInfo automatically) — do NOT hand-edit <handlers> in .form. set_item also reaches Buttons inside a table/form autoCommandBar (by item_id or item_name), so picture/representation on existing command-bar buttons go through set_item. The 'set' map accepts ANY scalar property of the element: showInHeader (bool), headerHorizontalAlign (HorizontalAlign enum), showInFooter, footerHorizontalAlign, titleLocation (FormItemTitleLocation enum), width, minWidth, maxWidth, enabled, readOnly, userVisible (bool, or a per-role map: flat {common:bool,\\"RoleFQNorName\\":bool,...} or structured {common:bool,for:[{role,value}]}; a bare bool sets common), picture, representation. Column header/footer props must be set via set_item, not by hand-editing .form."
                },
                "validation_token": {
                  "type": "string",
                  "description": "Одноразовый токен из edt_validate_request for this form-mutation request."
                }
              },
              "required": ["project", "form_fqn", "operations", "validation_token"]
            }
            """; //$NON-NLS-1$

    private final EdtFormService formService;
    private final MetadataRequestValidationService validationService;

    public MutateFormModelTool() {
        this(new EdtFormService(), new MetadataRequestValidationService());
    }

    MutateFormModelTool(EdtFormService formService, MetadataRequestValidationService validationService) {
        this.formService = formService;
        this.validationService = validationService;
    }

    @Override
    public String getDescription() {
        return "Applies targeted changes to the model of an existing managed form via the EDT BM API."; //$NON-NLS-1$
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
            String opId = LogSanitizer.newId("mutate-form"); //$NON-NLS-1$
            long startedAt = System.currentTimeMillis();
            LOG.info("[%s] START mutate_form_model", opId); //$NON-NLS-1$
            LOG.debug("[%s] Raw parameters: %s", opId, // $NON-NLS-1$
                    LogSanitizer.truncate(LogSanitizer.redactSecrets(String.valueOf(parameters)), 4000));
            try {
                String projectName = stringParam(parameters, "project"); //$NON-NLS-1$
                String formFqn = stringParam(parameters, "form_fqn"); //$NON-NLS-1$
                List<Map<String, Object>> operations = asListOfMaps(parameters.get("operations")); //$NON-NLS-1$
                String validationToken = stringParam(parameters, "validation_token"); //$NON-NLS-1$

                Map<String, Object> normalizedPayload = validationService.normalizeUpdateFormModelPayload(
                        projectName,
                        formFqn,
                        operations);
                Map<String, Object> validatedPayload = validationService.consumeToken(
                        validationToken,
                        ValidationOperation.MUTATE_FORM_MODEL,
                        projectName);
                if (!validatedPayload.equals(normalizedPayload)) {
                    LOG.warn("[%s] Input payload differs from validated payload, applying validated payload from token", opId); //$NON-NLS-1$
                }

                UpdateFormModelRequest request = new UpdateFormModelRequest(
                        projectName,
                        asRequiredString(validatedPayload, "form_fqn"), //$NON-NLS-1$
                        asListOfMaps(validatedPayload.get("operations"))); //$NON-NLS-1$
                UpdateFormModelResult result = formService.updateFormModel(request);
                LOG.info("[%s] SUCCESS in %s form=%s operations=%d", opId, //$NON-NLS-1$
                        LogSanitizer.formatDuration(System.currentTimeMillis() - startedAt),
                        result.formFqn(),
                        Integer.valueOf(result.operationsApplied()));
                return ToolResult.success(result.formatForLlm());
            } catch (MetadataOperationException e) {
                LOG.warn("[%s] FAILED in %s: %s (%s)", opId, //$NON-NLS-1$
                        LogSanitizer.formatDuration(System.currentTimeMillis() - startedAt),
                        e.getMessage(),
                        e.getCode());
                return ToolResult.failure("[" + e.getCode() + "] " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            } catch (Exception e) {
                LOG.error("[" + opId + "] mutate_form_model failed", e); //$NON-NLS-1$ //$NON-NLS-2$
                return ToolResult.failure("Ошибка mutate_form_model: " + e.getMessage()); //$NON-NLS-1$
            }
        });
    }

    private String stringParam(Map<String, Object> params, String key) {
        Object value = params.get(key);
        return value == null ? null : String.valueOf(value);
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

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> asListOfMaps(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
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
