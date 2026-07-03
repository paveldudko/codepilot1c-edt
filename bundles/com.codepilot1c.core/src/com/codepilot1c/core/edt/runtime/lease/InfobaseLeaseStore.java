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

import com.codepilot1c.core.edt.runtime.InfobaseIdentity;
import com.codepilot1c.core.logging.VibeLogger;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * File-based lease store: one {@code <resource-key>.json} per claimed infobase in a shared
 * directory (by convention {@code <bare-hub>/leases/}). The resource being leased is the
 * PHYSICAL INFOBASE — the key is its canonical connection identity (see {@link #resourceKey}),
 * so any number of branches working the same infobase (phase branches, renames, detached HEADs)
 * contend for ONE file; the branch is a payload attribute, not the key. A branch-named key is
 * only the fallback for identity-less reservations. All stacks of a pool run on ONE machine
 * (the pool design is local-by-design — linked worktrees/remote hubs are out), so plain
 * filesystem atomicity is the coordination primitive:
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

    /** Atomically claims the lease's resource. Loser of a concurrent race gets the winner's payload back. */
    public TakeResult take(InfobaseLease lease) {
        String key = resourceKeyOf(lease);
        Path target = leaseFile(key);
        ensureDirectory();
        try {
            Files.createFile(target);
        } catch (FileAlreadyExistsException e) {
            return new TakeResult(false, find(key).orElse(InfobaseLease.unknown(key)));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create lease file " + target + ": " + e.getMessage(), e); //$NON-NLS-1$ //$NON-NLS-2$
        }
        writePayload(target, lease);
        return new TakeResult(true, null);
    }

    /** Steals the lease's resource unconditionally (atomic replace); returns the previous holder, if any. */
    public TakeResult forceTake(InfobaseLease lease) {
        String key = resourceKeyOf(lease);
        Path target = leaseFile(key);
        ensureDirectory();
        InfobaseLease previous = find(key).orElse(null);
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
     * Releases the lease stored under {@code resourceKey}. Without {@code force} only the holder
     * itself may release; {@code force=true} drops another stack's (stale) lease and reports whose it was.
     */
    public ReleaseResult release(String resourceKey, String selfStackId, boolean force) {
        Path target = leaseFile(resourceKey);
        Optional<InfobaseLease> current = find(resourceKey);
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

    /** Reads the lease stored under {@code resourceKey}; empty when free. An unreadable payload reads as "held, holder unknown". */
    public Optional<InfobaseLease> find(String resourceKey) {
        Path target = leaseFile(resourceKey);
        if (!Files.exists(target)) {
            return Optional.empty();
        }
        return Optional.of(read(target, resourceKey));
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
                String fallbackKey = p.getFileName().toString();
                fallbackKey = fallbackKey.substring(0, fallbackKey.length() - LEASE_SUFFIX.length());
                leases.add(read(p, fallbackKey));
            });
        } catch (IOException e) {
            LOG.warn("Failed to list lease directory %s: %s", directory, e.getMessage()); //$NON-NLS-1$
        }
        return leases;
    }

    /**
     * The store's primary key. The CANONICAL infobase connection identity when one is known —
     * the lease then claims the physical infobase, and every branch working that infobase
     * contends for the same file. Falls back to the (trimmed) branch name for identity-less
     * reservations (e.g. {@code manage_leases take} before the branch has an infobase);
     * {@code null} when neither is available — such a request is not leasable.
     */
    public static String resourceKey(String ibIdentity, String branch) {
        String canonical = InfobaseIdentity.canonical(ibIdentity);
        if (canonical != null && !canonical.isBlank()) {
            return canonical;
        }
        return branch == null || branch.isBlank() ? null : branch.trim();
    }

    /** Resource key of a lease payload — the file key it is (and must be) stored under. */
    public static String resourceKeyOf(InfobaseLease lease) {
        return resourceKey(lease.ibIdentity(), lease.branch());
    }

    /**
     * Filesystem-safe file name for a resource key. Characters outside {@code [A-Za-z0-9._-]} are
     * folded to {@code _}; when folding changed anything a short hash of the ORIGINAL key is
     * appended so distinct keys (e.g. {@code feature/x} vs {@code feature_x}) cannot collide on
     * one file. Already-folded names (as re-read from a directory listing) round-trip unchanged.
     */
    public static String fileNameFor(String resourceKey) {
        String safe = resourceKey.replaceAll("[^A-Za-z0-9._-]", "_"); //$NON-NLS-1$ //$NON-NLS-2$
        if (!safe.equals(resourceKey)) {
            safe = safe + "-" + Integer.toHexString(resourceKey.hashCode()); //$NON-NLS-1$
        }
        return safe + LEASE_SUFFIX;
    }

    private Path leaseFile(String resourceKey) {
        if (resourceKey == null || resourceKey.isBlank()) {
            throw new IllegalArgumentException("lease resource key is required (infobase identity or branch)"); //$NON-NLS-1$
        }
        return directory.resolve(fileNameFor(resourceKey.trim()));
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

    private static InfobaseLease read(Path file, String fallbackKey) {
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            if (content.isBlank()) {
                return InfobaseLease.unknown(fallbackKey);
            }
            JsonObject json = JsonParser.parseString(content).getAsJsonObject();
            return InfobaseLease.fromJson(json, fallbackKey);
        } catch (IOException | RuntimeException e) {
            // Mid-write race or corruption: the file EXISTS, so the lease is held — never free.
            return InfobaseLease.unknown(fallbackKey);
        }
    }
}
