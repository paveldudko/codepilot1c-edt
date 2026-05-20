/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.diagnostics;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Builds the set of candidate workspace-relative path strings used by
 * {@code EdtDiagnosticsCollector} to resolve a file and to derive
 * marker-haystack match tokens via {@link PathMatchTokens}.
 *
 * <p>Splitting this out of the UI bundle lets us unit-test the
 * project-prefix-stripping rule that fixes the silent-zero diagnostics
 * bug for projects whose name was previously tokenized as a
 * "discriminator" but never appears in marker haystacks (see
 * {@code 2026-05-19-diagnostics-space-in-project-name.md}).</p>
 *
 * <p>Rule set:
 * <ol>
 *   <li>Original path with normalized separators always survives.</li>
 *   <li>If the first segment matches a known workspace project name,
 *       the project-stripped remainder is added as a second candidate.</li>
 *   <li>Legacy {@code Configuration/} and {@code Конфигурация/} prefixes
 *       are also stripped (existing convention).</li>
 *   <li>The two strip steps compose — for a path like
 *       {@code MyProject/Configuration/src/...} all three forms are
 *       returned.</li>
 * </ol></p>
 */
public final class RelativePathCandidates {

    private RelativePathCandidates() {
        // static utility
    }

    /**
     * @param rawPath           input path (workspace-rooted, project-rooted,
     *                          or fully relative — leading {@code /} is
     *                          stripped, backslashes normalised to forward
     *                          slashes)
     * @param knownProjectNames names of workspace projects that we can
     *                          recognise; {@code null} treated as empty
     */
    public static List<String> build(String rawPath, Set<String> knownProjectNames) {
        if (rawPath == null) {
            return List.of();
        }
        String normalized = normalize(rawPath);
        if (normalized.isBlank()) {
            return List.of();
        }
        Set<String> projects = knownProjectNames == null ? Set.of() : knownProjectNames;

        LinkedHashSet<String> out = new LinkedHashSet<>();
        out.add(normalized);

        // Project-prefix stripping.
        String afterProject = stripKnownProjectPrefix(normalized, projects);
        if (afterProject != null) {
            out.add(afterProject);
        } else {
            afterProject = normalized;
        }

        // Configuration/Конфигурация prefix stripping — applied to BOTH
        // the original and the project-stripped form, so we cover all
        // combinations a user might type.
        for (String candidate : List.of(normalized, afterProject)) {
            String stripped = stripConfigurationPrefix(candidate);
            if (stripped != null) {
                out.add(stripped);
            }
        }

        return List.copyOf(out);
    }

    private static String normalize(String raw) {
        if (raw == null) return "";
        String s = raw.trim().replace('\\', '/');
        while (s.startsWith("/")) {
            s = s.substring(1);
        }
        return s;
    }

    private static String stripKnownProjectPrefix(String path, Set<String> projects) {
        int slash = path.indexOf('/');
        if (slash <= 0 || slash == path.length() - 1) {
            return null;
        }
        String firstSegment = path.substring(0, slash);
        if (!projects.contains(firstSegment)) {
            return null;
        }
        String rest = path.substring(slash + 1);
        return rest.isBlank() ? null : rest;
    }

    private static String stripConfigurationPrefix(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.startsWith("configuration/")) {
            return path.substring("configuration/".length());
        }
        if (lower.startsWith("конфигурация/")) {
            return path.substring("конфигурация/".length());
        }
        return null;
    }

    /**
     * Returns the subset of {@link #build(String, Set)} candidates suitable
     * for marker-haystack tokenization — i.e. those that do NOT start with
     * a known workspace project name segment.
     *
     * <p>Why this is separate from {@link #build}: the project-prefixed
     * form is necessary for the workspace-rooted {@code IFile} lookup
     * (step 1 in {@code EdtDiagnosticsCollector.resolveFileContext}), but
     * its presence in {@code buildMatchTokens} produces a tokens-union
     * that includes the project-name segment as a "discriminating" token.
     * Since EDT marker haystacks never carry the project name, the
     * resulting ALL-tokens threshold (= 2) becomes unsatisfiable for
     * every marker — exactly the silent-zero regression we fixed in
     * Phase 19, with the with-prefix input shape still hitting it.</p>
     *
     * <p>If every candidate happens to be project-prefixed (pathological
     * case — caller passed only {@code "Project/"} with no rest), the
     * original list is returned unchanged as a safety net.</p>
     */
    public static List<String> buildForMatch(String rawPath, Set<String> knownProjectNames) {
        List<String> all = build(rawPath, knownProjectNames);
        if (all.isEmpty()) {
            return all;
        }
        Set<String> projects = knownProjectNames == null ? Set.of() : knownProjectNames;
        java.util.ArrayList<String> filtered = new java.util.ArrayList<>(all.size());
        for (String candidate : all) {
            int slash = candidate.indexOf('/');
            if (slash <= 0) {
                filtered.add(candidate);
                continue;
            }
            String firstSegment = candidate.substring(0, slash);
            if (!projects.contains(firstSegment)) {
                filtered.add(candidate);
            }
        }
        return filtered.isEmpty() ? all : List.copyOf(filtered);
    }
}
