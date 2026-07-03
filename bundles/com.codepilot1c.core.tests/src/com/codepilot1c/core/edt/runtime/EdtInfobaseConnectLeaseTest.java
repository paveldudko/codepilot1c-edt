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
 * Lease enforcement at connect_infobase bind-time (phase-4 pool exclusivity): a branch leased by
 * another stack must refuse the bind with a typed {@code EDT_LEASE_HELD}, a free lease must be
 * auto-taken (bind = claim), a non-branch context and an unconfigured guard must change nothing.
 */
public class EdtInfobaseConnectLeaseTest {

    private Path dir;

    @Before
    public void setUp() throws IOException {
        dir = Files.createTempDirectory("connect-lease-test"); //$NON-NLS-1$
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
    public void foreignLeaseRefusesTheBindWithTypedError() {
        InfobaseLeaseGuard stack3 = new InfobaseLeaseGuard(dir, "stack-3", null); //$NON-NLS-1$
        stack3.checkOrAcquire("task-C", null, null, null); //$NON-NLS-1$
        TestableConnectService service = serviceFor("stack-4", "refs/heads/task-C"); //$NON-NLS-1$ //$NON-NLS-2$

        try {
            service.invokeEnforceLease(newProjectProxy("Polygon"), null, "C:\\db\\task-C"); //$NON-NLS-1$ //$NON-NLS-2$
            fail("expected EDT_LEASE_HELD for a branch leased by another stack"); //$NON-NLS-1$
        } catch (EdtToolException expected) {
            assertEquals("REGRESSION: a bind into a branch leased by another stack must surface " //$NON-NLS-1$
                    + "the typed EDT_LEASE_HELD — anything else sends the agent into a generic " //$NON-NLS-1$
                    + "retry loop against an infobase someone else is working", //$NON-NLS-1$
                    EdtToolErrorCode.EDT_LEASE_HELD, expected.getCode());
            assertTrue("the error must name the holder", //$NON-NLS-1$
                    expected.getMessage().contains("stack-3")); //$NON-NLS-1$
        }
    }

    @Test
    public void foreignLeaseErrorCarriesStructuredHolderDetails() {
        new InfobaseLeaseGuard(dir, "stack-3", "C:\\stacks\\stack-3\\workspace") //$NON-NLS-1$ //$NON-NLS-2$
                .checkOrAcquire("task-C", "C:\\db\\task-C", //$NON-NLS-1$ //$NON-NLS-2$
                        "File=\"C:\\db\\task-C\";", null); //$NON-NLS-1$
        TestableConnectService service = serviceFor("stack-4", "refs/heads/task-C"); //$NON-NLS-1$ //$NON-NLS-2$
        try {
            service.invokeEnforceLease(newProjectProxy("Polygon"), null, "C:\\db\\task-C"); //$NON-NLS-1$ //$NON-NLS-2$
            fail("expected EDT_LEASE_HELD"); //$NON-NLS-1$
        } catch (EdtToolException e) {
            assertEquals("REGRESSION: the escalation command is built from holder.stack_id — the " //$NON-NLS-1$
                    + "connect/update lease error must carry the holder as machine-readable details, " //$NON-NLS-1$
                    + "not only in the message prose", //$NON-NLS-1$
                    "stack-3", e.getDetails().get("holder_stack_id")); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals("task-C", e.getDetails().get("branch")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    @Test
    public void freeLeaseIsAutoTakenByTheBind() {
        TestableConnectService service = serviceFor("stack-4", "refs/heads/task-D"); //$NON-NLS-1$ //$NON-NLS-2$

        service.invokeEnforceLease(newProjectProxy("Polygon"), null, "C:\\db\\task-D"); //$NON-NLS-1$ //$NON-NLS-2$

        InfobaseLeaseGuard reader = new InfobaseLeaseGuard(dir, "stack-4", null); //$NON-NLS-1$
        assertTrue("the bind itself must claim the lease (auto-take)", //$NON-NLS-1$
                reader.store().find("task-D").orElseThrow().isHeldBy("stack-4")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void ownLeaseAllowsReconnect() {
        InfobaseLeaseGuard stack4 = new InfobaseLeaseGuard(dir, "stack-4", null); //$NON-NLS-1$
        stack4.checkOrAcquire("task-C", null, null, null); //$NON-NLS-1$
        TestableConnectService service = serviceFor("stack-4", "refs/heads/task-C"); //$NON-NLS-1$ //$NON-NLS-2$

        service.invokeEnforceLease(newProjectProxy("Polygon"), null, "C:\\db\\task-C"); //$NON-NLS-1$ //$NON-NLS-2$
        // no exception — reconnect of the lease holder is the normal idempotent path
    }

    @Test
    public void nonBranchContextWithoutIdentitySkipsEnforcement() {
        InfobaseLeaseGuard stack3 = new InfobaseLeaseGuard(dir, "stack-3", null); //$NON-NLS-1$
        stack3.checkOrAcquire("task-C", null, null, null); //$NON-NLS-1$
        // Detached HEAD (commit hash, not refs/heads/...) AND no resolvable reference identity:
        // nothing identifies a resource, so the guard stays out. With a real reference the
        // infobase identity would key the lease regardless of the missing branch (guard test).
        TestableConnectService service = serviceFor("stack-4", "4dbf89f0aa3c5d2e"); //$NON-NLS-1$ //$NON-NLS-2$

        service.invokeEnforceLease(newProjectProxy("Polygon"), null, "C:\\db\\task-C"); //$NON-NLS-1$ //$NON-NLS-2$
        // no exception — neither a branch nor an infobase identity was available
    }

    @Test
    public void unconfiguredGuardChangesNothing() throws IOException {
        TestableConnectService service = new TestableConnectService(
                new ContextStubGateway("refs/heads/task-C"), //$NON-NLS-1$
                new InfobaseLeaseGuard(null, "stack-4", null)); //$NON-NLS-1$

        service.invokeEnforceLease(newProjectProxy("Polygon"), null, "C:\\db\\task-C"); //$NON-NLS-1$ //$NON-NLS-2$

        try (Stream<Path> files = Files.list(dir)) {
            assertEquals("an unconfigured guard must not write any lease file", //$NON-NLS-1$
                    0L, files.count());
        }
    }

    // ---- support -------------------------------------------------------------------------------

    private TestableConnectService serviceFor(String stackId, String contextValue) {
        return new TestableConnectService(new ContextStubGateway(contextValue),
                new InfobaseLeaseGuard(dir, stackId, null));
    }

    /** Exposes the protected lease gate. */
    private static final class TestableConnectService extends EdtInfobaseConnectService {
        TestableConnectService(EdtRuntimeGateway gateway, InfobaseLeaseGuard guard) {
            super(gateway, guard);
        }

        void invokeEnforceLease(IProject project,
                com._1c.g5.v8.dt.platform.services.model.InfobaseReference reference, String path) {
            enforceLease(project, reference, path);
        }
    }

    /** Gateway whose context provider reports a fixed association-context value. */
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
