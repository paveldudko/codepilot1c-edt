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
                  "description": "Block server-side until the job reaches a terminal state (or timeout_seconds elapses), then return the final result — instead of returning the current state immediately. PREFER this over a client-side poll loop: it makes one call and never times out the transport (the host allows this tool up to 1860s, above the 1800s update_infobase itself permits). Default false returns the last-known state immediately (cheap status read)."
                },
                "timeout_seconds": {
                  "type": "integer",
                  "description": "Max seconds to wait when wait_for_completion=true (default 120, clamped to [1, 1800] — the same ceiling update_infobase allows for timeout_s, so one blocking call can cover the longest update the tool permits). Set it to match the expected op duration (e.g. 1200 for the first full-schema update of a multi-GB file infobase) so the call returns the final result in one shot; on expiry the still-running state is returned with timed_out=true, which means KEEP WAITING (re-call), not failed."
                }
              },
              "required": ["job_id"]
            }
            """; //$NON-NLS-1$

    private static final long POLL_INTERVAL_MS = 500L;
    private static final int DEFAULT_TIMEOUT_SECONDS = 120;
    /**
     * Matches the {@code timeout_s} ceiling of {@code update_infobase} itself. It used to be 600, below
     * even the 1200s a real first full-schema update needs, so a blocking wait returned before the job
     * it was observing and a caller that read that as the verdict reported a FALSE {@code failed} on a
     * healthy update (feedback 2026-07-29 §4). A poller must be able to outwait what it polls.
     */
    private static final int MAX_TIMEOUT_SECONDS = 1800;

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
                    BackgroundJobRegistry.JobStatus current = registry.lookupJob(jobId).getStatus();
                    JsonObject payload = render(current);
                    payload.addProperty("timed_out", true); //$NON-NLS-1$
                    payload.addProperty("waited_ms", //$NON-NLS-1$
                            (System.nanoTime() - startedAt) / 1_000_000L);
                    // Say which one expired. A caller that reads an expired WAIT as the job's verdict
                    // reports a false "failed" on a healthy long update — the same symptom as the
                    // client-side abort timeout_s was added to fix, so it gets misdiagnosed as that
                    // (feedback 2026-07-29 §4).
                    payload.addProperty("message", //$NON-NLS-1$
                            "The WAIT expired, not the job — it is still " + current.getState() //$NON-NLS-1$
                                    + ". This is not a verdict: re-call to keep waiting, or poll with" //$NON-NLS-1$
                                    + " wait_for_completion=false. Raise timeout_seconds (max 1800) to" //$NON-NLS-1$
                                    + " cover the update's own timeout_s in a single call."); //$NON-NLS-1$
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
                // "sync"/"starting" are update_infobase in-flight-guard sentinels, not registry jobs.
                // A caller that (mistakenly, or from a stale hint) polls one gets a targeted explanation
                // instead of the bare "Unknown job" (feedback 2026-07-16-update-infobase-sync-job-id-unpollable).
                if ("sync".equals(jobId) || "starting".equals(jobId)) { //$NON-NLS-1$ //$NON-NLS-2$
                    LOG.warn("update_infobase_status: sentinel job id %s is not pollable", jobId); //$NON-NLS-1$
                    JsonObject payload = new JsonObject();
                    payload.addProperty("job_id", jobId); //$NON-NLS-1$
                    payload.addProperty("error", "not_pollable_sentinel"); //$NON-NLS-1$ //$NON-NLS-2$
                    payload.addProperty("message", //$NON-NLS-1$
                            "\"" + jobId + "\" is not a job id — it is an update_infobase in-flight sentinel " //$NON-NLS-1$ //$NON-NLS-2$
                                    + ("sync".equals(jobId) //$NON-NLS-1$
                                            ? "for a SYNCHRONOUS update, which has no pollable job. Wait for the " //$NON-NLS-1$
                                                    + "blocking update_infobase call to return; for a pollable job, " //$NON-NLS-1$
                                                    + "call update_infobase with async=true." //$NON-NLS-1$
                                            : "for an async update still being registered. Retry in a moment to " //$NON-NLS-1$
                                                    + "get the real job_id.")); //$NON-NLS-1$
                    return ToolResult.failure(pretty(payload));
                }
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
