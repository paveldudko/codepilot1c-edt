/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.mcp.host;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import com.codepilot1c.core.logging.VibeLogger;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

/**
 * Per-user JSON store for the machine-shared half of the MCP endpoint profiles
 * (see {@link SharedProfile}). One file under the user home is read by every
 * plugin instance / EDT installation of that OS user, so the profile <em>set</em>
 * (names, tool sets, tokens, enabled flags) is common; only the per-instance port
 * mapping stays in each workspace's {@code InstanceScope} (see
 * {@link McpHostConfigStore}).
 *
 * <p>Default location {@code ${user.home}/.codepilot1c/mcp-profiles.json}; override
 * with {@code -Dcodepilot.mcp.host.profilesFile=…} or the {@code CODEPILOT1C_PROFILES_FILE}
 * environment variable (used by tests). Writes are atomic (temp file + move) so a
 * concurrent reader never sees a half-written file; concurrent writers are
 * last-writer-wins, which is fine for an interactive preference page.</p>
 *
 * <p>Tokens are stored in plain text here (they previously lived in EDT secure
 * storage). Acceptable for a loopback-bound dev MCP host; documented trade-off of
 * making the token machine-shared.</p>
 */
public class SharedProfileStore {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(SharedProfileStore.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type PROFILE_LIST_TYPE = new TypeToken<List<SharedProfile>>() { }.getType();

    private static final String PROP_FILE = "codepilot.mcp.host.profilesFile"; //$NON-NLS-1$
    private static final String ENV_FILE = "CODEPILOT1C_PROFILES_FILE"; //$NON-NLS-1$

    private final Path file;

    public SharedProfileStore(Path file) {
        this.file = file;
    }

    /** The store at the resolved default (or {@code -D}/env overridden) location. */
    public static SharedProfileStore getDefault() {
        return new SharedProfileStore(defaultPath());
    }

    /** Resolve the shared-profiles file location (override-aware). */
    public static Path defaultPath() {
        String override = System.getProperty(PROP_FILE);
        if (override == null || override.isBlank()) {
            override = System.getenv(ENV_FILE);
        }
        if (override != null && !override.isBlank()) {
            return Path.of(override.trim());
        }
        return Path.of(System.getProperty("user.home"), ".codepilot1c", "mcp-profiles.json"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    public Path getFile() {
        return file;
    }

    public boolean exists() {
        return Files.isRegularFile(file);
    }

    /**
     * Load the shared profiles. Returns {@code null} when the file does not exist
     * yet (the caller treats this as "never migrated" and seeds), or an empty list
     * on a corrupt/unreadable file (logged — never re-seeds over a present file).
     */
    public List<SharedProfile> load() {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            if (json.isBlank()) {
                return new ArrayList<>();
            }
            List<SharedProfile> parsed = GSON.fromJson(json, PROFILE_LIST_TYPE);
            return parsed != null ? parsed : new ArrayList<>();
        } catch (IOException | RuntimeException e) {
            LOG.error("Failed to read shared MCP profiles from " + file + "; treating as empty", e); //$NON-NLS-1$ //$NON-NLS-2$
            return new ArrayList<>();
        }
    }

    /** Persist the shared profiles atomically (creating the parent directory). */
    public void save(List<SharedProfile> profiles) {
        List<SharedProfile> list = profiles != null ? profiles : new ArrayList<>();
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = (parent != null ? parent : file).resolve(file.getFileName() + ".tmp"); //$NON-NLS-1$
            Files.writeString(tmp, GSON.toJson(list, PROFILE_LIST_TYPE), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                // Some filesystems reject ATOMIC_MOVE across a rename target — fall back.
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            LOG.error("Failed to write shared MCP profiles to " + file, e); //$NON-NLS-1$
        }
    }
}
