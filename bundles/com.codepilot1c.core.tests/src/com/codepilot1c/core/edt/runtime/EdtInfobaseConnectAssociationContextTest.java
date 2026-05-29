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

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;
import org.junit.Test;

import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationContextProvider;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAssociationContext;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAssociationSettings;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;

/**
 * Regression test for the {@code connect_infobase} "success but association not persisted" bug
 * (feedback {@code 2026-05-29-connect-infobase-success-not-persisted.md}).
 *
 * <p>EDT stores a project's infobase association under an {@link InfobaseAssociationContext} and
 * the no-arg {@code IInfobaseAssociationManager.getAssociation(project)} read path — used by
 * {@code setDefaultInfobase} and the {@code update_infobase} diagnostic — resolves that context via
 * {@link IInfobaseAssociationContextProvider}. The previous code wrote the binding with
 * {@code InfobaseAssociationSettings.alreadySynchronized()} and
 * {@code InfobaseAssociationContext.empty()} unconditionally. On workspaces where an
 * association-context extension is active (remote/SSH projects), the provider returns a non-empty
 * context, so the write landed in a different partition than the read: {@code associate()} reported
 * success, yet {@code setDefaultInfobase} threw "Project ... is not associated with infobase ..."
 * and {@code update_infobase} returned {@code INFOBASE_ASSOCIATION_NOT_FOUND}.</p>
 *
 * <p>The fix resolves the project's effective context via the provider and threads it into both
 * {@code associate()} and {@code setDefaultInfobase()}. This test drives {@code associate()} with a
 * provider returning a non-empty context and asserts the binding is written under that context, not
 * {@code empty()}.</p>
 */
public class EdtInfobaseConnectAssociationContextTest {

    private static final InfobaseAssociationContext REMOTE_CONTEXT =
            InfobaseAssociationContext.of("remote-ssh-context"); //$NON-NLS-1$

    /**
     * When the association-context provider returns a non-empty context, {@code associate()} must
     * persist the binding under that context (so EDT's no-arg {@code getAssociation(project)} reads
     * it back) — and pass the same context to {@code setDefaultInfobase}.
     */
    @Test
    public void associateWritesUnderProviderContextWhenSetPrimary() {
        RecordingAssociationManager manager = new RecordingAssociationManager();
        TestableConnectService service = new TestableConnectService(
                new StubGateway(manager.proxy, newProviderProxy(REMOTE_CONTEXT)));
        IProject project = newProjectProxy("Accounting management"); //$NON-NLS-1$
        // associate() treats the reference opaquely (it only forwards it to the stubbed manager and
        // never dereferences it), so a null reference exercises the context-threading path fully
        // without dragging in EDT's EMF ModelFactory.
        InfobaseReference reference = null;

        boolean primary = service.invokeAssociate(project, reference, true);

        assertEquals("set_primary=true must report primary", Boolean.TRUE, Boolean.valueOf(primary)); //$NON-NLS-1$

        InfobaseAssociationSettings settings = manager.associateSettings.get();
        assertNotNull("associate() must have been invoked", settings); //$NON-NLS-1$
        assertEquals("REGRESSION: associate() must write under the project's effective association " //$NON-NLS-1$
                + "context (from IInfobaseAssociationContextProvider), not the empty context — " //$NON-NLS-1$
                + "otherwise the binding is invisible to getAssociation(project)/update_infobase.", //$NON-NLS-1$
                REMOTE_CONTEXT, settings.getContext());

        assertEquals("setDefaultInfobase must receive the same effective context as associate()", //$NON-NLS-1$
                REMOTE_CONTEXT, manager.setDefaultContext.get());
    }

    /**
     * With no association-context extension contributed the provider is absent; the resolver must
     * fall back to {@link InfobaseAssociationContext#empty()}, preserving historical behaviour and
     * skipping the {@code setDefaultInfobase} call when {@code set_primary=false}.
     */
    @Test
    public void associateFallsBackToEmptyContextWhenProviderAbsent() {
        RecordingAssociationManager manager = new RecordingAssociationManager();
        TestableConnectService service = new TestableConnectService(
                new StubGateway(manager.proxy, null)); // no provider registered
        IProject project = newProjectProxy("Demo"); //$NON-NLS-1$
        InfobaseReference reference = null; // opaque to associate(); see the set_primary=true test

        boolean primary = service.invokeAssociate(project, reference, false);

        assertEquals("set_primary=false must report not-primary", Boolean.FALSE, Boolean.valueOf(primary)); //$NON-NLS-1$

        InfobaseAssociationSettings settings = manager.associateSettings.get();
        assertNotNull("associate() must have been invoked", settings); //$NON-NLS-1$
        assertEquals("absent provider must fall back to the empty context", //$NON-NLS-1$
                InfobaseAssociationContext.empty(), settings.getContext());
        assertNull("set_primary=false must not call setDefaultInfobase", manager.setDefaultContext.get()); //$NON-NLS-1$
    }

    // ---- support -----------------------------------------------------------------------------

    /** Exposes the protected {@code associate} for direct invocation by the test. */
    private static final class TestableConnectService extends EdtInfobaseConnectService {
        TestableConnectService(EdtRuntimeGateway gateway) {
            super(gateway);
        }

        boolean invokeAssociate(IProject project, InfobaseReference reference, boolean setPrimary) {
            return associate(project, reference, setPrimary);
        }
    }

    /** Gateway returning the recording association manager and the (possibly null) context provider. */
    private static final class StubGateway extends EdtRuntimeGateway {
        private final IInfobaseAssociationManager associationManager;
        private final IInfobaseAssociationContextProvider contextProvider;

        StubGateway(IInfobaseAssociationManager associationManager,
                IInfobaseAssociationContextProvider contextProvider) {
            this.associationManager = associationManager;
            this.contextProvider = contextProvider;
        }

        @Override
        public IInfobaseAssociationManager getInfobaseAssociationManager() {
            return associationManager;
        }

        @Override
        public IInfobaseAssociationContextProvider peekInfobaseAssociationContextProvider() {
            return contextProvider;
        }
    }

    /** Captures the settings handed to {@code associate} and the context handed to {@code setDefaultInfobase}. */
    private static final class RecordingAssociationManager implements InvocationHandler {
        final AtomicReference<InfobaseAssociationSettings> associateSettings = new AtomicReference<>();
        final AtomicReference<InfobaseAssociationContext> setDefaultContext = new AtomicReference<>();

        private final IInfobaseAssociationManager proxy = (IInfobaseAssociationManager) Proxy.newProxyInstance(
                IInfobaseAssociationManager.class.getClassLoader(),
                new Class<?>[] { IInfobaseAssociationManager.class }, this);

        @Override
        public Object invoke(Object p, Method method, Object[] args) {
            switch (method.getName()) {
                case "associate": //$NON-NLS-1$
                    associateSettings.set((InfobaseAssociationSettings) args[2]);
                    return null;
                case "setDefaultInfobase": //$NON-NLS-1$
                    setDefaultContext.set((InfobaseAssociationContext) args[2]);
                    return null;
                default:
                    return defaultReturn(method);
            }
        }
    }

    private static IInfobaseAssociationContextProvider newProviderProxy(InfobaseAssociationContext context) {
        return (IInfobaseAssociationContextProvider) Proxy.newProxyInstance(
                IInfobaseAssociationContextProvider.class.getClassLoader(),
                new Class<?>[] { IInfobaseAssociationContextProvider.class },
                (proxy, method, args) -> "get".equals(method.getName()) ? context : defaultReturn(method)); //$NON-NLS-1$
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
        if (ret == java.util.Optional.class) {
            return java.util.Optional.empty();
        }
        if (ret.isPrimitive()) {
            return Integer.valueOf(0);
        }
        return null;
    }
}
