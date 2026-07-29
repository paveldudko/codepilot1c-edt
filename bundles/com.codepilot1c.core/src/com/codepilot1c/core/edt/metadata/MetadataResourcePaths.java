/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edt.metadata;

import java.util.Locale;

/**
 * Decides whether a string that came out of a metadata object's URI can be used as a workspace
 * file path at all.
 *
 * <p>Why this is a guard and not a formatter: the URI of a base-configuration top object is not a
 * platform-resource URI, so the project-relative conversion has nothing to strip and hands back an
 * FQN-shaped string such as {@code Catalog.Catalog}. That value is not {@code null}, which is the
 * trap — a caller that treats "non-null" as "resolved" then addresses a workspace file that can
 * never exist, and only finds out by timing out. Every caller must therefore filter before use,
 * and this class is the single place that says what "usable" means.</p>
 *
 * <p>Pure string plumbing on purpose: no EDT runtime, no workspace, so the rules are unit-testable
 * on their own.</p>
 */
public final class MetadataResourcePaths {

    private static final String SRC_PREFIX = "src/"; //$NON-NLS-1$
    private static final String MDO_SUFFIX = ".mdo"; //$NON-NLS-1$
    private static final String FORM_SUFFIX = ".form"; //$NON-NLS-1$

    private MetadataResourcePaths() {
    }

    /**
     * Answers whether {@code resourcePath} looks like a metadata source file inside the project's
     * {@code src/} tree — the only shape the workspace can resolve to a real {@code .mdo}/
     * {@code .form} file.
     */
    public static boolean isUsableMetadataResourcePath(String resourcePath) {
        String normalized = normalize(resourcePath);
        if (normalized == null || !normalized.startsWith(SRC_PREFIX)) {
            return false;
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        return lower.endsWith(MDO_SUFFIX) || lower.endsWith(FORM_SUFFIX);
    }

    /**
     * Returns {@code resourcePath} when it can name an owner {@code .mdo} file, else {@code null}
     * so the caller falls back to a path it computes itself.
     *
     * <p>A {@code .form} path is rejected as deliberately as an FQN is: the owner side of a form
     * lives in the owner's own {@code .mdo}, so accepting the form file would point the caller at
     * the wrong document rather than at no document.</p>
     */
    public static String asOwnerMdoPath(String resourcePath) {
        if (!isUsableMetadataResourcePath(resourcePath)) {
            return null;
        }
        return resourcePath.toLowerCase(Locale.ROOT).endsWith(MDO_SUFFIX) ? resourcePath : null;
    }

    private static String normalize(String resourcePath) {
        if (resourcePath == null || resourcePath.isBlank()) {
            return null;
        }
        String normalized = resourcePath.replace('\\', '/');
        return normalized.startsWith("/") ? normalized.substring(1) : normalized; //$NON-NLS-1$
    }
}
