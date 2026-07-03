/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edt.runtime.lease;

import com.google.gson.JsonObject;

/**
 * One exclusivity lease: a task (git branch + its per-branch infobase) claimed by one EDT stack
 * of a multi-stack pool. Serialized as a small JSON file in the shared lease directory (see
 * {@link InfobaseLeaseStore}); the FILE's existence is the claim, this object is its payload.
 */
public final class InfobaseLease {

    public static final int CURRENT_VERSION = 1;

    /** Holder marker used when a lease file exists but its payload is unreadable (mid-write race, corruption). */
    public static final String UNKNOWN_HOLDER = "<unknown>"; //$NON-NLS-1$

    private final String branch;
    private final String ibPath;
    private final String ibIdentity;
    private final String stackId;
    private final String workspace;
    private final String host;
    private final long pid;
    private final String acquiredAt;
    private final String acquiredByOp;

    public InfobaseLease(String branch, String ibPath, String ibIdentity, String stackId,
            String workspace, String host, long pid, String acquiredAt, String acquiredByOp) {
        this.branch = branch;
        this.ibPath = ibPath;
        this.ibIdentity = ibIdentity;
        this.stackId = stackId;
        this.workspace = workspace;
        this.host = host;
        this.pid = pid;
        this.acquiredAt = acquiredAt;
        this.acquiredByOp = acquiredByOp;
    }

    /** Placeholder for a claim file whose payload cannot be read yet/anymore: held, holder unknown. */
    public static InfobaseLease unknown(String branch) {
        return new InfobaseLease(branch, null, null, UNKNOWN_HOLDER, null, null, -1L, null, null);
    }

    public String branch() { return branch; }
    public String ibPath() { return ibPath; }
    /** Raw infobase connection identity (connection string); matched canonically, never with equals(). */
    public String ibIdentity() { return ibIdentity; }
    public String stackId() { return stackId; }
    public String workspace() { return workspace; }
    public String host() { return host; }
    public long pid() { return pid; }
    /** ISO-8601 instant of acquisition, or {@code null} for an unreadable payload. */
    public String acquiredAt() { return acquiredAt; }
    public String acquiredByOp() { return acquiredByOp; }

    public boolean isHeldBy(String candidateStackId) {
        return stackId != null && !UNKNOWN_HOLDER.equals(stackId) && stackId.equals(candidateStackId);
    }

    /**
     * Flat, machine-readable holder fields for an error payload (blank fields omitted), so a
     * caller reads {@code holder_stack_id} etc. instead of parsing {@link #describeHolder()} prose.
     */
    public java.util.Map<String, String> holderFields() {
        java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
        if (stackId != null && !stackId.isBlank()) {
            m.put("holder_stack_id", stackId); //$NON-NLS-1$
        }
        if (workspace != null && !workspace.isBlank()) {
            m.put("holder_workspace", workspace); //$NON-NLS-1$
        }
        if (host != null && !host.isBlank()) {
            m.put("holder_host", host); //$NON-NLS-1$
        }
        if (pid > 0) {
            m.put("holder_pid", Long.toString(pid)); //$NON-NLS-1$
        }
        if (acquiredAt != null && !acquiredAt.isBlank()) {
            m.put("acquired_at", acquiredAt); //$NON-NLS-1$
        }
        if (branch != null && !branch.isBlank()) {
            m.put("branch", branch); //$NON-NLS-1$
        }
        return m;
    }

    /** Short human-readable holder description for error messages and logs. */
    public String describeHolder() {
        StringBuilder sb = new StringBuilder();
        sb.append("stack '").append(stackId == null ? UNKNOWN_HOLDER : stackId).append('\''); //$NON-NLS-1$
        if (workspace != null && !workspace.isBlank()) {
            sb.append(", workspace ").append(workspace); //$NON-NLS-1$
        }
        if (host != null && !host.isBlank()) {
            sb.append(", host ").append(host); //$NON-NLS-1$
        }
        if (pid > 0) {
            sb.append(", pid ").append(pid); //$NON-NLS-1$
        }
        if (acquiredAt != null && !acquiredAt.isBlank()) {
            sb.append(", since ").append(acquiredAt); //$NON-NLS-1$
        }
        return sb.toString();
    }

    JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("version", Integer.valueOf(CURRENT_VERSION)); //$NON-NLS-1$
        json.addProperty("branch", branch); //$NON-NLS-1$
        if (ibPath != null) {
            json.addProperty("ib_path", ibPath); //$NON-NLS-1$
        }
        if (ibIdentity != null) {
            json.addProperty("ib_identity", ibIdentity); //$NON-NLS-1$
        }
        JsonObject holder = new JsonObject();
        holder.addProperty("stack_id", stackId); //$NON-NLS-1$
        if (workspace != null) {
            holder.addProperty("workspace", workspace); //$NON-NLS-1$
        }
        if (host != null) {
            holder.addProperty("host", host); //$NON-NLS-1$
        }
        if (pid > 0) {
            holder.addProperty("pid", Long.valueOf(pid)); //$NON-NLS-1$
        }
        json.add("holder", holder); //$NON-NLS-1$
        if (acquiredAt != null) {
            json.addProperty("acquired_at", acquiredAt); //$NON-NLS-1$
        }
        if (acquiredByOp != null) {
            json.addProperty("acquired_by_op", acquiredByOp); //$NON-NLS-1$
        }
        return json;
    }

    static InfobaseLease fromJson(JsonObject json, String fallbackBranch) {
        if (json == null) {
            return unknown(fallbackBranch);
        }
        JsonObject holder = json.has("holder") && json.get("holder").isJsonObject() //$NON-NLS-1$ //$NON-NLS-2$
                ? json.getAsJsonObject("holder") : new JsonObject(); //$NON-NLS-1$
        long parsedPid = -1L;
        try {
            if (holder.has("pid")) { //$NON-NLS-1$
                parsedPid = holder.get("pid").getAsLong(); //$NON-NLS-1$
            }
        } catch (RuntimeException ignored) {
            // malformed pid — keep -1
        }
        return new InfobaseLease(
                asString(json, "branch", fallbackBranch), //$NON-NLS-1$
                asString(json, "ib_path", null), //$NON-NLS-1$
                asString(json, "ib_identity", null), //$NON-NLS-1$
                asString(holder, "stack_id", UNKNOWN_HOLDER), //$NON-NLS-1$
                asString(holder, "workspace", null), //$NON-NLS-1$
                asString(holder, "host", null), //$NON-NLS-1$
                parsedPid,
                asString(json, "acquired_at", null), //$NON-NLS-1$
                asString(json, "acquired_by_op", null)); //$NON-NLS-1$
    }

    private static String asString(JsonObject json, String key, String fallback) {
        try {
            if (json.has(key) && json.get(key).isJsonPrimitive()) {
                String value = json.get(key).getAsString();
                return value == null || value.isBlank() ? fallback : value;
            }
        } catch (RuntimeException ignored) {
            // malformed field — fall back
        }
        return fallback;
    }
}
