/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools;

import java.util.function.Predicate;

/**
 * Request-scoped context for a single tool invocation, carried via a
 * {@link ThreadLocal}. The MCP host router sets it on the handler thread right
 * before {@code tool.execute(...)} (which runs synchronously on that thread) and
 * clears it afterwards, so a tool can answer strictly for the <em>calling
 * endpoint</em> rather than a global view.
 *
 * <p>Currently used by {@code discover_tools}: each MCP endpoint (profile) has its
 * own exposure policy, so {@code discover_tools} must reveal only the tools that
 * the calling port would actually let through — otherwise it would advertise tools
 * that a later {@code tools/call} rejects as "not exposed". When no context is set
 * (e.g. the in-process agent, not the MCP host), tools fall back to their global
 * view.</p>
 */
public final class ToolExecutionContext {

    private static final ThreadLocal<Predicate<String>> ENDPOINT_TOOL_VISIBILITY = new ThreadLocal<>();

    private ToolExecutionContext() {
    }

    /**
     * Set the calling endpoint's tool-visibility test ({@code toolName -> exposed?}).
     * Must be paired with {@link #clearEndpointToolVisibility()} in a finally block.
     */
    public static void setEndpointToolVisibility(Predicate<String> isExposed) {
        ENDPOINT_TOOL_VISIBILITY.set(isExposed);
    }

    /** The calling endpoint's visibility test, or {@code null} when none is set. */
    public static Predicate<String> endpointToolVisibility() {
        return ENDPOINT_TOOL_VISIBILITY.get();
    }

    /** Clear the request-scoped visibility test. */
    public static void clearEndpointToolVisibility() {
        ENDPOINT_TOOL_VISIBILITY.remove();
    }
}
