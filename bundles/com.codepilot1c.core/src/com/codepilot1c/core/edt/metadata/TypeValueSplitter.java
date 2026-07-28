package com.codepilot1c.core.edt.metadata;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Splits a user-supplied {@code type} value into one <em>carrier</em> per requested type.
 *
 * <p>A carrier is the object a single-type normalizer can consume on its own: either the
 * original scalar/map the caller passed (when the value asks for exactly one type) or, for a
 * composite request, a synthetic map that pairs one element with the qualifier siblings that
 * surrounded the list. The metadata service then turns every carrier into its own
 * {@code TypeSpec}, so {@code ["String(100)", "CatalogRef.Goods"]} keeps the length on the
 * String element instead of collapsing the whole request.</p>
 *
 * <p>Why this exists: every {@code type} shape the tools accept
 * ({@code "CatalogRef.Goods"}, {@code ["CatalogRef.A", "CatalogRef.B"]},
 * {@code {type:"String", length:100}}, {@code {types:[…], stringQualifiers:{…}}}) used to be
 * funnelled through a first-element-wins normalizer. A composite request therefore wrote only
 * its first type and reported success — a silent drop. Splitting is separated from
 * normalization because the split is pure map/list plumbing and is unit-testable without the
 * EDT runtime.</p>
 *
 * <p><strong>Single-type identity.</strong> Whenever the value asks for one type, the carrier
 * list holds the caller's own object (a map is returned by identity, not copied), so the
 * downstream normalizer sees byte-identical input to what it saw before this class existed.
 * The one deliberate exception is a single-element list: {@code ["String(100)"]} now yields the
 * element rather than the list, which is what makes its inline qualifier readable — the same
 * qualifier the two-element form has always needed.</p>
 */
public final class TypeValueSplitter {

    /** The two interchangeable keys a type-carrying map may use for its payload. */
    private static final String KEY_TYPE = "type"; //$NON-NLS-1$
    private static final String KEY_TYPES = "types"; //$NON-NLS-1$

    private TypeValueSplitter() {
    }

    /**
     * Returns one carrier per requested type, in request order.
     *
     * <p>An empty result means "nothing was actually requested" (a {@code null}, a blank
     * string, an empty list, a list of blanks); callers decide whether that is an error or the
     * legitimate "any / not specified" state. Never returns {@code null}.</p>
     */
    public static List<Object> split(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            List<Object> carriers = new ArrayList<>(list.size());
            for (Object element : list) {
                carriers.addAll(split(element));
            }
            return carriers;
        }
        if (value instanceof Map<?, ?> map) {
            return splitMap(map);
        }
        if (value instanceof String str) {
            return str.isBlank() ? List.of() : List.of(value);
        }
        return List.of(value);
    }

    /**
     * Splits a type-carrying map. The map is returned as-is unless its {@code type}/{@code types}
     * payload asks for more than one type — that keeps the single-type path byte-identical and
     * confines the synthetic-carrier machinery to the composite case.
     */
    private static List<Object> splitMap(Map<?, ?> map) {
        Object payloadKey = findKeyIgnoreCase(map, KEY_TYPE);
        if (payloadKey == null) {
            payloadKey = findKeyIgnoreCase(map, KEY_TYPES);
        }
        if (payloadKey == null) {
            return List.of(map);
        }
        List<Object> elements = split(map.get(payloadKey));
        if (elements.size() <= 1) {
            return List.of(map);
        }
        List<Object> carriers = new ArrayList<>(elements.size());
        for (Object element : elements) {
            carriers.add(mergeCarrier(map, element));
        }
        return carriers;
    }

    /**
     * Builds the carrier for one element of a composite map request: the surrounding map's
     * qualifier siblings, then the element's own entries (which win), then the element itself
     * under the canonical {@code type} key.
     *
     * <p>The element must land under {@code type} explicitly. The surrounding map may be a whole
     * {@code properties} payload carrying unrelated keys such as {@code name}, and the lookup
     * that reads a carrier falls back to {@code name} when no {@code type} is present — an
     * element left implicit would then be resolved as the attribute's own name.</p>
     */
    private static Object mergeCarrier(Map<?, ?> outer, Object element) {
        Map<String, Object> carrier = new LinkedHashMap<>();
        copyNonPayloadEntries(outer, carrier);
        if (element instanceof Map<?, ?> elementMap) {
            copyNonPayloadEntries(elementMap, carrier);
        }
        carrier.put(KEY_TYPE, element);
        return carrier;
    }

    private static void copyNonPayloadEntries(Map<?, ?> source, Map<String, Object> target) {
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            Object rawKey = entry.getKey();
            String key = rawKey instanceof String str ? str : String.valueOf(rawKey);
            if (KEY_TYPE.equalsIgnoreCase(key) || KEY_TYPES.equalsIgnoreCase(key)) {
                continue;
            }
            target.put(key, entry.getValue());
        }
    }

    /**
     * Returns the map's own spelling of {@code key} (the tools accept {@code type},
     * {@code Type}, {@code TYPE}), so the value can be read back with the exact key.
     */
    private static Object findKeyIgnoreCase(Map<?, ?> map, String key) {
        if (map.isEmpty()) {
            return null;
        }
        if (map.containsKey(key)) {
            return key;
        }
        for (Object rawKey : map.keySet()) {
            if (rawKey instanceof String str && str.equalsIgnoreCase(key)) {
                return rawKey;
            }
        }
        return null;
    }
}
