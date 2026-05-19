/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edt.geometry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure-Java schematic layout engine for {@link FormElementNode} trees.
 *
 * <p>Given a tree and a canvas width, returns a {@link Layout} mapping
 * each visible node's name to a {@link BoundingBox} plus the canvas
 * height required to contain everything. The layout is "schematic":
 * fixed-height controls, vertical stacking by default, with horizontal
 * groups laying children out side-by-side. Sufficient for the agent to
 * answer spatial questions ("X is above Y", "items overlap") without
 * needing the real EDT designer renderer.</p>
 *
 * <p>The engine does NOT itself walk EMF — see {@code FormGeometryService}
 * for the EMF→{@link FormElementNode} adaptation. Splitting the two
 * lets us unit-test layout math without an Eclipse runtime.</p>
 */
public class FormGeometryEngine {

    // Standard layout constants — chosen to match the typical EDT designer
    // grid (Segoe UI 11pt, 26px input height). Tweaking these shifts the
    // absolute bbox values but not their ordering.
    private static final int PADDING = 12;
    private static final int ELEMENT_SPACING = 6;
    private static final int GROUP_HEADER_HEIGHT = 28;
    private static final int FIELD_HEIGHT = 26;
    private static final int BUTTON_HEIGHT = 30;
    private static final int TABLE_HEADER_HEIGHT = 28;
    private static final int TABLE_ROW_HEIGHT = 24;
    private static final int TABLE_DISPLAY_ROWS = 3;
    private static final int DECORATION_HEIGHT = 20;
    private static final int COMMAND_BAR_HEIGHT = 30;
    private static final int PAGES_TAB_STRIP_HEIGHT = 34;
    private static final int COLUMN_HEIGHT = TABLE_HEADER_HEIGHT + TABLE_DISPLAY_ROWS * TABLE_ROW_HEIGHT;

    public record BoundingBox(int x, int y, int width, int height) {}

    public record Layout(int canvasWidth, int canvasHeight, Map<String, BoundingBox> bboxes) {}

    /**
     * Lays out the tree at canvas width {@code canvasWidth}. Invisible
     * nodes are silently skipped — they never appear in the result.
     */
    public Layout compute(FormElementNode root, int canvasWidth) {
        if (canvasWidth <= 0) {
            canvasWidth = 1000;
        }
        Map<String, BoundingBox> out = new LinkedHashMap<>();
        int innerWidth = canvasWidth - 2 * PADDING;
        int yAfter = layoutNode(root, PADDING, PADDING, innerWidth, out);
        int canvasHeight = yAfter + PADDING;
        return new Layout(canvasWidth, canvasHeight, out);
    }

    /**
     * Lays out {@code node} starting at {@code (x, y)} within a region of
     * width {@code width}, returns the y coordinate immediately below
     * the node's footprint.
     */
    private int layoutNode(FormElementNode node, int x, int y, int width, Map<String, BoundingBox> out) {
        if (node == null || !node.visible()) {
            return y;
        }
        return switch (node.kind()) {
            case FORM -> layoutForm(node, x, y, width, out);
            case GROUP -> layoutGroup(node, x, y, width, out);
            case PAGES -> layoutPages(node, x, y, width, out);
            case PAGE -> layoutGroup(node, x, y, width, out);
            case FIELD -> layoutFixedHeight(node, x, y, width, FIELD_HEIGHT, out);
            case BUTTON -> layoutFixedHeight(node, x, y, width, BUTTON_HEIGHT, out);
            case DECORATION -> layoutFixedHeight(node, x, y, width, DECORATION_HEIGHT, out);
            case COMMAND_BAR -> layoutFixedHeight(node, x, y, width, COMMAND_BAR_HEIGHT, out);
            case TABLE -> layoutTable(node, x, y, width, out);
            case COLUMN -> layoutFixedHeight(node, x, y, width, COLUMN_HEIGHT, out);
        };
    }

    private int layoutForm(FormElementNode node, int x, int y, int width, Map<String, BoundingBox> out) {
        // FORM does NOT emit a bbox of its own — its kids occupy the canvas.
        int cursor = y;
        for (FormElementNode child : node.children()) {
            if (!child.visible()) continue;
            cursor = layoutNode(child, x, cursor, width, out);
            cursor += ELEMENT_SPACING;
        }
        if (!node.children().isEmpty()) {
            cursor -= ELEMENT_SPACING;
        }
        return cursor;
    }

    private int layoutGroup(FormElementNode node, int x, int y, int width, Map<String, BoundingBox> out) {
        int innerX = x + PADDING;
        int innerWidth = width - 2 * PADDING;
        int contentTop = y + (node.kind() == FormElementNode.Kind.GROUP ? GROUP_HEADER_HEIGHT + ELEMENT_SPACING : 0);

        int contentBottom;
        if (node.horizontalGroup() && !node.children().isEmpty()) {
            contentBottom = layoutHorizontal(node.children(), innerX, contentTop, innerWidth, out);
        } else {
            contentBottom = layoutVerticalStack(node.children(), innerX, contentTop, innerWidth, out);
        }
        int height = contentBottom - y + PADDING;
        // Minimum height — a header-only group still needs to show its header.
        if (height < GROUP_HEADER_HEIGHT) {
            height = GROUP_HEADER_HEIGHT;
        }
        out.put(node.name(), new BoundingBox(x, y, width, height));
        return y + height;
    }

    private int layoutPages(FormElementNode node, int x, int y, int width, Map<String, BoundingBox> out) {
        int strip = y + PAGES_TAB_STRIP_HEIGHT;
        int contentBottom = strip;
        // Only the FIRST visible PAGE renders content — others are tabs we
        // know are present but don't compute geometry for.
        for (FormElementNode child : node.children()) {
            if (!child.visible() || child.kind() != FormElementNode.Kind.PAGE) {
                continue;
            }
            contentBottom = layoutVerticalStack(child.children(), x + PADDING, strip + ELEMENT_SPACING,
                    width - 2 * PADDING, out);
            // Emit a bbox for the active page itself (size = strip+content).
            out.put(child.name(),
                    new BoundingBox(x, strip, width, Math.max(contentBottom - strip + PADDING, ELEMENT_SPACING)));
            break;
        }
        int height = Math.max(contentBottom - y + PADDING, PAGES_TAB_STRIP_HEIGHT);
        out.put(node.name(), new BoundingBox(x, y, width, height));
        return y + height;
    }

    private int layoutTable(FormElementNode node, int x, int y, int width, Map<String, BoundingBox> out) {
        int tableHeight = TABLE_HEADER_HEIGHT + TABLE_DISPLAY_ROWS * TABLE_ROW_HEIGHT;
        out.put(node.name(), new BoundingBox(x, y, width, tableHeight));
        // Columns line up horizontally inside the header row.
        List<FormElementNode> columns = node.children();
        if (!columns.isEmpty()) {
            int columnCount = (int) columns.stream().filter(FormElementNode::visible).count();
            if (columnCount == 0) {
                return y + tableHeight;
            }
            int colWidth = width / columnCount;
            int cursorX = x;
            for (FormElementNode column : columns) {
                if (!column.visible()) continue;
                out.put(column.name(),
                        new BoundingBox(cursorX, y, colWidth, TABLE_HEADER_HEIGHT));
                cursorX += colWidth;
            }
        }
        return y + tableHeight;
    }

    private int layoutFixedHeight(FormElementNode node, int x, int y, int width, int height,
                                  Map<String, BoundingBox> out) {
        out.put(node.name(), new BoundingBox(x, y, width, height));
        return y + height;
    }

    private int layoutVerticalStack(List<FormElementNode> children, int x, int y, int width,
                                    Map<String, BoundingBox> out) {
        int cursor = y;
        for (FormElementNode child : children) {
            if (!child.visible()) continue;
            cursor = layoutNode(child, x, cursor, width, out);
            cursor += ELEMENT_SPACING;
        }
        // Drop trailing spacing if anything was added.
        boolean hasVisibleChild = children.stream().anyMatch(FormElementNode::visible);
        if (hasVisibleChild) {
            cursor -= ELEMENT_SPACING;
        }
        return cursor;
    }

    private int layoutHorizontal(List<FormElementNode> children, int x, int y, int width,
                                 Map<String, BoundingBox> out) {
        int visibleCount = (int) children.stream().filter(FormElementNode::visible).count();
        if (visibleCount == 0) {
            return y;
        }
        int slot = width / visibleCount;
        int cursorX = x;
        int maxBottom = y;
        for (FormElementNode child : children) {
            if (!child.visible()) continue;
            int childBottom = layoutNode(child, cursorX, y, slot, out);
            if (childBottom > maxBottom) {
                maxBottom = childBottom;
            }
            cursorX += slot;
        }
        return maxBottom;
    }
}
