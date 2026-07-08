/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.workspace;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.tools.AbstractTool;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolResult;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

/**
 * Polls the status of a background {@code edt_update_infobase} job
 * started via {@code async=true}.
 */
@ToolMeta(
        name = "update_infobase_status",
        category = "diagnostics",
        surfaceCategory = "smoke_runtime_recovery",
        mutating = false,
        tags = {"workspace", "edt"})
public class UpdateInfobaseStatusTool extends AbstractTool {

    private static final VibeLogger.CategoryLogger LOG =
            VibeLogger.forClass(UpdateInfobaseStatusTool.class);

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "job_id": {
                  "type": "string",
                  "description": "Job id returned by edt_update_infobase when async=true"
                },
                "wait_for_completion": {
                  "type": "boolean",
                  "description": "Block server-side until the job reaches a terminal state (or timeout_seconds elapses), then return the final result — instead of returning the current state immediately. PREFER this over a client-side poll loop: it makes one call and never times out the transport (the host allows this tool up to 660s). Default false returns the last-known state immediately (cheap status read)."
                },
                "timeout_seconds": {
                  "type": "integer",
                  "description": "Max seconds to wait when wait_for_completion=true (default 120, clamped to [1, 600]). Set it to match the expected op duration (e.g. 600 for a full config update) so the call returns the final result in one shot; on expiry the still-running state is returned with timed_out=true (re-call to keep waiting)."
                }
              },
              "required": ["job_id"]
            }
            """; //$NON-NLS-1$

    private static final long POLL_INTERVAL_MS = 500L;
    private static final int DEFAULT_TIMEOUT_SECONDS = 120;
    private static final int MAX_TIMEOUT_SECONDS = 600;

    private final BackgroundJobRegistry registry;

    public UpdateInfobaseStatusTool() {
        this(BackgroundJobRegistry.getInstance());
    }

    UpdateInfobaseStatusTool(BackgroundJobRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String getDescription() {
        return "Polls the status of a background job by job_id: state, start/finish time, result, or error. " //$NON-NLS-1$
                + "Generic poller — works with any job from async mode (update_infobase, connect_infobase, qa_run). " //$NON-NLS-1$
                + "wait_for_completion=true blocks until terminal (no client-side polling)."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    public boolean requiresConfirmation() {
        return false;
    }

    @Override
    public boolean isDestructive() {
        return false;
    }

    @Override
    protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        Map<String, Object> parameters = params.getRaw();
        String jobId = asString(parameters == null ? null : parameters.get("job_id")); //$NON-NLS-1$
        if (jobId == null || jobId.isEmpty()) {
            return CompletableFuture.completedFuture(
                    ToolResult.failure("job_id is required")); //$NON-NLS-1$
        }
        boolean waitForCompletion = asBoolean(parameters == null ? null : parameters.get("wait_for_completion")); //$NON-NLS-1$
        if (waitForCompletion) {
            int timeoutSeconds = clampTimeout(asInt(parameters == null ? null : parameters.get("timeout_seconds"))); //$NON-NLS-1$
            return CompletableFuture.supplyAsync(() -> waitAndRender(jobId, timeoutSeconds));
        }
        return CompletableFuture.completedFuture(renderLookup(jobId));
    }

    /** Polls the registry until the job is terminal or the timeout elapses, then renders. */
    private ToolResult waitAndRender(String jobId, int timeoutSeconds) {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(timeoutSeconds);
        long startedAt = System.nanoTime();
        while (true) {
            BackgroundJobRegistry.JobLookup lookup = registry.lookupJob(jobId);
            boolean present = lookup.getKind() == BackgroundJobRegistry.JobLookupKind.PRESENT;
            boolean terminal = present && isTerminal(lookup.getStatus().getState());
            if (!present || terminal || System.nanoTime() >= deadline) {
                ToolResult result = renderLookup(jobId);
                if (present && !terminal) {
                    // Re-render with a timed_out marker so the caller knows the wait expired.
                    JsonObject payload = render(registry.lookupJob(jobId).getStatus());
                    payload.addProperty("timed_out", true); //$NON-NLS-1$
                    payload.addProperty("waited_ms", //$NON-NLS-1$
                            (System.nanoTime() - startedAt) / 1_000_000L);
                    return ToolResult.success(pretty(payload), ToolResult.ToolResultType.CODE);
                }
                return result;
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return renderLookup(jobId);
            }
        }
    }

    private static boolean isTerminal(BackgroundJobRegistry.JobState state) {
        return state == BackgroundJobRegistry.JobState.DONE
                || state == BackgroundJobRegistry.JobState.FAILED;
    }

    private ToolResult renderLookup(String jobId) {
        BackgroundJobRegistry.JobLookup lookup = registry.lookupJob(jobId);
        switch (lookup.getKind()) {
            case PRESENT: {
                JsonObject payload = render(lookup.getStatus());
                return ToolResult.success(pretty(payload), ToolResult.ToolResultType.CODE);
            }
            case EXPIRED: {
                LOG.warn("update_infobase_status: expired job id %s", jobId); //$NON-NLS-1$
                JsonObject payload = new JsonObject();
                payload.addProperty("job_id", jobId); //$NON-NLS-1$
                payload.addProperty("error", "job_expired"); //$NON-NLS-1$ //$NON-NLS-2$
                if (lookup.getExpiredAt() != null) {
                    payload.addProperty("expired_at", lookup.getExpiredAt().toString()); //$NON-NLS-1$
                }
                payload.addProperty("message", //$NON-NLS-1$
                        "Job result retention window has elapsed"); //$NON-NLS-1$
                return ToolResult.failure(pretty(payload));
            }
            case UNKNOWN:
            default: {
                LOG.warn("update_infobase_status: unknown job id %s", jobId); //$NON-NLS-1$
                return ToolResult.failure("Unknown job: " + jobId); //$NON-NLS-1$
            }
        }
    }

    private static JsonObject render(BackgroundJobRegistry.JobStatus status) {
        JsonObject out = new JsonObject();
        out.addProperty("job_id", status.getJobId()); //$NON-NLS-1$
        out.addProperty("kind", status.getKind()); //$NON-NLS-1$
        out.addProperty("state", status.getState().name()); //$NON-NLS-1$
        addInstant(out, "submittedAt", status.getSubmittedAt()); //$NON-NLS-1$
        addInstant(out, "startedAt", status.getStartedAt()); //$NON-NLS-1$
        addInstant(out, "finishedAt", status.getFinishedAt()); //$NON-NLS-1$
        if (status.getState() == BackgroundJobRegistry.JobState.DONE && status.getResult() != null) {
            out.addProperty("result", status.getResult()); //$NON-NLS-1$
        }
        if (status.getState() == BackgroundJobRegistry.JobState.FAILED && status.getError() != null) {
            out.addProperty("error", status.getError()); //$NON-NLS-1$
        }
        return out;
    }

    private static void addInstant(JsonObject out, String key, Instant instant) {
        if (instant != null) {
            out.addProperty(key, instant.toString());
        }
    }

    private static String pretty(JsonObject object) {
        return new GsonBuilder().setPrettyPrinting().create().toJson(object);
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private static boolean asBoolean(Object value) {
        if (value instanceof Boolean b) {
            return b.booleanValue();
        }
        return value != null && "true".equalsIgnoreCase(String.valueOf(value).trim()); //$NON-NLS-1$
    }

    private static int asInt(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int clampTimeout(int requested) {
        if (requested <= 0) {
            return DEFAULT_TIMEOUT_SECONDS;
        }
        return Math.min(requested, MAX_TIMEOUT_SECONDS);
    }
}
