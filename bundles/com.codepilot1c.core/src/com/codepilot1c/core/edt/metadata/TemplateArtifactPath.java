/**
 * Copyright (c) 2025 codepilot1c contributors.
 */
package com.codepilot1c.core.edt.metadata;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * File name of a template's artifact inside {@code Templates/<Name>/}, by template type.
 *
 * <p>What was broken: every artifact path was hard-coded to {@code Template.mxl}. EDT does not use
 * that name for any template type. Proven live 2026-07-29 against a real configuration (Accounting
 * management, 680 template artifacts): 270 spreadsheet templates are stored as
 * {@code Template.mxlx} and <b>zero</b> as {@code Template.mxl}, and the format is XML
 * ({@code <document xmlns="http://v8.1c.ru/8.2/data/spreadsheet">}), not binary MOXCEL.
 * {@code inspect_template} on one of those real templates answered "(файл не найден)" — the read
 * path missed every template a real project has.</p>
 *
 * <p>The table below is not inferred from that survey; it is EDT's own, read out of the constant
 * pool of {@code com._1c.g5.v8.dt.ide.QualifiedNameFilePathConverter} (EDT 2025.2.3), and it covers
 * all ten {@code TemplateType} constants. The survey only confirms it.</p>
 *
 * <p>Keys are matched after stripping case and separators, so a {@code TemplateType} constant name
 * ({@code SPREADSHEET_DOCUMENT}) and its literal ({@code SpreadsheetDocument}) resolve alike. An
 * unknown type yields {@code null} rather than a guessed extension — a wrong file name is what this
 * class exists to stop.</p>
 */
final class TemplateArtifactPath {

    /** The artifact file name stem; the extension is what varies by template type. */
    private static final String STEM = "Template."; //$NON-NLS-1$

    /**
     * The name this plugin used to write for every type. Kept as a read-only fallback so a project
     * that already received one of those artifacts still resolves instead of reporting nothing.
     */
    private static final String LEGACY_SPREADSHEET_NAME = "Template.mxl"; //$NON-NLS-1$

    private static final Map<String, String> EXTENSIONS = new LinkedHashMap<>();

    static {
        EXTENSIONS.put(key("SPREADSHEET_DOCUMENT"), "mxlx"); //$NON-NLS-1$ //$NON-NLS-2$
        EXTENSIONS.put(key("TEXT_DOCUMENT"), "txt"); //$NON-NLS-1$ //$NON-NLS-2$
        EXTENSIONS.put(key("BINARY_DATA"), "bin"); //$NON-NLS-1$ //$NON-NLS-2$
        EXTENSIONS.put(key("HTML_DOCUMENT"), "htmldoc"); //$NON-NLS-1$ //$NON-NLS-2$
        EXTENSIONS.put(key("ACTIVE_DOCUMENT"), "axdt"); //$NON-NLS-1$ //$NON-NLS-2$
        EXTENSIONS.put(key("GEOGRAPHICAL_SCHEMA"), "geos"); //$NON-NLS-1$ //$NON-NLS-2$
        EXTENSIONS.put(key("DATA_COMPOSITION_SCHEMA"), "dcs"); //$NON-NLS-1$ //$NON-NLS-2$
        EXTENSIONS.put(key("DATA_COMPOSITION_APPEARANCE_TEMPLATE"), "dcsat"); //$NON-NLS-1$ //$NON-NLS-2$
        EXTENSIONS.put(key("ADD_IN"), "addin"); //$NON-NLS-1$ //$NON-NLS-2$
        // EDT spells this one "GraphicalScheme" while the EMF constant is GRAPHICAL_SCHEMA, so the
        // two normalise differently and both spellings have to be registered.
        EXTENSIONS.put(key("GRAPHICAL_SCHEMA"), "scheme"); //$NON-NLS-1$ //$NON-NLS-2$
        EXTENSIONS.put(key("GRAPHICAL_SCHEME"), "scheme"); //$NON-NLS-1$ //$NON-NLS-2$
        // Same shape, for symmetry with how EDT names the geographical one.
        EXTENSIONS.put(key("GEOGRAPHICAL_SCHEME"), "geos"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private TemplateArtifactPath() {
        // utility
    }

    /** The artifact extension for a template type, or {@code null} when the type is unknown. */
    static String extensionFor(String templateType) {
        if (templateType == null || templateType.isBlank()) {
            return null;
        }
        return EXTENSIONS.get(key(templateType));
    }

    /** The artifact file name for a template type, or {@code null} when the type is unknown. */
    static String fileNameFor(String templateType) {
        String extension = extensionFor(templateType);
        return extension == null ? null : STEM + extension;
    }

    /**
     * Names to look for when reading, most correct first. For spreadsheets the legacy
     * {@code Template.mxl} this plugin used to write is included as a second chance.
     */
    static List<String> candidateFileNames(String templateType) {
        String preferred = fileNameFor(templateType);
        if (preferred == null) {
            return List.of();
        }
        List<String> candidates = new ArrayList<>(2);
        candidates.add(preferred);
        if (!LEGACY_SPREADSHEET_NAME.equals(preferred) && isSpreadsheet(templateType)) {
            candidates.add(LEGACY_SPREADSHEET_NAME);
        }
        return List.copyOf(candidates);
    }

    /** Whether this type is the spreadsheet one, whichever spelling it arrived in. */
    static boolean isSpreadsheet(String templateType) {
        return templateType != null && key("SPREADSHEET_DOCUMENT").equals(key(templateType)); //$NON-NLS-1$
    }

    /**
     * Whether the artifact of this type is owned by the DCS service rather than by the generic
     * template path.
     */
    static boolean isDataCompositionManaged(String templateType) {
        if (templateType == null) {
            return false;
        }
        String normalized = key(templateType);
        return key("DATA_COMPOSITION_SCHEMA").equals(normalized) //$NON-NLS-1$
                || key("DATA_COMPOSITION_APPEARANCE_TEMPLATE").equals(normalized); //$NON-NLS-1$
    }

    /** Case- and separator-insensitive key, so constant names and literals collapse together. */
    private static String key(String value) {
        StringBuilder normalized = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                normalized.append(Character.toLowerCase(c));
            }
        }
        return normalized.toString();
    }
}
