/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edt.context;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

/**
 * Source-grep contract test that pins the composition of the
 * {@code bsl_object_context} orchestrator and tool.
 *
 * <p>The behavior end of {@code BslObjectContextService} touches
 * EDT runtime services that need an OSGi/EMF environment; this contract
 * test asserts only that the wiring exists — each underlying service is
 * referenced, each include flag has a code path, the tool is registered.
 * Behavioral correctness of the underlying services is already covered
 * by their own tests, and FQN parsing + the include-flag surface by the
 * pure-Java unit tests {@link ObjectFqnResolverTest} and
 * {@link BslObjectContextRequestTest}.</p>
 */
public class BslObjectContextWiringContractTest {

    private static final String SERVICE_PATH =
            "bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/context/BslObjectContextService.java"; //$NON-NLS-1$
    private static final String TOOL_PATH =
            "bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/bsl/BslObjectContextTool.java"; //$NON-NLS-1$
    private static final String REGISTRY_PATH =
            "bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/ToolRegistry.java"; //$NON-NLS-1$

    // --- Service composition --------------------------------------------------

    @Test
    public void serviceComposesBslSemanticService() throws Exception {
        String src = read(SERVICE_PATH);
        assertTrue("must invoke BslSemanticService.getModuleContext for the methods section", //$NON-NLS-1$
                src.contains("bslSemanticService.getModuleContext(")); //$NON-NLS-1$
        assertTrue("must invoke BslSemanticService.getModuleExports for the methods section", //$NON-NLS-1$
                src.contains("bslSemanticService.getModuleExports(")); //$NON-NLS-1$
    }

    @Test
    public void serviceComposesEdtAstServiceForMetadataDetails() throws Exception {
        String src = read(SERVICE_PATH);
        assertTrue("must invoke EdtAstService.getMetadataDetails for attributes / tabular sections", //$NON-NLS-1$
                src.contains("edtAstService.getMetadataDetails(")); //$NON-NLS-1$
    }

    @Test
    public void serviceComposesEdtFormServiceForFormLayout() throws Exception {
        String src = read(SERVICE_PATH);
        assertTrue("must invoke EdtFormService.inspectFormLayout for form_layout section", //$NON-NLS-1$
                src.contains("edtFormService.inspectFormLayout(")); //$NON-NLS-1$
    }

    @Test
    public void serviceComposesEdtReferenceServiceForCallers() throws Exception {
        String src = read(SERVICE_PATH);
        assertTrue("must invoke EdtReferenceService.findReferences for callers section", //$NON-NLS-1$
                src.contains("edtReferenceService.findReferences(")); //$NON-NLS-1$
    }

    @Test
    public void serviceUsesObjectFqnResolver() throws Exception {
        String src = read(SERVICE_PATH);
        assertTrue("must delegate FQN parsing to ObjectFqnResolver (covered by ObjectFqnResolverTest)", //$NON-NLS-1$
                src.contains("fqnResolver.parse(")); //$NON-NLS-1$
        assertTrue("must delegate module-path resolution to the resolver", //$NON-NLS-1$
                src.contains("fqnResolver.standardModulePaths(")); //$NON-NLS-1$
        assertTrue("must delegate form-module-path build to the resolver", //$NON-NLS-1$
                src.contains("fqnResolver.formModulePath(")); //$NON-NLS-1$
    }

    @Test
    public void serviceEmitsPartialResultsOnSectionFailure() throws Exception {
        String src = read(SERVICE_PATH);
        // The best-effort contract: an EdtAstException inside a per-section
        // delegate must be captured into the errors[] array, not propagate.
        assertTrue("must catch EdtAstException per section", //$NON-NLS-1$
                src.contains("} catch (EdtAstException")); //$NON-NLS-1$
        assertTrue("must emit errors[] accumulation", //$NON-NLS-1$
                src.contains("errors.add(errorEntry(")); //$NON-NLS-1$
    }

    @Test
    public void serviceGatesModuleSectionsByIncludeFlags() throws Exception {
        String src = read(SERVICE_PATH);
        assertTrue("manager_module_methods include flag must gate ManagerModule", //$NON-NLS-1$
                src.contains("req.managerModuleMethods()")); //$NON-NLS-1$
        assertTrue("object_module_methods include flag must gate Object/RecordSet/ValueManager modules", //$NON-NLS-1$
                src.contains("req.objectModuleMethods()")); //$NON-NLS-1$
    }

    // --- Tool wiring ---------------------------------------------------------

    @Test
    public void toolNameIsBslObjectContext() throws Exception {
        String src = read(TOOL_PATH);
        assertTrue("tool meta name must be bsl_object_context", //$NON-NLS-1$
                src.contains("name = \"bsl_object_context\"")); //$NON-NLS-1$
    }

    @Test
    public void toolDelegatesToService() throws Exception {
        String src = read(TOOL_PATH);
        assertTrue("tool must build Request via fromParameters", //$NON-NLS-1$
                src.contains("BslObjectContextRequest.fromParameters(")); //$NON-NLS-1$
        assertTrue("tool must call service.loadContext", //$NON-NLS-1$
                src.contains("service.loadContext(")); //$NON-NLS-1$
    }

    @Test
    public void toolSchemaAdvertisesIncludeBlock() throws Exception {
        String src = read(TOOL_PATH);
        assertTrue("schema must declare include nested object", //$NON-NLS-1$
                src.contains("\"include\"")); //$NON-NLS-1$
        assertTrue("schema must declare methods enum", //$NON-NLS-1$
                src.contains("\"methods\"")); //$NON-NLS-1$
        assertTrue("schema must declare attributes flag", //$NON-NLS-1$
                src.contains("\"attributes\"")); //$NON-NLS-1$
        assertTrue("schema must declare tabular_sections flag", //$NON-NLS-1$
                src.contains("\"tabular_sections\"")); //$NON-NLS-1$
        assertTrue("schema must declare form_layout enum", //$NON-NLS-1$
                src.contains("\"form_layout\"")); //$NON-NLS-1$
        assertTrue("schema must declare callers flag", //$NON-NLS-1$
                src.contains("\"callers\"")); //$NON-NLS-1$
    }

    @Test
    public void toolMapsIllegalArgumentToInvalidArgumentError() throws Exception {
        String src = read(TOOL_PATH);
        // Request.fromParameters throws IllegalArgumentException on bad enum
        // values / missing required params. The tool surface must convert
        // that into a structured tool-error.
        assertTrue("tool must catch IllegalArgumentException from request parsing", //$NON-NLS-1$
                src.contains("} catch (IllegalArgumentException")); //$NON-NLS-1$
        assertTrue("tool must emit INVALID_ARGUMENT error code", //$NON-NLS-1$
                src.contains("INVALID_ARGUMENT")); //$NON-NLS-1$
    }

    @Test
    public void toolRegistryWiresBslObjectContextTool() throws Exception {
        String src = read(REGISTRY_PATH);
        assertTrue("ToolRegistry must instantiate BslObjectContextTool", //$NON-NLS-1$
                src.contains("new BslObjectContextTool()")); //$NON-NLS-1$
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
