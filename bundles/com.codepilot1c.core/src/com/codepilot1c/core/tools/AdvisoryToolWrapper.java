/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Gives a tool that does not extend {@link AbstractTool} the same unknown-parameter advisory, by
 * wrapping it at registration time.
 *
 * <p>Coverage by inheritance left a hole exactly where it hurt most: {@code get_diagnostics} and
 * {@code get_diagnostics_details} implement {@link ITool} directly, so a caller who passed
 * {@code project} (the accepted spelling is {@code project_name}) got a complete, confident answer
 * about a different project and no note — the case {@link ToolAdvisory}'s javadoc cites as the
 * reason the advisory exists. Wrapping in {@link ToolRegistry} makes the note a property of being
 * registered, so a tool cannot lose it by choosing a different base class.</p>
 *
 * <p>Every {@link ITool} method delegates, including the ones with interface defaults: the delegate
 * may override any of them, and a wrapper that answered the default instead would silently change
 * confirmation, mutation and tag behaviour.</p>
 */
public final class AdvisoryToolWrapper implements ITool {

    private final ITool delegate;

    private AdvisoryToolWrapper(ITool delegate) {
        this.delegate = delegate;
    }

    /**
     * Wraps {@code tool} unless it already carries the advisory.
     *
     * <p>An {@link AbstractTool} applies the note inside its own {@code execute}, and a wrapper is
     * idempotent, so both are returned unchanged — double-wrapping would append the same note
     * twice.</p>
     *
     * @param tool the tool about to be registered; {@code null} is passed through
     * @return the tool, wrapped when it would otherwise drop keys silently
     */
    public static ITool wrapIfNeeded(ITool tool) {
        if (tool == null || tool instanceof AbstractTool || tool instanceof AdvisoryToolWrapper) {
            return tool;
        }
        return new AdvisoryToolWrapper(tool);
    }

    /** The wrapped tool, so callers that need the concrete type can still reach it. */
    public ITool delegate() {
        return delegate;
    }

    @Override
    public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
        String name = delegate.getName();
        String schema = delegate.getParameterSchema();
        try {
            return delegate.execute(parameters)
                    .thenApply(result -> ToolAdvisory.annotate(name, schema, result, parameters));
        } catch (RuntimeException e) {
            // A tool that throws instead of returning a failed future must still keep its advisory.
            return CompletableFuture.completedFuture(ToolAdvisory.annotate(
                    name, schema,
                    ToolResult.failure(String.format("Internal error in %s: %s", name, e.getMessage())), //$NON-NLS-1$
                    parameters));
        }
    }

    @Override
    public String getName() {
        return delegate.getName();
    }

    @Override
    public String getDescription() {
        return delegate.getDescription();
    }

    @Override
    public String getParameterSchema() {
        return delegate.getParameterSchema();
    }

    @Override
    public boolean requiresConfirmation() {
        return delegate.requiresConfirmation();
    }

    @Override
    public boolean isDestructive() {
        return delegate.isDestructive();
    }

    @Override
    public String getCategory() {
        return delegate.getCategory();
    }

    @Override
    public String getSurfaceCategory() {
        return delegate.getSurfaceCategory();
    }

    @Override
    public boolean isMutating() {
        return delegate.isMutating();
    }

    @Override
    public boolean requiresValidationToken() {
        return delegate.requiresValidationToken();
    }

    @Override
    public Set<String> getTags() {
        return delegate.getTags();
    }
}
