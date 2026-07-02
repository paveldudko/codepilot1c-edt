/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edt.runtime.lease;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import com.codepilot1c.core.logging.VibeLogger;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * File-based lease store: one {@code <branch>.json} per claimed task in a shared directory
 * (by convention {@code <bare-hub>/leases/}). All stacks of a pool run on ONE machine (the pool
 * design is local-by-design — linked worktrees/remote hubs are out), so plain filesystem
 * atomicity is the coordination primitive:
 *
 * <ul>
 *   <li><b>take</b> — {@link Files#createFile} (atomic fail-if-exists on NTFS/POSIX) IS the
 *       claim; the payload is written right after. A reader hitting the tiny claim-to-payload
 *       window sees an empty file and treats it as "held, holder unknown" — never as free.
 *       NB: tmp-file + ATOMIC_MOVE is NOT a safe claim: without REPLACE_EXISTING the move's
 *       fail-if-exists check is not atomic on all platforms, and with it the move silently
 *       replaces.</li>
 *   <li><b>forceTake (steal)</b> — tmp file + atomic replace; the previous payload is returned
 *       so callers can journal whom they stole from.</li>
 *   <li><b>release</b> — read-check-delete. The TOCTOU window between the holder check and the
 *       delete is accepted: same machine, human-scale contention, and the failure mode is an
 *       extra release of a lease that was just stolen anyway.</li>
 * </ul>
 */
public final class InfobaseLeaseStore {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(InfobaseLeaseStore.class);

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final String LEASE_SUFFIX = ".json"; //$NON-NLS-1$
    private static final String TMP_PREFIX = ".tmp-"; //$NON-NLS-1$

    private final Path directory;

    public InfobaseLeaseStore(Path directory) {
        if (directory == null) {
            throw new IllegalArgumentException("lease directory is required"); //$NON-NLS-1$
        }
        this.directory = directory;
    }

    public Path directory() {
        return directory;
    }

    /**
     * Outcome of a (force-)take. For a plain take: {@code taken=false} means the lease is held and
     * {@code lease} is the current holder's payload. For a force-take: {@code taken=true} always,
     * and {@code lease} is the PREVIOUS holder's payload ({@code null} when the lease was free).
     */
    public record TakeResult(boolean taken, InfobaseLease lease) {
    }

    public enum ReleaseStatus {
        RELEASED,
        NOT_HELD,
        HELD_BY_OTHER
    }

    public record ReleaseResult(ReleaseStatus status, InfobaseLease lease) {
    }

    /** Atomically claims the branch. Loser of a concurrent race gets the winner's payload back. */
    public TakeResult take(InfobaseLease lease) {
        Path target = leaseFile(lease.branch());
        ensureDirectory();
        try {
            Files.createFile(target);
        } catch (FileAlreadyExistsException e) {
            return new TakeResult(false, find(lease.branch()).orElse(InfobaseLease.unknown(lease.branch())));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create lease file " + target + ": " + e.getMessage(), e); //$NON-NLS-1$ //$NON-NLS-2$
        }
        writePayload(target, lease);
        return new TakeResult(true, null);
    }

    /** Steals the branch lease unconditionally (atomic replace); returns the previous holder, if any. */
    public TakeResult forceTake(InfobaseLease lease) {
        Path target = leaseFile(lease.branch());
        ensureDirectory();
        InfobaseLease previous = find(lease.branch()).orElse(null);
        Path tmp = directory.resolve(TMP_PREFIX + UUID.randomUUID() + LEASE_SUFFIX);
        try {
            Files.write(tmp, GSON.toJson(lease.toJson()).getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                // Same-directory moves on NTFS/POSIX support ATOMIC_MOVE; this is a defensive
                // fallback for exotic filesystems — the replace is still a single rename.
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // best-effort tmp cleanup
            }
            throw new IllegalStateException("Failed to steal lease " + target + ": " + e.getMessage(), e); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return new TakeResult(true, previous);
    }

    /**
     * Releases the branch lease. Without {@code force} only the holder itself may release;
     * {@code force=true} drops another stack's (stale) lease and reports whose it was.
     */
    public ReleaseResult release(String branch, String selfStackId, boolean force) {
        Path target = leaseFile(branch);
        Optional<InfobaseLease> current = find(branch);
        if (current.isEmpty()) {
            return new ReleaseResult(ReleaseStatus.NOT_HELD, null);
        }
        if (!force && !current.get().isHeldBy(selfStackId)) {
            return new ReleaseResult(ReleaseStatus.HELD_BY_OTHER, current.get());
        }
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to release lease " + target + ": " + e.getMessage(), e); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return new ReleaseResult(ReleaseStatus.RELEASED, current.get());
    }

    /** Reads the branch's lease; empty when free. An unreadable payload reads as "held, holder unknown". */
    public Optional<InfobaseLease> find(String branch) {
        Path target = leaseFile(branch);
        if (!Files.exists(target)) {
            return Optional.empty();
        }
        return Optional.of(read(target, branch));
    }

    /** All leases in the directory (tmp files excluded). Empty when the directory does not exist yet. */
    public List<InfobaseLease> list() {
        List<InfobaseLease> leases = new ArrayList<>();
        if (!Files.isDirectory(directory)) {
            return leases;
        }
        try (Stream<Path> files = Files.list(directory)) {
            files.filter(p -> {
                String name = p.getFileName().toString();
                return name.endsWith(LEASE_SUFFIX) && !name.startsWith(TMP_PREFIX);
            }).sorted().forEach(p -> {
                String fallbackBranch = p.getFileName().toString();
                fallbackBranch = fallbackBranch.substring(0, fallbackBranch.length() - LEASE_SUFFIX.length());
                leases.add(read(p, fallbackBranch));
            });
        } catch (IOException e) {
            LOG.warn("Failed to list lease directory %s: %s", directory, e.getMessage()); //$NON-NLS-1$
        }
        return leases;
    }

    /**
     * Filesystem-safe file name for a branch. Characters outside {@code [A-Za-z0-9._-]} are folded
     * to {@code _}; when folding changed anything a short hash of the ORIGINAL name is appended so
     * distinct branches (e.g. {@code feature/x} vs {@code feature_x}) cannot collide on one file.
     */
    public static String fileNameFor(String branch) {
        String safe = branch.replaceAll("[^A-Za-z0-9._-]", "_"); //$NON-NLS-1$ //$NON-NLS-2$
        if (!safe.equals(branch)) {
            safe = safe + "-" + Integer.toHexString(branch.hashCode()); //$NON-NLS-1$
        }
        return safe + LEASE_SUFFIX;
    }

    private Path leaseFile(String branch) {
        if (branch == null || branch.isBlank()) {
            throw new IllegalArgumentException("branch is required"); //$NON-NLS-1$
        }
        return directory.resolve(fileNameFor(branch.trim()));
    }

    private void ensureDirectory() {
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to create lease directory " + directory + ": " + e.getMessage(), e); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private void writePayload(Path claimedFile, InfobaseLease lease) {
        try {
            Files.write(claimedFile, GSON.toJson(lease.toJson()).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // The claim file exists but carries no payload — readers see "held, holder unknown",
            // which is still a correct (conservative) answer. Surface the failure to the caller.
            throw new IllegalStateException(
                    "Lease claimed but payload write failed for " + claimedFile + ": " + e.getMessage(), e); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static InfobaseLease read(Path file, String fallbackBranch) {
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            if (content.isBlank()) {
                return InfobaseLease.unknown(fallbackBranch);
            }
            JsonObject json = JsonParser.parseString(content).getAsJsonObject();
            return InfobaseLease.fromJson(json, fallbackBranch);
        } catch (IOException | RuntimeException e) {
            // Mid-write race or corruption: the file EXISTS, so the lease is held — never free.
            return InfobaseLease.unknown(fallbackBranch);
        }
    }
}
