/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.state;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.codepilot1c.core.edt.runtime.lease.InfobaseLeaseGuard;
import com.codepilot1c.core.edt.runtime.lease.InfobaseLeaseStore;
import com.codepilot1c.core.logging.VibeLogger;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

/**
 * Periodic writer of this stack's {@link EdtWorkspaceStateService} snapshot to a shared "beacon"
 * file, so an external stack-pool orchestrator can read each stack's liveness and basic state
 * WITHOUT calling its MCP port.
 *
 * <p><b>Hard opt-in</b> (mirrors {@link InfobaseLeaseGuard}). The beacon is active only when a
 * state directory resolves from, in order: the {@code CODEPILOT1C_STATE_DIR} environment variable,
 * the {@code edt.state.dir} instance preference, or — when the lease guard is itself configured —
 * a sibling {@code edt-state} directory next to the pool's lease directory. With none of these set
 * the beacon is a no-op, so ordinary single-instance users see zero behavior change.</p>
 *
 * <p>One snapshot is written every {@link #TICK_SECONDS} seconds on a single daemon thread; the
 * first tick fires immediately so a partial beacon (identity + liveness, {@code index=UNKNOWN})
 * appears before EDT finishes indexing. The heavier {@code bound_infobases} field is gathered only
 * on a slow sub-cadence (every {@link #SLOW_EVERY_N_TICKS}-th tick). The file is written atomically
 * (temp file in the same directory + atomic rename) and is deleted on a clean {@link #stop()} so a
 * stopped stack leaves no stale beacon behind.</p>
 */
public final class EdtStateBeacon {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(EdtStateBeacon.class);

    /** Environment variable pointing at the pool's shared state directory. */
    public static final String ENV_STATE_DIR = "CODEPILOT1C_STATE_DIR"; //$NON-NLS-1$
    /** Instance-scope preference key (node {@code com.codepilot1c.core}) for the state directory. */
    public static final String PREF_STATE_DIR = "edt.state.dir"; //$NON-NLS-1$

    /** Beacon cadence, seconds (kept in the agreed 30–60 s band). */
    static final int TICK_SECONDS = 45;
    /** Gather the heavier {@code bound_infobases} field on every Nth tick only. */
    static final int SLOW_EVERY_N_TICKS = 4;

    private static final String CORE_PLUGIN_ID = "com.codepilot1c.core"; //$NON-NLS-1$
    private static final String STATE_DIR_NAME = "edt-state"; //$NON-NLS-1$
    private static final String TMP_PREFIX = ".tmp-"; //$NON-NLS-1$
    private static final String JSON_SUFFIX = ".json"; //$NON-NLS-1$
    private static final long SHUTDOWN_GRACE_MS = 2000L;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final EdtStateBeacon INSTANCE = new EdtStateBeacon(new EdtWorkspaceStateService());

    public static EdtStateBeacon getInstance() {
        return INSTANCE;
    }

    private final EdtWorkspaceStateService stateService;

    // All mutable fields below are published under the instance monitor (start/stop are synchronized);
    // tick() runs on the single beacon thread, so tickCounter needs no extra guard.
    private ScheduledExecutorService scheduler;
    private Path stateDir;
    private String stackId;
    private Path targetFile;
    private int tickCounter;

    EdtStateBeacon(EdtWorkspaceStateService stateService) {
        this.stateService = stateService;
    }

    /**
     * Starts the beacon when a state directory is configured; a no-op otherwise. Idempotent —
     * a second call while already running does nothing.
     */
    public synchronized void startIfConfigured() {
        if (scheduler != null) {
            return;
        }
        Path dir = resolveStateDir();
        if (dir == null) {
            LOG.info("state beacon disabled — no state dir configured (%s / pref %s / lease-dir sibling)", //$NON-NLS-1$
                    ENV_STATE_DIR, PREF_STATE_DIR);
            return;
        }
        this.stateDir = dir;
        this.stackId = InfobaseLeaseGuard.fromEnvironment().stackId();
        this.targetFile = beaconFile(dir, stackId);
        this.tickCounter = 0;
        ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "codepilot1c-state-beacon"); //$NON-NLS-1$
            thread.setDaemon(true);
            return thread;
        });
        exec.scheduleWithFixedDelay(this::tick, 0, TICK_SECONDS, TimeUnit.SECONDS);
        this.scheduler = exec;
        LOG.info("state beacon started: dir=%s stack=%s tick=%ds file=%s", //$NON-NLS-1$
                dir, stackId, Integer.valueOf(TICK_SECONDS), targetFile.getFileName());
    }

    /**
     * One beacon tick: builds a snapshot and writes it atomically. Never throws — a failed tick is
     * logged and the scheduler keeps running so a transient error does not silently kill the beacon.
     */
    void tick() {
        try {
            boolean includeSlow = (tickCounter++ % SLOW_EVERY_N_TICKS) == 0;
            JsonObject snapshot = stateService.buildSnapshot(includeSlow);
            writeAtomically(targetFile, GSON.toJson(snapshot).getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            LOG.warn("state beacon tick failed (beacon stays alive): %s", //$NON-NLS-1$
                    t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
        }
    }

    /**
     * Stops the scheduler and deletes the beacon file so a cleanly-stopped stack leaves no stale
     * beacon. Idempotent and safe to call when the beacon was never started.
     */
    public synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(SHUTDOWN_GRACE_MS, TimeUnit.MILLISECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                scheduler.shutdownNow();
            }
            scheduler = null;
        }
        if (targetFile != null) {
            try {
                Files.deleteIfExists(targetFile);
            } catch (IOException e) {
                LOG.warn("state beacon: failed to delete beacon file %s: %s", targetFile, e.getMessage()); //$NON-NLS-1$
            }
        }
    }

    /**
     * Resolves the state directory or {@code null} when the beacon should stay off: env
     * {@code CODEPILOT1C_STATE_DIR} first, the {@code edt.state.dir} instance preference next, else a
     * sibling {@code edt-state} directory next to the lease directory when the lease guard is
     * configured.
     */
    static Path resolveStateDir() {
        String configured = System.getenv(ENV_STATE_DIR);
        if (configured == null || configured.isBlank()) {
            configured = instancePreference(PREF_STATE_DIR);
        }
        if (configured != null && !configured.isBlank()) {
            try {
                return Path.of(configured.trim()).toAbsolutePath().normalize();
            } catch (RuntimeException e) {
                LOG.warn("Invalid %s value '%s' — state beacon stays off: %s", //$NON-NLS-1$
                        ENV_STATE_DIR, configured, e.getMessage());
                return null;
            }
        }
        // Derive from the lease directory when the pool guard is configured: <lease-dir>/../edt-state.
        InfobaseLeaseStore leaseStore = InfobaseLeaseGuard.fromEnvironment().store();
        if (leaseStore != null && leaseStore.directory() != null
                && leaseStore.directory().getParent() != null) {
            return leaseStore.directory().getParent().resolve(STATE_DIR_NAME);
        }
        return null;
    }

    /** The beacon file for a stack id, using the lease store's FS-safe, {@code .json}-suffixed naming. */
    static Path beaconFile(Path dir, String stackId) {
        return dir.resolve(InfobaseLeaseStore.fileNameFor(stackId));
    }

    /**
     * Atomic write: temp file in the SAME directory, then {@code ATOMIC_MOVE} onto the target with a
     * plain-replace fallback for exotic filesystems. Copied from
     * {@code InfobaseLeaseStore.forceTake} — same coordination guarantee.
     */
    static void writeAtomically(Path target, byte[] content) throws IOException {
        Path dir = target.getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        Path tmpDir = dir != null ? dir : target.toAbsolutePath().getParent();
        Path tmp = tmpDir.resolve(TMP_PREFIX + UUID.randomUUID() + JSON_SUFFIX);
        try {
            Files.write(tmp, content);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // best-effort tmp cleanup
            }
            throw e;
        }
    }

    /** Test seam: a beacon bound to an explicit dir + stack id, not scheduled, no env resolution. */
    static EdtStateBeacon createForTest(Path stateDir, String stackId, EdtWorkspaceStateService service) {
        EdtStateBeacon beacon = new EdtStateBeacon(service);
        beacon.stateDir = stateDir;
        beacon.stackId = stackId;
        beacon.targetFile = beaconFile(stateDir, stackId);
        return beacon;
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
}
