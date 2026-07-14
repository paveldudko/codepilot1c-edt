/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.meta;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.Test;

import com.codepilot1c.core.tools.ITool;
import com.codepilot1c.core.tools.ToolExecutionContext;
import com.codepilot1c.core.tools.ToolRegistry;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.core.tools.surface.ToolSurfaceAugmentor;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import sun.misc.Unsafe;

/**
 * Verifies that {@code discover_tools} reflects per-endpoint tool gating: a
 * category tool the calling endpoint gates out must be reported under
 * {@code unavailable} with {@code available:false}, never listed as callable.
 *
 * <p>Backs feedback
 * {@code 2026-07-14-yaxunit-run-discover-tools-claims-available-but-uncallable.md}.</p>
 */
public class DiscoverToolsGatingTest {

    @Test
    public void gatedCategoryToolIsReportedUnavailableNotCallable() throws Exception {
        ToolRegistry registry = createIsolatedRegistry();
        registry.registerDynamicTool(new FakeQaTool("qa_exposed")); //$NON-NLS-1$
        registry.registerDynamicTool(new FakeQaTool("qa_gated")); //$NON-NLS-1$

        ToolRegistry previous = installSingleton(registry);
        // Endpoint exposes qa_exposed but gates out qa_gated (e.g. a profile that
        // enables one qa tool via enableTools while the qa group stays disabled).
        ToolExecutionContext.setEndpointToolVisibility(name -> !"qa_gated".equals(name)); //$NON-NLS-1$
        try {
            DiscoverToolsTool tool = new DiscoverToolsTool(registry);
            CompletableFuture<ToolResult> future = tool.execute(Map.of("category", "qa")); //$NON-NLS-1$ //$NON-NLS-2$
            ToolResult result = future.join();
            assertTrue("discover_tools should succeed", result.isSuccess()); //$NON-NLS-1$

            JsonObject json = new Gson().fromJson(result.getContentForLlm(), JsonObject.class);

            JsonArray tools = json.getAsJsonArray("tools"); //$NON-NLS-1$
            assertEquals("only the exposed qa tool is callable", 1, tools.size()); //$NON-NLS-1$
            JsonObject exposed = tools.get(0).getAsJsonObject();
            assertEquals("qa_exposed", exposed.get("name").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue("exposed tool marked available:true", //$NON-NLS-1$
                    exposed.get("available").getAsBoolean()); //$NON-NLS-1$

            JsonArray unavailable = json.getAsJsonArray("unavailable"); //$NON-NLS-1$
            assertNotNull("gated tool must be surfaced under 'unavailable'", unavailable); //$NON-NLS-1$
            assertEquals(1, unavailable.size());
            JsonObject gated = unavailable.get(0).getAsJsonObject();
            assertEquals("qa_gated", gated.get("name").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
            assertFalse("gated tool marked available:false", //$NON-NLS-1$
                    gated.get("available").getAsBoolean()); //$NON-NLS-1$
            assertNotNull("gated tool carries a reason", gated.get("reason")); //$NON-NLS-1$ //$NON-NLS-2$

            // The old unconditional note implied every category tool was callable —
            // it must no longer claim so.
            String note = json.get("note").getAsString(); //$NON-NLS-1$
            assertFalse("note must not claim unconditional availability", //$NON-NLS-1$
                    note.contains("now available")); //$NON-NLS-1$
        } finally {
            ToolExecutionContext.clearEndpointToolVisibility();
            installSingleton(previous);
        }
    }

    /** A minimal QA-category tool: {@code getSurfaceCategory()="qa"} pins the category. */
    private static final class FakeQaTool implements ITool {
        private final String name;

        FakeQaTool(String name) {
            this.name = name;
        }

        @Override public String getName() { return name; }
        @Override public String getDescription() { return "fake qa tool"; } //$NON-NLS-1$
        @Override public String getParameterSchema() { return "{\"type\":\"object\"}"; } //$NON-NLS-1$
        @Override public String getSurfaceCategory() { return "qa"; } //$NON-NLS-1$
        @Override public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
            return CompletableFuture.completedFuture(ToolResult.success("ok")); //$NON-NLS-1$
        }
    }

    private static ToolRegistry createIsolatedRegistry() throws Exception {
        ToolRegistry registry = (ToolRegistry) unsafe().allocateInstance(ToolRegistry.class);
        setRegistryField(registry, "tools", new HashMap<String, ITool>()); //$NON-NLS-1$
        setRegistryField(registry, "dynamicTools", new ConcurrentHashMap<String, ITool>()); //$NON-NLS-1$
        setRegistryField(registry, "gson", new Gson()); //$NON-NLS-1$
        setRegistryField(registry, "augmentor", ToolSurfaceAugmentor.defaultAugmentor()); //$NON-NLS-1$
        return registry;
    }

    private static ToolRegistry installSingleton(ToolRegistry registry) throws Exception {
        Field field = ToolRegistry.class.getDeclaredField("instance"); //$NON-NLS-1$
        field.setAccessible(true);
        ToolRegistry previous = (ToolRegistry) field.get(null);
        field.set(null, registry);
        return previous;
    }

    private static void setRegistryField(ToolRegistry registry, String name, Object value) throws Exception {
        Field field = ToolRegistry.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(registry, value);
    }

    private static Unsafe unsafe() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe"); //$NON-NLS-1$
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }
}
