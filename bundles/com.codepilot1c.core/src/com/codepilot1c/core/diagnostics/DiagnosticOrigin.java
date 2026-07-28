/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.diagnostics;

import java.util.Locale;

/**
 * Classifies where a diagnostic came from, so callers can tell real EDT
 * diagnostics apart from foreign marker payloads that merely live on the same
 * resource.
 *
 * <p>Motivation: {@code get_diagnostics} reads Eclipse markers with
 * {@code findMarkers(null, true, …)} — i.e. <em>every</em> marker type attached
 * to the file, including markers contributed by unrelated plugins. The sibling
 * commit-review plugin ({@code com.dudko.edt.review.commentMarker}, a
 * {@code org.eclipse.core.resources.textmarker} subtype) declares no
 * {@code IMarker.SEVERITY}, so it used to degrade to {@code INFO} and blend into
 * the diagnostics list with nothing to tell it apart. This class provides that
 * "nothing" — a provenance label.</p>
 *
 * <p>Deliberately dependency-free: inputs are plain strings/booleans, no Eclipse
 * types. That keeps the whole rule set covered by hermetic JUnit in
 * {@code com.codepilot1c.core.tests} (the UI bundle's collector is not
 * reachable from a headless test runtime).</p>
 *
 * <p>Origin values:</p>
 * <ul>
 *   <li>{@link #COMPILER} — parser/compiler problem markers (JDT, platform
 *       {@code problemmarker}, BSL syntax errors);</li>
 *   <li>{@link #ANALYZER} — EDT/1C check infrastructure: the runtime marker
 *       manager, EDT check markers, the Xtext validation layer, our own
 *       curated validators;</li>
 *   <li>{@link #REVIEW_ANNOTATION} — review/comment overlays contributed by
 *       other plugins; not diagnostics at all;</li>
 *   <li>{@link #CUSTOM_CHECK} — an unrecognized marker type that still declares
 *       a severity, i.e. it looks like a genuine diagnostic from a third-party
 *       checker;</li>
 *   <li>{@link #UNKNOWN} — nothing recognizable (also the fallback for blank
 *       input).</li>
 * </ul>
 */
public final class DiagnosticOrigin {

    /** Parser/compiler problem. */
    public static final String COMPILER = "compiler"; //$NON-NLS-1$
    /** EDT check / validation infrastructure. */
    public static final String ANALYZER = "analyzer"; //$NON-NLS-1$
    /** Third-party marker that carries a severity but is not a known checker. */
    public static final String CUSTOM_CHECK = "custom-check"; //$NON-NLS-1$
    /** Review/comment overlay from another plugin — not a diagnostic. */
    public static final String REVIEW_ANNOTATION = "review-annotation"; //$NON-NLS-1$
    /** Unclassifiable. */
    public static final String UNKNOWN = "unknown"; //$NON-NLS-1$

    /** Filter value: every origin except {@link #REVIEW_ANNOTATION}. The default. */
    public static final String FILTER_DIAGNOSTICS = "diagnostics"; //$NON-NLS-1$
    /** Filter value: no filtering at all. */
    public static final String FILTER_ALL = "all"; //$NON-NLS-1$

    /** Collection-path label used by marker-based collection. */
    public static final String SOURCE_MARKER = "marker"; //$NON-NLS-1$
    /** Collection-path label used by editor-annotation collection. */
    public static final String SOURCE_ANNOTATION = "annotation"; //$NON-NLS-1$
    /** Collection-path label used by the EDT runtime marker manager. */
    public static final String SOURCE_MARKER_MANAGER = "marker_manager"; //$NON-NLS-1$

    /** {@code org.eclipse.core.resources.textmarker} — the annotation-ish base type. */
    public static final String TEXT_MARKER_TYPE = "org.eclipse.core.resources.textmarker"; //$NON-NLS-1$

    private DiagnosticOrigin() {
    }

    /**
     * Classifies by marker/annotation type and collection path only.
     *
     * <p>Use this from paths where a severity is always present by construction
     * (editor annotations, runtime marker manager). Marker-based collection
     * should prefer {@link #classify(String, String, boolean, boolean)}, which
     * also sees the "declares no severity" signal.</p>
     *
     * @param markerType marker type id or annotation type ({@code null} tolerated)
     * @param source collection path label ({@code null} tolerated) — see
     *        {@link #SOURCE_MARKER}, {@link #SOURCE_ANNOTATION},
     *        {@link #SOURCE_MARKER_MANAGER}
     * @return one of the origin constants, never {@code null}
     */
    public static String classify(String markerType, String source) {
        return classify(markerType, source, true, false);
    }

    /**
     * Classifies by marker/annotation type, collection path and the two marker
     * traits that separate a real diagnostic from an annotation overlay.
     *
     * @param markerType marker type id or annotation type ({@code null} tolerated)
     * @param source collection path label ({@code null} tolerated)
     * @param severityDeclared whether the marker actually declared
     *        {@code IMarker.SEVERITY} (a missing severity silently degrades to
     *        {@code INFO}, which is how review markers used to leak in)
     * @param textMarkerSubtype whether the marker is a subtype of
     *        {@link #TEXT_MARKER_TYPE}
     * @return one of the origin constants, never {@code null}
     */
    public static String classify(
            String markerType, String source, boolean severityDeclared, boolean textMarkerSubtype) {

        String type = lower(markerType);
        String src = lower(source);

        // 1. Review / comment overlays: named by the contributing plugin, or a
        //    textmarker subtype that declares no severity (an annotation, not a
        //    diagnostic). Well-known platform types (task/bookmark/plain) are
        //    exempt — they predate this rule and callers still expect them.
        if (isReviewType(type)) {
            return REVIEW_ANNOTATION;
        }
        if (!severityDeclared && !isPlatformGenericType(type)
                && (textMarkerSubtype || type.endsWith("commentmarker"))) { //$NON-NLS-1$
            return REVIEW_ANNOTATION;
        }

        // 2. Compiler / parser problems.
        if (isCompilerType(type)) {
            return COMPILER;
        }

        // 3. EDT check / validation infrastructure.
        if (SOURCE_MARKER_MANAGER.equals(src) || isAnalyzerType(type)) {
            return ANALYZER;
        }

        // 4. Leftovers: a severity-carrying stranger still looks like a real
        //    check from some other plugin; anything else is simply unknown.
        if (type.isEmpty() && src.isEmpty()) {
            return UNKNOWN;
        }
        if (isPlatformGenericType(type) || UNKNOWN.equals(type)) {
            return UNKNOWN;
        }
        return severityDeclared && !type.isEmpty() ? CUSTOM_CHECK : UNKNOWN;
    }

    /**
     * Applies an origin filter expression to a classified origin.
     *
     * <p>Accepted filter values: {@link #FILTER_DIAGNOSTICS} (default —
     * everything but review annotations), {@link #FILTER_ALL}, a single origin
     * constant, or a comma-separated list of origin constants. A blank or
     * entirely unrecognized expression falls back to the default rather than
     * filtering everything away.</p>
     *
     * @param originFilter filter expression ({@code null} tolerated)
     * @param origin origin label ({@code null} treated as {@link #UNKNOWN})
     * @return {@code true} when the diagnostic should be kept
     */
    public static boolean accepts(String originFilter, String origin) {
        String value = normalize(origin);
        String filter = lower(originFilter);
        if (filter.isEmpty() || FILTER_DIAGNOSTICS.equals(filter)) {
            return !REVIEW_ANNOTATION.equals(value);
        }
        if (FILTER_ALL.equals(filter)) {
            return true;
        }
        boolean sawKnown = false;
        boolean matched = false;
        for (String part : filter.split(",")) { //$NON-NLS-1$
            String candidate = part.trim();
            if (candidate.isEmpty()) {
                continue;
            }
            if (FILTER_ALL.equals(candidate)) {
                return true;
            }
            if (isKnownOrigin(candidate)) {
                sawKnown = true;
                if (candidate.equals(value)) {
                    matched = true;
                }
            }
        }
        if (!sawKnown) {
            // Unrecognized expression — behave like the default instead of
            // silently returning an empty diagnostics list.
            return !REVIEW_ANNOTATION.equals(value);
        }
        return matched;
    }

    /** True when {@code value} is one of the origin constants. */
    public static boolean isKnownOrigin(String value) {
        String candidate = lower(value);
        return COMPILER.equals(candidate)
                || ANALYZER.equals(candidate)
                || CUSTOM_CHECK.equals(candidate)
                || REVIEW_ANNOTATION.equals(candidate)
                || UNKNOWN.equals(candidate);
    }

    /** True for the review-overlay origin (the one excluded by default). */
    public static boolean isReviewAnnotation(String origin) {
        return REVIEW_ANNOTATION.equals(normalize(origin));
    }

    /** Normalizes an origin label; blank/unknown input becomes {@link #UNKNOWN}. */
    public static String normalize(String origin) {
        String candidate = lower(origin);
        return candidate.isEmpty() ? UNKNOWN : candidate;
    }

    /** The filter expression applied when a caller passes nothing. */
    public static String defaultFilter() {
        return FILTER_DIAGNOSTICS;
    }

    // --- rules ----------------------------------------------------------------

    private static boolean isReviewType(String type) {
        if (type.isEmpty()) {
            return false;
        }
        return type.startsWith("com.dudko.edt.review") //$NON-NLS-1$
                || type.contains("edt.review") //$NON-NLS-1$
                || type.contains("reviewmarker") //$NON-NLS-1$
                || (type.contains("review") && type.contains("comment")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static boolean isCompilerType(String type) {
        if (type.isEmpty()) {
            return false;
        }
        return type.startsWith("org.eclipse.jdt") //$NON-NLS-1$
                || type.contains("problemmarker") //$NON-NLS-1$
                || type.endsWith(".problem") //$NON-NLS-1$
                || type.contains("syntax") //$NON-NLS-1$
                || type.contains("compile") //$NON-NLS-1$
                || (type.contains("xtext") && type.contains("error")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static boolean isAnalyzerType(String type) {
        if (type.isEmpty()) {
            return false;
        }
        return type.contains("xtext") //$NON-NLS-1$
                || type.contains("com._1c") //$NON-NLS-1$
                || type.contains("com.e1c") //$NON-NLS-1$
                || type.contains("check") //$NON-NLS-1$
                || type.contains("validation") //$NON-NLS-1$
                || type.startsWith("dcs-") //$NON-NLS-1$
                || type.startsWith("bsl"); //$NON-NLS-1$
    }

    private static boolean isPlatformGenericType(String type) {
        return "org.eclipse.core.resources.marker".equals(type) //$NON-NLS-1$
                || TEXT_MARKER_TYPE.equals(type)
                || "org.eclipse.core.resources.taskmarker".equals(type) //$NON-NLS-1$
                || "org.eclipse.core.resources.bookmark".equals(type); //$NON-NLS-1$
    }

    private static String lower(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT); //$NON-NLS-1$
    }
}
