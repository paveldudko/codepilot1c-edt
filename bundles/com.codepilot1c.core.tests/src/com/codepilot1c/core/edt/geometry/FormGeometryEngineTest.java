/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edt.geometry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.codepilot1c.core.edt.geometry.FormElementNode.Kind;
import com.codepilot1c.core.edt.geometry.FormGeometryEngine.BoundingBox;
import com.codepilot1c.core.edt.geometry.FormGeometryEngine.Layout;

/**
 * Tests for the pure-Java geometry engine that powers
 * {@code get_form_geometry}. The engine takes an EMF-decoupled
 * {@link FormElementNode} tree and produces a {@code Map<name, BoundingBox>}
 * — enough for the agent to assert spatial relationships ("group X is
 * above table Y") and detect overlapping items, without an SWT designer.
 *
 * <p>Pure-Java by design: zero Eclipse dependencies, exercises the most
 * fragile part of Phase 3 (layout math) in plain JUnit.</p>
 */
public class FormGeometryEngineTest {

    private static final int CANVAS = 1000;

    private final FormGeometryEngine engine = new FormGeometryEngine();

    // --- single-element layouts ----------------------------------------------

    @Test
    public void singleFieldGetsExpectedHeight() {
        FormElementNode root = form(field("LastName"));
        Layout layout = engine.compute(root, CANVAS);
        BoundingBox bbox = layout.bboxes().get("LastName");
        assertNotNull(bbox);
        assertEquals("FIELD baseline height is 26", 26, bbox.height()); //$NON-NLS-1$
    }

    @Test
    public void singleGroupHasHeaderPlusBodyHeight() {
        FormElementNode root = form(group("Personal", List.of(field("FirstName"), field("LastName"))));
        Layout layout = engine.compute(root, CANVAS);
        BoundingBox group = layout.bboxes().get("Personal");
        // 28 header + 6 + (26 + 6 + 26) + 12 bottom padding = ~104. Don't
        // pin the exact value; pin the lower-bound that excludes pathological
        // collapses ("group only 28px tall = header without body").
        assertTrue("group must be at least header+body high", group.height() > 60); //$NON-NLS-1$
    }

    // --- vertical stacking ---------------------------------------------------

    @Test
    public void verticalStackOrdersChildrenTopToBottom() {
        FormElementNode root = form(group("Outer", List.of(field("A"), field("B"), field("C"))));
        Layout layout = engine.compute(root, CANVAS);
        BoundingBox a = layout.bboxes().get("A");
        BoundingBox b = layout.bboxes().get("B");
        BoundingBox c = layout.bboxes().get("C");
        assertTrue("A must be above B", a.y() < b.y()); //$NON-NLS-1$
        assertTrue("B must be above C", b.y() < c.y()); //$NON-NLS-1$
        assertEquals("All vertical children share the same x", a.x(), b.x());
        assertEquals(b.x(), c.x());
    }

    // --- horizontal layout (UsualGroup.ChildrenGroup=AlwaysHorizontal) ------

    @Test
    public void horizontalGroupPlacesChildrenSideBySide() {
        FormElementNode root = form(horizontalGroup("Row", List.of(field("L"), field("R"))));
        Layout layout = engine.compute(root, CANVAS);
        BoundingBox l = layout.bboxes().get("L");
        BoundingBox r = layout.bboxes().get("R");
        assertEquals("siblings of a horizontal group share the same y", l.y(), r.y()); //$NON-NLS-1$
        assertTrue("right sibling x must be greater", r.x() > l.x()); //$NON-NLS-1$
        assertTrue("right sibling x must clear the left bbox", r.x() >= l.x() + l.width()); //$NON-NLS-1$
    }

    @Test
    public void horizontalGroupSharesWidthEquallyByDefault() {
        FormElementNode root = form(horizontalGroup("Row", List.of(field("L"), field("R"))));
        Layout layout = engine.compute(root, CANVAS);
        BoundingBox l = layout.bboxes().get("L");
        BoundingBox r = layout.bboxes().get("R");
        int delta = Math.abs(l.width() - r.width());
        assertTrue("equal-share widths must be within ±2 px (rounding)", delta <= 2); //$NON-NLS-1$
    }

    // --- nested layout -------------------------------------------------------

    @Test
    public void nestedGroupContainsItsChildrenSpatially() {
        FormElementNode root = form(
                group("Container", List.of(
                        group("Inner", List.of(field("Inside"))))));
        Layout layout = engine.compute(root, CANVAS);
        BoundingBox container = layout.bboxes().get("Container");
        BoundingBox inner = layout.bboxes().get("Inner");
        BoundingBox inside = layout.bboxes().get("Inside");
        assertTrue("Inner must be inside Container x-range",
                inner.x() >= container.x() && inner.x() + inner.width() <= container.x() + container.width());
        assertTrue("Inner must be inside Container y-range",
                inner.y() >= container.y() && inner.y() + inner.height() <= container.y() + container.height());
        assertTrue("Inside must be inside Inner",
                inside.y() > inner.y());
    }

    // --- pages ---------------------------------------------------------------

    @Test
    public void pagesContainerOnlyLaysOutFirstPage() {
        FormElementNode root = form(pages("Pages", List.of(
                page("Page1", List.of(field("OnFirst"))),
                page("Page2", List.of(field("OnSecond"))))));
        Layout layout = engine.compute(root, CANVAS);
        assertNotNull("first page content must be laid out", layout.bboxes().get("OnFirst")); //$NON-NLS-1$ //$NON-NLS-2$
        // Inactive pages don't get bboxes — we only render the active one.
        assertTrue("inactive page content should not appear in layout", //$NON-NLS-1$
                !layout.bboxes().containsKey("OnSecond")); //$NON-NLS-1$
    }

    // --- canvas size ---------------------------------------------------------

    @Test
    public void canvasSizeReflectsContentHeight() {
        FormElementNode root = form(
                field("F1"),
                field("F2"),
                field("F3"));
        Layout layout = engine.compute(root, CANVAS);
        BoundingBox f3 = layout.bboxes().get("F3");
        assertTrue("canvas height must accommodate last child", //$NON-NLS-1$
                layout.canvasHeight() >= f3.y() + f3.height());
    }

    @Test
    public void canvasWidthMatchesInput() {
        FormElementNode root = form(field("X"));
        Layout layout = engine.compute(root, 800);
        assertEquals(800, layout.canvasWidth());
    }

    // --- invisible items -----------------------------------------------------

    @Test
    public void invisibleItemsAreOmittedFromLayout() {
        FormElementNode hidden = new FormElementNode("Hidden", Kind.FIELD, false, true, false, null, List.of());
        FormElementNode root = form(field("Shown"), hidden);
        Layout layout = engine.compute(root, CANVAS);
        assertTrue("invisible items must not appear", !layout.bboxes().containsKey("Hidden")); //$NON-NLS-1$
        assertNotNull("visible siblings remain", layout.bboxes().get("Shown")); //$NON-NLS-1$
    }

    // --- buttons + tables ----------------------------------------------------

    @Test
    public void buttonHasFixedHeightDistinctFromField() {
        FormElementNode root = form(button("Save"));
        Layout layout = engine.compute(root, CANVAS);
        BoundingBox btn = layout.bboxes().get("Save");
        assertEquals("BUTTON height is 30", 30, btn.height()); //$NON-NLS-1$
    }

    @Test
    public void tableHeaderAndColumnsLayoutSideBySide() {
        FormElementNode table = new FormElementNode(
                "Goods", Kind.TABLE, true, true, false, null,
                List.of(column("Item"), column("Qty"), column("Price")));
        FormElementNode root = form(table);
        Layout layout = engine.compute(root, CANVAS);
        BoundingBox tbl = layout.bboxes().get("Goods");
        BoundingBox item = layout.bboxes().get("Item");
        BoundingBox price = layout.bboxes().get("Price");
        assertNotNull(item);
        assertNotNull(price);
        assertEquals("table columns share y", item.y(), price.y()); //$NON-NLS-1$
        assertTrue("Price column must be to the right of Item", item.x() < price.x()); //$NON-NLS-1$
        assertTrue("table must contain its columns vertically", //$NON-NLS-1$
                item.y() >= tbl.y() && item.y() + item.height() <= tbl.y() + tbl.height());
    }

    // --- helpers --------------------------------------------------------------

    private static FormElementNode form(FormElementNode... children) {
        return new FormElementNode("Form", Kind.FORM, true, true, false, null, List.of(children));
    }

    private static FormElementNode group(String name, List<FormElementNode> children) {
        return new FormElementNode(name, Kind.GROUP, true, true, false, null, children);
    }

    private static FormElementNode horizontalGroup(String name, List<FormElementNode> children) {
        return new FormElementNode(name, Kind.GROUP, true, true, true, null, children);
    }

    private static FormElementNode pages(String name, List<FormElementNode> children) {
        return new FormElementNode(name, Kind.PAGES, true, true, false, null, children);
    }

    private static FormElementNode page(String name, List<FormElementNode> children) {
        return new FormElementNode(name, Kind.PAGE, true, true, false, null, children);
    }

    private static FormElementNode field(String name) {
        return new FormElementNode(name, Kind.FIELD, true, true, false, null, List.of());
    }

    private static FormElementNode button(String name) {
        return new FormElementNode(name, Kind.BUTTON, true, true, false, null, List.of());
    }

    private static FormElementNode column(String name) {
        return new FormElementNode(name, Kind.COLUMN, true, true, false, null, List.of());
    }
}
