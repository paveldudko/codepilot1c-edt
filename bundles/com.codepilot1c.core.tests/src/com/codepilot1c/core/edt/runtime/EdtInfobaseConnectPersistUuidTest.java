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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseReferences;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.services.model.Section;

/**
 * Regression test for the "ID=null UUID churn" defect (stack polygon live finding, 2026-07-02).
 *
 * <p>Live-observed on EDT 2025.2.x: {@code IInfobaseManager.add()} wrote the registry row into
 * {@code ibases.v8i} with {@code ID=null}. {@code persistReference} then assigned a fresh UUID to
 * the in-memory reference only — the registry row kept {@code ID=null} forever, so EVERY
 * subsequent connect minted yet another UUID for the same infobase (three different UUIDs were
 * observed for one infobase across two workspaces and a restart), and per-branch associations in
 * different workspaces pointed at diverging identities.</p>
 *
 * <p>The fix writes the locally assigned UUID back through {@link IInfobaseManager#update} on all
 * three assignment paths: after {@code add()} left it null, when an existing row is found with a
 * null UUID, and on the force=true same-path reuse of a null-UUID candidate.</p>
 */
public class EdtInfobaseConnectPersistUuidTest {

    /** Fresh registry: {@code add()} does not populate the UUID — the local one must be written back. */
    @Test
    public void uuidAssignedAfterAddIsPersistedBack() {
        RecordingInfobaseManager manager = new RecordingInfobaseManager(List.of());
        TestableConnectService service = new TestableConnectService(new StubGateway(manager.proxy));
        InfobaseReference reference = newFileReference("polygon-fresh"); //$NON-NLS-1$

        service.invokePersistReference(reference, false);

        assertNotNull("persistReference must leave a non-null UUID on the reference", reference.getUuid()); //$NON-NLS-1$
        assertSame("the added row is the reference itself", reference, manager.added.get()); //$NON-NLS-1$
        assertSame("REGRESSION: the UUID assigned after add() must be written back via " //$NON-NLS-1$
                + "IInfobaseManager.update(), otherwise the v8i row keeps ID=null and every " //$NON-NLS-1$
                + "connect mints a new identity for the same infobase.", //$NON-NLS-1$
                reference, manager.updated.get());
        assertEquals("update() must see the assigned UUID on the row", //$NON-NLS-1$
                reference.getUuid(), manager.updatedUuid.get());
    }

    /** Existing registry row with a null UUID: both objects get the same UUID and the row is updated. */
    @Test
    public void nullUuidOnExistingRowIsRepairedAndPersisted() {
        InfobaseReference row = newFileReference("polygon-existing"); //$NON-NLS-1$
        RecordingInfobaseManager manager = new RecordingInfobaseManager(List.of(row));
        TestableConnectService service = new TestableConnectService(new StubGateway(manager.proxy));
        // Same path + same name -> findExistingByIdentity matches the registered row.
        InfobaseReference reference = newFileReference("polygon-existing"); //$NON-NLS-1$

        service.invokePersistReference(reference, false);

        assertNull("idempotent reuse must not re-add the row", manager.added.get()); //$NON-NLS-1$
        assertNotNull("the reference must carry a usable UUID", reference.getUuid()); //$NON-NLS-1$
        assertEquals("REGRESSION: the registry row must adopt the SAME UUID as the reference — " //$NON-NLS-1$
                + "otherwise each workspace invents its own identity for one physical infobase.", //$NON-NLS-1$
                reference.getUuid(), row.getUuid());
        assertSame("the repaired row must be persisted via update()", row, manager.updated.get()); //$NON-NLS-1$
    }

    /** A row that already has a UUID is reused as-is: no update, no churn. */
    @Test
    public void existingUuidIsAdoptedWithoutUpdate() {
        InfobaseReference row = newFileReference("polygon-stable"); //$NON-NLS-1$
        UUID stable = UUID.randomUUID();
        row.setUuid(stable);
        RecordingInfobaseManager manager = new RecordingInfobaseManager(List.of(row));
        TestableConnectService service = new TestableConnectService(new StubGateway(manager.proxy));
        InfobaseReference reference = newFileReference("polygon-stable"); //$NON-NLS-1$

        service.invokePersistReference(reference, false);

        assertEquals("the registered UUID must be adopted onto the reference", stable, reference.getUuid()); //$NON-NLS-1$
        assertNull("a healthy row must not be rewritten", manager.updated.get()); //$NON-NLS-1$
        assertNull("a healthy row must not be re-added", manager.added.get()); //$NON-NLS-1$
    }

    // ---- support -------------------------------------------------------------------------------

    private static InfobaseReference newFileReference(String name) {
        InfobaseReference reference =
                InfobaseReferences.newFileInfobaseReference("C:\\polygon\\db\\" + name); //$NON-NLS-1$
        reference.setName(name);
        return reference;
    }

    /** Exposes the protected {@code persistReference} for direct invocation by the test. */
    private static final class TestableConnectService extends EdtInfobaseConnectService {
        TestableConnectService(EdtRuntimeGateway gateway) {
            super(gateway);
        }

        void invokePersistReference(InfobaseReference reference, boolean force) {
            persistReference(reference, force);
        }
    }

    private static final class StubGateway extends EdtRuntimeGateway {
        private final IInfobaseManager manager;

        StubGateway(IInfobaseManager manager) {
            this.manager = manager;
        }

        @Override
        public IInfobaseManager getInfobaseManager() {
            return manager;
        }
    }

    /**
     * Registry stub modelling the live 2025.2.x behaviour: {@code add()} accepts the row but never
     * populates its UUID (the v8i row is written with {@code ID=null}). Records what was added and
     * what was passed to {@code update()} (with the UUID visible at call time).
     */
    private static final class RecordingInfobaseManager implements InvocationHandler {
        final AtomicReference<InfobaseReference> added = new AtomicReference<>();
        final AtomicReference<InfobaseReference> updated = new AtomicReference<>();
        final AtomicReference<UUID> updatedUuid = new AtomicReference<>();
        private final List<InfobaseReference> registry;

        private final IInfobaseManager proxy = (IInfobaseManager) Proxy.newProxyInstance(
                IInfobaseManager.class.getClassLoader(),
                new Class<?>[] { IInfobaseManager.class }, this);

        RecordingInfobaseManager(List<InfobaseReference> registry) {
            this.registry = registry;
        }

        @Override
        public Object invoke(Object p, Method method, Object[] args) {
            switch (method.getName()) {
                case "isPersistenceSupported": //$NON-NLS-1$
                    return Boolean.TRUE;
                case "findInfobaseByUuid": //$NON-NLS-1$
                case "findInfobaseByName": //$NON-NLS-1$
                    return Optional.empty(); // model the live "targeted lookups miss" behaviour
                case "findInfobasesByNames": //$NON-NLS-1$
                    return List.of();
                case "getAll": //$NON-NLS-1$
                    return new ArrayList<Section>(registry); // the getAll() sweep is what finds rows
                case "add": //$NON-NLS-1$
                    if (args != null && args.length == 2 && args[0] instanceof InfobaseReference ref) {
                        added.set(ref); // deliberately do NOT assign a UUID (ID=null in v8i)
                    }
                    return null;
                case "update": //$NON-NLS-1$
                    if (args != null && args.length == 1 && args[0] instanceof InfobaseReference ref) {
                        updated.set(ref);
                        updatedUuid.set(ref.getUuid());
                    }
                    return null;
                default:
                    return defaultReturn(method);
            }
        }
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
