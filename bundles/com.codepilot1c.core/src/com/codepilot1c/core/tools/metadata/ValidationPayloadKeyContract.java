/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.metadata;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import com.codepilot1c.core.edt.validation.MetadataRequestValidationService;
import com.codepilot1c.core.edt.validation.ValidationOperation;
import com.codepilot1c.core.tools.ITool;
import com.codepilot1c.core.tools.dcs.DcsManageTool;
import com.codepilot1c.core.tools.extension.ExtensionManageTool;
import com.codepilot1c.core.tools.external.ExternalManageTool;
import com.codepilot1c.core.tools.forms.ApplyFormRecipeTool;
import com.codepilot1c.core.tools.forms.CreateFormTool;
import com.codepilot1c.core.tools.forms.MutateFormModelTool;

/**
 * Answers, for one {@link ValidationOperation}, which mutating tool will actually receive the
 * {@code edt_validate_request} payload and therefore which top-level keys that payload may carry.
 *
 * <p>The accepted-key set is the target tool's own {@code getParameterSchema()} — single source, so
 * it cannot drift from what the tool really takes — plus, for three operations, the extra spellings
 * {@link MetadataRequestValidationService} itself reads. A key the normalizer reads is by definition
 * not silently discarded, so it must not be refused even though the tool schema does not advertise
 * it.</p>
 *
 * <p><strong>Composite tools.</strong> {@code external_manage}, {@code extension_manage} and
 * {@code dcs_manage} route a {@code command} plus per-command parameters, and
 * {@code edt_validate_request} accepts BOTH the composite name and the resolved per-command
 * operation name. Either way the payload ends up at the composite tool, so the composite schema is
 * the contract. Those three schemas were audited key by key against every branch of
 * {@code MetadataRequestValidationService.normalizePayload}: each one enumerates every key its
 * commands read (and more — the read-command parameters such as {@code limit} / {@code offset} /
 * {@code node_kind} are declared too, which can only make the guard more permissive, never
 * stricter). Per-command precision is therefore deliberately NOT attempted here: passing
 * {@code limit} alongside {@code command=create_schema} stays silently ignored, which is the
 * pre-existing behaviour and is not the defect being closed.</p>
 *
 * <p><strong>Exemptions.</strong> {@link #isExempt} exists for a tool that genuinely cannot
 * enumerate its keys. The audit found none, so the set is empty; a future tool that needs one must
 * be listed there with the reason rather than by weakening the guard for everyone.</p>
 */
public final class ValidationPayloadKeyContract {

    /**
     * Operations whose payload is routed by a composite tool. The value is the tool whose schema
     * governs the payload.
     */
    private static final Map<ValidationOperation, String> COMPOSITE_TOOL_NAMES =
            new EnumMap<>(ValidationOperation.class);

    /**
     * The {@code payload.command} each composite operation dispatches to — the mirror image of the
     * {@code resolve*ManageCommand} switches in {@link ValidationOperation}.
     *
     * <p>Needed because {@code edt_validate_request} accepts the resolved per-command operation name
     * ({@code dcs_upsert_parameter}) as well as the composite one, and in that spelling the payload
     * need not repeat {@code command} at all. Without this table a per-command key check would simply
     * fail open on exactly the calls that skip {@code command}.</p>
     */
    private static final Map<ValidationOperation, String> COMPOSITE_COMMANDS =
            new EnumMap<>(ValidationOperation.class);

    /**
     * Extra top-level spellings {@code MetadataRequestValidationService} accepts that the target
     * tool's schema does not advertise. Each entry cites the code that reads it.
     */
    private static final Map<ValidationOperation, Set<String>> EXTRA_ACCEPTED_KEYS =
            new EnumMap<>(ValidationOperation.class);

    /**
     * Tools that cannot enumerate their top-level keys and must be skipped entirely.
     *
     * <p>EMPTY on purpose: every operation in the {@code edt_validate_request} enum was audited
     * against its target tool's schema and all of them enumerate their keys. Add a tool name here —
     * with the reason — only if that stops being true; do not relax the guard globally instead.</p>
     */
    private static final Set<String> EXEMPT_TOOL_NAMES = Set.of();

    private static final Map<String, Supplier<ITool>> TOOL_FACTORIES = new LinkedHashMap<>();

    /** Schema text per tool name; {@code ""} marks a tool whose schema could not be obtained. */
    private static final Map<String, String> SCHEMA_CACHE = new ConcurrentHashMap<>();

    static {
        COMPOSITE_TOOL_NAMES.put(ValidationOperation.EXTERNAL_CREATE_REPORT, "external_manage"); //$NON-NLS-1$
        COMPOSITE_TOOL_NAMES.put(ValidationOperation.EXTERNAL_CREATE_PROCESSING, "external_manage"); //$NON-NLS-1$
        COMPOSITE_TOOL_NAMES.put(ValidationOperation.EXTENSION_CREATE_PROJECT, "extension_manage"); //$NON-NLS-1$
        COMPOSITE_TOOL_NAMES.put(ValidationOperation.EXTENSION_ADOPT_OBJECT, "extension_manage"); //$NON-NLS-1$
        COMPOSITE_TOOL_NAMES.put(ValidationOperation.EXTENSION_SET_PROPERTY_STATE, "extension_manage"); //$NON-NLS-1$
        COMPOSITE_TOOL_NAMES.put(ValidationOperation.DCS_CREATE_MAIN_SCHEMA, "dcs_manage"); //$NON-NLS-1$
        COMPOSITE_TOOL_NAMES.put(ValidationOperation.DCS_UPSERT_QUERY_DATASET, "dcs_manage"); //$NON-NLS-1$
        COMPOSITE_TOOL_NAMES.put(ValidationOperation.DCS_UPSERT_PARAMETER, "dcs_manage"); //$NON-NLS-1$
        COMPOSITE_TOOL_NAMES.put(ValidationOperation.DCS_UPSERT_CALCULATED_FIELD, "dcs_manage"); //$NON-NLS-1$

        COMPOSITE_COMMANDS.put(ValidationOperation.EXTERNAL_CREATE_REPORT, "create_report"); //$NON-NLS-1$
        COMPOSITE_COMMANDS.put(ValidationOperation.EXTERNAL_CREATE_PROCESSING, "create_processing"); //$NON-NLS-1$
        COMPOSITE_COMMANDS.put(ValidationOperation.EXTENSION_CREATE_PROJECT, "create"); //$NON-NLS-1$
        COMPOSITE_COMMANDS.put(ValidationOperation.EXTENSION_ADOPT_OBJECT, "adopt"); //$NON-NLS-1$
        COMPOSITE_COMMANDS.put(ValidationOperation.EXTENSION_SET_PROPERTY_STATE, "set_state"); //$NON-NLS-1$
        COMPOSITE_COMMANDS.put(ValidationOperation.DCS_CREATE_MAIN_SCHEMA, "create_schema"); //$NON-NLS-1$
        COMPOSITE_COMMANDS.put(ValidationOperation.DCS_UPSERT_QUERY_DATASET, "upsert_dataset"); //$NON-NLS-1$
        COMPOSITE_COMMANDS.put(ValidationOperation.DCS_UPSERT_PARAMETER, "upsert_param"); //$NON-NLS-1$
        COMPOSITE_COMMANDS.put(ValidationOperation.DCS_UPSERT_CALCULATED_FIELD, "upsert_field"); //$NON-NLS-1$

        // normalizePayload CREATE_METADATA: firstValue(payload, "adopt_existing", "adoptExisting", "adopt")
        EXTRA_ACCEPTED_KEYS.put(ValidationOperation.CREATE_METADATA,
                Set.of("adoptExisting", "adopt")); //$NON-NLS-1$ //$NON-NLS-2$
        // normalizeEnsureModuleArtifactPayload(String, Map): camelCase aliases for every field
        EXTRA_ACCEPTED_KEYS.put(ValidationOperation.ENSURE_MODULE_ARTIFACT,
                Set.of("objectFqn", "moduleType", "moduleKind", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        "createIfMissing", "initialContent")); //$NON-NLS-1$ //$NON-NLS-2$
        // normalizePayload RIGHTS_MANAGE: firstValue(payload, "role", "role_fqn", "role_name")
        EXTRA_ACCEPTED_KEYS.put(ValidationOperation.RIGHTS_MANAGE,
                Set.of("role_fqn", "role_name")); //$NON-NLS-1$ //$NON-NLS-2$

        TOOL_FACTORIES.put("create_metadata", CreateMetadataTool::new); //$NON-NLS-1$
        TOOL_FACTORIES.put("create_form", CreateFormTool::new); //$NON-NLS-1$
        TOOL_FACTORIES.put("apply_form_recipe", ApplyFormRecipeTool::new); //$NON-NLS-1$
        TOOL_FACTORIES.put("external_manage", ExternalManageTool::new); //$NON-NLS-1$
        TOOL_FACTORIES.put("extension_manage", ExtensionManageTool::new); //$NON-NLS-1$
        TOOL_FACTORIES.put("dcs_manage", DcsManageTool::new); //$NON-NLS-1$
        TOOL_FACTORIES.put("add_metadata_child", AddMetadataChildTool::new); //$NON-NLS-1$
        TOOL_FACTORIES.put("ensure_module_artifact", EnsureModuleArtifactTool::new); //$NON-NLS-1$
        TOOL_FACTORIES.put("update_metadata", UpdateMetadataTool::new); //$NON-NLS-1$
        TOOL_FACTORIES.put("delete_metadata", DeleteMetadataTool::new); //$NON-NLS-1$
        TOOL_FACTORIES.put("mutate_form_model", MutateFormModelTool::new); //$NON-NLS-1$
        TOOL_FACTORIES.put("rights_manage", RightsManageTool::new); //$NON-NLS-1$
        TOOL_FACTORIES.put("render_template", RenderTemplateTool::new); //$NON-NLS-1$
        TOOL_FACTORIES.put("create_event_subscription", CreateEventSubscriptionTool::new); //$NON-NLS-1$
        TOOL_FACTORIES.put("create_information_register", CreateInformationRegisterTool::new); //$NON-NLS-1$
    }

    private ValidationPayloadKeyContract() {
    }

    /** The tool that will receive the payload — the composite router for composite operations. */
    public static String targetToolName(ValidationOperation operation) {
        if (operation == null) {
            return null;
        }
        String composite = COMPOSITE_TOOL_NAMES.get(operation);
        return composite != null ? composite : operation.getToolName();
    }

    /**
     * The composite router for a composite operation, or {@code null} when the operation goes straight
     * to its own tool and per-command key checking does not apply.
     */
    public static String compositeToolName(ValidationOperation operation) {
        return operation == null ? null : COMPOSITE_TOOL_NAMES.get(operation);
    }

    /**
     * The {@code command} the composite tool will dispatch: the payload's own value when it carries
     * one, otherwise the one implied by the resolved operation name. {@code null} for a non-composite
     * operation, which leaves the per-command guard fail-open.
     */
    public static String compositeCommand(ValidationOperation operation, Map<?, ?> payload) {
        if (compositeToolName(operation) == null) {
            return null;
        }
        Object declared = payload == null ? null : payload.get("command"); //$NON-NLS-1$
        if (declared != null && !String.valueOf(declared).isBlank()) {
            // What the tool really dispatches on wins over what the operation name implies; if the two
            // disagree the token will fail to consume later anyway.
            return String.valueOf(declared);
        }
        return COMPOSITE_COMMANDS.get(operation);
    }

    public static Set<String> extraAcceptedKeys(ValidationOperation operation) {
        Set<String> extra = operation == null ? null : EXTRA_ACCEPTED_KEYS.get(operation);
        return extra != null ? extra : Set.of();
    }

    public static boolean isExempt(ValidationOperation operation) {
        return EXEMPT_TOOL_NAMES.contains(targetToolName(operation));
    }

    /**
     * The target tool's advertised parameter schema, or {@code null} when it cannot be obtained —
     * in which case the caller must fail open rather than refuse.
     */
    public static String targetToolSchema(ValidationOperation operation) {
        String toolName = targetToolName(operation);
        if (toolName == null) {
            return null;
        }
        String cached = SCHEMA_CACHE.computeIfAbsent(toolName, ValidationPayloadKeyContract::loadSchema);
        return cached.isEmpty() ? null : cached;
    }

    private static String loadSchema(String toolName) {
        Supplier<ITool> factory = TOOL_FACTORIES.get(toolName);
        if (factory == null) {
            return ""; //$NON-NLS-1$
        }
        try {
            String schema = factory.get().getParameterSchema();
            return schema == null ? "" : schema; //$NON-NLS-1$
        } catch (RuntimeException | LinkageError e) {
            // A tool that cannot even be constructed here must not break request validation.
            return ""; //$NON-NLS-1$
        }
    }
}
