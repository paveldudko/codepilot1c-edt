/*
 * Copyright (c) 2026 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.diagnostics;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.osgi.framework.Bundle;

import com.codepilot1c.core.internal.VibeCorePlugin;
import com.codepilot1c.core.logging.VibeLogger;

/**
 * Locates rich check-description Markdown for EDT check IDs.
 *
 * <p>EDT check contributors (e.g. {@code com.e1c.v8codestyle.bsl}) ship the
 * same HTML the EDT Check Info view renders, at:
 * <ul>
 *   <li>{@code check.descriptions/<checkId>.html} — English (root)</li>
 *   <li>{@code check.descriptions/<locale>/<checkId>.html} — localized (e.g. {@code ru})</li>
 * </ul>
 *
 * <p>This resolver scans every active OSGi bundle exactly once, indexes which
 * bundles contain which check IDs, and on demand reads + converts the HTML to
 * Markdown via {@link CheckHtmlToMarkdown}. The check {@link Bundle} mapping
 * is cached for the lifetime of the runtime.
 *
 * <p>Checks whose contributor bundle does not ship an HTML description simply
 * return {@link Optional#empty()} — callers can fall back to the short
 * {@code ICheckDescription.getDescription()} blurb at a higher layer.
 */
public final class CheckInfoResolver {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(CheckInfoResolver.class);

    private static final String RESOURCE_PREFIX = "check.descriptions"; //$NON-NLS-1$
    private static final String DEFAULT_LOCALE = "en"; //$NON-NLS-1$

    private static final CheckInfoResolver INSTANCE = new CheckInfoResolver();

    private final AtomicReference<Map<String, Bundle>> indexRef = new AtomicReference<>();

    public static CheckInfoResolver getInstance() {
        return INSTANCE;
    }

    private CheckInfoResolver() {}

    /**
     * Returns Markdown rendered from the EDT check description page for
     * {@code checkId}, preferring {@code locale} and falling back to English.
     *
     * @param checkId stable check identifier (e.g. {@code manager-module-named-self-reference})
     * @param locale  preferred locale (e.g. {@code en}, {@code ru}); {@code null}/blank → {@code en}
     * @return Markdown body, or empty if no bundle ships a description for {@code checkId}
     */
    public Optional<String> findMarkdown(String checkId, String locale) {
        if (checkId == null || checkId.isBlank()) {
            return Optional.empty();
        }
        Bundle bundle = lookup(checkId);
        if (bundle == null) {
            return Optional.empty();
        }
        String normalizedLocale = normalizeLocale(locale);
        URL url = null;
        if (!DEFAULT_LOCALE.equals(normalizedLocale)) {
            url = bundle.getEntry(RESOURCE_PREFIX + "/" + normalizedLocale + "/" + checkId + ".html"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        if (url == null) {
            url = bundle.getEntry(RESOURCE_PREFIX + "/" + checkId + ".html"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (url == null) {
            return Optional.empty();
        }
        try (InputStream in = url.openStream()) {
            String html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            String md = CheckHtmlToMarkdown.convert(html);
            return md.isBlank() ? Optional.empty() : Optional.of(md);
        } catch (IOException e) {
            LOG.warn("Failed to read check description for %s from %s: %s", //$NON-NLS-1$
                    checkId, bundle.getSymbolicName(), e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Returns {@code true} when this resolver can locate any description for
     * {@code checkId} — useful for callers that want to short-circuit without
     * paying for HTML parsing.
     */
    public boolean hasDescription(String checkId) {
        return checkId != null && !checkId.isBlank() && lookup(checkId) != null;
    }

    private Bundle lookup(String checkId) {
        Map<String, Bundle> index = indexRef.get();
        if (index == null) {
            Map<String, Bundle> built = buildIndex();
            indexRef.compareAndSet(null, built);
            index = indexRef.get();
        }
        return index.get(checkId);
    }

    private Map<String, Bundle> buildIndex() {
        VibeCorePlugin plugin = VibeCorePlugin.getDefault();
        if (plugin == null || plugin.getBundle() == null
                || plugin.getBundle().getBundleContext() == null) {
            LOG.debug("CheckInfoResolver: no BundleContext available, returning empty index"); //$NON-NLS-1$
            return Map.of();
        }
        Bundle[] bundles = plugin.getBundle().getBundleContext().getBundles();
        if (bundles == null || bundles.length == 0) {
            return Map.of();
        }
        long start = System.nanoTime();
        int contributorCount = 0;
        Map<String, Bundle> map = new HashMap<>();
        for (Bundle bundle : bundles) {
            if (bundle == null) {
                continue;
            }
            String name = bundle.getSymbolicName();
            // Check contributors live in 1C / E1C / community bundles. Skipping
            // org.eclipse.* / org.osgi.* keeps the scan from walking JDT/Xtext.
            if (name == null || name.startsWith("org.eclipse.") || name.startsWith("org.osgi.") //$NON-NLS-1$ //$NON-NLS-2$
                    || name.startsWith("javax.") || name.startsWith("jakarta.") //$NON-NLS-1$ //$NON-NLS-2$
                    || name.startsWith("com.google.")) { //$NON-NLS-1$
                continue;
            }
            try {
                Enumeration<URL> entries = bundle.findEntries(RESOURCE_PREFIX, "*.html", true); //$NON-NLS-1$
                if (entries == null) {
                    continue;
                }
                int bundleHits = 0;
                while (entries.hasMoreElements()) {
                    URL entry = entries.nextElement();
                    String id = extractCheckId(entry.getPath());
                    if (id == null) {
                        continue;
                    }
                    // First bundle wins — the localized variant from the same
                    // bundle resolves later via getEntry(<locale>/<id>.html).
                    if (map.putIfAbsent(id, bundle) == null) {
                        bundleHits++;
                    }
                }
                if (bundleHits > 0) {
                    contributorCount++;
                }
            } catch (RuntimeException e) {
                LOG.debug("CheckInfoResolver: skipping bundle %s: %s", name, e.getMessage()); //$NON-NLS-1$
            }
        }
        long ms = (System.nanoTime() - start) / 1_000_000L;
        LOG.info("CheckInfoResolver indexed %d check description(s) across %d contributor bundle(s) in %d ms", //$NON-NLS-1$
                map.size(), contributorCount, ms);
        return Map.copyOf(map);
    }

    private String extractCheckId(String resourcePath) {
        if (resourcePath == null || resourcePath.isBlank()) {
            return null;
        }
        int slash = resourcePath.lastIndexOf('/');
        int dot = resourcePath.lastIndexOf('.');
        if (slash < 0 || dot <= slash) {
            return null;
        }
        return resourcePath.substring(slash + 1, dot);
    }

    private String normalizeLocale(String locale) {
        if (locale == null || locale.isBlank()) {
            return DEFAULT_LOCALE;
        }
        String lower = locale.trim().toLowerCase(Locale.ROOT);
        int dash = lower.indexOf('-');
        if (dash > 0) {
            lower = lower.substring(0, dash);
        }
        int underscore = lower.indexOf('_');
        if (underscore > 0) {
            lower = lower.substring(0, underscore);
        }
        return lower.isEmpty() ? DEFAULT_LOCALE : lower;
    }
}
