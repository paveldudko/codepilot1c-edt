/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edt.metadata;

/**
 * Decides what to do with the path a relocated subsystem left behind.
 *
 * <p>Moving a subsystem is an FQN change ({@code updateTopObjectFqn}) and the export then writes the
 * {@code .mdo} at the path the NEW FQN dictates — nothing removes the old file. Live-measured
 * 2026-07-29: after moving {@code WaveR6Child} under {@code WaveParent}, the new descriptor appeared
 * at {@code src/Subsystems/WaveParent/Subsystems/WaveR6Child/WaveR6Child.mdo} while
 * {@code src/Subsystems/WaveR6Child/WaveR6Child.mdo} stayed where it was. That leftover is a second
 * definition of the same subsystem, which the next refresh or re-import reads as a second,
 * top-level subsystem.</p>
 *
 * <p>Kept out of the service as a decision over plain booleans because both ways of getting it wrong
 * destroy data and neither shows up in a source-level assertion: removing the old directory when the
 * export never produced the new descriptor deletes the only copy there is, and removing it
 * recursively deletes descendants that were NOT relocated along with it (a subsystem's children are
 * separate top objects, each registered under its own FQN chain).</p>
 */
public final class VacatedSubsystemStorage {

    /** What the post-export cleanup may do with the vacated path. */
    public enum Cleanup {
        /** Nothing to do: the old path holds no descriptor of this subsystem. */
        NOTHING_LEFT,
        /** Refuse: the new descriptor is not on disk, so the old file is the only copy. */
        KEEP_ONLY_COPY,
        /** Remove the descriptor and the directory it sat in. */
        REMOVE_DIRECTORY,
        /** Remove the descriptor alone: the old directory still holds entries of its own. */
        REMOVE_DESCRIPTOR_ONLY
    }

    private VacatedSubsystemStorage() {
    }

    /**
     * @param previousFqn the FQN the subsystem was registered under before the move
     * @param currentFqn the FQN it is registered under now
     * @param previousDescriptorExists whether {@code previousFqn}'s {@code .mdo} is still on disk
     * @param currentDescriptorExists whether {@code currentFqn}'s {@code .mdo} has reached disk —
     *        the export is what puts it there, so this is only true after the export ran
     * @param previousDirectoryHasOtherEntries whether the old directory holds anything besides that
     *        descriptor, a nested {@code Subsystems/} subtree being the case that matters
     */
    public static Cleanup decide(
            String previousFqn,
            String currentFqn,
            boolean previousDescriptorExists,
            boolean currentDescriptorExists,
            boolean previousDirectoryHasOtherEntries
    ) {
        String previousDirectory = MetadataResourcePaths.subsystemDirectory(previousFqn);
        String currentDirectory = MetadataResourcePaths.subsystemDirectory(currentFqn);
        if (previousDirectory == null) {
            // Not a subsystem chain: no storage path can be derived, so there is nothing to clean.
            return Cleanup.NOTHING_LEFT;
        }
        if (previousDirectory.equals(currentDirectory)) {
            // Same slot — the "relocation" moved nothing, and the file at that path is the live one.
            return Cleanup.NOTHING_LEFT;
        }
        if (!previousDescriptorExists) {
            return Cleanup.NOTHING_LEFT;
        }
        if (currentDirectory == null || !currentDescriptorExists) {
            return Cleanup.KEEP_ONLY_COPY;
        }
        if (previousDirectoryHasOtherEntries || isInside(currentDirectory, previousDirectory)) {
            // The new descriptor sitting INSIDE the old directory is what a move under one's own
            // descendant looks like; deleting the directory would take the new copy with it.
            return Cleanup.REMOVE_DESCRIPTOR_ONLY;
        }
        return Cleanup.REMOVE_DIRECTORY;
    }

    private static boolean isInside(String candidate, String directory) {
        return candidate.startsWith(directory + "/"); //$NON-NLS-1$
    }
}
