/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Compares the TOP-LEVEL keys of an incoming argument map against a tool's advertised JSON schema
 * and works out what an unrecognised key was probably meant to be.
 *
 * <p>Why this exists: a mistyped or misplaced parameter used to be discarded without a word. The
 * proven case (live, 2026-07-29) is {@code add_metadata_child} called with {@code type} at the top
 * level instead of inside {@code properties}: {@code edt_validate_request} answered
 * {@code valid:true} with a {@code normalizedPayload} that no longer contained {@code type} at all
 * and issued a token, and the mutation then took its "no type requested" branch and wrote the
 * DEFAULT {@code String(150)} into the .mdo — reporting full success. Two earlier live instances of
 * the same class: {@code scan_metadata_index} with {@code kinds} instead of {@code scope} (returned
 * everything, unfiltered, silently) and {@code get_diagnostics} with {@code project} instead of
 * {@code project_name} (fell back to the default project). A silent drop turns a caller typo into
 * wrong data plus a success report, which is the worst possible outcome.</p>
 *
 * <p><strong>Top level only.</strong> Nested free-form objects ({@code properties}, {@code changes},
 * {@code set}, operation descriptors) are legitimately open-ended — every kind of metadata object
 * accepts its own property names there — so this class never recurses into their contents to judge
 * them. It only <em>reads</em> the nested declarations to answer "which container does this key
 * belong to?".</p>
 *
 * <p><strong>Fail-open.</strong> A schema that cannot be parsed, declares no {@code properties}, or
 * explicitly says {@code additionalProperties: true} yields an empty (clean) report, so a tool whose
 * schema under-declares its pass-through keys can never be broken by this guard.</p>
 *
 * <p>Pure plumbing: string/JSON handling only, no EDT runtime, so it is exhaustively unit-testable
 * (see {@code SchemaKeyGuardTest}).</p>
 */
public final class SchemaKeyGuard {

    /** Words shorter than this are not harvested from description prose (too noisy to match on). */
    private static final int MIN_VOCABULARY_TOKEN_LENGTH = 3;

    /** Maximum edit distance still reported as a "did you mean" typo candidate. */
    private static final int MAX_TYPO_DISTANCE = 2;

    /** Keys shorter than this are not matched by edit distance (too many false neighbours). */
    private static final int MIN_TYPO_KEY_LENGTH = 4;

    /** Upper bound on how many accepted key names a message spells out. */
    private static final int MAX_LISTED_ACCEPTED_KEYS = 30;

    private SchemaKeyGuard() {
    }

    /**
     * One unrecognised top-level key plus the best guess at what was meant.
     *
     * @param key         the key as the caller spelled it
     * @param suggestion  the probable intent — an accepted top-level key, or
     *                    {@code container.key} when the key is documented as living inside a nested
     *                    container — or {@code null} when nothing plausible was found
     */
    public record UnknownKey(String key, String suggestion) {
    }

    /**
     * The verdict for one argument map.
     *
     * @param unknownKeys  unrecognised keys, in the order the caller's map iterated them
     * @param acceptedKeys every top-level key the schema (plus any extra accepted keys) allows
     * @param enforceable  whether the schema could be used at all; {@code false} means fail-open and
     *                     {@code unknownKeys} is always empty
     */
    public record Report(List<UnknownKey> unknownKeys, Set<String> acceptedKeys, boolean enforceable) {

        public boolean isClean() {
            return unknownKeys.isEmpty();
        }
    }

    private static final Report CLEAN_UNENFORCEABLE = new Report(List.of(), Set.of(), false);

    public static Report inspect(String schemaJson, Collection<?> actualKeys) {
        return inspect(schemaJson, actualKeys, Set.of());
    }

    /**
     * Inspects {@code actualKeys} against {@code schemaJson}.
     *
     * @param extraAcceptedKeys keys the consumer accepts even though the schema does not advertise
     *                          them (e.g. camelCase aliases a normalizer reads). A key that IS read
     *                          somewhere is by definition not silently discarded, so it must never
     *                          be reported.
     */
    public static Report inspect(String schemaJson, Collection<?> actualKeys, Set<String> extraAcceptedKeys) {
        JsonObject properties = declaredProperties(schemaJson);
        if (properties == null) {
            return CLEAN_UNENFORCEABLE;
        }
        Set<String> accepted = new LinkedHashSet<>(properties.keySet());
        if (extraAcceptedKeys != null) {
            accepted.addAll(extraAcceptedKeys);
        }
        if (actualKeys == null || actualKeys.isEmpty()) {
            return new Report(List.of(), accepted, true);
        }

        Map<String, Set<String>> containers = null;
        List<UnknownKey> unknown = new ArrayList<>();
        for (Object rawKey : actualKeys) {
            if (rawKey == null) {
                continue;
            }
            String key = String.valueOf(rawKey);
            if (accepted.contains(key)) {
                continue;
            }
            if (containers == null) {
                containers = containerVocabularies(properties);
            }
            unknown.add(new UnknownKey(key, suggestFor(key, accepted, containers)));
        }
        return new Report(List.copyOf(unknown), accepted, true);
    }

    /**
     * Returns the schema's top-level {@code properties} object, or {@code null} when the schema
     * cannot be enforced (unparsable, no properties, or extra keys explicitly allowed).
     */
    static JsonObject declaredProperties(String schemaJson) {
        if (schemaJson == null || schemaJson.isBlank()) {
            return null;
        }
        try {
            JsonElement root = JsonParser.parseString(schemaJson);
            if (!root.isJsonObject()) {
                return null;
            }
            JsonObject object = root.getAsJsonObject();
            if (allowsAdditionalProperties(object)) {
                // The schema states in so many words that it does not enumerate its keys
                // (edt_diagnostics, qa_inspect, qa_generate). Refusing or advising there would be
                // guessing against an explicit declaration.
                return null;
            }
            JsonElement properties = object.get("properties"); //$NON-NLS-1$
            if (properties == null || !properties.isJsonObject() || properties.getAsJsonObject().size() == 0) {
                return null;
            }
            return properties.getAsJsonObject();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean allowsAdditionalProperties(JsonObject schema) {
        JsonElement additional = schema.get("additionalProperties"); //$NON-NLS-1$
        return additional != null
                && additional.isJsonPrimitive()
                && additional.getAsJsonPrimitive().isBoolean()
                && additional.getAsBoolean();
    }

    /**
     * Builds, per nested container ({@code object}/{@code array} typed top-level property), the
     * vocabulary of key names it is documented to carry: the property names declared beneath it plus
     * the word tokens of its descriptions. Schema keywords ({@code type}, {@code items},
     * {@code required}) are deliberately NOT harvested — only names and prose — so a container is
     * not matched just because every schema node happens to spell the word "type".
     */
    private static Map<String, Set<String>> containerVocabularies(JsonObject properties) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (String name : properties.keySet()) {
            JsonElement value = properties.get(name);
            if (value == null || !value.isJsonObject()) {
                continue;
            }
            JsonObject spec = value.getAsJsonObject();
            String type = primitiveString(spec, "type"); //$NON-NLS-1$
            if (!"object".equals(type) && !"array".equals(type)) { //$NON-NLS-1$ //$NON-NLS-2$
                continue;
            }
            Set<String> vocabulary = new LinkedHashSet<>();
            harvestVocabulary(spec, vocabulary, 0);
            if (!vocabulary.isEmpty()) {
                result.put(name, vocabulary);
            }
        }
        return result;
    }

    private static void harvestVocabulary(JsonObject node, Set<String> vocabulary, int depth) {
        if (depth > 6) {
            return;
        }
        String description = primitiveString(node, "description"); //$NON-NLS-1$
        if (description != null) {
            for (String token : description.split("[^A-Za-z0-9_]+")) { //$NON-NLS-1$
                if (token.length() >= MIN_VOCABULARY_TOKEN_LENGTH) {
                    vocabulary.add(normalize(token));
                }
            }
        }
        JsonElement nestedProperties = node.get("properties"); //$NON-NLS-1$
        if (nestedProperties != null && nestedProperties.isJsonObject()) {
            JsonObject nested = nestedProperties.getAsJsonObject();
            for (String name : nested.keySet()) {
                vocabulary.add(normalize(name));
                JsonElement child = nested.get(name);
                if (child != null && child.isJsonObject()) {
                    harvestVocabulary(child.getAsJsonObject(), vocabulary, depth + 1);
                }
            }
        }
        JsonElement items = node.get("items"); //$NON-NLS-1$
        if (items != null && items.isJsonObject()) {
            harvestVocabulary(items.getAsJsonObject(), vocabulary, depth + 1);
        } else if (items != null && items.isJsonArray()) {
            JsonArray array = items.getAsJsonArray();
            for (JsonElement element : array) {
                if (element.isJsonObject()) {
                    harvestVocabulary(element.getAsJsonObject(), vocabulary, depth + 1);
                }
            }
        }
    }

    /**
     * Best guess at what {@code unknownKey} was meant to be, in decreasing order of certainty:
     * an exact spelling variant (case / underscores), a singular-plural variant, a key documented
     * inside a nested container, then a short-edit-distance typo.
     */
    static String suggestFor(String unknownKey, Collection<String> acceptedKeys,
            Map<String, Set<String>> containerVocabularies) {
        String normalized = normalize(unknownKey);
        if (normalized.isEmpty()) {
            return null;
        }

        for (String accepted : acceptedKeys) {
            if (normalize(accepted).equals(normalized)) {
                return accepted;
            }
        }
        for (String accepted : acceptedKeys) {
            String acceptedNormalized = normalize(accepted);
            if (acceptedNormalized.equals(normalized + "s") //$NON-NLS-1$
                    || (normalized.endsWith("s") //$NON-NLS-1$
                            && acceptedNormalized.equals(normalized.substring(0, normalized.length() - 1)))) {
                return accepted;
            }
        }
        if (containerVocabularies != null) {
            for (Map.Entry<String, Set<String>> container : containerVocabularies.entrySet()) {
                if (container.getValue().contains(normalized)) {
                    return container.getKey() + "." + unknownKey; //$NON-NLS-1$
                }
            }
        }
        if (normalized.length() >= MIN_TYPO_KEY_LENGTH) {
            String best = null;
            int bestDistance = MAX_TYPO_DISTANCE + 1;
            for (String accepted : new TreeSet<>(acceptedKeys)) {
                int distance = editDistance(normalized, normalize(accepted), bestDistance);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = accepted;
                }
            }
            if (best != null && bestDistance <= MAX_TYPO_DISTANCE) {
                return best;
            }
        }
        return null;
    }

    /** Lowercases and drops separators, so {@code parentFqn}, {@code parent_fqn} and {@code Parent-FQN} agree. */
    static String normalize(String key) {
        if (key == null) {
            return ""; //$NON-NLS-1$
        }
        StringBuilder sb = new StringBuilder(key.length());
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c == '_' || c == '-' || c == ' ' || c == '.') {
                continue;
            }
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    /** Levenshtein distance, abandoned as soon as it can only exceed {@code limit}. */
    static int editDistance(String a, String b, int limit) {
        if (Math.abs(a.length() - b.length()) > limit) {
            return limit + 1;
        }
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            int rowMinimum = current[0];
            for (int j = 1; j <= b.length(); j++) {
                int substitution = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), substitution);
                rowMinimum = Math.min(rowMinimum, current[j]);
            }
            if (rowMinimum > limit) {
                return limit + 1;
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

    // --- messages ------------------------------------------------------------

    /**
     * The refusal {@code edt_validate_request} returns instead of a token. Deliberately states that
     * no token was issued and WHY silently dropping the key would have been worse than refusing.
     */
    public static String refusalMessage(String operationName, List<UnknownKey> unknownKeys,
            Collection<String> acceptedKeys) {
        StringBuilder sb = new StringBuilder();
        sb.append("payload contains "); //$NON-NLS-1$
        sb.append(unknownKeys.size() == 1
                ? "an unknown top-level key" //$NON-NLS-1$
                : unknownKeys.size() + " unknown top-level keys"); //$NON-NLS-1$
        sb.append(" for operation '").append(operationName).append("': "); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append(describe(unknownKeys));
        sb.append(". No validation token was issued: an unknown key is dropped silently, so the" //$NON-NLS-1$
                + " mutation would have run with a default value instead of the one you passed."); //$NON-NLS-1$
        appendAccepted(sb, "Accepted top-level payload keys", acceptedKeys); //$NON-NLS-1$
        return sb.toString();
    }

    /**
     * The advisory line appended to a tool result. Never fails a call — it only states that a key
     * had no effect, so a tool whose schema under-declares a pass-through key loses nothing but the
     * accuracy of this note.
     */
    public static String advisoryLine(String toolName, List<UnknownKey> unknownKeys,
            Collection<String> acceptedKeys) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n\nNote: ").append(toolName).append(" ignored "); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append(unknownKeys.size() == 1
                ? "an unknown parameter" //$NON-NLS-1$
                : unknownKeys.size() + " unknown parameters"); //$NON-NLS-1$
        sb.append(": ").append(describe(unknownKeys)); //$NON-NLS-1$
        sb.append(". It had no effect on this call."); //$NON-NLS-1$
        appendAccepted(sb, "Accepted parameters", acceptedKeys); //$NON-NLS-1$
        return sb.toString();
    }

    private static String describe(List<UnknownKey> unknownKeys) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < unknownKeys.size(); i++) {
            if (i > 0) {
                sb.append(", "); //$NON-NLS-1$
            }
            UnknownKey unknown = unknownKeys.get(i);
            sb.append('\'').append(unknown.key()).append('\'');
            if (unknown.suggestion() != null) {
                sb.append(" (did you mean '").append(unknown.suggestion()).append("'?)"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        return sb.toString();
    }

    /** Shared by {@link CompositeCommandKeyGuard} so both guards spell "accepted keys" identically. */
    static void appendAccepted(StringBuilder sb, String label, Collection<String> acceptedKeys) {
        if (acceptedKeys == null || acceptedKeys.isEmpty()) {
            return;
        }
        sb.append(' ').append(label).append(": "); //$NON-NLS-1$
        int listed = 0;
        for (String key : acceptedKeys) {
            if (listed == MAX_LISTED_ACCEPTED_KEYS) {
                sb.append(", …"); //$NON-NLS-1$
                break;
            }
            if (listed > 0) {
                sb.append(", "); //$NON-NLS-1$
            }
            sb.append(key);
            listed++;
        }
        sb.append('.');
    }

    /** Sorted view used when a message spells the accepted keys out. */
    public static List<String> forDisplay(Collection<String> keys, Collection<String> hidden) {
        Set<String> sorted = new TreeSet<>();
        for (String key : keys) {
            if (hidden == null || !hidden.contains(key)) {
                sorted.add(key);
            }
        }
        return List.copyOf(sorted);
    }

    private static String primitiveString(JsonObject node, String member) {
        JsonElement element = node.get(member);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            return null;
        }
        return element.getAsString();
    }
}
