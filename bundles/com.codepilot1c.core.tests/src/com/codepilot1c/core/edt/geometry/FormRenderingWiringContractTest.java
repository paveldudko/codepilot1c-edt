/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edt.geometry;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

/**
 * Source-grep contract for the Phase 4 {@code get_form_rendering}
 * orchestration. Behaviour of the renderer + encoder is covered by
 * {@link FormRendererAwtTest} and {@link PngBase64EncoderTest}; this
 * test asserts only that the wiring is in place and the tool is
 * registered.
 */
public class FormRenderingWiringContractTest {

    private static final String SERVICE_PATH =
            "bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/geometry/FormRenderingService.java"; //$NON-NLS-1$
    private static final String TOOL_PATH =
            "bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/forms/GetFormRenderingTool.java"; //$NON-NLS-1$
    private static final String REGISTRY_PATH =
            "bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/ToolRegistry.java"; //$NON-NLS-1$

    @Test
    public void serviceComposesGeometryServicePlusRenderer() throws Exception {
        String src = read(SERVICE_PATH);
        assertTrue("must reuse FormGeometryService.computeGeometry", //$NON-NLS-1$
                src.contains("geometryService.computeGeometry(")); //$NON-NLS-1$
        assertTrue("must call FormRendererAwt.render", //$NON-NLS-1$
                src.contains("renderer.render(")); //$NON-NLS-1$
        assertTrue("must encode via PngBase64Encoder", //$NON-NLS-1$
                src.contains("PngBase64Encoder.encode(")); //$NON-NLS-1$
    }

    @Test
    public void serviceSupportsReturnPngFalseToSkipEncoding() throws Exception {
        String src = read(SERVICE_PATH);
        // Skip-PNG branch is a cost optimization in the contract: agents
        // that already have the geometry (Phase 3) don't pay for re-render.
        assertTrue("must support returnPng=false skip path", //$NON-NLS-1$
                src.contains("if (returnPng)")); //$NON-NLS-1$
    }

    @Test
    public void toolNameIsGetFormRendering() throws Exception {
        String src = read(TOOL_PATH);
        assertTrue(src.contains("name = \"get_form_rendering\"")); //$NON-NLS-1$
    }

    @Test
    public void toolSchemaAdvertisesReturnPngAndHighlight() throws Exception {
        String src = read(TOOL_PATH);
        assertTrue("schema must declare return_png", //$NON-NLS-1$
                src.contains("\"return_png\"")); //$NON-NLS-1$
        assertTrue("schema must declare highlight", //$NON-NLS-1$
                src.contains("\"highlight\"")); //$NON-NLS-1$
        assertTrue("schema must declare canvas_width", //$NON-NLS-1$
                src.contains("\"canvas_width\"")); //$NON-NLS-1$
    }

    @Test
    public void toolClampsCanvasWidth() throws Exception {
        String src = read(TOOL_PATH);
        assertTrue("tool must clamp canvas_width", //$NON-NLS-1$
                src.contains("clampCanvasWidth(")); //$NON-NLS-1$
        assertTrue("tool must enforce MAX_CANVAS_WIDTH", //$NON-NLS-1$
                src.contains("MAX_CANVAS_WIDTH")); //$NON-NLS-1$
    }

    @Test
    public void toolDelegatesToFormRenderingService() throws Exception {
        String src = read(TOOL_PATH);
        assertTrue("tool must call service.renderForm", //$NON-NLS-1$
                src.contains("service.renderForm(")); //$NON-NLS-1$
    }

    @Test
    public void toolEmitsPngBase64WhenPresent() throws Exception {
        String src = read(TOOL_PATH);
        // PNG-base64 inclusion is conditional on a non-null result —
        // pin that we emit both the data field and a mime-type hint
        // so consumers can identify the format unambiguously.
        assertTrue("must emit png_base64 field", //$NON-NLS-1$
                src.contains("\"png_base64\"")); //$NON-NLS-1$
        assertTrue("must emit png_format/mime hint", //$NON-NLS-1$
                src.contains("image/png")); //$NON-NLS-1$
    }

    @Test
    public void toolEmitsBboxAsFourElementArray() throws Exception {
        String src = read(TOOL_PATH);
        assertTrue(src.contains("bboxArray.add(bbox.x())") //$NON-NLS-1$
                && src.contains("bboxArray.add(bbox.y())") //$NON-NLS-1$
                && src.contains("bboxArray.add(bbox.width())") //$NON-NLS-1$
                && src.contains("bboxArray.add(bbox.height())")); //$NON-NLS-1$
    }

    @Test
    public void toolRegistryWiresGetFormRenderingTool() throws Exception {
        String src = read(REGISTRY_PATH);
        assertTrue("ToolRegistry must instantiate GetFormRenderingTool", //$NON-NLS-1$
                src.contains("new GetFormRenderingTool()")); //$NON-NLS-1$
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
