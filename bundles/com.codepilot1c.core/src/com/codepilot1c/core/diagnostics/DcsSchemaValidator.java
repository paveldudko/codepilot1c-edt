/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.diagnostics;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.eclipse.core.resources.IFile;

import com.codepilot1c.core.logging.VibeLogger;

/**
 * Targeted lint for {@code .dcs} (DataCompositionSchema) files.
 *
 * <p><b>Why this is a denylist, not real schema validation.</b> A {@code .dcs}
 * is the 1C <em>platform</em> serialization (namespace
 * {@code http://v8.1c.ru/8.1/data-composition-system/schema}). EDT does not load
 * it as a plain EMF resource: it is translated into the BM model by a custom,
 * <em>lenient</em> importer ({@code com._1c.g5.v8.dt.internal.dcs.resource.DcsBmImporter}),
 * which silently drops any element it cannot map — taking the enclosing dataset
 * with it. EDT's own EMF packages are registered only under the Ecore URIs
 * ({@code http://g5.1c.ru/v8/dt/...}), so a strict generic EMF reader cannot even
 * resolve the file's platform namespace, and there is no reusable strict parser
 * to surface "unknown element" errors. Full schema validation is therefore not
 * feasible from this plugin.</p>
 *
 * <p>So we do the cheap, reliable thing instead: a dependency-free text scan that
 * flags a small curated set of elements known to be invalid inside a {@code .dcs}
 * and to be silently dropped. Today that is {@code <editFormat>} (an {@code .mxlx}-only
 * element); extend {@link #INVALID_ELEMENTS} as new cases surface. This catches the
 * documented breakage with ~zero false-positive risk; it does NOT catch arbitrary
 * schema violations.</p>
 */
public class DcsSchemaValidator {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(DcsSchemaValidator.class);

    /**
     * Element local-name → user-facing message, for elements that are invalid inside
     * a {@code .dcs} and that EDT's importer silently drops. Insertion-ordered.
     */
    private static final Map<String, String> INVALID_ELEMENTS = buildInvalidElements();

    private static Map<String, String> buildInvalidElements() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("editFormat", //$NON-NLS-1$
                "<editFormat> is an .mxlx-only element and is not valid inside a .dcs field. " //$NON-NLS-1$
                + "EDT's DCS importer silently drops it together with the enclosing dataset " //$NON-NLS-1$
                + "(the schema designer then shows nothing and the report returns no rows). " //$NON-NLS-1$
                + "Use an <appearance> block with a Format parameter (dcscor:parameter=Format) instead."); //$NON-NLS-1$
        return Collections.unmodifiableMap(map);
    }

    /**
     * Neutral DTO so the UI bundle can map to {@code EdtDiagnostic} without core types.
     *
     * @param line     1-based line number ({@code -1} when unknown)
     * @param column   1-based column ({@code -1} — not tracked by the text scan)
     * @param severity {@code "error"}, {@code "warning"} or {@code "info"}
     * @param message  user-facing message
     */
    public record DcsSchemaIssue(int line, int column, String severity, String message) {
    }

    public List<DcsSchemaIssue> validate(IFile file) {
        if (file == null || !file.exists()) {
            return Collections.emptyList();
        }
        String name = file.getName();
        if (name == null || !name.toLowerCase(Locale.ROOT).endsWith(".dcs")) { //$NON-NLS-1$
            return Collections.emptyList();
        }
        String content = readContent(file);
        if (content == null || content.isBlank()) {
            return Collections.emptyList();
        }

        List<DcsSchemaIssue> issues = new ArrayList<>();
        for (Map.Entry<String, String> entry : INVALID_ELEMENTS.entrySet()) {
            scanElement(content, entry.getKey(), entry.getValue(), issues);
        }
        LOG.info("DcsSchemaValidator: %s — %d known-invalid DCS element(s)", name, issues.size()); //$NON-NLS-1$
        return issues;
    }

    private void scanElement(String content, String localName, String message, List<DcsSchemaIssue> out) {
        String openTag = "<" + localName; //$NON-NLS-1$
        int from = 0;
        while (true) {
            int idx = content.indexOf(openTag, from);
            if (idx < 0) {
                break;
            }
            from = idx + openTag.length();
            // Only a genuine start tag: the element name must be terminated by '>', '/' or whitespace
            // (so "<editFormatExtra" does not match "<editFormat").
            if (from < content.length()) {
                char next = content.charAt(from);
                if (next != '>' && next != '/' && !Character.isWhitespace(next)) {
                    continue;
                }
            }
            out.add(new DcsSchemaIssue(lineOf(content, idx), -1, "warning", message)); //$NON-NLS-1$
        }
    }

    private int lineOf(String content, int offset) {
        int line = 1;
        int limit = Math.min(offset, content.length());
        for (int i = 0; i < limit; i++) {
            if (content.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private String readContent(IFile file) {
        try (InputStream in = file.getContents(true);
                BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            char[] buffer = new char[8192];
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                sb.append(buffer, 0, read);
            }
            return sb.toString();
        } catch (Exception e) {
            LOG.info("DcsSchemaValidator: read failed for %s: %s — %s", //$NON-NLS-1$
                    file.getName(), e.getClass().getSimpleName(), e.getMessage());
            return null;
        }
    }
}
