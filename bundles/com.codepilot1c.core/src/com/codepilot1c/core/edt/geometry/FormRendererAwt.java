/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edt.geometry;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Set;

import com.codepilot1c.core.edt.geometry.FormGeometryEngine.BoundingBox;
import com.codepilot1c.core.edt.geometry.FormGeometryEngine.Layout;

/**
 * Pure-Java AWT renderer that draws a schematic image of a form from a
 * {@link FormElementNode} tree + a pre-computed {@link Layout}. Fully
 * headless (no SWT/Display) — designed to run inside a tool-execution
 * thread on a server JVM.
 *
 * <p>The rendering is intentionally schematic: enough to verify "Pages
 * widget really landed inside the right group", "two items don't
 * overlap visually", or "the highlighted item is where the agent
 * expected". For pixel-perfect designer screenshots use the live EDT
 * editor; this is the cheap headless approximation.</p>
 */
public class FormRendererAwt {

    // --- palette -------------------------------------------------------------
    private static final Color BG               = new Color(245, 245, 248);
    private static final Color CANVAS_BG        = new Color(255, 255, 255);
    private static final Color GROUP_BORDER     = new Color(200, 200, 210);
    private static final Color GROUP_HEAD_TOP   = new Color(240, 242, 247);
    private static final Color GROUP_HEAD_BOT   = new Color(225, 228, 235);
    private static final Color FIELD_BG         = new Color(255, 255, 255);
    private static final Color FIELD_BORDER     = new Color(190, 195, 205);
    private static final Color FIELD_LABEL      = new Color(60, 60, 70);
    private static final Color BUTTON_TOP       = new Color(240, 243, 250);
    private static final Color BUTTON_BOT       = new Color(218, 222, 235);
    private static final Color BUTTON_BORDER    = new Color(170, 175, 190);
    private static final Color BUTTON_TEXT      = new Color(40, 45, 55);
    private static final Color TABLE_HEADER     = new Color(230, 235, 245);
    private static final Color TABLE_BORDER     = new Color(195, 200, 210);
    private static final Color TITLE            = new Color(30, 35, 50);
    private static final Color SEPARATOR        = new Color(210, 215, 225);
    private static final Color HIGHLIGHT        = new Color(220, 50, 50);
    private static final Color PAGE_ACTIVE      = new Color(255, 255, 255);
    private static final Color PAGE_INACTIVE    = new Color(240, 242, 247);

    private static final Font FONT_TITLE   = new Font(Font.SANS_SERIF, Font.BOLD, 13);
    private static final Font FONT_GROUP   = new Font(Font.SANS_SERIF, Font.BOLD, 12);
    private static final Font FONT_LABEL   = new Font(Font.SANS_SERIF, Font.PLAIN, 11);
    private static final Font FONT_BUTTON  = new Font(Font.SANS_SERIF, Font.PLAIN, 11);

    /** Renders the form tree using the supplied geometry layout. */
    public BufferedImage render(FormElementNode root, Layout layout, Set<String> highlightSet) {
        if (highlightSet == null) {
            highlightSet = Set.of();
        }
        BufferedImage img = new BufferedImage(layout.canvasWidth(), layout.canvasHeight(),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            paintBackground(g, layout);
            paintNode(g, root, layout, highlightSet);
        } finally {
            g.dispose();
        }
        return img;
    }

    private void paintBackground(Graphics2D g, Layout layout) {
        g.setColor(BG);
        g.fillRect(0, 0, layout.canvasWidth(), layout.canvasHeight());
        // Subtle canvas plate inside the padding so the form has a visible "page".
        int pad = 6;
        g.setColor(CANVAS_BG);
        g.fillRect(pad, pad, layout.canvasWidth() - 2 * pad, layout.canvasHeight() - 2 * pad);
    }

    private void paintNode(Graphics2D g, FormElementNode node, Layout layout, Set<String> highlight) {
        if (node == null || !node.visible()) {
            return;
        }
        BoundingBox bbox = layout.bboxes().get(node.name());
        if (bbox != null) {
            switch (node.kind()) {
                case GROUP, PAGE -> paintGroup(g, node, bbox);
                case PAGES -> paintPages(g, node, bbox, layout);
                case FIELD -> paintField(g, node, bbox);
                case BUTTON -> paintButton(g, node, bbox);
                case TABLE -> paintTable(g, node, bbox, layout);
                case DECORATION -> paintDecoration(g, node, bbox);
                case COMMAND_BAR -> paintCommandBar(g, node, bbox, layout);
                case COLUMN -> { /* drawn as part of TABLE */ }
                case FORM -> { /* synthetic — nothing to paint, just recurse */ }
            }
            if (highlight.contains(node.name())) {
                paintHighlight(g, bbox);
            }
        }
        // For PAGES/PAGE we already recurse internally; for everything else,
        // recurse into children.
        if (node.kind() != FormElementNode.Kind.PAGES) {
            for (FormElementNode child : node.children()) {
                paintNode(g, child, layout, highlight);
            }
        }
    }

    private void paintGroup(Graphics2D g, FormElementNode node, BoundingBox bbox) {
        int headerHeight = Math.min(26, bbox.height());
        GradientPaint header = new GradientPaint(
                bbox.x(), bbox.y(), GROUP_HEAD_TOP,
                bbox.x(), bbox.y() + headerHeight, GROUP_HEAD_BOT);
        g.setPaint(header);
        g.fillRect(bbox.x(), bbox.y(), bbox.width(), headerHeight);

        g.setColor(GROUP_BORDER);
        g.setStroke(new BasicStroke(1f));
        g.drawRect(bbox.x(), bbox.y(), bbox.width(), bbox.height());
        g.drawLine(bbox.x(), bbox.y() + headerHeight, bbox.x() + bbox.width(), bbox.y() + headerHeight);

        g.setFont(FONT_GROUP);
        g.setColor(TITLE);
        String title = node.name() != null ? node.name() : "Group";
        drawClippedString(g, title, bbox.x() + 8, bbox.y() + 16, bbox.width() - 16);
    }

    private void paintPages(Graphics2D g, FormElementNode node, BoundingBox bbox, Layout layout) {
        // Tab strip occupies the top ~28 px of the bbox.
        int stripHeight = Math.min(28, bbox.height());
        g.setColor(GROUP_HEAD_TOP);
        g.fillRect(bbox.x(), bbox.y(), bbox.width(), stripHeight);
        g.setColor(GROUP_BORDER);
        g.drawRect(bbox.x(), bbox.y(), bbox.width(), bbox.height());
        g.drawLine(bbox.x(), bbox.y() + stripHeight, bbox.x() + bbox.width(), bbox.y() + stripHeight);

        // Tab labels — each visible PAGE child gets its name as a tab.
        g.setFont(FONT_GROUP);
        int tabX = bbox.x() + 8;
        boolean firstActive = true;
        for (FormElementNode page : node.children()) {
            if (!page.visible() || page.kind() != FormElementNode.Kind.PAGE) {
                continue;
            }
            String label = page.name() != null ? page.name() : "Page";
            FontMetrics fm = g.getFontMetrics();
            int labelWidth = fm.stringWidth(label) + 14;
            // Active tab (first one) gets a brighter background.
            g.setColor(firstActive ? PAGE_ACTIVE : PAGE_INACTIVE);
            g.fillRect(tabX, bbox.y() + 2, labelWidth, stripHeight - 2);
            g.setColor(GROUP_BORDER);
            g.drawRect(tabX, bbox.y() + 2, labelWidth, stripHeight - 2);
            g.setColor(TITLE);
            g.drawString(label, tabX + 7, bbox.y() + stripHeight - 9);
            tabX += labelWidth + 2;
            firstActive = false;
        }
        // Recurse into active page only — same selection rule the engine used.
        for (FormElementNode page : node.children()) {
            if (page.visible() && page.kind() == FormElementNode.Kind.PAGE) {
                for (FormElementNode child : page.children()) {
                    paintNode(g, child, layout, Set.of());
                }
                break;
            }
        }
    }

    private void paintField(Graphics2D g, FormElementNode node, BoundingBox bbox) {
        int labelWidth = Math.min(160, bbox.width() / 3);
        // Label on the left
        g.setFont(FONT_LABEL);
        g.setColor(FIELD_LABEL);
        String label = node.name() != null ? node.name() : "";
        drawClippedString(g, label, bbox.x() + 2, bbox.y() + bbox.height() / 2 + 4, labelWidth - 4);
        // Input rectangle on the right
        int inputX = bbox.x() + labelWidth + 4;
        int inputW = bbox.width() - labelWidth - 4;
        if (inputW > 0) {
            g.setColor(FIELD_BG);
            g.fillRect(inputX, bbox.y(), inputW, bbox.height());
            g.setColor(FIELD_BORDER);
            g.drawRect(inputX, bbox.y(), inputW, bbox.height());
        }
    }

    private void paintButton(Graphics2D g, FormElementNode node, BoundingBox bbox) {
        int width = Math.min(bbox.width(), 160);
        GradientPaint paint = new GradientPaint(
                bbox.x(), bbox.y(), BUTTON_TOP,
                bbox.x(), bbox.y() + bbox.height(), BUTTON_BOT);
        g.setPaint(paint);
        g.fillRoundRect(bbox.x(), bbox.y(), width, bbox.height(), 6, 6);
        g.setColor(BUTTON_BORDER);
        g.drawRoundRect(bbox.x(), bbox.y(), width, bbox.height(), 6, 6);
        g.setColor(BUTTON_TEXT);
        g.setFont(FONT_BUTTON);
        String text = node.name() != null ? node.name() : "Button";
        drawClippedString(g, text, bbox.x() + 12, bbox.y() + bbox.height() / 2 + 4, width - 18);
    }

    private void paintTable(Graphics2D g, FormElementNode node, BoundingBox bbox, Layout layout) {
        // Header row
        int headerHeight = 24;
        g.setColor(TABLE_HEADER);
        g.fillRect(bbox.x(), bbox.y(), bbox.width(), headerHeight);
        g.setColor(TABLE_BORDER);
        g.drawRect(bbox.x(), bbox.y(), bbox.width(), bbox.height());
        g.drawLine(bbox.x(), bbox.y() + headerHeight, bbox.x() + bbox.width(), bbox.y() + headerHeight);
        // Column labels
        g.setFont(FONT_GROUP);
        g.setColor(TITLE);
        for (FormElementNode col : node.children()) {
            if (!col.visible() || col.kind() != FormElementNode.Kind.COLUMN) continue;
            BoundingBox cbox = layout.bboxes().get(col.name());
            if (cbox == null) continue;
            drawClippedString(g, col.name() != null ? col.name() : "Col",
                    cbox.x() + 6, cbox.y() + 16, cbox.width() - 12);
            if (cbox.x() > bbox.x()) {
                g.setColor(TABLE_BORDER);
                g.drawLine(cbox.x(), bbox.y(), cbox.x(), bbox.y() + bbox.height());
                g.setColor(TITLE);
            }
        }
        // Row separators (3 schematic rows)
        int rowHeight = 24;
        g.setColor(TABLE_BORDER);
        for (int r = 1; r <= 3; r++) {
            int yLine = bbox.y() + headerHeight + r * rowHeight;
            if (yLine >= bbox.y() + bbox.height()) break;
            g.drawLine(bbox.x(), yLine, bbox.x() + bbox.width(), yLine);
        }
    }

    private void paintDecoration(Graphics2D g, FormElementNode node, BoundingBox bbox) {
        g.setFont(FONT_LABEL);
        g.setColor(FIELD_LABEL);
        String label = node.name() != null ? node.name() : "";
        drawClippedString(g, label, bbox.x() + 2, bbox.y() + bbox.height() / 2 + 4, bbox.width() - 4);
    }

    private void paintCommandBar(Graphics2D g, FormElementNode node, BoundingBox bbox, Layout layout) {
        g.setColor(SEPARATOR);
        g.drawLine(bbox.x(), bbox.y(), bbox.x() + bbox.width(), bbox.y());
        // Buttons inside command bar share the same row; we don't have
        // per-child bboxes from the engine, so paint a simple row of
        // button-shaped tokens based on child count.
        int childCount = (int) node.children().stream().filter(FormElementNode::visible).count();
        if (childCount == 0) {
            return;
        }
        int gap = 4;
        int btnWidth = Math.max(40, (bbox.width() - (childCount + 1) * gap) / childCount);
        int x = bbox.x() + gap;
        for (FormElementNode child : node.children()) {
            if (!child.visible()) continue;
            GradientPaint paint = new GradientPaint(
                    x, bbox.y() + 4, BUTTON_TOP,
                    x, bbox.y() + bbox.height() - 4, BUTTON_BOT);
            g.setPaint(paint);
            g.fillRoundRect(x, bbox.y() + 4, btnWidth, bbox.height() - 8, 5, 5);
            g.setColor(BUTTON_BORDER);
            g.drawRoundRect(x, bbox.y() + 4, btnWidth, bbox.height() - 8, 5, 5);
            g.setColor(BUTTON_TEXT);
            g.setFont(FONT_BUTTON);
            drawClippedString(g, child.name() != null ? child.name() : "Btn",
                    x + 6, bbox.y() + bbox.height() / 2 + 4, btnWidth - 12);
            x += btnWidth + gap;
        }
    }

    private void paintHighlight(Graphics2D g, BoundingBox bbox) {
        g.setColor(HIGHLIGHT);
        g.setStroke(new BasicStroke(2f));
        g.drawRect(bbox.x(), bbox.y(), bbox.width(), bbox.height());
        g.setStroke(new BasicStroke(1f));
    }

    private void drawClippedString(Graphics2D g, String text, int x, int y, int maxWidth) {
        if (text == null || text.isEmpty() || maxWidth <= 0) {
            return;
        }
        FontMetrics fm = g.getFontMetrics();
        String shown = text;
        if (fm.stringWidth(shown) > maxWidth) {
            int len = shown.length();
            while (len > 1 && fm.stringWidth(shown.substring(0, len) + "…") > maxWidth) {
                len--;
            }
            shown = shown.substring(0, len) + "…";
        }
        g.drawString(shown, x, y);
    }
}
