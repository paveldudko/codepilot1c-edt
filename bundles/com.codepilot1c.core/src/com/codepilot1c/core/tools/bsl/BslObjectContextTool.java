/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.bsl;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.ast.EdtAstException;
import com.codepilot1c.core.edt.context.BslObjectContextRequest;
import com.codepilot1c.core.edt.context.BslObjectContextService;
import com.codepilot1c.core.tools.AbstractTool;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolResult;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * One-call object-level context aggregator. Composes the existing
 * {@code bsl_module_*} and {@code edt_metadata_details} /
 * {@code inspect_form_layout} / {@code edt_find_references} primitives
 * into a single response with caller-controlled {@code include} flags.
 *
 * <p>Driven by the AM-side feedback at
 * {@code 2026-05-19-bsl-edit-and-context-gaps.md} (Gap 2). The win over
 * the prior 5–7-tool-call orientation cycle: a Document or CommonModule
 * is now one round-trip, and the caller pays per section.</p>
 */
@ToolMeta(name = "bsl_object_context", category = "bsl", tags = {"read-only", "workspace", "edt"})
public class BslObjectContextTool extends AbstractTool {

    private static final Gson GSON = new Gson();

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "project_name": {"type": "string", "description": "EDT project containing the object"},
                "object_fqn": {"type": "string", "description": "Metadata FQN: 'Type.Name', e.g. 'Document.SalesOrder', 'CommonModule.GeneralPurpose'. Supported types: Document, Catalog, CommonModule, InformationRegister, AccumulationRegister, Report, DataProcessor, Enum, Constant."},
                "include": {
                  "type": "object",
                  "description": "Per-section include flags. Defaults yield a minimal-cost response: methods=signatures, no attributes/forms/callers.",
                  "properties": {
                    "methods": {"type": "string", "enum": ["none", "signatures", "bodies"], "description": "How much method info to include. 'signatures' (default) returns names + kind + export flag; 'bodies' adds the full source."},
                    "exports_only": {"type": "boolean", "description": "When true, restrict the methods section to exported (Export) procedures/functions only."},
                    "attributes": {"type": "boolean", "description": "Include attributes + standard attributes (Document/Catalog/Register etc.)."},
                    "tabular_sections": {"type": "boolean", "description": "Include tabular section names + their attributes."},
                    "form_layout": {"type": "string", "enum": ["none", "shallow", "full"], "description": "Whether to include each owned form's layout tree. 'shallow' caps depth at 2 (root groups only); 'full' returns the full tree."},
                    "callers": {"type": ["object", "boolean"], "description": "Find references to the object. Boolean true uses defaults; object form accepts {exports_only, max_per_method}."},
                    "manager_module_methods": {"type": "boolean", "description": "Include ManagerModule.bsl content (default true)."},
                    "object_module_methods": {"type": "boolean", "description": "Include ObjectModule.bsl / RecordSetModule.bsl content (default true). Set to false on objects where only ManagerModule is interesting."},
                    "module_directives": {"type": "boolean", "description": "Surface compiler directives (&AtClient / &AtServer / etc.) in the methods section."}
                  }
                }
              },
              "required": ["project_name", "object_fqn"]
            }
            """; //$NON-NLS-1$

    private final BslObjectContextService service;

    public BslObjectContextTool() {
        this(new BslObjectContextService());
    }

    public BslObjectContextTool(BslObjectContextService service) {
        this.service = service;
    }

    @Override
    public String getDescription() {
        return "Aggregates object-level context (methods, attributes, tabular sections, forms, callers) for a single 1C metadata object in one call. Каждый include-флаг управляет ценой запроса."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Object> raw = params.getRaw();
            try {
                BslObjectContextRequest req = BslObjectContextRequest.fromParameters(raw);
                JsonObject result = service.loadContext(req);
                return ToolResult.success(GSON.toJson(result), ToolResult.ToolResultType.SEARCH_RESULTS, result);
            } catch (IllegalArgumentException e) {
                return ToolResult.failure("{\"error\":\"INVALID_ARGUMENT\",\"message\":\"" //$NON-NLS-1$
                        + escapeJson(e.getMessage()) + "\"}"); //$NON-NLS-1$
            } catch (EdtAstException e) {
                JsonObject obj = new JsonObject();
                obj.addProperty("error", e.getCode().name()); //$NON-NLS-1$
                obj.addProperty("message", e.getMessage()); //$NON-NLS-1$
                obj.addProperty("recoverable", e.isRecoverable()); //$NON-NLS-1$
                return ToolResult.failure(GSON.toJson(obj));
            } catch (Exception e) {
                return ToolResult.failure("{\"error\":\"INTERNAL_ERROR\",\"message\":\"" //$NON-NLS-1$
                        + escapeJson(e.getMessage()) + "\"}"); //$NON-NLS-1$
            }
        });
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}
