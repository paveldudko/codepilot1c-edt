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

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import org.eclipse.core.resources.IProject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationContextProvider;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAssociationContext;
import com.codepilot1c.core.edt.runtime.lease.InfobaseLeaseGuard;

/**
 * Lease enforcement before update_infobase writes into the infobase (phase-4 pool exclusivity):
 * the configurator run is the most dangerous concurrent access, so a branch/infobase leased by
 * another stack refuses the update with {@code EDT_LEASE_HELD}; a free lease is auto-taken
 * (working the task claims it — same semantics as connect_infobase).
 */
public class EdtRuntimeServiceUpdateLeaseTest {

    private Path dir;

    @Before
    public void setUp() throws IOException {
        dir = Files.createTempDirectory("update-lease-test"); //$NON-NLS-1$
    }

    @After
    public void tearDown() throws IOException {
        if (dir != null && Files.exists(dir)) {
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // best-effort cleanup
                    }
                });
            }
        }
    }

    @Test
    public void foreignLeaseRefusesTheUpdate() {
        new InfobaseLeaseGuard(dir, "stack-3", null) //$NON-NLS-1$
                .checkOrAcquire("task-C", null, null, null); //$NON-NLS-1$
        TestableRuntimeService service = serviceFor("stack-4", "refs/heads/task-C"); //$NON-NLS-1$ //$NON-NLS-2$

        try {
            service.invokeEnforceUpdateLease(newProjectProxy("Polygon"), null); //$NON-NLS-1$
            fail("expected EDT_LEASE_HELD for an update on a branch leased by another stack"); //$NON-NLS-1$
        } catch (EdtToolException expected) {
            assertEquals("REGRESSION: bind-time checks alone cannot stop an already-bound second " //$NON-NLS-1$
                    + "stack from writing into the infobase — the update path must be guarded too", //$NON-NLS-1$
                    EdtToolErrorCode.EDT_LEASE_HELD, expected.getCode());
            assertTrue(expected.getMessage().contains("stack-3")); //$NON-NLS-1$
        }
    }

    @Test
    public void freeLeaseIsAutoTakenByTheUpdate() {
        TestableRuntimeService service = serviceFor("stack-4", "refs/heads/task-D"); //$NON-NLS-1$ //$NON-NLS-2$

        service.invokeEnforceUpdateLease(newProjectProxy("Polygon"), null); //$NON-NLS-1$

        assertTrue("working the task must claim its lease", //$NON-NLS-1$
                new InfobaseLeaseGuard(dir, "stack-4", null) //$NON-NLS-1$
                        .store().find("task-D").orElseThrow().isHeldBy("stack-4")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void unconfiguredGuardChangesNothing() {
        TestableRuntimeService service = new TestableRuntimeService(
                new ContextStubGateway("refs/heads/task-C"), //$NON-NLS-1$
                new InfobaseLeaseGuard(null, "stack-4", null)); //$NON-NLS-1$

        service.invokeEnforceUpdateLease(newProjectProxy("Polygon"), null); //$NON-NLS-1$
        // no exception, no files — ordinary single-instance setups keep today's behavior
    }

    // ---- support -------------------------------------------------------------------------------

    private TestableRuntimeService serviceFor(String stackId, String contextValue) {
        return new TestableRuntimeService(new ContextStubGateway(contextValue),
                new InfobaseLeaseGuard(dir, stackId, null));
    }

    private static final class TestableRuntimeService extends EdtRuntimeService {
        TestableRuntimeService(EdtRuntimeGateway gateway, InfobaseLeaseGuard guard) {
            super(gateway, guard);
        }

        void invokeEnforceUpdateLease(IProject project,
                com._1c.g5.v8.dt.platform.services.model.InfobaseReference infobase) {
            enforceUpdateLease(project, infobase);
        }
    }

    private static final class ContextStubGateway extends EdtRuntimeGateway {
        private final String contextValue;

        ContextStubGateway(String contextValue) {
            this.contextValue = contextValue;
        }

        @Override
        public IInfobaseAssociationContextProvider peekInfobaseAssociationContextProvider() {
            return (IInfobaseAssociationContextProvider) Proxy.newProxyInstance(
                    IInfobaseAssociationContextProvider.class.getClassLoader(),
                    new Class<?>[] { IInfobaseAssociationContextProvider.class },
                    (proxy, method, args) -> "get".equals(method.getName()) //$NON-NLS-1$
                            ? InfobaseAssociationContext.of(contextValue)
                            : defaultReturn(method));
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
