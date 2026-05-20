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

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Set;

import org.junit.Test;

import com.codepilot1c.core.edt.geometry.FormElementNode.Kind;
import com.codepilot1c.core.edt.geometry.FormGeometryEngine.BoundingBox;
import com.codepilot1c.core.edt.geometry.FormGeometryEngine.Layout;

/**
 * Tests for the pure-Java AWT form renderer. Verifies the renderer
 * produces a {@link BufferedImage} of the expected dimensions and that
 * non-background pixels appear inside the bbox region of each item, so
 * the agent receives a visually-meaningful schematic view.
 *
 * <p>Pixel-perfect comparison is intentionally avoided — the renderer's
 * value is structural (Pages widget visible, group borders present,
 * highlighted items outlined), not artistic. Tests assert presence and
 * region-coverage, not exact pixel values.</p>
 */
public class FormRendererAwtTest {

    private static final int CANVAS = 800;

    private final FormGeometryEngine engine = new FormGeometryEngine();
    private final FormRendererAwt renderer = new FormRendererAwt();

    // --- output shape --------------------------------------------------------

    @Test
    public void rendererProducesImageOfLayoutDimensions() {
        FormElementNode root = form(field("Name"));
        Layout layout = engine.compute(root, CANVAS);
        BufferedImage img = renderer.render(root, layout, Set.of());
        assertNotNull(img);
        assertEquals(layout.canvasWidth(), img.getWidth());
        assertEquals(layout.canvasHeight(), img.getHeight());
    }

    @Test
    public void rendererRespectsRequestedCanvasWidth() {
        FormElementNode root = form(field("F"));
        Layout layout = engine.compute(root, 1200);
        BufferedImage img = renderer.render(root, layout, Set.of());
        assertEquals(1200, img.getWidth());
    }

    // --- visual content coverage ---------------------------------------------

    @Test
    public void rendererDrawsNonBackgroundPixelsInsideEveryVisibleItemBbox() {
        FormElementNode root = form(
                group("Personal", List.of(field("FirstName"), field("LastName"))),
                button("Save"));
        Layout layout = engine.compute(root, CANVAS);
        BufferedImage img = renderer.render(root, layout, Set.of());

        // Every named bbox must contain at least one pixel that's not the
        // canvas background — proves the renderer actually drew the item.
        for (var entry : layout.bboxes().entrySet()) {
            BoundingBox bbox = entry.getValue();
            assertTrue("bbox for '" + entry.getKey() + "' must contain drawn content",
                    bboxContainsNonBackgroundPixel(img, bbox));
        }
    }

    @Test
    public void emptyFormStillProducesValidImage() {
        FormElementNode root = form(); // no children
        Layout layout = engine.compute(root, CANVAS);
        BufferedImage img = renderer.render(root, layout, Set.of());
        assertNotNull(img);
        assertEquals(CANVAS, img.getWidth());
        assertTrue("canvas height must be at least 2*padding for an empty form",
                img.getHeight() >= 24);
    }

    // --- highlight overlay ---------------------------------------------------

    @Test
    public void highlightedItemsHaveDistinctOutlinePixels() {
        FormElementNode root = form(field("Normal"), field("Special"));
        Layout layout = engine.compute(root, CANVAS);
        BufferedImage plain = renderer.render(root, layout, Set.of());
        BufferedImage highlighted = renderer.render(root, layout, Set.of("Special"));

        BoundingBox specialBbox = layout.bboxes().get("Special");
        BoundingBox normalBbox = layout.bboxes().get("Normal");

        // Pixels around the perimeter of the highlighted item must differ
        // between the plain and highlighted renderings.
        int differencesOnSpecial = countPerimeterDifferences(plain, highlighted, specialBbox);
        int differencesOnNormal = countPerimeterDifferences(plain, highlighted, normalBbox);

        assertTrue("highlight overlay must draw on the highlighted item's perimeter",
                differencesOnSpecial > 0);
        // Non-highlighted siblings should look identical between the two
        // renderings (some overdraw on outermost pixel is acceptable; allow tiny slack).
        assertTrue("highlight overlay must not bleed onto non-highlighted siblings",
                differencesOnNormal == 0);
    }

    // --- horizontal layout sanity -------------------------------------------

    @Test
    public void horizontalGroupSiblingsRenderSideBySideOnSameRow() {
        FormElementNode root = form(horizontalGroup("Row", List.of(field("L"), field("R"))));
        Layout layout = engine.compute(root, CANVAS);
        BufferedImage img = renderer.render(root, layout, Set.of());
        BoundingBox l = layout.bboxes().get("L");
        BoundingBox r = layout.bboxes().get("R");
        assertEquals("horizontal siblings share y", l.y(), r.y());
        // Both visible
        assertTrue(bboxContainsNonBackgroundPixel(img, l));
        assertTrue(bboxContainsNonBackgroundPixel(img, r));
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

    private static FormElementNode field(String name) {
        return new FormElementNode(name, Kind.FIELD, true, true, false, null, List.of());
    }

    private static FormElementNode button(String name) {
        return new FormElementNode(name, Kind.BUTTON, true, true, false, null, List.of());
    }

    /** Background is whatever the renderer paints in the corners outside any bbox. */
    private boolean bboxContainsNonBackgroundPixel(BufferedImage img, BoundingBox bbox) {
        int background = img.getRGB(0, 0); // top-left corner is always canvas background
        int x0 = Math.max(0, bbox.x());
        int y0 = Math.max(0, bbox.y());
        int x1 = Math.min(img.getWidth() - 1, bbox.x() + bbox.width() - 1);
        int y1 = Math.min(img.getHeight() - 1, bbox.y() + bbox.height() - 1);
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                if (img.getRGB(x, y) != background) {
                    return true;
                }
            }
        }
        return false;
    }

    private int countPerimeterDifferences(BufferedImage a, BufferedImage b, BoundingBox bbox) {
        if (bbox == null) return 0;
        int diffs = 0;
        int x0 = Math.max(0, bbox.x());
        int y0 = Math.max(0, bbox.y());
        int x1 = Math.min(Math.min(a.getWidth(), b.getWidth()) - 1, bbox.x() + bbox.width() - 1);
        int y1 = Math.min(Math.min(a.getHeight(), b.getHeight()) - 1, bbox.y() + bbox.height() - 1);
        // Top + bottom edges
        for (int x = x0; x <= x1; x++) {
            if (a.getRGB(x, y0) != b.getRGB(x, y0)) diffs++;
            if (a.getRGB(x, y1) != b.getRGB(x, y1)) diffs++;
        }
        // Left + right edges (avoid double-counting corners)
        for (int y = y0 + 1; y < y1; y++) {
            if (a.getRGB(x0, y) != b.getRGB(x0, y)) diffs++;
            if (a.getRGB(x1, y) != b.getRGB(x1, y)) diffs++;
        }
        return diffs;
    }
}
