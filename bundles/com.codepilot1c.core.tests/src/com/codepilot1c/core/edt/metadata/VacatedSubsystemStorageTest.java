/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import com.codepilot1c.core.edt.metadata.VacatedSubsystemStorage.Cleanup;

/**
 * What the post-export cleanup of a relocated subsystem is allowed to delete.
 *
 * <p>Tested by result rather than by reading the service source, because every wrong answer here
 * destroys data: the {@code KEEP_ONLY_COPY} and {@code REMOVE_DESCRIPTOR_ONLY} branches are the two
 * cases where deleting the vacated directory removes a definition that nothing else holds.</p>
 *
 * <p>The case that made this exist (live 2026-07-29): {@code Subsystem.WaveR6Child} moved under
 * {@code WaveParent}; the new descriptor was written under the parent and
 * {@code src/Subsystems/WaveR6Child/WaveR6Child.mdo} stayed behind as a second definition.</p>
 */
public class VacatedSubsystemStorageTest {

    private static final String FLAT = "Subsystem.WaveR6Child"; //$NON-NLS-1$
    private static final String NESTED = "Subsystem.WaveParent.Subsystem.WaveR6Child"; //$NON-NLS-1$

    @Test
    public void theVacatedDirectoryGoesOnceTheNewDescriptorIsOnDisk() {
        assertEquals(Cleanup.REMOVE_DIRECTORY,
                VacatedSubsystemStorage.decide(FLAT, NESTED, true, true, false));
    }

    @Test
    public void aMoveBackToTheRootIsCleanedTheSameWay() {
        // Detaching a nested subsystem vacates src/Subsystems/WaveParent/Subsystems/WaveR6Child,
        // which the flat-FQN rule cannot name — the same defect in the other direction.
        assertEquals(Cleanup.REMOVE_DIRECTORY,
                VacatedSubsystemStorage.decide(NESTED, FLAT, true, true, false));
    }

    @Test
    public void nothingIsTouchedWhenTheNewDescriptorNeverReachedDisk() {
        assertEquals("the vacated file is then the only copy of the definition", //$NON-NLS-1$
                Cleanup.KEEP_ONLY_COPY,
                VacatedSubsystemStorage.decide(FLAT, NESTED, true, false, false));
    }

    @Test
    public void anUnknownDestinationIsTreatedAsAMissingOne() {
        assertEquals(Cleanup.KEEP_ONLY_COPY,
                VacatedSubsystemStorage.decide(FLAT, null, true, true, false));
        assertEquals(Cleanup.KEEP_ONLY_COPY,
                VacatedSubsystemStorage.decide(FLAT, "Catalog.Products", true, true, false)); //$NON-NLS-1$
    }

    @Test
    public void descendantsThatDidNotMoveAreNotDeletedWithTheDirectory() {
        // A subsystem's children are separate top objects, each registered under its own FQN chain,
        // and relocating the parent does not re-register them. Their files sit inside the vacated
        // directory, so only the descriptor may go.
        assertEquals(Cleanup.REMOVE_DESCRIPTOR_ONLY,
                VacatedSubsystemStorage.decide(FLAT, NESTED, true, true, true));
    }

    @Test
    public void aMoveUnderOwnDescendantNeverDeletesTheDirectory() {
        // The new descriptor then sits INSIDE the vacated directory, so removing the directory would
        // take the copy that was just written with it.
        assertEquals(Cleanup.REMOVE_DESCRIPTOR_ONLY,
                VacatedSubsystemStorage.decide(
                        "Subsystem.A", //$NON-NLS-1$
                        "Subsystem.A.Subsystem.B.Subsystem.A", //$NON-NLS-1$
                        true, true, false));
    }

    @Test
    public void anEmptyVacatedPathIsNotAnError() {
        assertEquals("a brand-new object had no file at the old path", //$NON-NLS-1$
                Cleanup.NOTHING_LEFT,
                VacatedSubsystemStorage.decide(FLAT, NESTED, false, true, false));
    }

    @Test
    public void anIdempotentRerunHasNothingToClean() {
        assertEquals("same slot: the file at that path is the live one", //$NON-NLS-1$
                Cleanup.NOTHING_LEFT,
                VacatedSubsystemStorage.decide(NESTED, NESTED, true, true, false));
    }

    @Test
    public void aNonSubsystemFqnIsIgnoredRatherThanGuessedAt() {
        assertEquals(Cleanup.NOTHING_LEFT,
                VacatedSubsystemStorage.decide("Catalog.Products", NESTED, true, true, false)); //$NON-NLS-1$
        assertEquals(Cleanup.NOTHING_LEFT,
                VacatedSubsystemStorage.decide(null, NESTED, true, true, false));
        assertEquals(Cleanup.NOTHING_LEFT,
                VacatedSubsystemStorage.decide("   ", NESTED, true, true, false)); //$NON-NLS-1$
    }
}
