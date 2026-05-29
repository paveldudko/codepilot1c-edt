/*******************************************************************************
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 * Copyright (C) 2026 codepilot1c-edt contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License v3.0 as published by the
 * Free Software Foundation.
 ******************************************************************************/
package com.codepilot1c.core.tools.qa;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.codepilot1c.core.qa.QaConfig;

/**
 * Unit tests for {@link QaRunTool#shouldSpawnTestClients} — the gate deciding whether qa_run
 * launches external Vanessa TestClient processes before the TestManager. Guards the fix for
 * feedback {@code 2026-05-29-qa-run-testmanager-no-va-log-no-junit.md}: headless TestManager runs
 * must spawn their TestClients (VA connects to them by {@code -TPort}) or they hang pre-FeaturePlayer.
 */
public class QaRunSpawnTestClientsDecisionTest {

    private static QaConfig configWithOneTestClient() {
        QaConfig config = new QaConfig();
        QaConfig.TestClient client = new QaConfig.TestClient();
        client.name = "TestClient"; //$NON-NLS-1$
        client.port = Integer.valueOf(48111);
        config.test_clients.add(client);
        return config;
    }

    @Test
    public void spawnsForEdtTestManagerRunWithConfiguredClients() {
        assertTrue("EDT + TestManager + test_clients (flag default) must spawn", //$NON-NLS-1$
                QaRunTool.shouldSpawnTestClients(true, true, true, configWithOneTestClient()));
    }

    @Test
    public void doesNotSpawnInSingleClientMode() {
        assertFalse("SingleClient mode (use_test_manager=false) must not spawn external clients", //$NON-NLS-1$
                QaRunTool.shouldSpawnTestClients(true, false, true, configWithOneTestClient()));
    }

    @Test
    public void doesNotSpawnWithoutEdtRuntime() {
        assertFalse("the non-EDT-runtime path has no command builder, so it must not spawn", //$NON-NLS-1$
                QaRunTool.shouldSpawnTestClients(false, true, true, configWithOneTestClient()));
    }

    @Test
    public void doesNotSpawnWithoutRuntimeService() {
        assertFalse("a null runtimeService cannot build the TestClient command", //$NON-NLS-1$
                QaRunTool.shouldSpawnTestClients(true, true, false, configWithOneTestClient()));
    }

    @Test
    public void doesNotSpawnWhenNoTestClientsConfigured() {
        assertFalse("no test_clients means nothing to launch", //$NON-NLS-1$
                QaRunTool.shouldSpawnTestClients(true, true, true, new QaConfig()));
    }

    @Test
    public void respectsExplicitOptOut() {
        QaConfig config = configWithOneTestClient();
        config.vanessa = new QaConfig.Vanessa();
        config.vanessa.spawn_test_clients = Boolean.FALSE;
        assertFalse("vanessa.spawn_test_clients=false must disable spawning", //$NON-NLS-1$
                QaRunTool.shouldSpawnTestClients(true, true, true, config));
    }

    @Test
    public void defaultsOnWhenFlagUnset() {
        QaConfig config = configWithOneTestClient();
        config.vanessa = new QaConfig.Vanessa(); // spawn_test_clients left null
        assertTrue("an unset spawn_test_clients flag defaults to on", //$NON-NLS-1$
                QaRunTool.shouldSpawnTestClients(true, true, true, config));
    }
}
