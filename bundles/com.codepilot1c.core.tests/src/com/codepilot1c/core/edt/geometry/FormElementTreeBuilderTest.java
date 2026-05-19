/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edt.geometry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.codepilot1c.core.edt.forms.InspectFormLayoutResult;
import com.codepilot1c.core.edt.forms.InspectFormLayoutResult.FormItemNode;
import com.codepilot1c.core.edt.geometry.FormElementNode.Kind;

/**
 * Tests for the EMF-decoupled conversion from {@link InspectFormLayoutResult}
 * to {@link FormElementNode}. Pinning the kind-string mapping is the
 * point: production passes use {@code item.eClass().getName()} with an
 * optional {@code :GROUP_TYPE} suffix — the builder must recognize each
 * variant correctly.
 */
public class FormElementTreeBuilderTest {

    private final FormElementTreeBuilder builder = new FormElementTreeBuilder();

    // --- kind mapping --------------------------------------------------------

    @Test
    public void mapsFormFieldToFieldKind() {
        FormElementNode out = build("FormField", List.of());
        assertEquals(Kind.FIELD, out.kind());
    }

    @Test
    public void mapsTableKind() {
        FormElementNode out = build("Table", List.of());
        assertEquals(Kind.TABLE, out.kind());
    }

    @Test
    public void mapsButtonKind() {
        FormElementNode out = build("Button", List.of());
        assertEquals(Kind.BUTTON, out.kind());
    }

    @Test
    public void mapsDecorationKind() {
        FormElementNode out = build("Decoration", List.of());
        assertEquals(Kind.DECORATION, out.kind());
    }

    @Test
    public void mapsUsualGroupToGroupKind() {
        FormElementNode out = build("FormGroup:USUAL_GROUP", List.of());
        assertEquals(Kind.GROUP, out.kind());
    }

    @Test
    public void mapsPagesGroupToPagesKind() {
        FormElementNode out = build("FormGroup:PAGES", List.of());
        assertEquals(Kind.PAGES, out.kind());
    }

    @Test
    public void mapsPageGroupToPageKind() {
        FormElementNode out = build("FormGroup:PAGE", List.of());
        assertEquals(Kind.PAGE, out.kind());
    }

    @Test
    public void mapsCommandBarGroupToCommandBarKind() {
        FormElementNode out = build("FormGroup:COMMAND_BAR", List.of());
        assertEquals(Kind.COMMAND_BAR, out.kind());
    }

    @Test
    public void mapsUnknownKindToFieldAsFallback() {
        FormElementNode out = build("WeirdNewKind", List.of());
        assertEquals("Unknown kinds must default to FIELD so the engine doesn't crash", //$NON-NLS-1$
                Kind.FIELD, out.kind());
    }

    // --- horizontal-group detection ------------------------------------------

    @Test
    public void usualGroupWithAlwaysHorizontalChildrenGroupIsHorizontal() {
        FormItemNode src = node("Row", "FormGroup:USUAL_GROUP",
                Map.of("childrenGroup", "AlwaysHorizontal"), List.of(
                        node("L", "FormField", Map.of(), List.of()),
                        node("R", "FormField", Map.of(), List.of())));
        FormElementNode out = builder.buildSubtree(src);
        assertTrue("childrenGroup=AlwaysHorizontal must yield horizontalGroup=true", //$NON-NLS-1$
                out.horizontalGroup());
    }

    @Test
    public void usualGroupWithHorizontalIfPossibleIsHorizontal() {
        FormItemNode src = node("Row", "FormGroup:USUAL_GROUP",
                Map.of("childrenGroup", "HorizontalIfPossible"), List.of(
                        node("L", "FormField", Map.of(), List.of()),
                        node("R", "FormField", Map.of(), List.of())));
        FormElementNode out = builder.buildSubtree(src);
        assertTrue(out.horizontalGroup());
    }

    @Test
    public void usualGroupWithVerticalChildrenGroupIsNotHorizontal() {
        FormItemNode src = node("Stack", "FormGroup:USUAL_GROUP",
                Map.of("childrenGroup", "Vertical"), List.of(
                        node("A", "FormField", Map.of(), List.of())));
        FormElementNode out = builder.buildSubtree(src);
        assertFalse(out.horizontalGroup());
    }

    @Test
    public void usualGroupWithoutChildrenGroupDefaultsToVertical() {
        FormItemNode src = node("Stack", "FormGroup:USUAL_GROUP", Map.of(), List.of());
        FormElementNode out = builder.buildSubtree(src);
        assertFalse(out.horizontalGroup());
    }

    // --- visibility / enabled propagation ------------------------------------

    @Test
    public void visibleFalsePropagates() {
        FormItemNode src = new FormItemNode(1, null, 0, "Form/X", "X", "FormField",
                Map.of(), Boolean.FALSE, Boolean.TRUE, Boolean.FALSE,
                null, null, null, Map.of(), List.of());
        FormElementNode out = builder.buildSubtree(src);
        assertFalse(out.visible());
        assertTrue(out.enabled());
    }

    @Test
    public void nullVisibleDefaultsToVisible() {
        FormItemNode src = new FormItemNode(1, null, 0, "Form/X", "X", "FormField",
                Map.of(), null, null, null, null, null, null, Map.of(), List.of());
        FormElementNode out = builder.buildSubtree(src);
        assertTrue("null visible flag must default to true (matches Configurator)", //$NON-NLS-1$
                out.visible());
        assertTrue(out.enabled());
    }

    // --- root assembly -------------------------------------------------------

    @Test
    public void rootAssemblesFromItemListWithSyntheticFormNode() {
        InspectFormLayoutResult result = new InspectFormLayoutResult(
                "AM", "Document.X.Form.DocForm", "DocForm",
                Map.of(), 2, false,
                List.of(node("A", "FormField", Map.of(), List.of()),
                        node("B", "FormField", Map.of(), List.of())));
        FormElementNode root = builder.buildRoot(result);
        assertEquals("Root must be a synthetic FORM node", Kind.FORM, root.kind()); //$NON-NLS-1$
        assertEquals(2, root.children().size());
        assertEquals("A", root.children().get(0).name());
        assertEquals("B", root.children().get(1).name());
    }

    @Test
    public void rootNamePicksFormNameWhenPresent() {
        InspectFormLayoutResult result = new InspectFormLayoutResult(
                "AM", "Document.X.Form.DocumentForm", "DocumentForm",
                Map.of(), 0, false, List.of());
        FormElementNode root = builder.buildRoot(result);
        assertNotNull(root.name());
        assertTrue("root name should embed the form name", //$NON-NLS-1$
                root.name().contains("DocumentForm"));
    }

    // --- helpers --------------------------------------------------------------

    private FormElementNode build(String kind, List<FormItemNode> children) {
        return builder.buildSubtree(node("Item_" + kind, kind, Map.of(), children));
    }

    private static FormItemNode node(String name, String kind,
                                     Map<String, Object> properties, List<FormItemNode> children) {
        return new FormItemNode(name.hashCode(), null, 0, "Form/" + name, name, kind,
                Map.of(), Boolean.TRUE, Boolean.TRUE, Boolean.FALSE,
                null, null, null, properties, children);
    }
}
