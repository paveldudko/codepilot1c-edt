package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

/**
 * Tests for {@link DynamicListExtInfoRules} (BF-13330).
 *
 * <p>Pins the whitelist that lets a flat {@code set:{customQuery:true}} reach the
 * attribute's {@code form:DynamicListExtInfo} instead of dying on
 * {@code Unknown form property: customQuery}. The two properties that matter:
 * recognized keys are hoisted under their canonical EMF feature names, and
 * <em>nothing else</em> is — a generic "look inside extInfo when the target has
 * no such feature" rule would silently swallow typos for every ExtInfo kind.</p>
 */
public class DynamicListExtInfoRulesTest {

    // --- recognition + normalization ----------------------------------------

    @Test
    public void recognizesEveryDynamicListFeature() {
        assertEquals("customQuery", DynamicListExtInfoRules.canonicalKey("customQuery")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("queryText", DynamicListExtInfoRules.canonicalKey("queryText")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("mainTable", DynamicListExtInfoRules.canonicalKey("mainTable")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("autoFillAvailableFields", //$NON-NLS-1$
                DynamicListExtInfoRules.canonicalKey("autoFillAvailableFields")); //$NON-NLS-1$
        assertEquals("dynamicDataRead", DynamicListExtInfoRules.canonicalKey("dynamicDataRead")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("autoSaveUserSettings", //$NON-NLS-1$
                DynamicListExtInfoRules.canonicalKey("autoSaveUserSettings")); //$NON-NLS-1$
        assertEquals("getInvisibleFieldPresentations", //$NON-NLS-1$
                DynamicListExtInfoRules.canonicalKey("getInvisibleFieldPresentations")); //$NON-NLS-1$
        assertEquals("keyType", DynamicListExtInfoRules.canonicalKey("keyType")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("keyField", DynamicListExtInfoRules.canonicalKey("keyField")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("fields", DynamicListExtInfoRules.canonicalKey("fields")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("calculatedFields", DynamicListExtInfoRules.canonicalKey("calculatedFields")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("parameters", DynamicListExtInfoRules.canonicalKey("parameters")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("listSettings", DynamicListExtInfoRules.canonicalKey("listSettings")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void normalizesSnakeKebabAndCase() {
        // Callers write whatever their JSON style dictates; all of these are the same feature.
        assertEquals("customQuery", DynamicListExtInfoRules.canonicalKey("custom_query")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("customQuery", DynamicListExtInfoRules.canonicalKey("custom-query")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("customQuery", DynamicListExtInfoRules.canonicalKey("CUSTOMQUERY")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("customQuery", DynamicListExtInfoRules.canonicalKey("  CustomQuery  ")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("autoFillAvailableFields", //$NON-NLS-1$
                DynamicListExtInfoRules.canonicalKey("auto_fill_available_fields")); //$NON-NLS-1$
        assertEquals("mainTable", DynamicListExtInfoRules.canonicalKey("main_table")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void queryIsAnAliasOfQueryText() {
        // The form editor labels the field "query", so callers reach for the short name.
        assertEquals("queryText", DynamicListExtInfoRules.canonicalKey("query")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("queryText", DynamicListExtInfoRules.canonicalKey("Query")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // --- no false positives -------------------------------------------------

    @Test
    public void plainFormAttributeKeysAreNotDynamicListKeys() {
        // The critical negative case: hoisting a generic FormAttribute key would silently
        // divert it into extInfo instead of applying it to the attribute.
        assertNull(DynamicListExtInfoRules.canonicalKey("name")); //$NON-NLS-1$
        assertNull(DynamicListExtInfoRules.canonicalKey("id")); //$NON-NLS-1$
        assertNull(DynamicListExtInfoRules.canonicalKey("main")); //$NON-NLS-1$
        assertNull(DynamicListExtInfoRules.canonicalKey("savedData")); //$NON-NLS-1$
        assertNull(DynamicListExtInfoRules.canonicalKey("saved_data")); //$NON-NLS-1$
        assertNull(DynamicListExtInfoRules.canonicalKey("columns")); //$NON-NLS-1$
        assertNull(DynamicListExtInfoRules.canonicalKey("title")); //$NON-NLS-1$
        assertNull(DynamicListExtInfoRules.canonicalKey("view")); //$NON-NLS-1$
        assertNull(DynamicListExtInfoRules.canonicalKey("edit")); //$NON-NLS-1$
        assertNull(DynamicListExtInfoRules.canonicalKey("useAlways")); //$NON-NLS-1$
        assertNull(DynamicListExtInfoRules.canonicalKey("valueType")); //$NON-NLS-1$
        assertNull(DynamicListExtInfoRules.canonicalKey("extInfo")); //$NON-NLS-1$
    }

    @Test
    public void nearMissesStayUnrecognized() {
        // A typo must keep failing loudly rather than being absorbed as "close enough".
        assertNull(DynamicListExtInfoRules.canonicalKey("customQueryText")); //$NON-NLS-1$
        assertNull(DynamicListExtInfoRules.canonicalKey("queryTxt")); //$NON-NLS-1$
        assertNull(DynamicListExtInfoRules.canonicalKey("mainTabel")); //$NON-NLS-1$
        assertNull(DynamicListExtInfoRules.canonicalKey("autoFillFields")); //$NON-NLS-1$
        assertNull(DynamicListExtInfoRules.canonicalKey(null));
        assertNull(DynamicListExtInfoRules.canonicalKey("")); //$NON-NLS-1$
        assertNull(DynamicListExtInfoRules.canonicalKey("   ")); //$NON-NLS-1$
    }

    @Test
    public void isDynamicListOnlyKeyMirrorsCanonicalKey() {
        assertTrue(DynamicListExtInfoRules.isDynamicListOnlyKey("custom_query")); //$NON-NLS-1$
        assertTrue(DynamicListExtInfoRules.isDynamicListOnlyKey("queryText")); //$NON-NLS-1$
        assertFalse(DynamicListExtInfoRules.isDynamicListOnlyKey("readOnly")); //$NON-NLS-1$
        assertFalse(DynamicListExtInfoRules.isDynamicListOnlyKey("name")); //$NON-NLS-1$
        assertFalse(DynamicListExtInfoRules.isDynamicListOnlyKey(null));
    }

    // --- hoist --------------------------------------------------------------

    @Test
    public void hoistConsumesRecognizedKeysAndLeavesTheRest() {
        Map<String, Object> set = new LinkedHashMap<>();
        set.put("custom_query", Boolean.TRUE); //$NON-NLS-1$
        set.put("query", "SELECT 1"); //$NON-NLS-1$ //$NON-NLS-2$
        set.put("title", "Orders"); //$NON-NLS-1$ //$NON-NLS-2$
        set.put("savedData", Boolean.TRUE); //$NON-NLS-1$

        Map<String, Object> hoisted = DynamicListExtInfoRules.hoist(set);

        assertEquals(Boolean.TRUE, hoisted.get("customQuery")); //$NON-NLS-1$
        assertEquals("SELECT 1", hoisted.get("queryText")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(2, hoisted.size());
        // Consumed in place so the downstream generic resolver does not retry them.
        assertFalse(set.containsKey("custom_query")); //$NON-NLS-1$
        assertFalse(set.containsKey("query")); //$NON-NLS-1$
        assertTrue(set.containsKey("title")); //$NON-NLS-1$
        assertTrue(set.containsKey("savedData")); //$NON-NLS-1$
    }

    @Test
    public void hoistToleratesNullAndEmptyInput() {
        assertTrue(DynamicListExtInfoRules.hoist(null).isEmpty());
        assertTrue(DynamicListExtInfoRules.hoist(new LinkedHashMap<>()).isEmpty());
    }

    // --- explicit extInfo wins ---------------------------------------------

    @Test
    public void explicitExtInfoBlockOverridesFlatKeys() {
        // Both sides are canonicalized first, so a case/alias variant of the same feature
        // cannot slip past the override and end up applied twice with different values.
        Map<String, Object> set = new LinkedHashMap<>();
        set.put("custom_query", Boolean.TRUE); //$NON-NLS-1$
        set.put("query", "flat"); //$NON-NLS-1$ //$NON-NLS-2$
        Map<String, Object> hoisted = DynamicListExtInfoRules.hoist(set);

        Map<String, Object> explicit = new LinkedHashMap<>();
        explicit.put("QUERY_TEXT", "nested"); //$NON-NLS-1$ //$NON-NLS-2$

        Map<String, Object> merged = new LinkedHashMap<>(hoisted);
        merged.putAll(DynamicListExtInfoRules.canonicalize(explicit));

        assertEquals("nested", merged.get("queryText")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Boolean.TRUE, merged.get("customQuery")); //$NON-NLS-1$
        assertEquals(2, merged.size());
    }

    @Test
    public void canonicalizePassesUnknownKeysThrough() {
        // An unrecognized key inside extInfo:{...} must still reach the generic resolver,
        // which is what keeps typos on other ExtInfo kinds fail-loud.
        Map<String, Object> explicit = new LinkedHashMap<>();
        explicit.put("main_table", "Catalog.Products"); //$NON-NLS-1$ //$NON-NLS-2$
        explicit.put("someUnknownFeature", "x"); //$NON-NLS-1$ //$NON-NLS-2$

        Map<String, Object> canonical = DynamicListExtInfoRules.canonicalize(explicit);

        assertEquals("Catalog.Products", canonical.get("mainTable")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("x", canonical.get("someUnknownFeature")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // --- refused containment collections -----------------------------------

    @Test
    public void dcsContainmentCollectionsAreFlaggedUnsupported() {
        assertTrue(DynamicListExtInfoRules.isUnsupportedContainmentFeature("fields")); //$NON-NLS-1$
        assertTrue(DynamicListExtInfoRules.isUnsupportedContainmentFeature("calculatedFields")); //$NON-NLS-1$
        assertTrue(DynamicListExtInfoRules.isUnsupportedContainmentFeature("parameters")); //$NON-NLS-1$
        assertTrue(DynamicListExtInfoRules.isUnsupportedContainmentFeature("listSettings")); //$NON-NLS-1$
        // Scalars stay authorable.
        assertFalse(DynamicListExtInfoRules.isUnsupportedContainmentFeature("customQuery")); //$NON-NLS-1$
        assertFalse(DynamicListExtInfoRules.isUnsupportedContainmentFeature("autoFillAvailableFields")); //$NON-NLS-1$
        assertFalse(DynamicListExtInfoRules.isUnsupportedContainmentFeature(null));
    }
}
