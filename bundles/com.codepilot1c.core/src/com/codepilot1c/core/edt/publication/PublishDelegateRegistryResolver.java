package com.codepilot1c.core.edt.publication;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.osgi.framework.Bundle;

import com._1c.g5.v8.dt.platform.services.core.publication.IWebServerPublishDelegateRegistry;
import com.codepilot1c.core.logging.VibeLogger;

/**
 * Resolves EDT's {@link IWebServerPublishDelegateRegistry}, which — unlike its siblings
 * {@code IWebServerManager} / {@code IPublicationManager} — is <em>not</em> exported as an OSGi
 * service by {@code PlatformServicesCore}'s activator. Decompile audit of
 * {@code com._1c.g5.v8.dt.platform.services.core 21.0} (2026-07-17):
 * <ul>
 *   <li>{@code PlatformServicesCoreModule} binds
 *       {@code IWebServerPublishDelegateRegistry -> WebServerPublishDelegateRegistry} in the Guice
 *       injector, but the activator's {@code InjectorAwareServiceRegistrator.registerServices(...)}
 *       batch omits it (it lists {@code IPublicationManager}, {@code IWebServerManager}, … but not
 *       the registry).</li>
 *   <li>Consequently a plain OSGi {@code ServiceTracker.waitForService(30s)} — the mechanism the
 *       plugin (and EDT's own {@code com._1c.g5.wiring.ServiceAccess}) uses — <em>never</em>
 *       resolves it: it waits the full timeout and fails with "EDT service not available:
 *       IWebServerPublishDelegateRegistry". This is not a headless/lazy-activation timing issue;
 *       the lookup mechanism is simply wrong for an injector-only binding
 *       (feedback 2026-07-17-web-publication-webserverpublishdelegateregistry-unavailable).</li>
 * </ul>
 *
 * <p>Two independent reflective routes reach the same singleton, tried in order:</p>
 * <ol>
 *   <li>{@link #fromInjector(Bundle)} — the canonical binding: {@code PlatformServicesCore.getDefault()}
 *       (public) then the package-private {@code getInjector()} (a stable EDT-plugin convention),
 *       then {@code Injector.getInstance(IWebServerPublishDelegateRegistry.class)}. Classes are loaded
 *       through the platform-services bundle's own class loader, so no extra {@code Import-Package}
 *       wiring is needed.</li>
 *   <li>{@link #fromServiceHolderField(Object)} — reads the registry the EDT
 *       {@code PublicationManager} (reachable as the OSGi {@code IPublicationManager}) keeps in a
 *       private field, matched by assignable type rather than by name so a field rename does not
 *       break it. This survives even if {@code getInjector()} is renamed/removed.</li>
 * </ol>
 */
public final class PublishDelegateRegistryResolver {

    private static final VibeLogger.CategoryLogger LOG =
            VibeLogger.forClass(PublishDelegateRegistryResolver.class);

    private static final String PLATFORM_SERVICES_CORE_CLASS =
            "com._1c.g5.v8.dt.internal.platform.services.core.PlatformServicesCore"; //$NON-NLS-1$
    private static final String GUICE_INJECTOR_CLASS = "com.google.inject.Injector"; //$NON-NLS-1$

    private PublishDelegateRegistryResolver() {
        // utility
    }

    /**
     * Resolves the registry from the EDT platform-services Guice injector via reflection.
     *
     * @param platformServicesCore the {@code com._1c.g5.v8.dt.platform.services.core} bundle
     *            (typically {@code Platform.getBundle(...)}); {@code null} yields {@code null}
     * @return the registry, or {@code null} if the bundle is absent/inactive or the reflective path
     *         does not hold on this EDT build
     */
    public static IWebServerPublishDelegateRegistry fromInjector(Bundle platformServicesCore) {
        if (platformServicesCore == null) {
            return null;
        }
        try {
            Class<?> pscClass = platformServicesCore.loadClass(PLATFORM_SERVICES_CORE_CLASS);
            Object plugin = pscClass.getMethod("getDefault").invoke(null); //$NON-NLS-1$
            if (plugin == null) {
                // Bundle installed but not yet started — nothing to resolve from.
                return null;
            }
            Method getInjector = pscClass.getDeclaredMethod("getInjector"); //$NON-NLS-1$
            getInjector.setAccessible(true);
            Object injector = getInjector.invoke(plugin);
            if (injector == null) {
                return null;
            }
            // Invoke through the public com.google.inject.Injector interface, not the internal
            // (package-private) InjectorImpl, so the reflective call is legal.
            Class<?> injectorIface = platformServicesCore.loadClass(GUICE_INJECTOR_CLASS);
            Method getInstance = injectorIface.getMethod("getInstance", Class.class); //$NON-NLS-1$
            Object registry = getInstance.invoke(injector, IWebServerPublishDelegateRegistry.class);
            if (registry instanceof IWebServerPublishDelegateRegistry typed) {
                return typed;
            }
            return null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LOG.warn("Could not resolve IWebServerPublishDelegateRegistry via the EDT platform-services " //$NON-NLS-1$
                    + "injector: %s", e); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Reads an {@link IWebServerPublishDelegateRegistry} that {@code holder} keeps in a field,
     * matched by assignable type (walking the class hierarchy). Intended for the EDT
     * {@code PublicationManager}, which has the registry injected. Returns the first non-null match.
     *
     * @param holder the service instance to introspect (e.g. the OSGi {@code IPublicationManager});
     *            {@code null} yields {@code null}
     * @return the registry, or {@code null} when no assignable, non-null field is found
     */
    public static IWebServerPublishDelegateRegistry fromServiceHolderField(Object holder) {
        if (holder == null) {
            return null;
        }
        for (Class<?> type = holder.getClass(); type != null && type != Object.class; type =
                type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!IWebServerPublishDelegateRegistry.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(holder);
                    if (value instanceof IWebServerPublishDelegateRegistry typed) {
                        return typed;
                    }
                } catch (ReflectiveOperationException | RuntimeException e) {
                    LOG.warn("Reflective read of the %s.%s registry field failed: %s", //$NON-NLS-1$
                            type.getName(), field.getName(), e);
                }
            }
        }
        return null;
    }
}
