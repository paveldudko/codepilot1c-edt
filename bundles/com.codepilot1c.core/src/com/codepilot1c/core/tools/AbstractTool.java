/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.ILogListener;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.osgi.framework.Bundle;

/**
 * Base class for tool implementations.
 *
 * <p>Provides:</p>
 * <ul>
 *   <li>Automatic metadata from {@link ToolMeta} annotation</li>
 *   <li>{@link ToolParameters} wrapper for typed parameter access</li>
 *   <li>CompletableFuture wrapping with exception handling</li>
 *   <li>Logging via Eclipse ILog</li>
 * </ul>
 *
 * <p>Subclasses implement {@link #doExecute(ToolParameters)} instead of
 * {@link #execute(Map)}.</p>
 */
public abstract class AbstractTool implements ITool {

    private static final String PLUGIN_ID = "com.codepilot1c.core"; //$NON-NLS-1$

    /**
     * Tools reached only by dispatch, never called directly, so an "unknown parameter" advisory on
     * them would report the dispatcher's own routing rather than a caller mistake.
     *
     * <p>{@code edt_diagnostics}, {@code qa_inspect} and {@code qa_generate} forward the whole
     * argument map to the chosen delegate — {@code command} included, and {@code edt_diagnostics}
     * additionally injects BOTH {@code project} and {@code project_name} via
     * {@code EdtDiagnosticsCommandContract.applyProjectFieldAliases}. The delegates below therefore
     * always see keys their own schemas do not declare. (The three dispatchers themselves need no
     * entry: their schemas state {@code additionalProperties: true}, which already turns the guard
     * off.)</p>
     */
    private static final Set<String> ADVISORY_EXEMPT_TOOLS = Set.of(
            "edt_metadata_smoke", "edt_trace_export", "analyze_tool_error", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "edt_update_infobase", "edt_launch_app", //$NON-NLS-1$ //$NON-NLS-2$
            "qa_explain_config", "qa_status", "qa_steps_search", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "qa_init_config", "qa_migrate_config", "qa_compile_feature"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    private static final ILog NOOP_LOG = new ILog() {
        @Override
        public void addLogListener(ILogListener listener) {
            // No-op in headless/plain JUnit mode.
        }

        @Override
        public Bundle getBundle() {
            return null;
        }

        @Override
        public void log(IStatus status) {
            // No-op in headless/plain JUnit mode.
        }

        @Override
        public void removeLogListener(ILogListener listener) {
            // No-op in headless/plain JUnit mode.
        }
    };

    protected final ILog log;

    private final String name;
    private final String category;
    private final String surfaceCategory;
    private final boolean mutating;
    private final boolean requiresValidationToken;
    private final Set<String> tags;

    protected AbstractTool() {
        this.log = createLogger();
        ToolMeta meta = getClass().getAnnotation(ToolMeta.class);
        if (meta != null) {
            this.name = meta.name();
            this.category = meta.category();
            this.surfaceCategory = meta.surfaceCategory();
            this.mutating = meta.mutating();
            this.requiresValidationToken = meta.requiresValidationToken();
            this.tags = Set.of(meta.tags());
        } else {
            // Fallback for subclasses without annotation
            this.name = inferName();
            this.category = "general"; //$NON-NLS-1$
            this.surfaceCategory = ""; //$NON-NLS-1$
            this.mutating = false;
            this.requiresValidationToken = false;
            this.tags = Collections.emptySet();
        }
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getCategory() {
        return category;
    }

    @Override
    public String getSurfaceCategory() {
        return surfaceCategory;
    }

    @Override
    public boolean isMutating() {
        return mutating;
    }

    @Override
    public boolean requiresValidationToken() {
        return requiresValidationToken;
    }

    @Override
    public Set<String> getTags() {
        return tags;
    }

    @Override
    public boolean requiresConfirmation() {
        return mutating;
    }

    @Override
    public boolean isDestructive() {
        return mutating;
    }

    @Override
    public final CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
        try {
            // Run before interceptors
            Map<String, Object> effectiveArgs = parameters;
            for (IToolInterceptor interceptor : ToolInterceptorRegistry.getInstance().getInterceptors()) {
                Optional<Map<String, Object>> modified = interceptor.beforeToolCall(name, effectiveArgs);
                if (modified.isEmpty()) {
                    return CompletableFuture.completedFuture(
                            ToolResult.failure("Tool call cancelled by interceptor")); //$NON-NLS-1$
                }
                effectiveArgs = modified.get();
            }

            long startTime = System.currentTimeMillis();
            ToolParameters params = new ToolParameters(effectiveArgs);
            return doExecute(params).thenApply(result -> {
                // Run after interceptors
                long duration = System.currentTimeMillis() - startTime;
                ToolResult effective = result;
                for (IToolInterceptor interceptor : ToolInterceptorRegistry.getInstance().getInterceptors()) {
                    effective = interceptor.afterToolCall(name, effective, duration);
                }
                return withUnknownParameterAdvisory(effective, parameters);
            });
        } catch (ToolParameters.ToolParameterException e) {
            return CompletableFuture.completedFuture(withUnknownParameterAdvisory(
                    ToolResult.failure(String.format("Parameter error in %s: %s", name, e.getMessage())), //$NON-NLS-1$
                    parameters));
        } catch (Exception e) {
            log.log(new Status(IStatus.ERROR, "com.codepilot1c.core", //$NON-NLS-1$
                    String.format("Tool %s failed: %s", name, e.getMessage()), e)); //$NON-NLS-1$
            return CompletableFuture.completedFuture(withUnknownParameterAdvisory(
                    ToolResult.failure(String.format("Internal error in %s: %s", name, e.getMessage())), //$NON-NLS-1$
                    parameters));
        }
    }

    /**
     * Appends an honest advisory when the caller passed a top-level parameter this tool's schema does
     * not declare, so a mistyped or misplaced key stops being a silent no-op.
     *
     * <p>This is the read-only half of the silent-drop class that {@code edt_validate_request} closes
     * for mutations: {@code scan_metadata_index} answered a {@code kinds=…} call with everything,
     * unfiltered, and {@code get_diagnostics} answered a {@code project=…} call about the default
     * project — both without a word. Advisory ONLY: it never fails a call and never changes
     * behaviour, so a tool whose schema under-declares a pass-through key loses nothing but the
     * accuracy of this note.</p>
     */
    private ToolResult withUnknownParameterAdvisory(ToolResult result, Map<String, Object> parameters) {
        try {
            if (result == null || parameters == null || parameters.isEmpty()
                    || ADVISORY_EXEMPT_TOOLS.contains(name)) {
                return result;
            }
            SchemaKeyGuard.Report report = SchemaKeyGuard.inspect(getParameterSchema(), parameters.keySet());
            if (report.isClean()) {
                return result;
            }
            String advisory = SchemaKeyGuard.advisoryLine(name, report.unknownKeys(),
                    SchemaKeyGuard.forDisplay(report.acceptedKeys(), null));
            if (!result.isSuccess()) {
                String error = result.getErrorMessage() == null ? "" : result.getErrorMessage(); //$NON-NLS-1$
                return ToolResult.failure(error + advisory);
            }
            String content = result.getContent() == null ? "" : result.getContent(); //$NON-NLS-1$
            return result.getStructuredData() != null
                    ? ToolResult.success(content + advisory, result.getType(), result.getStructuredData())
                    : ToolResult.success(content + advisory, result.getType());
        } catch (RuntimeException e) {
            // An advisory must never be the reason a call fails.
            return result;
        }
    }

    /**
     * Executes the tool with typed parameters.
     *
     * @param params typed parameter wrapper
     * @return a future containing the tool result
     */
    protected abstract CompletableFuture<ToolResult> doExecute(ToolParameters params);

    private ILog createLogger() {
        try {
            Bundle bundle = Platform.getBundle(PLUGIN_ID);
            if (bundle != null) {
                return Platform.getLog(bundle);
            }
        } catch (RuntimeException ignored) {
            // Fall through to a no-op logger in plain JUnit/headless mode.
        }
        return NOOP_LOG;
    }

    private String inferName() {
        String className = getClass().getSimpleName();
        // Convert "ReadFileTool" → "read_file"
        if (className.endsWith("Tool")) { //$NON-NLS-1$
            className = className.substring(0, className.length() - 4);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < className.length(); i++) {
            char c = className.charAt(i);
            if (Character.isUpperCase(c) && i > 0) {
                sb.append('_');
            }
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }
}
