/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edt.geometry;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

/**
 * Source-grep contract test for the Phase 3 {@code get_form_geometry}
 * orchestration. Behaviour of the engine + tree builder is covered by
 * {@link FormGeometryEngineTest} and {@link FormElementTreeBuilderTest};
 * this test asserts only that the service composes the existing
 * {@code EdtFormService.inspectFormLayout} EMF walk and that the tool
 * is registered.
 */
public class FormGeometryWiringContractTest {

    private static final String SERVICE_PATH =
            "bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/geometry/FormGeometryService.java"; //$NON-NLS-1$
    private static final String TOOL_PATH =
            "bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/forms/GetFormGeometryTool.java"; //$NON-NLS-1$
    private static final String REGISTRY_PATH =
            "bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/ToolRegistry.java"; //$NON-NLS-1$

    @Test
    public void serviceDelegatesEmfWalkToInspectFormLayout() throws Exception {
        String src = read(SERVICE_PATH);
        assertTrue("must reuse EdtFormService.inspectFormLayout for the EMF walk", //$NON-NLS-1$
                src.contains("edtFormService.inspectFormLayout(")); //$NON-NLS-1$
    }

    @Test
    public void serviceUsesTreeBuilderAndEngine() throws Exception {
        String src = read(SERVICE_PATH);
        assertTrue("must delegate to FormElementTreeBuilder.buildRoot", //$NON-NLS-1$
                src.contains("treeBuilder.buildRoot(")); //$NON-NLS-1$
        assertTrue("must delegate to FormGeometryEngine.compute", //$NON-NLS-1$
                src.contains("engine.compute(")); //$NON-NLS-1$
    }

    @Test
    public void serviceExposesConstructorInjectionSeam() throws Exception {
        String src = read(SERVICE_PATH);
        // The test seam: a constructor that takes EdtFormService +
        // FormElementTreeBuilder + FormGeometryEngine so tests can wire
        // mocked services without touching production paths.
        assertTrue("must expose 3-arg constructor for test injection", //$NON-NLS-1$
                src.contains("public FormGeometryService(EdtFormService")); //$NON-NLS-1$
    }

    @Test
    public void toolNameIsGetFormGeometry() throws Exception {
        String src = read(TOOL_PATH);
        assertTrue("tool meta name must be get_form_geometry", //$NON-NLS-1$
                src.contains("name = \"get_form_geometry\"")); //$NON-NLS-1$
    }

    @Test
    public void toolSchemaAdvertisesCanvasWidthAndHighlight() throws Exception {
        String src = read(TOOL_PATH);
        assertTrue("schema must declare canvas_width", //$NON-NLS-1$
                src.contains("\"canvas_width\"")); //$NON-NLS-1$
        assertTrue("schema must declare highlight array", //$NON-NLS-1$
                src.contains("\"highlight\"")); //$NON-NLS-1$
    }

    @Test
    public void toolClampsCanvasWidth() throws Exception {
        String src = read(TOOL_PATH);
        // Defensive: a runaway agent shouldn't be able to request a
        // 1_000_000-pixel-wide canvas. The clamp is part of the tool
        // contract.
        assertTrue("tool must clamp canvas_width to a sane range", //$NON-NLS-1$
                src.contains("clampCanvasWidth(")); //$NON-NLS-1$
        assertTrue("tool must enforce a maximum canvas width", //$NON-NLS-1$
                src.contains("MAX_CANVAS_WIDTH")); //$NON-NLS-1$
    }

    @Test
    public void toolDelegatesToFormGeometryService() throws Exception {
        String src = read(TOOL_PATH);
        assertTrue("tool must call service.computeGeometry", //$NON-NLS-1$
                src.contains("service.computeGeometry(")); //$NON-NLS-1$
    }

    @Test
    public void toolEmitsBboxAsFourElementArray() throws Exception {
        String src = read(TOOL_PATH);
        // Bbox shape [x, y, w, h] is part of the API contract — pin it
        // so a future refactor can't silently switch to {x, y, w, h}
        // object form.
        assertTrue("bbox must be a 4-element array of x/y/w/h", //$NON-NLS-1$
                src.contains("bboxArray.add(bbox.x())") //$NON-NLS-1$
                        && src.contains("bboxArray.add(bbox.y())") //$NON-NLS-1$
                        && src.contains("bboxArray.add(bbox.width())") //$NON-NLS-1$
                        && src.contains("bboxArray.add(bbox.height())")); //$NON-NLS-1$
    }

    @Test
    public void toolEchoesHighlightFlag() throws Exception {
        String src = read(TOOL_PATH);
        assertTrue("items in the highlight set must be marked", //$NON-NLS-1$
                src.contains("entry.addProperty(\"highlighted\", true)")); //$NON-NLS-1$
    }

    @Test
    public void toolFormRootIsNotEmittedAsItem() throws Exception {
        String src = read(TOOL_PATH);
        // The synthetic FORM root carries no useful bbox (it equals the
        // canvas). Pinning that we skip it avoids cluttering items[]
        // with the meta-root.
        assertTrue("synthetic FORM root must be excluded from items[]", //$NON-NLS-1$
                src.contains("Kind.FORM")); //$NON-NLS-1$
    }

    @Test
    public void toolRegistryWiresGetFormGeometryTool() throws Exception {
        String src = read(REGISTRY_PATH);
        assertTrue("ToolRegistry must instantiate GetFormGeometryTool", //$NON-NLS-1$
                src.contains("new GetFormGeometryTool()")); //$NON-NLS-1$
    }

    // --- Helpers --------------------------------------------------------------

    private String read(String relativePath) throws Exception {
        return Files.readString(findRepoRoot().resolve(relativePath), StandardCharsets.UTF_8);
    }

    private Path findRepoRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize(); //$NON-NLS-1$
        while (current != null) {
            if (Files.isDirectory(current.resolve("bundles")) //$NON-NLS-1$
                    && Files.isRegularFile(current.resolve("pom.xml")) //$NON-NLS-1$
                    && Files.isRegularFile(current.resolve("LICENSE"))) { //$NON-NLS-1$
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Cannot locate repository root"); //$NON-NLS-1$
    }
}
