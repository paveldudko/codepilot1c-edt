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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;

/**
 * Regression coverage for {@code EdtRuntimeService.handleUpdateCallback}, the dynamic-proxy
 * handler behind the headless {@code IInfobaseUpdateCallback} used by {@code update_infobase}.
 *
 * <p>EDT 2025.2.x invokes {@code IInfobaseChangesResolver.resolveInfobaseChanges} on a config
 * conflict. The previous handler only matched the legacy {@code onInfobaseChanges} name and
 * returned {@code null} otherwise; EDT then called {@code InfobaseConflictResolutionResult.ordinal()}
 * on that null and threw an NPE on every conflict. These tests pin the post-fix behaviour without
 * a live EDT runtime by driving the private static handler through reflection with synthetic
 * {@link Method} shapes that mirror the platform callback interface.
 */
public class EdtUpdateCallbackResolutionTest {

    /** Mirrors {@code InfobaseConflictResolutionResult} on EDT 2025.2.3. */
    enum Resolution {
        DEFERRED, IMPORTED, OVERRIDDEN, IGNORED
    }

    /** An older/other API enum lacking {@code OVERRIDDEN}, to exercise the DEFERRED fallback. */
    enum LegacyResolution {
        DEFERRED, IGNORED
    }

    /** Method shapes mirroring {@code IInfobaseUpdateCallback} / {@code IInfobaseChangesResolver}. */
    interface CallbackShapes {
        Resolution resolveInfobaseChanges(Object a, Object b, Object c, Object d, Object e, Object f, Object g,
                Object h);

        Resolution onInfobaseChanges(Object a, Object b, Object c, Object d, Object e, Object f, Object g);

        boolean onConfirm(Object flow);

        int someUnmodelledPrimitiveCallback();
    }

    /** Same callback name, but an enum return type that does not declare OVERRIDDEN. */
    interface LegacyEnumCallback {
        LegacyResolution resolveInfobaseChanges(Object a);
    }

    private static Object invoke(Object proxy, Method method, Object[] args, AtomicBoolean flag) throws Exception {
        Method handler = EdtRuntimeService.class.getDeclaredMethod(
                "handleUpdateCallback", Object.class, Method.class, Object[].class, AtomicBoolean.class); //$NON-NLS-1$
        handler.setAccessible(true);
        return handler.invoke(null, proxy, method, args, flag);
    }

    private static Method shape(Class<?> iface, String name) throws Exception {
        for (Method m : iface.getDeclaredMethods()) {
            if (m.getName().equals(name)) {
                return m;
            }
        }
        throw new AssertionError("no method " + name + " on " + iface); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void resolveInfobaseChangesReturnsOverridden() throws Exception {
        Object result = invoke(new Object(), shape(CallbackShapes.class, "resolveInfobaseChanges"), //$NON-NLS-1$
                new Object[8], new AtomicBoolean());
        // The core regression: this must NOT be null (null -> EDT calls ordinal() -> NPE).
        assertNotNull("resolveInfobaseChanges must not return null", result); //$NON-NLS-1$
        assertSame(Resolution.OVERRIDDEN, result);
    }

    @Test
    public void onInfobaseChangesLegacyAliasReturnsOverridden() throws Exception {
        Object result = invoke(new Object(), shape(CallbackShapes.class, "onInfobaseChanges"), //$NON-NLS-1$
                new Object[7], new AtomicBoolean());
        assertSame(Resolution.OVERRIDDEN, result);
    }

    @Test
    public void resolveInfobaseChangesFallsBackToDeferredWhenNoOverridden() throws Exception {
        Object result = invoke(new Object(), shape(LegacyEnumCallback.class, "resolveInfobaseChanges"), //$NON-NLS-1$
                new Object[1], new AtomicBoolean());
        assertSame(LegacyResolution.DEFERRED, result);
    }

    @Test
    public void onConfirmReturnsTrueAndRemembersExclusiveLock() throws Exception {
        AtomicBoolean exclusiveLockUnavailable = new AtomicBoolean(false);
        Object result = invoke(new Object(), shape(CallbackShapes.class, "onConfirm"), //$NON-NLS-1$
                new Object[1], exclusiveLockUnavailable);
        assertEquals(Boolean.TRUE, result);
        assertTrue("onConfirm must flag the exclusive-lock-unavailable case", //$NON-NLS-1$
                exclusiveLockUnavailable.get());
    }

    @Test
    public void unmodelledPrimitiveCallbackNeverReturnsNull() throws Exception {
        Object result = invoke(new Object(), shape(CallbackShapes.class, "someUnmodelledPrimitiveCallback"), //$NON-NLS-1$
                new Object[0], new AtomicBoolean());
        // A primitive-returning callback we do not model must coerce to the type default, not null.
        assertEquals(Integer.valueOf(0), result);
    }

    @Test
    public void objectMethodsAreHandled() throws Exception {
        Object proxy = new Object();
        Method equals = Object.class.getMethod("equals", Object.class); //$NON-NLS-1$
        Method hashCode = Object.class.getMethod("hashCode"); //$NON-NLS-1$
        Method toString = Object.class.getMethod("toString"); //$NON-NLS-1$

        assertEquals(Boolean.TRUE, invoke(proxy, equals, new Object[] { proxy }, new AtomicBoolean()));
        assertFalse((Boolean) invoke(proxy, equals, new Object[] { new Object() }, new AtomicBoolean()));
        assertEquals(Integer.valueOf(System.identityHashCode(proxy)),
                invoke(proxy, hashCode, new Object[0], new AtomicBoolean()));
        assertEquals("AutoUpdateCallbackProxy", invoke(proxy, toString, new Object[0], new AtomicBoolean())); //$NON-NLS-1$
    }
}
