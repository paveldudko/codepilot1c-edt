/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edt.geometry;

import java.util.Map;

import com.codepilot1c.core.edt.forms.EdtFormService;
import com.codepilot1c.core.edt.forms.InspectFormLayoutRequest;
import com.codepilot1c.core.edt.forms.InspectFormLayoutResult;
import com.codepilot1c.core.edt.geometry.FormGeometryEngine.BoundingBox;
import com.codepilot1c.core.edt.geometry.FormGeometryEngine.Layout;

/**
 * Orchestrator behind {@code get_form_geometry}. Composes the existing
 * {@link EdtFormService#inspectFormLayout} EMF walk with the pure-Java
 * {@link FormElementTreeBuilder} and {@link FormGeometryEngine}, so the
 * agent can ask spatial questions about a form ("X is above Y") without
 * needing a PNG render or an open EDT designer.
 */
public class FormGeometryService {

    private static final int DEFAULT_CANVAS_WIDTH = 1240;

    private final EdtFormService edtFormService;
    private final FormElementTreeBuilder treeBuilder;
    private final FormGeometryEngine engine;

    public FormGeometryService() {
        this(new EdtFormService(), new FormElementTreeBuilder(), new FormGeometryEngine());
    }

    public FormGeometryService(EdtFormService edtFormService,
                               FormElementTreeBuilder treeBuilder,
                               FormGeometryEngine engine) {
        this.edtFormService = edtFormService;
        this.treeBuilder = treeBuilder;
        this.engine = engine;
    }

    /**
     * Computes geometry for a single form. The {@code canvasWidth}
     * controls the layout's horizontal extent (taller-or-shorter is
     * derived from content). Pass {@code 0} to use the default 1240 px.
     */
    public FormGeometryResult computeGeometry(String projectName, String formFqn, int canvasWidth) {
        InspectFormLayoutRequest req = new InspectFormLayoutRequest(
                projectName, formFqn, true, true, false, 0, 0);
        InspectFormLayoutResult inspected = edtFormService.inspectFormLayout(req);
        FormElementNode root = treeBuilder.buildRoot(inspected);
        int width = canvasWidth > 0 ? canvasWidth : DEFAULT_CANVAS_WIDTH;
        Layout layout = engine.compute(root, width);
        return new FormGeometryResult(
                projectName,
                formFqn,
                inspected.formName(),
                layout.canvasWidth(),
                layout.canvasHeight(),
                inspected.totalItems(),
                inspected.truncated(),
                root,
                layout.bboxes());
    }

    public record FormGeometryResult(
            String projectName,
            String formFqn,
            String formName,
            int canvasWidth,
            int canvasHeight,
            int totalItems,
            boolean truncated,
            FormElementNode root,
            Map<String, BoundingBox> bboxes) {
    }
}
