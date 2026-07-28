package com.codepilot1c.core.edt.metadata;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pure-Java decision table for the {@code DynamicListExtInfo} properties of a
 * form attribute (BF-13330).
 *
 * <p>Background: a dynamic list's interesting properties —
 * {@code customQuery}, {@code queryText}, {@code mainTable},
 * {@code autoFillAvailableFields}, … — do <strong>not</strong> live on
 * {@code FormAttribute}. They live on the attribute's
 * {@code extInfo} ({@code form:DynamicListExtInfo}). The generic feature
 * resolver in {@code EdtMetadataService} only ever inspects
 * {@code target.eClass()}, so a flat {@code set:{customQuery:true}} was
 * rejected with the misleading {@code Unknown form property: customQuery}
 * even though the property exists — just one level deeper.</p>
 *
 * <p>This class encapsulates the <em>decision</em>: which flat keys belong to
 * {@code DynamicListExtInfo} and what their canonical EMF feature names are.
 * The EMF mutations themselves live in {@code EdtMetadataService}, where
 * {@code FormFactory} / {@code DynamicListAttributeService} are available.</p>
 *
 * <p><strong>Strict whitelist on purpose.</strong> The tempting generic rule
 * ("no such feature on the target → look inside extInfo") would silently
 * swallow typos for <em>every</em> ExtInfo kind (ValueTable / ValueTree / DCS),
 * turning a fail-loud {@code Unknown form property} into a no-op. Only the keys
 * listed here are hoisted; everything else keeps failing loudly.</p>
 */
public final class DynamicListExtInfoRules {

    private DynamicListExtInfoRules() { }

    /**
     * Canonical {@code DynamicListExtInfo} feature name, keyed by the
     * normalized (case/underscore/dash-insensitive) caller token.
     *
     * <p>Feature set taken from the decompiled EDT 2025.2.3
     * {@code com._1c.g5.v8.dt.form.model.DynamicListExtInfo}. The only alias is
     * {@code query} → {@code queryText}: callers reach for the short name
     * because that is what the form editor's field is labelled.</p>
     */
    private static final Map<String, String> CANONICAL_BY_TOKEN;
    static {
        Map<String, String> canonical = new LinkedHashMap<>();
        canonical.put("customquery", "customQuery"); //$NON-NLS-1$ //$NON-NLS-2$
        canonical.put("querytext", "queryText"); //$NON-NLS-1$ //$NON-NLS-2$
        canonical.put("query", "queryText"); //$NON-NLS-1$ //$NON-NLS-2$
        canonical.put("maintable", "mainTable"); //$NON-NLS-1$ //$NON-NLS-2$
        canonical.put("autofillavailablefields", "autoFillAvailableFields"); //$NON-NLS-1$ //$NON-NLS-2$
        canonical.put("dynamicdataread", "dynamicDataRead"); //$NON-NLS-1$ //$NON-NLS-2$
        canonical.put("autosaveusersettings", "autoSaveUserSettings"); //$NON-NLS-1$ //$NON-NLS-2$
        canonical.put("getinvisiblefieldpresentations", "getInvisibleFieldPresentations"); //$NON-NLS-1$ //$NON-NLS-2$
        canonical.put("keytype", "keyType"); //$NON-NLS-1$ //$NON-NLS-2$
        canonical.put("keyfield", "keyField"); //$NON-NLS-1$ //$NON-NLS-2$
        canonical.put("fields", "fields"); //$NON-NLS-1$ //$NON-NLS-2$
        canonical.put("calculatedfields", "calculatedFields"); //$NON-NLS-1$ //$NON-NLS-2$
        canonical.put("parameters", "parameters"); //$NON-NLS-1$ //$NON-NLS-2$
        canonical.put("listsettings", "listSettings"); //$NON-NLS-1$ //$NON-NLS-2$
        CANONICAL_BY_TOKEN = Collections.unmodifiableMap(canonical);
    }

    /**
     * DCS containment collections of {@code DynamicListExtInfo} that the plugin
     * recognizes but deliberately refuses to author.
     *
     * <p>Rationale: the EDT form editor's own
     * {@code ChangeDynamicListExtInfoCustomQueryTask} never populates them
     * (flipping {@code customQuery} on only <em>clears</em> them), and no live
     * {@code .form} with {@code customQuery=true} carries a {@code <fields>}
     * block — the platform derives the available fields from {@code queryText}
     * when {@code autoFillAvailableFields=true}. Authoring a
     * {@code DataSetField} / {@code DataCompositionSchemaParameter} tree by
     * hand is a separate feature, so the honest answer is a refusal, not a
     * silent ignore.</p>
     */
    public static final Set<String> UNSUPPORTED_CONTAINMENT_FEATURES = Collections.unmodifiableSet(
            new LinkedHashSet<>(Set.of(
                    "fields", //$NON-NLS-1$
                    "calculatedFields", //$NON-NLS-1$
                    "parameters", //$NON-NLS-1$
                    "listSettings"))); //$NON-NLS-1$

    /**
     * The canonical {@code DynamicListExtInfo} feature name for a caller-supplied
     * key, or {@code null} when the key is not a recognized dynamic-list property.
     */
    public static String canonicalKey(String key) {
        String token = normalize(key);
        if (token.isEmpty()) {
            return null;
        }
        return CANONICAL_BY_TOKEN.get(token);
    }

    /**
     * Whether the given key addresses a property that exists <em>only</em> on
     * {@code DynamicListExtInfo} and never on {@code FormAttribute} or a form
     * item. Drives the actionable hint the generic feature resolver emits when
     * such a key arrives at the wrong target.
     */
    public static boolean isDynamicListOnlyKey(String key) {
        return canonicalKey(key) != null;
    }

    /** Whether the canonical feature is one of the refused DCS containment collections. */
    public static boolean isUnsupportedContainmentFeature(String canonicalName) {
        return canonicalName != null && UNSUPPORTED_CONTAINMENT_FEATURES.contains(canonicalName);
    }

    /**
     * Consume every recognized dynamic-list key from {@code set} and return them
     * under their canonical feature names.
     *
     * <p>Mutates {@code set} in place (the caller always owns a defensive copy),
     * so the keys are not retried by the downstream generic
     * {@code applyFormPropertySet} pass — the same contract the
     * TypeDescription-qualifier hoist already follows.</p>
     */
    public static Map<String, Object> hoist(Map<String, Object> set) {
        Map<String, Object> hoisted = new LinkedHashMap<>();
        if (set == null || set.isEmpty()) {
            return hoisted;
        }
        Iterator<Map.Entry<String, Object>> iterator = set.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Object> entry = iterator.next();
            String canonical = canonicalKey(entry.getKey());
            if (canonical == null) {
                continue;
            }
            hoisted.put(canonical, entry.getValue());
            iterator.remove();
        }
        return hoisted;
    }

    /**
     * Rewrite the keys of an explicit {@code extInfo:{...}} block to canonical
     * feature names, leaving unrecognized keys untouched (they still reach the
     * generic resolver against the real {@code ExtInfo} eClass).
     *
     * <p>Canonicalizing both sides is what lets the explicit block win over a
     * flat key deterministically: a plain {@code putAll} on canonical names
     * cannot miss a case/alias variant of the same feature.</p>
     */
    public static Map<String, Object> canonicalize(Map<String, Object> set) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (set == null || set.isEmpty()) {
            return result;
        }
        for (Map.Entry<String, Object> entry : set.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank()) {
                continue;
            }
            String canonical = canonicalKey(key);
            result.put(canonical != null ? canonical : key, entry.getValue());
        }
        return result;
    }

    // ---- helpers --------------------------------------------------------

    private static String normalize(String key) {
        if (key == null) {
            return ""; //$NON-NLS-1$
        }
        return key.trim()
                .toLowerCase(Locale.ROOT)
                .replace("_", "") //$NON-NLS-1$ //$NON-NLS-2$
                .replace("-", "") //$NON-NLS-1$ //$NON-NLS-2$
                .replace(" ", ""); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
