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

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.core.resources.IProject;
import org.junit.Test;

import com._1c.g5.v8.dt.core.platform.IExtensionProject;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociation;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationManager;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;

/**
 * Regression test for feedback {@code 2026-05-29-update-infobase-extension-project-association-not-found}:
 * {@code update_infobase} for an <em>extension</em> project (e.g. {@code yaxunit}) failed with
 * {@code INFOBASE_ASSOCIATION_NOT_FOUND}, because extension projects publish into their base
 * configuration project's infobase and carry no association of their own — so
 * {@code IInfobaseAssociationManager.getAssociation(extensionProject)} returns empty.
 *
 * <p>The fix makes {@link EdtRuntimeService#resolveDefaultInfobase(String)} fall back to the parent
 * (base configuration) project's infobase when the project is an extension. This test drives that
 * path with stubbed collaborators: the extension's own association is empty, the parent's holds the
 * infobase, and asserts the parent's infobase is returned (with the parent consulted only after the
 * extension's own association came up empty).</p>
 */
public class EdtUpdateInfobaseExtensionParentTest {

    @Test
    public void resolvesExtensionInfobaseViaParentProject() {
        IProject extensionProject = newProjectProxy("yaxunit"); //$NON-NLS-1$
        IProject baseProject = newProjectProxy("Accounting management"); //$NON-NLS-1$
        InfobaseReference baseInfobase = newInfobaseProxy();

        RecordingAssociationManager associationManager = new RecordingAssociationManager(baseProject, baseInfobase);
        StubGateway gateway = new StubGateway(
                ProjectRegistry.of(extensionProject, baseProject),
                associationManager.proxy,
                newV8ProjectManagerProxy(extensionProject, baseProject));
        EdtRuntimeService service = new EdtRuntimeService(gateway);

        InfobaseReference resolved = service.resolveDefaultInfobase("yaxunit"); //$NON-NLS-1$

        assertSame("extension project must resolve to its parent's infobase", baseInfobase, resolved); //$NON-NLS-1$
        // The extension's own association must be consulted first (and come up empty), then the parent.
        assertEquals(List.of("yaxunit", "Accounting management"), //$NON-NLS-1$ //$NON-NLS-2$
                associationManager.queriedProjectNames);
    }

    // ---- stubs -------------------------------------------------------------------------------

    /** Tiny project-name -> IProject registry so the gateway can resolve both projects by name. */
    private static final class ProjectRegistry {
        private final IProject extension;
        private final IProject base;

        private ProjectRegistry(IProject extension, IProject base) {
            this.extension = extension;
            this.base = base;
        }

        static ProjectRegistry of(IProject extension, IProject base) {
            return new ProjectRegistry(extension, base);
        }

        IProject resolve(String name) {
            if (extension.getName().equals(name)) {
                return extension;
            }
            if (base.getName().equals(name)) {
                return base;
            }
            return null;
        }
    }

    private static final class StubGateway extends EdtRuntimeGateway {
        private final ProjectRegistry projects;
        private final IInfobaseAssociationManager associationManager;
        private final IV8ProjectManager v8ProjectManager;

        StubGateway(ProjectRegistry projects, IInfobaseAssociationManager associationManager,
                IV8ProjectManager v8ProjectManager) {
            this.projects = projects;
            this.associationManager = associationManager;
            this.v8ProjectManager = v8ProjectManager;
        }

        @Override
        public IProject resolveProject(String projectName) {
            return projects.resolve(projectName);
        }

        @Override
        public IInfobaseAssociationManager getInfobaseAssociationManager() {
            return associationManager;
        }

        @Override
        public IV8ProjectManager peekV8ProjectManager() {
            return v8ProjectManager;
        }

        @Override
        public com.e1c.g5.v8.dt.platform.standaloneserver.wst.core.IStandaloneServerService
                peekStandaloneServerService() {
            return null; // force the extension-parent fallback to be the resolving path
        }
    }

    /**
     * Records the projects passed to {@code getAssociation} and returns a populated association only
     * for the base project — the extension project gets {@link Optional#empty()}.
     */
    private static final class RecordingAssociationManager implements InvocationHandler {
        final List<String> queriedProjectNames = new CopyOnWriteArrayList<>();
        private final IProject baseProject;
        private final InfobaseReference baseInfobase;
        private final IInfobaseAssociationManager proxy = (IInfobaseAssociationManager) Proxy.newProxyInstance(
                IInfobaseAssociationManager.class.getClassLoader(),
                new Class<?>[] { IInfobaseAssociationManager.class }, this);

        RecordingAssociationManager(IProject baseProject, InfobaseReference baseInfobase) {
            this.baseProject = baseProject;
            this.baseInfobase = baseInfobase;
        }

        @Override
        public Object invoke(Object p, Method method, Object[] args) {
            if ("getAssociation".equals(method.getName()) //$NON-NLS-1$
                    && args != null && args.length >= 1 && args[0] instanceof IProject project) {
                queriedProjectNames.add(project.getName());
                if (project.equals(baseProject)) {
                    return Optional.of(newAssociation(baseProject, baseInfobase));
                }
                return Optional.empty();
            }
            return defaultReturn(method);
        }
    }

    private static IInfobaseAssociation newAssociation(IProject project, InfobaseReference infobase) {
        return (IInfobaseAssociation) Proxy.newProxyInstance(
                IInfobaseAssociation.class.getClassLoader(),
                new Class<?>[] { IInfobaseAssociation.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "getDefaultInfobase" -> infobase; //$NON-NLS-1$
                    case "getInfobases" -> Set.of(infobase); //$NON-NLS-1$
                    case "getProject" -> project; //$NON-NLS-1$
                    default -> defaultReturn(method);
                });
    }

    private static IV8ProjectManager newV8ProjectManagerProxy(IProject extensionProject, IProject baseProject) {
        return (IV8ProjectManager) Proxy.newProxyInstance(
                IV8ProjectManager.class.getClassLoader(),
                new Class<?>[] { IV8ProjectManager.class },
                (proxy, method, args) -> {
                    if ("getProject".equals(method.getName()) //$NON-NLS-1$
                            && args != null && args.length == 1 && args[0] instanceof IProject project) {
                        if (project.equals(extensionProject)) {
                            return newExtensionProjectProxy(extensionProject, baseProject);
                        }
                        return null; // base project is not an extension
                    }
                    return defaultReturn(method);
                });
    }

    private static IV8Project newExtensionProjectProxy(IProject extensionProject, IProject baseProject) {
        return (IExtensionProject) Proxy.newProxyInstance(
                IExtensionProject.class.getClassLoader(),
                new Class<?>[] { IExtensionProject.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "getParentProject" -> baseProject; //$NON-NLS-1$
                    case "getProject" -> extensionProject; //$NON-NLS-1$
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

    private static InfobaseReference newInfobaseProxy() {
        return (InfobaseReference) Proxy.newProxyInstance(
                InfobaseReference.class.getClassLoader(),
                new Class<?>[] { InfobaseReference.class },
                (proxy, method, args) -> "toString".equals(method.getName()) //$NON-NLS-1$
                        ? "StubInfobaseReference" //$NON-NLS-1$
                        : defaultReturn(method));
    }

    private static Object defaultReturn(Method method) {
        Class<?> ret = method.getReturnType();
        if (ret == boolean.class) {
            return Boolean.FALSE;
        }
        if (ret == Optional.class) {
            return Optional.empty();
        }
        if (ret.isPrimitive()) {
            return Integer.valueOf(0);
        }
        return null;
    }
}
