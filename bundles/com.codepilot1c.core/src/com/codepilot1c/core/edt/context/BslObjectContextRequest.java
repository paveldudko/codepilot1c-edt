/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edt.context;

import java.util.Locale;
import java.util.Map;

/**
 * Parsed parameters for the {@code bsl_object_context} tool.
 *
 * <p>The Request is built from the deferred-tool argument map. Every
 * {@code include.*} flag carries a documented default so a caller can
 * issue a minimal request ({@code object_fqn} only) and still receive a
 * useful skeletal response.</p>
 *
 * <p>This is the single source of truth for the include surface. The tool
 * schema, the orchestration service, and the contract tests all key off
 * the field names here.</p>
 */
public record BslObjectContextRequest(
        String projectName,
        String objectFqn,
        MethodInclusion methods,
        boolean exportsOnly,
        boolean attributes,
        boolean tabularSections,
        FormLayoutInclusion formLayout,
        boolean callers,
        boolean callersExportsOnly,
        int callersMaxPerMethod,
        boolean managerModuleMethods,
        boolean objectModuleMethods,
        boolean moduleDirectives) {

    public enum MethodInclusion { NONE, SIGNATURES, BODIES }

    public enum FormLayoutInclusion { NONE, SHALLOW, FULL }

    private static final int DEFAULT_CALLERS_MAX_PER_METHOD = 5;

    /**
     * Parses the raw deferred-tool argument map. Applies the documented
     * defaults and rejects malformed enum values with
     * {@link IllegalArgumentException}.
     */
    public static BslObjectContextRequest fromParameters(Map<String, Object> raw) {
        if (raw == null) {
            throw new IllegalArgumentException("parameters must not be null");
        }
        String projectName = requireString(raw, "project_name");
        String objectFqn = requireString(raw, "object_fqn");

        Map<String, Object> include = asMap(raw.get("include"));
        MethodInclusion methods = parseMethodInclusion(include.get("methods"));
        boolean exportsOnly = asBool(include.get("exports_only"), false);
        boolean attributes = asBool(include.get("attributes"), false);
        boolean tabularSections = asBool(include.get("tabular_sections"), false);
        FormLayoutInclusion formLayout = parseFormLayout(include.get("form_layout"));
        boolean managerModuleMethods = asBool(include.get("manager_module_methods"), true);
        boolean objectModuleMethods = asBool(include.get("object_module_methods"), true);
        boolean moduleDirectives = asBool(include.get("module_directives"), false);

        Object callersRaw = include.get("callers");
        boolean callers = false;
        boolean callersExportsOnly = false;
        int callersMaxPerMethod = DEFAULT_CALLERS_MAX_PER_METHOD;
        if (callersRaw instanceof Boolean b) {
            callers = b;
        } else if (callersRaw instanceof Map<?, ?> m) {
            callers = true;
            @SuppressWarnings("unchecked")
            Map<String, Object> callersMap = (Map<String, Object>) m;
            callersExportsOnly = asBool(callersMap.get("exports_only"), false);
            callersMaxPerMethod = asInt(callersMap.get("max_per_method"), DEFAULT_CALLERS_MAX_PER_METHOD);
        }

        return new BslObjectContextRequest(
                projectName,
                objectFqn,
                methods,
                exportsOnly,
                attributes,
                tabularSections,
                formLayout,
                callers,
                callersExportsOnly,
                callersMaxPerMethod,
                managerModuleMethods,
                objectModuleMethods,
                moduleDirectives);
    }

    // --- helpers -------------------------------------------------------------

    private static String requireString(Map<String, Object> raw, String key) {
        Object value = raw.get(key);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("Missing required parameter: " + key);
        }
        return value.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return Map.of();
    }

    private static boolean asBool(Object value, boolean defaultValue) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            return Boolean.parseBoolean(s);
        }
        return defaultValue;
    }

    private static int asInt(Object value, int defaultValue) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private static MethodInclusion parseMethodInclusion(Object value) {
        if (value == null) {
            return MethodInclusion.SIGNATURES;
        }
        String s = value.toString().trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "none" -> MethodInclusion.NONE;
            case "signatures" -> MethodInclusion.SIGNATURES;
            case "bodies" -> MethodInclusion.BODIES;
            default -> throw new IllegalArgumentException(
                    "Unknown include.methods value: " + value + " (expected: none, signatures, bodies)");
        };
    }

    private static FormLayoutInclusion parseFormLayout(Object value) {
        if (value == null) {
            return FormLayoutInclusion.NONE;
        }
        String s = value.toString().trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "none" -> FormLayoutInclusion.NONE;
            case "shallow" -> FormLayoutInclusion.SHALLOW;
            case "full" -> FormLayoutInclusion.FULL;
            default -> throw new IllegalArgumentException(
                    "Unknown include.form_layout value: " + value + " (expected: none, shallow, full)");
        };
    }
}
