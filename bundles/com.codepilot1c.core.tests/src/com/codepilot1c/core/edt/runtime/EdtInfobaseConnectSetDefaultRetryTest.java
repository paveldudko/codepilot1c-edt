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
import static org.junit.Assert.fail;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.resources.IProject;
import org.junit.Test;

import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseManager;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;

/**
 * Regression test for the "first connect into a fresh branch context fails" race
 * (stack polygon live finding, 2026-07-02).
 *
 * <p>Live-observed on EDT 2025.2.x: the very first {@code connect_infobase} for a new git-branch
 * association context persisted the association file, yet the immediately following
 * {@code setDefaultInfobase} threw a plain {@code IllegalArgumentException} ("Project ... is not
 * associated with infobase ...") — NOT {@code InfobaseAssociationException} (verified against the
 * 2025.2.x bytecode), which is why the historical wrap-and-rethrow never fired. An external re-run
 * of the whole connect always succeeded (2/2 reproduction on fresh contexts).</p>
 *
 * <p>The fix widens the catch to {@code RuntimeException} and retries once in place:
 * {@link EdtInfobaseConnectService#associate} re-adopts the persisted association identity,
 * best-effort re-persists the reference, re-issues {@code associate()} and retries
 * {@code setDefaultInfobase}. This test injects the live exception type on the first call and
 * asserts the retry converges; a companion test asserts a persistent failure is still surfaced as
 * a typed error after exactly one retry.</p>
 */
public class EdtInfobaseConnectSetDefaultRetryTest {

    /** First {@code setDefaultInfobase} throws, the retry succeeds — connect must report primary. */
    @Test
    public void firstSetDefaultFailureIsRetriedOnce() {
        FlakyAssociationManager manager = new FlakyAssociationManager(1);
        TestableConnectService service = new TestableConnectService(new StubGateway(manager.proxy));
        IProject project = newProjectProxy("Polygon"); //$NON-NLS-1$
        // A null reference keeps adoptExistingAssociationName a no-op and is treated opaquely by
        // associate()/the stubbed manager — same idiom as EdtInfobaseConnectAssociationContextTest.
        InfobaseReference reference = null;

        boolean primary = service.invokeAssociate(project, reference, true);

        assertTrue("REGRESSION: the first setDefaultInfobase failure right after associate() must " //$NON-NLS-1$
                + "be retried in place (re-adopt + re-associate + setDefault), not surfaced — " //$NON-NLS-1$
                + "otherwise the first connect of every new branch context fails and only an " //$NON-NLS-1$
                + "external retry heals it.", primary); //$NON-NLS-1$
        assertEquals("associate() must be re-issued before the retry", 2, manager.associateCalls.get()); //$NON-NLS-1$
        assertEquals("setDefaultInfobase must be attempted exactly twice", 2, manager.setDefaultCalls.get()); //$NON-NLS-1$
    }

    /** A persistently failing {@code setDefaultInfobase} is surfaced after exactly one retry. */
    @Test
    public void persistentSetDefaultFailureSurfacesAfterSingleRetry() {
        FlakyAssociationManager manager = new FlakyAssociationManager(Integer.MAX_VALUE);
        TestableConnectService service = new TestableConnectService(new StubGateway(manager.proxy));
        IProject project = newProjectProxy("Polygon"); //$NON-NLS-1$

        try {
            service.invokeAssociate(project, null, true);
            fail("expected EdtToolException for a persistently failing setDefaultInfobase"); //$NON-NLS-1$
        } catch (EdtToolException expected) {
            assertEquals("persistent failure must keep the typed error code", //$NON-NLS-1$
                    EdtToolErrorCode.EDT_SERVICE_UNAVAILABLE, expected.getCode());
        }
        assertEquals("retry must be bounded to a single attempt", 2, manager.setDefaultCalls.get()); //$NON-NLS-1$
    }

    // ---- support -------------------------------------------------------------------------------

    /** Exposes the protected {@code associate} and skips the retry grace pause. */
    private static final class TestableConnectService extends EdtInfobaseConnectService {
        TestableConnectService(EdtRuntimeGateway gateway) {
            super(gateway);
        }

        boolean invokeAssociate(IProject project, InfobaseReference reference, boolean setPrimary) {
            return associate(project, reference, setPrimary);
        }

        @Override
        protected void settleBeforeSetDefaultRetry() {
            // no pause in unit tests
        }
    }

    private static final class StubGateway extends EdtRuntimeGateway {
        private final IInfobaseAssociationManager associationManager;

        StubGateway(IInfobaseAssociationManager associationManager) {
            this.associationManager = associationManager;
        }

        @Override
        public IInfobaseAssociationManager getInfobaseAssociationManager() {
            return associationManager;
        }

        @Override
        public com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationContextProvider
                peekInfobaseAssociationContextProvider() {
            return null; // no context extension in unit tests -> empty context
        }

        @Override
        public IInfobaseManager getInfobaseManager() {
            // The retry path re-persists best-effort; an unavailable registry must not break it.
            throw new IllegalStateException("no infobase manager in unit tests"); //$NON-NLS-1$
        }
    }

    /** Association manager whose {@code setDefaultInfobase} fails the first {@code failures} calls. */
    private static final class FlakyAssociationManager implements InvocationHandler {
        final AtomicInteger associateCalls = new AtomicInteger();
        final AtomicInteger setDefaultCalls = new AtomicInteger();
        private final int failures;

        private final IInfobaseAssociationManager proxy = (IInfobaseAssociationManager) Proxy.newProxyInstance(
                IInfobaseAssociationManager.class.getClassLoader(),
                new Class<?>[] { IInfobaseAssociationManager.class }, this);

        FlakyAssociationManager(int failures) {
            this.failures = failures;
        }

        @Override
        public Object invoke(Object p, Method method, Object[] args) {
            switch (method.getName()) {
                case "associate": //$NON-NLS-1$
                    associateCalls.incrementAndGet();
                    return null;
                case "setDefaultInfobase": //$NON-NLS-1$
                    if (setDefaultCalls.incrementAndGet() <= failures) {
                        // The exact live exception type: plain IllegalArgumentException, NOT
                        // InfobaseAssociationException (EDT 2025.2.x InfobaseAssociationManager).
                        throw new IllegalArgumentException(
                                "Project Polygon is not associated with infobase polygon-task-A"); //$NON-NLS-1$
                    }
                    return null;
                default:
                    return defaultReturn(method);
            }
        }
    }

    private static IProject newProjectProxy(String projectName) {
        return (IProject) Proxy.newProxyInstance(
                IProject.class.getClassLoader(),
                new Class<?>[] { IProject.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> projectName; //$NON-NLS-1$
                    case "exists", "isOpen" -> Boolean.TRUE; //$NON-NLS-1$ //$NON-NLS-2$
                    case "equals" -> Boolean.valueOf(proxy == args[0]); //$NON-NLS-1$
                    case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy)); //$NON-NLS-1$
                    case "toString" -> "StubProject[" + projectName + "]"; //$NON-NLS-1$ //$NON-NLS-2$
                    default -> defaultReturn(method);
                });
    }

    private static Object defaultReturn(Method method) {
        Class<?> ret = method.getReturnType();
        if (ret == boolean.class) {
            return Boolean.FALSE;
        }
        if (ret.isPrimitive()) {
            return Integer.valueOf(0);
        }
        return null;
    }
}
