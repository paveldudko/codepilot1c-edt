/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edt.runtime.lease;

import java.net.InetAddress;
import java.nio.file.Path;
import java.time.Instant;

import com.codepilot1c.core.edt.runtime.InfobaseIdentity;
import com.codepilot1c.core.logging.VibeLogger;

/**
 * Pool-exclusivity guard: decides whether THIS EDT stack may work a (branch + infobase) task,
 * auto-claiming the lease when it is free. Consulted by {@code connect_infobase} (bind-time) and
 * {@code update_infobase} (before the configurator writes into the infobase); the
 * {@code manage_leases} tool exposes the same store for explicit take/release/status.
 *
 * <p><b>Hard opt-in:</b> the guard is active ONLY when the {@code CODEPILOT1C_LEASE_DIR}
 * environment variable (or the {@code infobase.lease.dir} instance preference) points at the
 * pool's shared lease directory. Unset — every decision is {@link Outcome#OFF} and no file is
 * ever touched, so ordinary single-instance users see zero behavior change. There is deliberately
 * NO auto-derivation from a project's git remote: a local-path remote of an ordinary user must
 * not silently switch enforcement on and start writing files into their remote.</p>
 *
 * <p><b>The leased resource is the physical infobase</b>, keyed by its canonical connection
 * identity ({@link InfobaseLeaseStore#resourceKey}); the branch is a payload attribute. Any
 * number of branches working the same infobase (phase branches of one task, renames, detached
 * HEADs) share ONE lease, and a branch hop on the same infobase by the holder just refreshes
 * the payload. A branch name is only the fallback key for identity-less reservations
 * ({@code manage_leases take} before the infobase exists); conflicts are still detected against
 * such reservations by the branch attribute. A request with neither an identity nor a branch
 * (nothing to protect) yields {@link Outcome#OFF}.</p>
 */
public final class InfobaseLeaseGuard {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(InfobaseLeaseGuard.class);

    public static final String ENV_LEASE_DIR = "CODEPILOT1C_LEASE_DIR"; //$NON-NLS-1$
    public static final String ENV_STACK_ID = "CODEPILOT1C_STACK_ID"; //$NON-NLS-1$
    public static final String PREF_LEASE_DIR = "infobase.lease.dir"; //$NON-NLS-1$

    private static final String CORE_PLUGIN_ID = "com.codepilot1c.core"; //$NON-NLS-1$
    private static final String BRANCH_REF_PREFIX = "refs/heads/"; //$NON-NLS-1$

    public enum Outcome {
        /** Guard not configured, or the context is not leasable (no branch) — no enforcement. */
        OFF,
        /** This stack holds (or has just auto-taken) the lease — proceed. */
        ALLOWED,
        /** Another stack holds a conflicting lease — refuse. */
        DENIED
    }

    /** {@code lease} is the conflicting lease for DENIED, the own lease for ALLOWED, else null. */
    public record Decision(Outcome outcome, InfobaseLease lease) {

        static Decision off() {
            return new Decision(Outcome.OFF, null);
        }
    }

    private final InfobaseLeaseStore store; // null => disabled
    private final String stackId;
    private final String workspace;

    /**
     * Production configuration: env {@code CODEPILOT1C_LEASE_DIR} first, instance preference
     * {@code infobase.lease.dir} second, otherwise disabled.
     */
    public static InfobaseLeaseGuard fromEnvironment() {
        String dir = System.getenv(ENV_LEASE_DIR);
        if (dir == null || dir.isBlank()) {
            dir = instancePreference(PREF_LEASE_DIR);
        }
        Path leaseDir = null;
        if (dir != null && !dir.isBlank()) {
            try {
                leaseDir = Path.of(dir.trim()).toAbsolutePath().normalize();
            } catch (RuntimeException e) {
                LOG.warn("Invalid %s value '%s' — lease enforcement stays OFF: %s", //$NON-NLS-1$
                        ENV_LEASE_DIR, dir, e.getMessage());
                leaseDir = null;
            }
        }
        String stack = System.getenv(ENV_STACK_ID);
        if (stack == null || stack.isBlank()) {
            stack = defaultStackId();
        }
        return new InfobaseLeaseGuard(leaseDir, stack.trim(), defaultWorkspacePath());
    }

    /** Direct wiring for tests. {@code leaseDir == null} disables the guard. */
    public InfobaseLeaseGuard(Path leaseDir, String stackId, String workspace) {
        this.store = leaseDir == null ? null : new InfobaseLeaseStore(leaseDir);
        this.stackId = stackId;
        this.workspace = workspace;
    }

    public boolean isEnabled() {
        return store != null;
    }

    public String stackId() {
        return stackId;
    }

    /** The underlying store for explicit lease operations; {@code null} when disabled. */
    public InfobaseLeaseStore store() {
        return store;
    }

    /**
     * Core decision: may this stack work the infobase with connection identity {@code ibIdentity}
     * (branch {@code branch} is the payload attribute)? Free lease is auto-taken (bind = claim).
     * A branch hop by the holder on the same infobase (phase branches of one task) refreshes the
     * payload in place; pointing the same branch at a NEW infobase claims a second lease — the
     * old infobase stays claimed until it is explicitly released.
     *
     * @param branch short branch name attribute; may be null (detached HEAD, non-git)
     * @param ibPath human-readable infobase path/connection for the lease payload; may be null
     * @param ibIdentity raw connection identity — the primary key (matched canonically); may be null
     * @param opId caller's operation id for the lease journal; may be null
     */
    public Decision checkOrAcquire(String branch, String ibPath, String ibIdentity, String opId) {
        if (!isEnabled()) {
            return Decision.off();
        }
        String normalizedBranch = branch == null || branch.isBlank() ? null : branch.trim();
        String resourceKey = InfobaseLeaseStore.resourceKey(ibIdentity, normalizedBranch);
        if (resourceKey == null) {
            // Neither an infobase identity nor a branch — nothing to protect.
            return Decision.off();
        }
        // One scan decides: a FOREIGN lease conflicts when it claims the same physical infobase
        // (primary rule) or reserves the same branch name (identity-less reservation).
        InfobaseLease own = null;
        for (InfobaseLease lease : store.list()) {
            boolean sameInfobase = ibIdentity != null
                    && InfobaseIdentity.matches(ibIdentity, lease.ibIdentity());
            boolean sameBranch = normalizedBranch != null && normalizedBranch.equals(lease.branch());
            if (!sameInfobase && !sameBranch) {
                continue;
            }
            if (!lease.isHeldBy(stackId)) {
                return new Decision(Outcome.DENIED, lease);
            }
            if (own == null || sameInfobase) {
                own = lease;
            }
        }
        if (own != null) {
            boolean sameStoredKey = resourceKey.equals(InfobaseLeaseStore.resourceKeyOf(own));
            boolean branchHopped = normalizedBranch != null && !normalizedBranch.equals(own.branch());
            if (sameStoredKey && branchHopped) {
                // Holder moved to another branch of the SAME infobase (phase hop) — refresh in place.
                InfobaseLeaseStore.TakeResult refreshed =
                        store.forceTake(newLease(normalizedBranch, ibPath, ibIdentity, opId));
                return new Decision(Outcome.ALLOWED, refreshed.lease() == null ? own : refreshed.lease());
            }
            if (sameStoredKey) {
                return new Decision(Outcome.ALLOWED, own);
            }
            // Own match under a DIFFERENT key: a branch-name reservation being exercised with a
            // real infobase, or the branch re-pointed at a new infobase. Claim the new resource
            // too (fall through) — the old claim stays until explicitly released.
        }
        InfobaseLeaseStore.TakeResult take = store.take(newLease(normalizedBranch, ibPath, ibIdentity, opId));
        if (take.taken()) {
            LOG.info("lease auto-taken: key=%s branch=%s stack=%s", resourceKey, normalizedBranch, stackId); //$NON-NLS-1$
            return new Decision(Outcome.ALLOWED, null);
        }
        // Lost a concurrent take race.
        InfobaseLease winner = take.lease();
        if (winner != null && winner.isHeldBy(stackId)) {
            return new Decision(Outcome.ALLOWED, winner);
        }
        return new Decision(Outcome.DENIED, winner);
    }

    /** Builds a lease payload for this stack, stamped now. */
    public InfobaseLease newLease(String branch, String ibPath, String ibIdentity, String opId) {
        return new InfobaseLease(branch, ibPath, ibIdentity, stackId, workspace, hostName(),
                ProcessHandle.current().pid(), Instant.now().toString(), opId);
    }

    /**
     * Short branch name from an association-context value: {@code refs/heads/task-C} → {@code task-C}.
     * Anything else (detached-HEAD commit hash, empty context, other refs) → null; the guard then
     * keys the lease by the infobase identity alone and records no branch attribute.
     */
    public static String branchFromContext(String contextValue) {
        if (contextValue == null) {
            return null;
        }
        String trimmed = contextValue.trim();
        if (!trimmed.startsWith(BRANCH_REF_PREFIX)) {
            return null;
        }
        String branch = trimmed.substring(BRANCH_REF_PREFIX.length());
        return branch.isBlank() ? null : branch;
    }

    private static String instancePreference(String key) {
        try {
            return org.eclipse.core.runtime.preferences.InstanceScope.INSTANCE
                    .getNode(CORE_PLUGIN_ID).get(key, null);
        } catch (Throwable t) {
            // Platform not running (plain JUnit) or preferences unavailable — treat as unset.
            return null;
        }
    }

    private static String defaultStackId() {
        String workspacePath = defaultWorkspacePath();
        if (workspacePath != null) {
            return workspacePath;
        }
        return System.getProperty("user.dir", "unknown-stack"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String defaultWorkspacePath() {
        try {
            org.eclipse.core.runtime.IPath location =
                    org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot().getLocation();
            return location == null ? null : location.toOSString();
        } catch (Throwable t) {
            // Workspace not running (plain JUnit/headless bootstrap).
            return null;
        }
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return null;
        }
    }
}
