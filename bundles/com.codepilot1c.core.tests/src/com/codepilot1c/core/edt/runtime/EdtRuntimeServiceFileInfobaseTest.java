/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edt.runtime;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit coverage for {@link EdtRuntimeService#isFileConnectionString(String)} — the file-vs-server
 * decision that drives the BF-13140 test-client credential default. A FILE infobase with no explicit
 * {@code test_client_login} must default to {@code Admin}/{@code 1}; a SERVER infobase must not, so
 * this predicate must classify connection strings exactly.
 *
 * <p>The tool-level wiring ({@code YaxunitRunTool.resolveAccessSettings} /
 * {@code QaRunTool.spawnTestClients}) resolves the real infobase through EDT platform services that
 * are unavailable in this plain-JUnit surface, so it is covered by live smoke rather than here; this
 * test pins the pure decision logic those callers share.</p>
 */
public class EdtRuntimeServiceFileInfobaseTest {

    @Test
    public void fileConnectionStringIsDetected() {
        assertTrue(EdtRuntimeService.isFileConnectionString("File=\"C:\\x\";"));
    }

    @Test
    public void serverConnectionStringIsNotFile() {
        assertFalse(EdtRuntimeService.isFileConnectionString("Srvr=\"h\";Ref=\"n\";"));
    }

    @Test
    public void nullIsNotFile() {
        assertFalse(EdtRuntimeService.isFileConnectionString(null));
    }

    @Test
    public void blankIsNotFile() {
        assertFalse(EdtRuntimeService.isFileConnectionString(""));
        assertFalse(EdtRuntimeService.isFileConnectionString("   "));
    }

    @Test
    public void detectionIsCaseInsensitive() {
        assertTrue(EdtRuntimeService.isFileConnectionString("FILE=\"C:\\x\";"));
        assertTrue(EdtRuntimeService.isFileConnectionString("file='C:/db';"));
    }

    @Test
    public void leadingAndTrailingWhitespaceIsTrimmed() {
        assertTrue(EdtRuntimeService.isFileConnectionString("   File=\"C:\\x\";   "));
    }

    @Test
    public void standaloneOrOtherConnectionStringsAreNotFile() {
        assertFalse(EdtRuntimeService.isFileConnectionString("ws=\"http://host/base\";"));
    }
}
