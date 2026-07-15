/*******************************************************************************
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 * Copyright (C) 2026 codepilot1c-edt contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License v3.0 as published by the
 * Free Software Foundation.
 ******************************************************************************/
package com.codepilot1c.core.edt.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.junit.Test;

import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com.codepilot1c.core.edt.runtime.EdtInfobaseConnectService.BindCommit;
import com.codepilot1c.core.edt.runtime.EdtInfobaseConnectService.CredentialOutcome;

/**
 * Regression test for the {@link EdtInfobaseConnectService#finishBind} credential/associate ORDER
 * (BF-12839, autonomy blocker, 3rd recurrence 2026-07-15).
 *
 * <p>{@code associate()} synchronously fires EDT's association event, whose behaviour delegate
 * restores previously-open Designer sessions ({@code connectAndRestoreState -> DesignerClient.connect})
 * using the infobase's <em>currently stored</em> access settings. Under the BF-13140 creds-last order
 * those are still OS/empty on a (re-)bind, so the restore fails to authenticate and EDT raises the
 * native "Configure Infobase Access Settings" modal — a hard block on a headless/agent bind. The fix:
 * when explicit credentials are passed, prime the access settings BEFORE {@code associate()} so the
 * connect-restore reads real credentials; the authoritative store still runs after. The no-creds path
 * keeps the BF-13140 creds-LAST order (primary durability against a blocked secure-storage flush).</p>
 */
public class EdtInfobaseConnectFinishBindOrderTest {

    /**
     * Explicit creds passed: a credential store MUST run before {@code associate()} (so the
     * association event's Designer connect-restore authenticates), and the authoritative store still
     * runs after it.
     */
    @Test
    public void explicitCredsPrimedBeforeAssociate() {
        RecordingConnectService service = new RecordingConnectService();

        BindCommit commit = service.invokeFinishBind(null, null, true, "admin", "1"); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("REGRESSION (BF-12839): with explicit credentials, storeAccessSettings MUST run " //$NON-NLS-1$
                + "before associate() — otherwise associate()'s synchronous Designer connect-restore " //$NON-NLS-1$
                + "reads OS/empty settings and EDT raises the native access-settings modal on a " //$NON-NLS-1$
                + "headless bind. Order was: " + service.order, //$NON-NLS-1$
                service.order.indexOf("store") < service.order.indexOf("associate")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("first step must be the pre-associate credential prime", //$NON-NLS-1$
                "store", service.order.get(0)); //$NON-NLS-1$
        assertEquals("expected prime -> associate -> authoritative store", //$NON-NLS-1$
                List.of("store", "associate", "store"), service.order); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue("bind must report primary committed", commit.primary()); //$NON-NLS-1$
        assertTrue("authoritative credential store outcome must be returned", //$NON-NLS-1$
                commit.credentials().persisted());
    }

    /**
     * No credentials passed: the BF-13140 creds-LAST order is preserved — {@code associate()} (and its
     * primary commit) runs first, the credential store last, and nothing is primed beforehand.
     */
    @Test
    public void noCredsKeepsBf13140LastOrder() {
        RecordingConnectService service = new RecordingConnectService();

        service.invokeFinishBind(null, null, true, null, null);

        assertEquals("REGRESSION (BF-13140): with no credentials the primary must commit FIRST and " //$NON-NLS-1$
                + "the credential store last — a blocked shared secure-storage flush must never " //$NON-NLS-1$
                + "strand the primary pointer. Order was: " + service.order, //$NON-NLS-1$
                List.of("associate", "store"), service.order); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A blank/whitespace login is NOT explicit credentials — must keep the BF-13140 creds-last order. */
    @Test
    public void blankLoginIsNotExplicitCreds() {
        RecordingConnectService service = new RecordingConnectService();

        service.invokeFinishBind(null, null, true, "   ", "1"); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("a blank login must not trigger pre-associate priming (still OS/reuse auth)", //$NON-NLS-1$
                List.of("associate", "store"), service.order); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ---- support -------------------------------------------------------------------------------

    /**
     * Records the order of the two collaborators {@code finishBind} drives, so the test pins the
     * relative ordering without a live EDT. Both are fully overridden — the gateway is never touched.
     */
    private static final class RecordingConnectService extends EdtInfobaseConnectService {
        final List<String> order = new ArrayList<>();

        BindCommit invokeFinishBind(IProject project, InfobaseReference reference, boolean setPrimary,
                String login, String password) {
            return finishBind(project, reference, setPrimary, login, password);
        }

        @Override
        protected boolean associate(IProject project, InfobaseReference reference, boolean setPrimary) {
            order.add("associate"); //$NON-NLS-1$
            return setPrimary;
        }

        @Override
        protected CredentialOutcome storeAccessSettings(InfobaseReference reference, String login,
                String password) {
            order.add("store"); //$NON-NLS-1$
            return CredentialOutcome.ok();
        }
    }
}
