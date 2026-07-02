/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.workspace;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.runtime.EdtToolErrorCode;
import com.codepilot1c.core.edt.runtime.lease.InfobaseLease;
import com.codepilot1c.core.edt.runtime.lease.InfobaseLeaseGuard;
import com.codepilot1c.core.edt.runtime.lease.InfobaseLeaseStore;
import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.tools.AbstractTool;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolResult;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Explicit lease operations for multi-EDT stack pools: status / take / release. The same store
 * is consulted implicitly by {@code connect_infobase} and {@code update_infobase} (a free lease
 * is auto-taken there); this tool covers the rest of the protocol — inspecting the pool, handing
 * a task over (release on the source stack), and stealing a stale lease ({@code take force=true}).
 */
@ToolMeta(
        name = "manage_leases",
        category = "workspace",
        mutating = true,
        tags = {"workspace", "edt"})
public class ManageLeasesTool extends AbstractTool {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(ManageLeasesTool.class);

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "action": {
                  "type": "string",
                  "enum": ["status", "take", "release"],
                  "description": "status: list leases (all, or one when branch is passed). take: claim a branch for THIS stack; with force=true steal it from a stale holder. release: drop this stack's lease; with force=true drop another stack's."
                },
                "branch": {
                  "type": "string",
                  "description": "Git branch (short name, e.g. 'task-C') identifying the task. Required for take/release."
                },
                "ib_path": {
                  "type": "string",
                  "description": "take: path of the branch's file infobase, recorded in the lease so same-IB conflicts are detected across branches. Optional but recommended."
                },
                "force": {
                  "type": "boolean",
                  "description": "take: steal the lease from its current holder (use only for stale holders). release: drop a lease held by another stack (default: false)."
                }
              },
              "required": ["action"]
            }
            """; //$NON-NLS-1$

    private final InfobaseLeaseGuard guard;

    public ManageLeasesTool() {
        this(InfobaseLeaseGuard.fromEnvironment());
    }

    public ManageLeasesTool(InfobaseLeaseGuard guard) {
        this.guard = guard;
    }

    @Override
    public String getDescription() {
        return "Exclusivity leases for per-branch infobases in a multi-EDT stack pool " //$NON-NLS-1$
                + "(status/take/release). connect_infobase and update_infobase auto-take a free " //$NON-NLS-1$
                + "lease; use this tool to inspect the pool, hand a task over (release), or steal " //$NON-NLS-1$
                + "a stale lease (take force=true). Active only when the CODEPILOT1C_LEASE_DIR " //$NON-NLS-1$
                + "environment variable points at the pool's shared lease directory."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    public boolean requiresConfirmation() {
        return false; // pure pool coordination; never touches EDT or infobase state
    }

    @Override
    public boolean isDestructive() {
        return false;
    }

    @Override
    protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        String opId = LogSanitizer.newId("lease"); //$NON-NLS-1$
        java.util.Map<String, Object> raw = params.getRaw() == null ? java.util.Map.of() : params.getRaw();
        String action = asString(raw.get("action")); //$NON-NLS-1$
        String branch = asString(raw.get("branch")); //$NON-NLS-1$
        String ibPath = asString(raw.get("ib_path")); //$NON-NLS-1$
        boolean force = Boolean.TRUE.equals(raw.get("force")) //$NON-NLS-1$
                || "true".equalsIgnoreCase(asString(raw.get("force"))); //$NON-NLS-1$ //$NON-NLS-2$

        try {
            if (guard == null || !guard.isEnabled()) {
                return failure(disabledPayload(opId));
            }
            if (action == null) {
                return failure(errorPayload(opId, EdtToolErrorCode.INVALID_ARGUMENT,
                        "action is required: status | take | release")); //$NON-NLS-1$
            }
            return switch (action) {
                case "status" -> success(status(opId, branch)); //$NON-NLS-1$
                case "take" -> take(opId, branch, ibPath, force); //$NON-NLS-1$
                case "release" -> release(opId, branch, force); //$NON-NLS-1$
                default -> failure(errorPayload(opId, EdtToolErrorCode.INVALID_ARGUMENT,
                        "unknown action '" + action + "': expected status | take | release")); //$NON-NLS-1$ //$NON-NLS-2$
            };
        } catch (Exception e) {
            LOG.error(String.format("[%s] manage_leases action=%s failed", opId, action), e); //$NON-NLS-1$
            return failure(errorPayload(opId, EdtToolErrorCode.EDT_SERVICE_UNAVAILABLE,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    private JsonObject status(String opId, String branch) {
        InfobaseLeaseStore store = guard.store();
        JsonObject payload = basePayload(opId, true);
        JsonArray leases = new JsonArray();
        if (branch != null) {
            store.find(branch).ifPresent(l -> leases.add(leaseJson(l)));
        } else {
            for (InfobaseLease lease : store.list()) {
                leases.add(leaseJson(lease));
            }
        }
        payload.add("leases", leases); //$NON-NLS-1$
        return payload;
    }

    private CompletableFuture<ToolResult> take(String opId, String branch, String ibPath, boolean force) {
        if (branch == null) {
            return failure(errorPayload(opId, EdtToolErrorCode.INVALID_ARGUMENT,
                    "branch is required for action=take")); //$NON-NLS-1$
        }
        InfobaseLeaseStore store = guard.store();
        InfobaseLease lease = guard.newLease(branch, ibPath, ibPath == null ? null
                : "File=\"" + ibPath + "\";", opId); //$NON-NLS-1$ //$NON-NLS-2$
        InfobaseLeaseStore.TakeResult result = force ? store.forceTake(lease) : store.take(lease);
        if (result.taken()) {
            JsonObject payload = basePayload(opId, true);
            payload.addProperty("taken", true); //$NON-NLS-1$
            payload.addProperty("branch", branch); //$NON-NLS-1$
            if (force) {
                payload.addProperty("forced", true); //$NON-NLS-1$
                if (result.lease() != null && !result.lease().isHeldBy(guard.stackId())) {
                    payload.add("previous_holder", leaseJson(result.lease())); //$NON-NLS-1$
                    LOG.warn("[%s] lease STOLEN: branch=%s previous holder: %s", //$NON-NLS-1$
                            opId, branch, result.lease().describeHolder());
                }
            }
            return success(payload);
        }
        InfobaseLease holder = result.lease();
        if (holder != null && holder.isHeldBy(guard.stackId())) {
            JsonObject payload = basePayload(opId, true);
            payload.addProperty("taken", true); //$NON-NLS-1$
            payload.addProperty("branch", branch); //$NON-NLS-1$
            payload.addProperty("already_own", true); //$NON-NLS-1$
            return success(payload);
        }
        JsonObject payload = errorPayload(opId, EdtToolErrorCode.EDT_LEASE_HELD,
                "lease for branch '" + branch + "' is held by " //$NON-NLS-1$ //$NON-NLS-2$
                        + (holder == null ? "another stack" : holder.describeHolder())); //$NON-NLS-1$
        if (holder != null) {
            payload.add("holder", leaseJson(holder)); //$NON-NLS-1$
        }
        payload.addProperty("hint", //$NON-NLS-1$
                "release it on the holding stack, or pass force=true here to steal a stale lease"); //$NON-NLS-1$
        return failure(payload);
    }

    private CompletableFuture<ToolResult> release(String opId, String branch, boolean force) {
        if (branch == null) {
            return failure(errorPayload(opId, EdtToolErrorCode.INVALID_ARGUMENT,
                    "branch is required for action=release")); //$NON-NLS-1$
        }
        InfobaseLeaseStore.ReleaseResult result = guard.store().release(branch, guard.stackId(), force);
        switch (result.status()) {
            case RELEASED -> {
                JsonObject payload = basePayload(opId, true);
                payload.addProperty("released", true); //$NON-NLS-1$
                payload.addProperty("branch", branch); //$NON-NLS-1$
                if (result.lease() != null && !result.lease().isHeldBy(guard.stackId())) {
                    payload.add("previous_holder", leaseJson(result.lease())); //$NON-NLS-1$
                }
                return success(payload);
            }
            case NOT_HELD -> {
                JsonObject payload = basePayload(opId, true);
                payload.addProperty("released", false); //$NON-NLS-1$
                payload.addProperty("branch", branch); //$NON-NLS-1$
                payload.addProperty("note", "no lease was held for this branch"); //$NON-NLS-1$ //$NON-NLS-2$
                return success(payload);
            }
            default -> {
                JsonObject payload = errorPayload(opId, EdtToolErrorCode.EDT_LEASE_HELD,
                        "lease for branch '" + branch + "' is held by " //$NON-NLS-1$ //$NON-NLS-2$
                                + result.lease().describeHolder()
                                + " — not released"); //$NON-NLS-1$
                payload.add("holder", leaseJson(result.lease())); //$NON-NLS-1$
                payload.addProperty("hint", "pass force=true to drop another stack's (stale) lease"); //$NON-NLS-1$ //$NON-NLS-2$
                return failure(payload);
            }
        }
    }

    private JsonObject basePayload(String opId, boolean success) {
        JsonObject payload = new JsonObject();
        payload.addProperty("op_id", opId); //$NON-NLS-1$
        payload.addProperty("success", Boolean.valueOf(success)); //$NON-NLS-1$
        payload.addProperty("lease_dir", guard.store() == null ? null //$NON-NLS-1$
                : guard.store().directory().toString());
        payload.addProperty("stack_id", guard.stackId()); //$NON-NLS-1$
        return payload;
    }

    private JsonObject disabledPayload(String opId) {
        JsonObject payload = new JsonObject();
        payload.addProperty("op_id", opId); //$NON-NLS-1$
        payload.addProperty("success", false); //$NON-NLS-1$
        payload.addProperty("error", "lease_disabled"); //$NON-NLS-1$ //$NON-NLS-2$
        payload.addProperty("message", //$NON-NLS-1$
                "Lease coordination is not configured on this EDT instance."); //$NON-NLS-1$
        payload.addProperty("hint", //$NON-NLS-1$
                "Set the " + InfobaseLeaseGuard.ENV_LEASE_DIR + " environment variable (and optionally " //$NON-NLS-1$ //$NON-NLS-2$
                        + InfobaseLeaseGuard.ENV_STACK_ID + ") in the EDT start script to the pool's " //$NON-NLS-1$
                        + "shared lease directory. Single-instance setups do not need leases."); //$NON-NLS-1$
        return payload;
    }

    private JsonObject errorPayload(String opId, EdtToolErrorCode code, String message) {
        JsonObject payload = new JsonObject();
        payload.addProperty("op_id", opId); //$NON-NLS-1$
        payload.addProperty("success", false); //$NON-NLS-1$
        payload.addProperty("error_code", code.name()); //$NON-NLS-1$
        payload.addProperty("message", message == null ? "" : message); //$NON-NLS-1$ //$NON-NLS-2$
        return payload;
    }

    private JsonObject leaseJson(InfobaseLease lease) {
        JsonObject json = new JsonObject();
        json.addProperty("branch", lease.branch()); //$NON-NLS-1$
        if (lease.ibPath() != null) {
            json.addProperty("ib_path", lease.ibPath()); //$NON-NLS-1$
        }
        JsonObject holder = new JsonObject();
        holder.addProperty("stack_id", lease.stackId()); //$NON-NLS-1$
        if (lease.workspace() != null) {
            holder.addProperty("workspace", lease.workspace()); //$NON-NLS-1$
        }
        if (lease.host() != null) {
            holder.addProperty("host", lease.host()); //$NON-NLS-1$
        }
        if (lease.pid() > 0) {
            holder.addProperty("pid", Long.valueOf(lease.pid())); //$NON-NLS-1$
        }
        json.add("holder", holder); //$NON-NLS-1$
        if (lease.acquiredAt() != null) {
            json.addProperty("acquired_at", lease.acquiredAt()); //$NON-NLS-1$
            Long age = ageSeconds(lease.acquiredAt());
            if (age != null) {
                json.addProperty("age_seconds", age); //$NON-NLS-1$
            }
        }
        json.addProperty("own", Boolean.valueOf(lease.isHeldBy(guard.stackId()))); //$NON-NLS-1$
        return json;
    }

    private static Long ageSeconds(String acquiredAt) {
        try {
            return Long.valueOf(Duration.between(Instant.parse(acquiredAt), Instant.now()).getSeconds());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static CompletableFuture<ToolResult> success(JsonObject payload) {
        return CompletableFuture.completedFuture(
                ToolResult.success(pretty(payload), ToolResult.ToolResultType.CODE));
    }

    private static CompletableFuture<ToolResult> failure(JsonObject payload) {
        return CompletableFuture.completedFuture(ToolResult.failure(pretty(payload)));
    }

    private static String pretty(JsonObject object) {
        return new GsonBuilder().setPrettyPrinting().create().toJson(object);
    }

    private static String asString(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }
}
