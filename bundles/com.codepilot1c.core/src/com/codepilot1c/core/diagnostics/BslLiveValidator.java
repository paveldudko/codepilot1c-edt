/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.diagnostics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.Platform;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.eclipse.xtext.resource.XtextResourceSet;
import org.eclipse.xtext.util.CancelIndicator;
import org.eclipse.xtext.validation.CheckMode;
import org.eclipse.xtext.validation.IResourceValidator;
import org.eclipse.xtext.validation.Issue;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleException;

import com._1c.g5.v8.dt.bm.xtext.BmAwareResourceSetProvider;
import com.codepilot1c.core.internal.VibeCorePlugin;
import com.codepilot1c.core.logging.VibeLogger;

/**
 * Runs the BSL Xtext validator on a workspace file without requiring an open
 * editor. Bridges the gap where region-structure / handler-placement warnings
 * live only as Xtext {@link Issue} objects emitted by an open editor's
 * validation pass — so {@code get_diagnostics scope=file} returned 0/0/0 on
 * files that had visible warnings in the EDT GUI but were not currently open
 * in an editor.
 *
 * <p>The validator pipeline used here is the same {@link IResourceValidator}
 * implementation Xtext binds for the BSL language, but invoked with
 * {@link CheckMode#ALL} so that fast / normal / expensive checks all run in
 * one pass. Cross-references that need a populated BM transaction context
 * are resolved through the project-bound {@link BmAwareResourceSetProvider}
 * obtained from the shared gateway; the standalone Xtext resource set is
 * tried as a fallback when BM services aren't ready yet.</p>
 *
 * <p>Best-effort: any failure to load or validate is swallowed and reported
 * via the diagnostic log; callers should treat the empty list as "live
 * validation not available right now" rather than "file is clean".</p>
 */
public class BslLiveValidator {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(BslLiveValidator.class);

    /**
     * Neutral DTO so UI bundle can build {@code EdtDiagnostic} without
     * pulling in Xtext types of its own.
     *
     * @param line     1-based line number (or {@code -1} when unknown)
     * @param offset   character offset in the document (or {@code -1})
     * @param length   length of the highlighted range
     * @param severity {@code "error"}, {@code "warning"} or {@code "info"}
     * @param message  user-facing message
     * @param code     check-id / issue-code (may be {@code null})
     */
    public record BslLiveIssue(
            int line,
            int offset,
            int length,
            String severity,
            String message,
            String code) {
    }

    public BslLiveValidator() {
    }

    public List<BslLiveIssue> validate(IFile file, IProject project) {
        if (file == null || !file.exists()) {
            return Collections.emptyList();
        }
        String name = file.getName();
        if (name == null || !name.toLowerCase(java.util.Locale.ROOT).endsWith(".bsl")) { //$NON-NLS-1$
            return Collections.emptyList();
        }

        URI uri = URI.createPlatformResourceURI(file.getFullPath().toString(), true);
        IResourceServiceProvider rsp = IResourceServiceProvider.Registry.INSTANCE.getResourceServiceProvider(uri);
        if (rsp == null) {
            LOG.info("BslLiveValidator: no IResourceServiceProvider for %s", uri); //$NON-NLS-1$
            return Collections.emptyList();
        }
        IResourceValidator validator = rsp.getResourceValidator();
        if (validator == null) {
            LOG.info("BslLiveValidator: validator unavailable for %s", uri); //$NON-NLS-1$
            return Collections.emptyList();
        }

        Resource resource = loadResource(project, uri);
        if (resource == null) {
            LOG.info("BslLiveValidator: failed to load resource %s", uri); //$NON-NLS-1$
            return Collections.emptyList();
        }

        try {
            List<Issue> issues = validator.validate(resource, CheckMode.ALL, CancelIndicator.NullImpl);
            LOG.info("BslLiveValidator: validate(%s) returned %d issues", //$NON-NLS-1$
                    uri.lastSegment(), issues == null ? 0 : issues.size());
            if (issues == null || issues.isEmpty()) {
                return Collections.emptyList();
            }
            List<BslLiveIssue> out = new ArrayList<>(issues.size());
            for (Issue issue : issues) {
                BslLiveIssue mapped = mapIssue(issue);
                if (mapped != null) {
                    out.add(mapped);
                }
            }
            return out;
        } catch (RuntimeException e) {
            LOG.warn("BslLiveValidator: validation failed for %s: %s — %s", //$NON-NLS-1$
                    uri, e.getClass().getSimpleName(), e.getMessage());
            return Collections.emptyList();
        }
    }

    private static final String BM_XTEXT_BUNDLE_ID = "com._1c.g5.v8.dt.bm.xtext"; //$NON-NLS-1$
    private static final long BM_SERVICE_REGISTRATION_POLL_MS = 200L;
    private static final long BM_SERVICE_REGISTRATION_TIMEOUT_MS = 5000L;

    private Resource loadResource(IProject project, URI uri) {
        ResourceSet projectRs = null;
        if (project != null && project.exists()) {
            BmAwareResourceSetProvider provider = resolveBmAwareResourceSetProvider(uri);
            if (provider != null) {
                try {
                    projectRs = provider.get(project);
                } catch (RuntimeException e) {
                    LOG.info("BslLiveValidator: BM-aware ResourceSet.get(%s) failed: %s — %s", //$NON-NLS-1$
                            project.getName(), e.getClass().getSimpleName(), e.getMessage());
                }
            } else {
                LOG.info("BslLiveValidator: BmAwareResourceSetProvider not resolvable — falling through to standalone"); //$NON-NLS-1$
            }
        }
        Resource resource = tryLoad(projectRs, uri);
        if (resource != null) {
            LOG.info("BslLiveValidator: loaded via project RS, class=%s contentsSize=%d", //$NON-NLS-1$
                    resource.getClass().getSimpleName(), resource.getContents().size());
            return resource;
        }
        Resource standalone = tryLoad(new XtextResourceSet(), uri);
        if (standalone != null) {
            LOG.info("BslLiveValidator: loaded via standalone XtextResourceSet, class=%s contentsSize=%d", //$NON-NLS-1$
                    standalone.getClass().getSimpleName(), standalone.getContents().size());
        }
        return standalone;
    }

    /**
     * Resolves the {@link BmAwareResourceSetProvider} via three escalating
     * strategies:
     * <ol>
     *   <li>Non-blocking OSGi service tracker peek — instant when the service
     *       has already been registered (i.e. some other code path has caused
     *       the Guice injector to instantiate it).</li>
     *   <li>Xtext Guice resolution via
     *       {@link IResourceServiceProvider#get(Class)} — bypasses OSGi DS
     *       entirely and asks the BSL language injector directly for an
     *       instance, which is the same pathway EDT's own form designer
     *       uses. Triggers lazy activation of {@code com._1c.g5.v8.dt.bm.xtext}
     *       as a side-effect and causes
     *       {@code InjectorAwareServiceRegistrator} to publish the OSGi
     *       service, so subsequent peeks succeed without overhead.</li>
     *   <li>Lazy bundle start + short OSGi poll — last-resort path for
     *       runtimes where the BSL service provider is not registered.</li>
     * </ol>
     */
    private BmAwareResourceSetProvider resolveBmAwareResourceSetProvider(URI uri) {
        VibeCorePlugin plugin = VibeCorePlugin.getDefault();
        BmAwareResourceSetProvider provider = plugin != null ? plugin.peekResourceSetProvider() : null;
        if (provider != null) {
            return provider;
        }
        // Strategy 2: ask Xtext's BSL language injector for the provider
        // directly. This is the same Guice path EDT's own form designer
        // uses to obtain the BM-aware resource set when opening a module.
        try {
            IResourceServiceProvider rsp = IResourceServiceProvider.Registry.INSTANCE.getResourceServiceProvider(uri);
            if (rsp != null) {
                BmAwareResourceSetProvider injected = rsp.get(BmAwareResourceSetProvider.class);
                if (injected != null) {
                    LOG.info("BslLiveValidator: BmAwareResourceSetProvider resolved via IResourceServiceProvider injector"); //$NON-NLS-1$
                    return injected;
                }
            }
        } catch (RuntimeException e) {
            LOG.info("BslLiveValidator: injector-based provider lookup failed: %s — %s", //$NON-NLS-1$
                    e.getClass().getSimpleName(), e.getMessage());
        }
        // Strategy 3: force-start the bundle and poll for OSGi service.
        if (plugin == null) {
            return null;
        }
        ensureBmXtextBundleActive();
        long start = System.currentTimeMillis();
        long deadline = start + BM_SERVICE_REGISTRATION_TIMEOUT_MS;
        provider = plugin.peekResourceSetProvider();
        while (provider == null && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(BM_SERVICE_REGISTRATION_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            provider = plugin.peekResourceSetProvider();
        }
        if (provider != null) {
            LOG.info("BslLiveValidator: BmAwareResourceSetProvider registered via OSGi after %d ms", //$NON-NLS-1$
                    System.currentTimeMillis() - start);
        }
        return provider;
    }

    private void ensureBmXtextBundleActive() {
        Bundle bundle = Platform.getBundle(BM_XTEXT_BUNDLE_ID);
        if (bundle == null) {
            LOG.info("BslLiveValidator: bundle %s not present in runtime", BM_XTEXT_BUNDLE_ID); //$NON-NLS-1$
            return;
        }
        int state = bundle.getState();
        if (state == Bundle.ACTIVE) {
            return;
        }
        try {
            bundle.start(Bundle.START_TRANSIENT | Bundle.START_ACTIVATION_POLICY);
            LOG.info("BslLiveValidator: triggered start of %s (was state=%d, now=%d)", //$NON-NLS-1$
                    BM_XTEXT_BUNDLE_ID, state, bundle.getState());
        } catch (BundleException e) {
            LOG.info("BslLiveValidator: bundle %s start failed: %s", //$NON-NLS-1$
                    BM_XTEXT_BUNDLE_ID, e.getMessage());
        }
    }

    private Resource tryLoad(ResourceSet resourceSet, URI uri) {
        if (resourceSet == null) {
            return null;
        }
        try {
            return resourceSet.getResource(uri, true);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private BslLiveIssue mapIssue(Issue issue) {
        if (issue == null) {
            return null;
        }
        String message = issue.getMessage();
        if (message == null || message.isBlank()) {
            return null;
        }
        int line = issue.getLineNumber() != null ? issue.getLineNumber().intValue() : -1;
        int offset = issue.getOffset() != null ? issue.getOffset().intValue() : -1;
        int length = issue.getLength() != null ? issue.getLength().intValue() : 0;
        return new BslLiveIssue(
                line,
                offset,
                length,
                xtextSeverityToString(issue.getSeverity()),
                message,
                issue.getCode());
    }

    private String xtextSeverityToString(org.eclipse.xtext.diagnostics.Severity severity) {
        if (severity == null) {
            return "info"; //$NON-NLS-1$
        }
        return switch (severity) {
            case ERROR -> "error"; //$NON-NLS-1$
            case WARNING -> "warning"; //$NON-NLS-1$
            case INFO -> "info"; //$NON-NLS-1$
            case IGNORE -> "info"; //$NON-NLS-1$
        };
    }
}
