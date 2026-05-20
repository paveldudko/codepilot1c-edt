/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edt.geometry;

import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.Set;

import com.codepilot1c.core.edt.geometry.FormGeometryEngine.BoundingBox;
import com.codepilot1c.core.edt.geometry.FormGeometryService.FormGeometryResult;

/**
 * Orchestrator behind {@code get_form_rendering}. Composes the Phase 3
 * {@link FormGeometryService} with a headless AWT renderer + PNG-base64
 * encoder, so the agent receives a schematic image alongside the
 * structured bbox list. Strictly headless (no SWT/Display).
 *
 * <p>The diff-mode variant from the original feedback ({@code baseline:"HEAD"})
 * is deferred — it requires loading the baseline {@code .form} blob
 * through the EMF resource set, which lives behind a workspace-aware
 * service we don't yet wire here. The bbox-changed list IS already
 * derivable by the agent from two separate {@code get_form_rendering}
 * calls.</p>
 */
public class FormRenderingService {

    private final FormGeometryService geometryService;
    private final FormRendererAwt renderer;

    public FormRenderingService() {
        this(new FormGeometryService(), new FormRendererAwt());
    }

    public FormRenderingService(FormGeometryService geometryService, FormRendererAwt renderer) {
        this.geometryService = geometryService;
        this.renderer = renderer;
    }

    /**
     * Renders the form. {@code canvasWidth} of 0 uses the geometry service
     * default. {@code returnPng=false} skips PNG encoding for cheaper
     * pure-geometry queries (the agent can still consume the items[]).
     */
    public FormRenderingResult renderForm(String projectName,
                                          String formFqn,
                                          int canvasWidth,
                                          boolean returnPng,
                                          Set<String> highlightSet) {
        FormGeometryResult geometry = geometryService.computeGeometry(projectName, formFqn, canvasWidth);
        String pngBase64 = null;
        if (returnPng) {
            BufferedImage img = renderer.render(
                    geometry.root(),
                    new FormGeometryEngine.Layout(geometry.canvasWidth(), geometry.canvasHeight(), geometry.bboxes()),
                    highlightSet == null ? Set.of() : highlightSet);
            pngBase64 = PngBase64Encoder.encode(img);
        }
        return new FormRenderingResult(
                geometry.projectName(),
                geometry.formFqn(),
                geometry.formName(),
                geometry.canvasWidth(),
                geometry.canvasHeight(),
                geometry.totalItems(),
                geometry.truncated(),
                geometry.root(),
                geometry.bboxes(),
                pngBase64);
    }

    public record FormRenderingResult(
            String projectName,
            String formFqn,
            String formName,
            int canvasWidth,
            int canvasHeight,
            int totalItems,
            boolean truncated,
            FormElementNode root,
            Map<String, BoundingBox> bboxes,
            String pngBase64) {}
}
