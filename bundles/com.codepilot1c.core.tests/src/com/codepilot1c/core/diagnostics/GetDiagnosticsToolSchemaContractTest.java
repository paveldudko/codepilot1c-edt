/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Schema contract for the UI-bundle {@code get_diagnostics} tool.
 *
 * <p>{@code EdtDiagnosticsToolSchemaTest} covers the CORE dispatcher
 * ({@code edt_diagnostics}) only; the live UI tool
 * {@code com.codepilot1c.ui.tools.GetDiagnosticsTool} is unreachable from this
 * headless bundle, so its schema is parsed straight out of the source text
 * block. Same stake as the dispatcher test: MCP clients strip arguments that the
 * advertised schema does not declare, so an undeclared {@code origin} would make
 * the provenance filter unreachable from the tool surface.</p>
 */
public class GetDiagnosticsToolSchemaContractTest {

    private static final String TOOL_PATH =
            "bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/tools/GetDiagnosticsTool.java"; //$NON-NLS-1$

    @Test
    public void schemaParsesAndDeclaresOrigin() throws Exception {
        JsonObject props = properties();
        assertTrue("get_diagnostics schema must declare the 'origin' param", //$NON-NLS-1$
                props.has("origin")); //$NON-NLS-1$
        assertEquals("origin must be typed string", "string", //$NON-NLS-1$ //$NON-NLS-2$
                props.getAsJsonObject("origin").get("type").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        // Pre-existing params must survive the addition.
        assertTrue(props.has("severity")); //$NON-NLS-1$
        assertTrue(props.has("scope")); //$NON-NLS-1$
    }

    @Test
    public void originEnumOffersTheDefaultAndTheOptOut() throws Exception {
        List<String> values = new ArrayList<>();
        JsonArray enumValues = properties().getAsJsonObject("origin").getAsJsonArray("enum"); //$NON-NLS-1$ //$NON-NLS-2$
        for (JsonElement element : enumValues) {
            values.add(element.getAsString());
        }
        assertTrue("enum must offer the default filter '" + DiagnosticOrigin.FILTER_DIAGNOSTICS + "'", //$NON-NLS-1$ //$NON-NLS-2$
                values.contains(DiagnosticOrigin.FILTER_DIAGNOSTICS));
        assertTrue("enum must offer '" + DiagnosticOrigin.FILTER_ALL //$NON-NLS-1$
                + "' so callers can opt back into review annotations", //$NON-NLS-1$
                values.contains(DiagnosticOrigin.FILTER_ALL));
        for (String origin : new String[] {
                DiagnosticOrigin.COMPILER, DiagnosticOrigin.ANALYZER,
                DiagnosticOrigin.CUSTOM_CHECK, DiagnosticOrigin.REVIEW_ANNOTATION,
                DiagnosticOrigin.UNKNOWN}) {
            assertTrue("enum must offer origin '" + origin + "'", values.contains(origin)); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    @Test
    public void toolPassesTheOriginParamIntoTheQuery() throws Exception {
        String src = read(TOOL_PATH);
        assertTrue("tool must read the 'origin' parameter", //$NON-NLS-1$
                src.contains("\"origin\"")); //$NON-NLS-1$
        assertTrue("tool must default the filter via DiagnosticOrigin.defaultFilter()", //$NON-NLS-1$
                src.contains("DiagnosticOrigin.defaultFilter()")); //$NON-NLS-1$
        assertTrue("tool must forward the filter into DiagnosticsQuery", //$NON-NLS-1$
                src.contains("originFilter)")); //$NON-NLS-1$
    }

    // --- helpers --------------------------------------------------------------

    private JsonObject properties() throws Exception {
        JsonObject schema = JsonParser.parseString(extractSchema(read(TOOL_PATH))).getAsJsonObject();
        return schema.getAsJsonObject("properties"); //$NON-NLS-1$
    }

    /** Pulls the JSON out of the {@code SCHEMA} text block. */
    private static String extractSchema(String src) {
        int marker = src.indexOf("SCHEMA = \"\"\""); //$NON-NLS-1$
        assertTrue("GetDiagnosticsTool must keep its SCHEMA text block", marker >= 0); //$NON-NLS-1$
        int contentStart = src.indexOf('\n', marker) + 1;
        int end = src.indexOf("\"\"\"", contentStart); //$NON-NLS-1$
        assertTrue("unterminated SCHEMA text block", end > contentStart); //$NON-NLS-1$
        return src.substring(contentStart, end);
    }

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
