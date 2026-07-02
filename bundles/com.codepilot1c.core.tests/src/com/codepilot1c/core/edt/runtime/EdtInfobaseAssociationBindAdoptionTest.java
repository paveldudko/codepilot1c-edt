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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;
import org.junit.Test;

import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociation;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseReferences;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.services.model.Section;
import com.codepilot1c.core.edt.runtime.lease.InfobaseLeaseGuard;

/**
 * Regression test for the manage_associations bind failure found live on the stack polygon
 * (scenario 4, 2026-07-02): binding a legacy {@code ID=null} registry row into a fresh branch
 * context succeeded at {@code associate()} but {@code setDefaultInfobase} then threw
 * "Association does not contain infobase ..." — EDT resolves the reference against the
 * association's OWN entry (which carries the UUID assigned at persist time), not the registry
 * row. The fix adopts the association's matching entry (canonical identity compare) before the
 * setDefault call — the ctx-parameterized twin of {@code adoptExistingAssociationName}.
 */
public class EdtInfobaseAssociationBindAdoptionTest {

    private static final String IB_PATH = "C:\\stacks\\db\\Branches\\task-D"; //$NON-NLS-1$

    @Test
    public void bindAdoptsTheAssociationEntryForSetDefault() {
        // Registry row: legacy ID=null (no UUID) — the exact live shape.
        InfobaseReference row = InfobaseReferences.newFileInfobaseReference(IB_PATH);
        row.setName("polygon-task-D"); //$NON-NLS-1$
        // Association entry: same connection, but carrying the UUID EDT assigned on persist.
        InfobaseReference entry = InfobaseReferences.newFileInfobaseReference(IB_PATH);
        entry.setName("polygon-task-D"); //$NON-NLS-1$
        entry.setUuid(UUID.randomUUID());

        StrictAssociationManager manager = new StrictAssociationManager(entry);
        TestableAssociationService service = new TestableAssociationService(
                new StubGateway(manager.proxy, row));

        EdtInfobaseAssociationService.BindOutcome outcome =
                service.bind("Polygon", "task-E", null, IB_PATH, true); //$NON-NLS-1$ //$NON-NLS-2$

        assertSame("REGRESSION: setDefaultInfobase must be given the ASSOCIATION's own entry, " //$NON-NLS-1$
                + "not the registry row — EDT resolves against the association entry, and a " //$NON-NLS-1$
                + "legacy ID=null registry row can never match it (live polygon finding).", //$NON-NLS-1$
                entry, manager.lastSetDefaultRef.get());
        assertEquals("the outcome must report the adopted entry's UUID", //$NON-NLS-1$
                entry.getUuid().toString(), outcome.uuid());
        assertTrue("setDefault must have been attempted", manager.setDefaultCalls.get() >= 1); //$NON-NLS-1$
    }

    /**
     * The non-current-context quirk (bytecode-verified live finding): EDT's 3-arg
     * {@code setDefaultInfobase} validates against the CURRENT context's association only, so for
     * a target branch that is not checked out it always throws — even for the association's own
     * entry. The service must then write the DefaultInfobase property through the manager's own
     * {@code storeProperty} (reflective workaround) instead of failing the bind.
     */
    @Test
    public void bindFallsBackToStorePropertyWhenOfficialSetDefaultValidatesCurrentContextOnly() {
        InfobaseReference row = InfobaseReferences.newFileInfobaseReference(IB_PATH);
        row.setName("polygon-task-D"); //$NON-NLS-1$
        InfobaseReference entry = InfobaseReferences.newFileInfobaseReference(IB_PATH);
        entry.setName("polygon-task-D"); //$NON-NLS-1$
        entry.setUuid(UUID.randomUUID());

        CurrentContextOnlyManager manager = new CurrentContextOnlyManager(entry);
        TestableAssociationService service = new TestableAssociationService(
                new StubGateway(manager, row));

        EdtInfobaseAssociationService.BindOutcome outcome =
                service.bind("Polygon", "task-E", null, IB_PATH, true); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("REGRESSION: when the official setDefaultInfobase refuses (it validates " //$NON-NLS-1$
                + "against the CURRENT context only — EDT 2025.2.x bytecode), the DefaultInfobase " //$NON-NLS-1$
                + "property must be written via the manager's own storeProperty for the TARGET " //$NON-NLS-1$
                + "context, not fail the bind.", //$NON-NLS-1$
                "DefaultInfobase=" + entry.getUuid() + "@refs/heads/task-E", //$NON-NLS-1$ //$NON-NLS-2$
                manager.storedProperty);
        assertEquals(entry.getUuid().toString(), outcome.uuid());
    }

    // ---- support -------------------------------------------------------------------------------

    /**
     * Fake manager mirroring the live quirk: {@code setDefaultInfobase} ALWAYS throws (as it does
     * for any non-current target context), the ctx-parameterized read returns the association
     * with {@code entry}, and the private {@code storeProperty} — the method the workaround must
     * find reflectively — records what was written.
     */
    public static final class CurrentContextOnlyManager implements IInfobaseAssociationManager {
        private final InfobaseReference entry;
        String storedProperty;

        CurrentContextOnlyManager(InfobaseReference entry) {
            this.entry = entry;
        }

        @Override
        public Optional<IInfobaseAssociation> getAssociation(IProject project) {
            return Optional.empty(); // the CURRENT context has nothing — the quirk's precondition
        }

        @Override
        public Optional<IInfobaseAssociation> getAssociation(IProject project,
                com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAssociationContext ctx) {
            return Optional.of(newAssociationProxy(List.of(entry)));
        }

        @Override
        public Optional<IInfobaseAssociation> getAssociation(InfobaseReference infobase) {
            return Optional.empty();
        }

        @Override
        public Optional<IInfobaseAssociation> getAssociation(InfobaseReference infobase,
                com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAssociationContext ctx) {
            return Optional.empty();
        }

        @Override
        public java.util.Collection<com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAssociationContext>
                getAssociationContexts(IProject project) {
            return List.of();
        }

        @Override
        public void associate(IProject project, InfobaseReference infobase,
                com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAssociationSettings settings) {
            // accepted silently
        }

        @Override
        public void dissociate(IProject project, InfobaseReference infobase,
                com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAssociationContext ctx) {
            // not used here
        }

        @Override
        public void setDefaultInfobase(IProject project, InfobaseReference infobase,
                com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAssociationContext ctx) {
            // Live shape: validates against getAssociation(project) — CURRENT context — and throws.
            throw new IllegalArgumentException(
                    "Association does not contain infobase " + infobase.getName()); //$NON-NLS-1$
        }

        @Override
        public void addInfobaseAssociationListener(
                com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationListener listener) {
            // not used here
        }

        @Override
        public void removeInfobaseAssociationListener(
                com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationListener listener) {
            // not used here
        }

        @SuppressWarnings("unused") // found and invoked reflectively by the workaround under test
        private void storeProperty(IProject project, String key, String value,
                com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAssociationContext ctx) {
            storedProperty = key + "=" + value + "@" + ctx.getContext().orElse(""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }

        @Override
        public void activate() {
            // IManagedService — not used here
        }

        @Override
        public void deactivate() {
            // IManagedService — not used here
        }
    }

    private static final class TestableAssociationService extends EdtInfobaseAssociationService {
        TestableAssociationService(EdtRuntimeGateway gateway) {
            super(gateway, new InfobaseLeaseGuard(null, "test-stack", null)); //$NON-NLS-1$
        }

        @Override
        protected IProject resolveProject(String projectName) {
            return newProjectProxy(projectName);
        }

        @Override
        protected void settleBeforeSetDefaultRetry() {
            // no pause in unit tests
        }
    }

    private static final class StubGateway extends EdtRuntimeGateway {
        private final IInfobaseAssociationManager associationManager;
        private final InfobaseReference registryRow;

        StubGateway(IInfobaseAssociationManager associationManager, InfobaseReference registryRow) {
            this.associationManager = associationManager;
            this.registryRow = registryRow;
        }

        @Override
        public IInfobaseAssociationManager getInfobaseAssociationManager() {
            return associationManager;
        }

        @Override
        public IInfobaseManager getInfobaseManager() {
            return (IInfobaseManager) Proxy.newProxyInstance(
                    IInfobaseManager.class.getClassLoader(),
                    new Class<?>[] { IInfobaseManager.class },
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getAll" -> List.<Section> of(registryRow); //$NON-NLS-1$
                        case "isPersistenceSupported" -> Boolean.TRUE; //$NON-NLS-1$
                        default -> defaultReturn(method);
                    });
        }
    }

    /**
     * Association manager mimicking the live EDT behavior: the association contains {@code entry},
     * and {@code setDefaultInfobase} REJECTS any reference that is not that exact entry.
     */
    private static final class StrictAssociationManager implements InvocationHandler {
        final AtomicInteger setDefaultCalls = new AtomicInteger();
        final AtomicReference<InfobaseReference> lastSetDefaultRef = new AtomicReference<>();
        private final InfobaseReference entry;

        private final IInfobaseAssociationManager proxy =
                (IInfobaseAssociationManager) Proxy.newProxyInstance(
                        IInfobaseAssociationManager.class.getClassLoader(),
                        new Class<?>[] { IInfobaseAssociationManager.class }, this);

        StrictAssociationManager(InfobaseReference entry) {
            this.entry = entry;
        }

        @Override
        public Object invoke(Object p, Method method, Object[] args) {
            switch (method.getName()) {
                case "associate": //$NON-NLS-1$
                    return null;
                case "getAssociation": //$NON-NLS-1$
                    return Optional.of(newAssociationProxy(List.of(entry)));
                case "setDefaultInfobase": { //$NON-NLS-1$
                    setDefaultCalls.incrementAndGet();
                    InfobaseReference ref = (InfobaseReference) args[1];
                    lastSetDefaultRef.set(ref);
                    if (ref != entry) {
                        // The exact live exception shape (plain IllegalArgumentException).
                        throw new IllegalArgumentException(
                                "Association does not contain infobase " + ref.getName()); //$NON-NLS-1$
                    }
                    return null;
                }
                default:
                    return defaultReturn(method);
            }
        }
    }

    private static IInfobaseAssociation newAssociationProxy(List<InfobaseReference> refs) {
        return (IInfobaseAssociation) Proxy.newProxyInstance(
                IInfobaseAssociation.class.getClassLoader(),
                new Class<?>[] { IInfobaseAssociation.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "getInfobases" -> refs; //$NON-NLS-1$
                    case "getDefaultInfobase" -> null; //$NON-NLS-1$
                    default -> defaultReturn(method);
                });
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
        if (ret == Optional.class) {
            return Optional.empty();
        }
        return null;
    }
}
