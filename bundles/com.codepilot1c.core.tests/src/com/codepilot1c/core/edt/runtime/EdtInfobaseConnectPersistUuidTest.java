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
 * Regression tests for the "ID=null UUID churn" defect (stack polygon live findings, 2026-07-02).
 *
 * <p>Live-observed on EDT 2025.2.x: {@code IInfobaseManager.add()} does not populate the row's
 * UUID — the {@code ibases.v8i} row is written with {@code ID=null}. The historical code assigned
 * a UUID to the in-memory reference only AFTER {@code add()}, so the registry kept {@code ID=null}
 * forever and every connect minted yet another UUID for the same infobase (three different UUIDs
 * observed across two workspaces and a restart); association-UUID resolution then failed. The row
 * also cannot be repaired by direct mutation afterwards — the registry model is transactional
 * ("Cannot modify resource set without a write transaction", live-observed).</p>
 *
 * <p>The fix: assign the UUID BEFORE {@code add()} so the row is stored with a resolvable ID;
 * repair legacy null-UUID rows through the manager API (delete + add); match rows canonically
 * (EDT normalizes stored connection strings); treat the registry row's UUID as the identity
 * authority.</p>
 */
public class EdtInfobaseConnectPersistUuidTest {

    /** Fresh registry: the reference must carry a non-null UUID already AT {@code add()} time. */
    @Test
    public void uuidAssignedBeforeAdd() {
        RecordingInfobaseManager manager = new RecordingInfobaseManager(List.of());
        TestableConnectService service = new TestableConnectService(new StubGateway(manager.proxy));
        InfobaseReference reference = newFileReference("polygon-fresh"); //$NON-NLS-1$

        service.invokePersistReference(reference, false);

        assertSame("the added row is the reference itself", reference, manager.added.get()); //$NON-NLS-1$
        assertNotNull("REGRESSION: the UUID must be assigned BEFORE add() — EDT stores the row " //$NON-NLS-1$
                + "as-is (ID=null otherwise) and the transactional registry model forbids " //$NON-NLS-1$
                + "repairing it by direct mutation afterwards.", //$NON-NLS-1$
                manager.addedUuid.get());
        assertEquals("the reference must keep the UUID that was stored", //$NON-NLS-1$
                manager.addedUuid.get(), reference.getUuid());
    }

    /** Legacy registry row with a null UUID is repaired through the manager API: delete + add. */
    @Test
    public void nullUuidOnExistingRowIsRepairedViaDeleteAndAdd() {
        InfobaseReference row = newFileReference("polygon-existing"); //$NON-NLS-1$
        RecordingInfobaseManager manager = new RecordingInfobaseManager(List.of(row));
        TestableConnectService service = new TestableConnectService(new StubGateway(manager.proxy));
        // Same path + same name -> findExistingByIdentity matches the registered row.
        InfobaseReference reference = newFileReference("polygon-existing"); //$NON-NLS-1$

        service.invokePersistReference(reference, false);

        assertNotNull("the reference must carry a usable UUID", reference.getUuid()); //$NON-NLS-1$
        assertSame("REGRESSION: the stale null-UUID row must be deleted through the manager API " //$NON-NLS-1$
                + "(direct mutation is forbidden by the transactional registry model)", //$NON-NLS-1$
                row, manager.deleted.get());
        assertSame("the replacement row must be our reference with the minted UUID", //$NON-NLS-1$
                reference, manager.added.get());
        assertNotNull("the replacement must be stored with a non-null UUID", manager.addedUuid.get()); //$NON-NLS-1$
    }

    /**
     * The stored connection string may differ cosmetically (drive-letter case, trailing
     * separator) from the freshly built one — EDT normalizes on store. Identity matching must be
     * canonical, otherwise a retry hits NAME_COLLISION on the row this very call just added
     * (live-observed, stack polygon 2026-07-02).
     */
    @Test
    public void existingRowMatchedDespiteCosmeticPathDifferences() {
        InfobaseReference row =
                InfobaseReferences.newFileInfobaseReference("C:\\polygon\\db\\polygon-cosmetic\\"); //$NON-NLS-1$
        row.setName("polygon-cosmetic"); //$NON-NLS-1$
        UUID registered = UUID.randomUUID();
        row.setUuid(registered);
        RecordingInfobaseManager manager = new RecordingInfobaseManager(List.of(row));
        TestableConnectService service = new TestableConnectService(new StubGateway(manager.proxy));
        InfobaseReference reference =
                InfobaseReferences.newFileInfobaseReference("c:\\polygon\\db\\polygon-cosmetic"); //$NON-NLS-1$
        reference.setName("polygon-cosmetic"); //$NON-NLS-1$

        service.invokePersistReference(reference, false);

        assertNull("REGRESSION: a cosmetic path difference must not be treated as a different " //$NON-NLS-1$
                + "infobase (no re-add, no NAME_COLLISION).", manager.added.get()); //$NON-NLS-1$
        assertEquals("the registered UUID must be adopted", registered, reference.getUuid()); //$NON-NLS-1$
    }

    /** A registry row's UUID outranks one the caller minted locally (e.g. on a retry). */
    @Test
    public void registryUuidWinsOverLocallyMintedOne() {
        InfobaseReference row = newFileReference("polygon-authority"); //$NON-NLS-1$
        UUID registered = UUID.randomUUID();
        row.setUuid(registered);
        RecordingInfobaseManager manager = new RecordingInfobaseManager(List.of(row));
        TestableConnectService service = new TestableConnectService(new StubGateway(manager.proxy));
        InfobaseReference reference = newFileReference("polygon-authority"); //$NON-NLS-1$
        reference.setUuid(UUID.randomUUID()); // minted by an earlier failed attempt

        service.invokePersistReference(reference, false);

        assertEquals("REGRESSION: the registry row's UUID must win over a locally minted one — " //$NON-NLS-1$
                + "associations must point at a UUID the registry can resolve.", //$NON-NLS-1$
                registered, reference.getUuid());
        assertNull("a healthy row must not be deleted", manager.deleted.get()); //$NON-NLS-1$
        assertNull("a healthy row must not be re-added", manager.added.get()); //$NON-NLS-1$
    }

    /** A row that already has a UUID is reused as-is: no delete, no add, no churn. */
    @Test
    public void existingUuidIsAdoptedWithoutRepair() {
        InfobaseReference row = newFileReference("polygon-stable"); //$NON-NLS-1$
        UUID stable = UUID.randomUUID();
        row.setUuid(stable);
        RecordingInfobaseManager manager = new RecordingInfobaseManager(List.of(row));
        TestableConnectService service = new TestableConnectService(new StubGateway(manager.proxy));
        InfobaseReference reference = newFileReference("polygon-stable"); //$NON-NLS-1$

        service.invokePersistReference(reference, false);

        assertEquals("the registered UUID must be adopted onto the reference", stable, reference.getUuid()); //$NON-NLS-1$
        assertNull("a healthy row must not be deleted", manager.deleted.get()); //$NON-NLS-1$
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
     * Registry stub modelling the live 2025.2.x behaviour: {@code add()} stores the row as-is and
     * never populates a missing UUID (the v8i row is written with {@code ID=null}). Records the
     * added/deleted rows and the added row's UUID visible AT call time.
     */
    private static final class RecordingInfobaseManager implements InvocationHandler {
        final AtomicReference<InfobaseReference> added = new AtomicReference<>();
        final AtomicReference<UUID> addedUuid = new AtomicReference<>();
        final AtomicReference<InfobaseReference> deleted = new AtomicReference<>();
        private final List<InfobaseReference> registry;

        private final IInfobaseManager proxy = (IInfobaseManager) Proxy.newProxyInstance(
                IInfobaseManager.class.getClassLoader(),
                new Class<?>[] { IInfobaseManager.class }, this);

        RecordingInfobaseManager(List<InfobaseReference> registry) {
            this.registry = new ArrayList<>(registry);
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
                        added.set(ref);
                        addedUuid.set(ref.getUuid()); // whatever the caller provided — never populated here
                        registry.add(ref);
                    }
                    return null;
                case "delete": //$NON-NLS-1$
                    if (args != null && args.length == 1 && args[0] instanceof InfobaseReference ref) {
                        deleted.set(ref);
                        registry.remove(ref);
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
