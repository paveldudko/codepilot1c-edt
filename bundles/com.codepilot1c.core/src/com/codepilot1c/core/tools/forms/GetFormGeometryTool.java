/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.forms;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.geometry.FormGeometryEngine.BoundingBox;
import com.codepilot1c.core.edt.geometry.FormGeometryService;
import com.codepilot1c.core.edt.geometry.FormGeometryService.FormGeometryResult;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;
import com.codepilot1c.core.tools.AbstractTool;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolResult;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Returns a schematic bounding-box layout for a managed form. The agent
 * uses this to assert spatial relationships (X above Y, items overlap,
 * a Pages widget really landed inside the right group) without paying
 * the cost of starting a TestClient + screenshot loop.
 *
 * <p>This is the first half of Gap 5 from the AM-side feedback at
 * {@code 2026-05-19-bsl-edit-and-context-gaps.md}: structured geometry
 * only, no PNG. The PNG render and pixel-diff variants land in Phase 4.</p>
 */
@ToolMeta(name = "get_form_geometry", category = "forms", tags = {"read-only", "workspace", "edt"})
public class GetFormGeometryTool extends AbstractTool {

    private static final Gson GSON = new Gson();
    private static final int MIN_CANVAS_WIDTH = 200;
    private static final int MAX_CANVAS_WIDTH = 4000;

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "project_name": {"type": "string", "description": "EDT project containing the form"},
                "form_fqn": {"type": "string", "description": "Form FQN, e.g. 'Document.SalesOrder.Form.DocumentForm', 'Catalog.Nomenclature.Form.ItemForm'"},
                "canvas_width": {"type": "integer", "description": "Layout canvas width in pixels (default 1240, clamped to [200, 4000]). Affects absolute bbox values but not their relative ordering."},
                "highlight": {"type": "array", "items": {"type": "string"}, "description": "Item names to mark as highlighted in the response. Informational — does not affect geometry."}
              },
              "required": ["project_name", "form_fqn"]
            }
            """; //$NON-NLS-1$

    private final FormGeometryService service;

    public GetFormGeometryTool() {
        this(new FormGeometryService());
    }

    public GetFormGeometryTool(FormGeometryService service) {
        this.service = service;
    }

    @Override
    public String getDescription() {
        return "Returns a schematic bounding-box layout of a form: items[].bbox [x,y,w,h] for each visible element. Headless, no PNG. Good for checking spatial relationships and detecting overlaps."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        return CompletableFuture.supplyAsync(() -> {
            String projectName = params.requireString("project_name"); //$NON-NLS-1$
            String formFqn = params.requireString("form_fqn"); //$NON-NLS-1$
            int canvasWidth = clampCanvasWidth(params.optInt("canvas_width", 0)); //$NON-NLS-1$
            Set<String> highlightSet = readHighlightSet(params.getRaw());

            try {
                FormGeometryResult result = service.computeGeometry(projectName, formFqn, canvasWidth);
                JsonObject payload = buildPayload(result, highlightSet);
                return ToolResult.success(GSON.toJson(payload), ToolResult.ToolResultType.SEARCH_RESULTS, payload);
            } catch (MetadataOperationException e) {
                JsonObject err = new JsonObject();
                err.addProperty("error", e.getCode().name()); //$NON-NLS-1$
                err.addProperty("message", e.getMessage()); //$NON-NLS-1$
                err.addProperty("recoverable", e.isRecoverable()); //$NON-NLS-1$
                return ToolResult.failure(GSON.toJson(err));
            } catch (Exception e) {
                return ToolResult.failure("{\"error\":\"INTERNAL_ERROR\",\"message\":\"" //$NON-NLS-1$
                        + escapeJson(e.getMessage()) + "\"}"); //$NON-NLS-1$
            }
        });
    }

    private static int clampCanvasWidth(int requested) {
        if (requested <= 0) {
            return 0; // service applies its own default
        }
        return Math.max(MIN_CANVAS_WIDTH, Math.min(MAX_CANVAS_WIDTH, requested));
    }

    @SuppressWarnings("unchecked")
    private static Set<String> readHighlightSet(Map<String, Object> raw) {
        Object highlight = raw.get("highlight"); //$NON-NLS-1$
        if (highlight instanceof Iterable<?> iter) {
            java.util.HashSet<String> set = new java.util.HashSet<>();
            for (Object item : iter) {
                if (item != null) set.add(item.toString());
            }
            return set;
        }
        return Set.of();
    }

    private JsonObject buildPayload(FormGeometryResult result, Set<String> highlightSet) {
        JsonObject root = new JsonObject();
        root.addProperty("project_name", result.projectName()); //$NON-NLS-1$
        root.addProperty("form_fqn", result.formFqn()); //$NON-NLS-1$
        if (result.formName() != null) {
            root.addProperty("form_name", result.formName()); //$NON-NLS-1$
        }
        root.addProperty("canvas_width", result.canvasWidth()); //$NON-NLS-1$
        root.addProperty("canvas_height", result.canvasHeight()); //$NON-NLS-1$
        root.addProperty("total_items", result.totalItems()); //$NON-NLS-1$
        root.addProperty("truncated", result.truncated()); //$NON-NLS-1$

        JsonArray items = new JsonArray();
        // Walk the tree in document order so the response array order is
        // stable across runs (Map<...> iteration order is LinkedHashMap-backed).
        walkAndEmit(result.root(), result.bboxes(), highlightSet, items);
        root.add("items", items); //$NON-NLS-1$
        return root;
    }

    private void walkAndEmit(com.codepilot1c.core.edt.geometry.FormElementNode node,
                             Map<String, BoundingBox> bboxes,
                             Set<String> highlightSet,
                             JsonArray out) {
        if (node == null) return;
        BoundingBox bbox = bboxes.get(node.name());
        if (bbox != null && node.kind() != com.codepilot1c.core.edt.geometry.FormElementNode.Kind.FORM) {
            JsonObject entry = new JsonObject();
            entry.addProperty("name", node.name()); //$NON-NLS-1$
            entry.addProperty("kind", node.kind().name()); //$NON-NLS-1$
            entry.addProperty("visible", node.visible()); //$NON-NLS-1$
            entry.addProperty("enabled", node.enabled()); //$NON-NLS-1$
            if (node.dataPath() != null) {
                entry.addProperty("data_path", node.dataPath()); //$NON-NLS-1$
            }
            JsonArray bboxArray = new JsonArray();
            bboxArray.add(bbox.x());
            bboxArray.add(bbox.y());
            bboxArray.add(bbox.width());
            bboxArray.add(bbox.height());
            entry.add("bbox", bboxArray); //$NON-NLS-1$
            if (highlightSet.contains(node.name())) {
                entry.addProperty("highlighted", true); //$NON-NLS-1$
            }
            out.add(entry);
        }
        for (com.codepilot1c.core.edt.geometry.FormElementNode child : node.children()) {
            walkAndEmit(child, bboxes, highlightSet, out);
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}
