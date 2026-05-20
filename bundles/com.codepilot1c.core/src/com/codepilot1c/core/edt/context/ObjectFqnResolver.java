/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edt.context;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Pure-Java parser for {@code Type.Name} EDT metadata FQNs plus a resolver
 * from {@code ParsedFqn} to the standard BSL module file paths a managed
 * configuration uses (e.g. {@code Documents/X/Ext/ObjectModule.bsl}).
 *
 * <p>Used by {@code bsl_object_context} to:
 * <ul>
 *   <li>Translate a single {@code object_fqn} into the set of module files
 *       it controls — for {@code methods} / {@code exports_only} inclusion.</li>
 *   <li>Build form module paths once the orchestrator knows form names —
 *       for the {@code form_layout} inclusion.</li>
 * </ul></p>
 *
 * <p>Why pure Java: the EDT EMF Configuration model can answer the same
 * question via reflection, but only at runtime against an open project.
 * The resolver here works without an open project — useful for FQN
 * validation, for unit tests, and for early-exit error messages.</p>
 */
public class ObjectFqnResolver {

    /**
     * The metadata-object kinds the resolver knows about. Mirrors the
     * switch in {@code EdtMetadataInspectorService.findMdObjectByFqn} so
     * the FQN surface stays consistent with the existing tooling.
     */
    public enum ObjectKind {
        CATALOG               ("Catalog",               "Catalogs",             List.of("ObjectModule.bsl", "ManagerModule.bsl")),
        DOCUMENT              ("Document",              "Documents",            List.of("ObjectModule.bsl", "ManagerModule.bsl")),
        COMMON_MODULE         ("CommonModule",          "CommonModules",        List.of("Module.bsl")),
        INFORMATION_REGISTER  ("InformationRegister",   "InformationRegisters", List.of("RecordSetModule.bsl", "ManagerModule.bsl")),
        ACCUMULATION_REGISTER ("AccumulationRegister",  "AccumulationRegisters",List.of("RecordSetModule.bsl", "ManagerModule.bsl")),
        REPORT                ("Report",                "Reports",              List.of("ObjectModule.bsl", "ManagerModule.bsl")),
        DATA_PROCESSOR        ("DataProcessor",         "DataProcessors",       List.of("ObjectModule.bsl", "ManagerModule.bsl")),
        ENUM                  ("Enum",                  "Enums",                List.of()),
        CONSTANT              ("Constant",              "Constants",            List.of("ValueManagerModule.bsl", "ManagerModule.bsl"));

        private final String keyword;
        private final String folderName;
        private final List<String> moduleFileNames;

        ObjectKind(String keyword, String folderName, List<String> moduleFileNames) {
            this.keyword = keyword;
            this.folderName = folderName;
            this.moduleFileNames = moduleFileNames;
        }

        public String keyword() {
            return keyword;
        }

        public String folderName() {
            return folderName;
        }

        public List<String> moduleFileNames() {
            return moduleFileNames;
        }
    }

    /** Parsed result of an FQN like {@code Document.SalesOrder}. */
    public record ParsedFqn(ObjectKind kind, String name) {}

    private static final Map<String, ObjectKind> KEYWORD_INDEX;

    static {
        java.util.HashMap<String, ObjectKind> idx = new java.util.HashMap<>();
        for (ObjectKind kind : ObjectKind.values()) {
            String lower = kind.keyword.toLowerCase(Locale.ROOT);
            idx.put(lower, kind);
            // Plural alias — mirrors EdtMetadataInspectorService.
            idx.put(lower + "s", kind);
        }
        KEYWORD_INDEX = Collections.unmodifiableMap(idx);
    }

    public Optional<ParsedFqn> parse(String fqn) {
        if (fqn == null) {
            return Optional.empty();
        }
        String trimmed = fqn.trim();
        int dot = trimmed.indexOf('.');
        if (dot <= 0 || dot == trimmed.length() - 1) {
            return Optional.empty();
        }
        String keyword = trimmed.substring(0, dot).toLowerCase(Locale.ROOT);
        String name = trimmed.substring(dot + 1).trim();
        if (name.isEmpty()) {
            return Optional.empty();
        }
        ObjectKind kind = KEYWORD_INDEX.get(keyword);
        if (kind == null) {
            return Optional.empty();
        }
        return Optional.of(new ParsedFqn(kind, name));
    }

    /**
     * Returns the standard module file paths (relative to the project
     * {@code src/}) that the object owns under the EDT convention.
     * Empty list for kinds without BSL modules (currently {@link ObjectKind#ENUM}).
     *
     * <p>EDT layout has no {@code /Ext/} segment — that is the legacy
     * configurator-XML-dump format. EDT places module files directly
     * inside the object folder, e.g. {@code Documents/SalesOrder/ObjectModule.bsl}
     * (not {@code Documents/SalesOrder/Ext/ObjectModule.bsl}).</p>
     */
    public List<String> standardModulePaths(ParsedFqn parsed) {
        if (parsed == null || parsed.kind().moduleFileNames().isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        String base = parsed.kind().folderName() + "/" + parsed.name() + "/";
        for (String moduleFile : parsed.kind().moduleFileNames()) {
            out.add(base + moduleFile);
        }
        return out;
    }

    /**
     * Returns the form module path for {@code formName} on the given
     * object, or {@code null} if {@code parsed.kind()} cannot host forms
     * with BSL modules in the standard convention (Enums).
     *
     * <p>EDT layout: {@code Documents/X/Forms/DocumentForm/Module.bsl}
     * (no {@code /Ext/Form/} segment — that was the legacy configurator
     * XML dump format).</p>
     */
    public String formModulePath(ParsedFqn parsed, String formName) {
        if (parsed == null || formName == null || formName.isEmpty()) {
            return null;
        }
        if (parsed.kind() == ObjectKind.ENUM) {
            return null;
        }
        return parsed.kind().folderName() + "/" + parsed.name()
                + "/Forms/" + formName + "/Module.bsl";
    }

    /** Convenience for callers that want to enumerate all known kinds. */
    public List<ObjectKind> knownKinds() {
        return Arrays.asList(ObjectKind.values());
    }
}
