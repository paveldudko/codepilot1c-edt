/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edt.geometry;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.codepilot1c.core.edt.forms.InspectFormLayoutResult;
import com.codepilot1c.core.edt.forms.InspectFormLayoutResult.FormItemNode;

/**
 * Converts the EMF-derived {@link InspectFormLayoutResult} tree into an
 * EMF-decoupled {@link FormElementNode} tree the geometry engine can
 * work with.
 *
 * <p>Pure-Java; the EMF walk lives behind {@code EdtFormService.inspectFormLayout}.
 * We reuse that walk instead of duplicating it, then translate the
 * record-based result into our IR. Mapping is driven by the {@code kind}
 * string produced by EDT — see {@code EdtMetadataService} where it
 * encodes {@code eClass().getName()} optionally suffixed with the
 * FormGroup type, e.g. {@code FormGroup:PAGES}.</p>
 */
public class FormElementTreeBuilder {

    /**
     * Builds the root {@link FormElementNode} for a whole form: a synthetic
     * FORM-kind node wrapping the items[] list.
     */
    public FormElementNode buildRoot(InspectFormLayoutResult layout) {
        List<FormElementNode> children = new ArrayList<>();
        if (layout != null && layout.items() != null) {
            for (FormItemNode item : layout.items()) {
                children.add(buildSubtree(item));
            }
        }
        String rootName = layout != null && layout.formName() != null && !layout.formName().isBlank()
                ? "Form:" + layout.formName()
                : "Form";
        return new FormElementNode(rootName, FormElementNode.Kind.FORM, true, true, false, null, children);
    }

    /** Recursively converts a single {@link FormItemNode} subtree. */
    public FormElementNode buildSubtree(FormItemNode src) {
        if (src == null) {
            return null;
        }
        FormElementNode.Kind kind = mapKind(src.kind());
        boolean visible = src.visible() == null || src.visible();
        boolean enabled = src.enabled() == null || src.enabled();
        boolean horizontal = isHorizontalGroup(kind, src.properties());

        List<FormElementNode> children = new ArrayList<>();
        if (src.children() != null) {
            for (FormItemNode child : src.children()) {
                FormElementNode mapped = buildSubtree(child);
                if (mapped != null) {
                    children.add(mapped);
                }
            }
        }

        return new FormElementNode(
                src.name(),
                kind,
                visible,
                enabled,
                horizontal,
                src.dataPath(),
                children);
    }

    private FormElementNode.Kind mapKind(String rawKind) {
        if (rawKind == null || rawKind.isBlank()) {
            return FormElementNode.Kind.FIELD;
        }
        String head = rawKind;
        String suffix = null;
        int colon = rawKind.indexOf(':');
        if (colon >= 0) {
            head = rawKind.substring(0, colon);
            suffix = rawKind.substring(colon + 1).toUpperCase(Locale.ROOT);
        }
        switch (head) {
            case "FormGroup":
                if (suffix == null) return FormElementNode.Kind.GROUP;
                return switch (suffix) {
                    case "PAGES" -> FormElementNode.Kind.PAGES;
                    case "PAGE" -> FormElementNode.Kind.PAGE;
                    case "COMMAND_BAR", "COMMANDBAR" -> FormElementNode.Kind.COMMAND_BAR;
                    default -> FormElementNode.Kind.GROUP; // USUAL_GROUP, COLUMN_GROUP, BUTTON_GROUP, …
                };
            case "FormField":
                return FormElementNode.Kind.FIELD;
            case "Table":
                return FormElementNode.Kind.TABLE;
            case "Button":
                return FormElementNode.Kind.BUTTON;
            case "Decoration":
                return FormElementNode.Kind.DECORATION;
            default:
                return FormElementNode.Kind.FIELD;
        }
    }

    private boolean isHorizontalGroup(FormElementNode.Kind kind, Map<String, Object> properties) {
        if (kind != FormElementNode.Kind.GROUP || properties == null) {
            return false;
        }
        Object cg = properties.get("childrenGroup");
        if (cg == null) {
            return false;
        }
        String s = cg.toString().toLowerCase(Locale.ROOT);
        return s.contains("alwayshorizontal") || s.contains("horizontalifpossible");
    }
}
