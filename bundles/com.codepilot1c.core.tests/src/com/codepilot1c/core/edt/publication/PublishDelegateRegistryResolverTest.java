package com.codepilot1c.core.edt.publication;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import java.util.Collection;
import java.util.List;

import org.junit.Test;

import com._1c.g5.v8.dt.platform.services.core.publication.IWebServerPublishDelegate;
import com._1c.g5.v8.dt.platform.services.core.publication.IWebServerPublishDelegateRegistry;
import com._1c.g5.v8.dt.platform.services.model.RuntimeInstallation;

/**
 * Plain-JUnit (no OSGi) tests for {@link PublishDelegateRegistryResolver}'s reflective
 * field-by-type route — the fallback that reads EDT's injector-bound
 * {@code IWebServerPublishDelegateRegistry} out of the {@code PublicationManager} instance without
 * depending on the field's name. The injector route ({@link PublishDelegateRegistryResolver#fromInjector})
 * needs a live EDT bundle and is covered by live validation; only its null-safety is asserted here.
 */
public class PublishDelegateRegistryResolverTest {

    /** Stand-in for EDT's {@code WebServerPublishDelegateRegistry}. */
    private static final class StubRegistry implements IWebServerPublishDelegateRegistry {
        @Override
        public IWebServerPublishDelegate getDelegate(RuntimeInstallation installation, String typeId) {
            return null;
        }

        @Override
        public IWebServerPublishDelegate getDelegate(String typeId) {
            return null;
        }

        @Override
        public Collection<IWebServerPublishDelegate> getAll() {
            return List.of();
        }
    }

    /** Mirrors {@code PublicationManager}: keeps the registry in a private field. */
    private static final class PublicationManagerLike {
        @SuppressWarnings("unused")
        private final IWebServerPublishDelegateRegistry publishDelegateRegistry;

        PublicationManagerLike(IWebServerPublishDelegateRegistry registry) {
            this.publishDelegateRegistry = registry;
        }
    }

    /** Base carrying the field, to prove the resolver walks up the hierarchy. */
    private static class HolderBase {
        @SuppressWarnings("unused")
        private final IWebServerPublishDelegateRegistry registry;

        HolderBase(IWebServerPublishDelegateRegistry registry) {
            this.registry = registry;
        }
    }

    private static final class HolderSub extends HolderBase {
        HolderSub(IWebServerPublishDelegateRegistry registry) {
            super(registry);
        }
    }

    /** No registry-typed field anywhere. */
    private static final class Unrelated {
        @SuppressWarnings("unused")
        private final String name = "x"; //$NON-NLS-1$
    }

    @Test
    public void findsRegistryTypedFieldOnDeclaringClass() {
        StubRegistry registry = new StubRegistry();
        Object holder = new PublicationManagerLike(registry);

        assertSame("must return the registry held by the private field", //$NON-NLS-1$
                registry, PublishDelegateRegistryResolver.fromServiceHolderField(holder));
    }

    @Test
    public void walksSuperclassForTheField() {
        StubRegistry registry = new StubRegistry();
        Object holder = new HolderSub(registry);

        assertSame("must find a field declared on a superclass", //$NON-NLS-1$
                registry, PublishDelegateRegistryResolver.fromServiceHolderField(holder));
    }

    @Test
    public void returnsNullWhenNoAssignableField() {
        assertNull(PublishDelegateRegistryResolver.fromServiceHolderField(new Unrelated()));
    }

    @Test
    public void returnsNullWhenFieldValueIsNull() {
        assertNull("a present-but-null field must not be reported as resolved", //$NON-NLS-1$
                PublishDelegateRegistryResolver.fromServiceHolderField(new PublicationManagerLike(null)));
    }

    @Test
    public void nullHolderIsNull() {
        assertNull(PublishDelegateRegistryResolver.fromServiceHolderField(null));
    }

    @Test
    public void nullBundleInjectorIsNull() {
        assertNull(PublishDelegateRegistryResolver.fromInjector(null));
    }
}
