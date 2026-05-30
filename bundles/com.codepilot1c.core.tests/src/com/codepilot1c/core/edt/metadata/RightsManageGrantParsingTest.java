package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.codepilot1c.core.edt.metadata.RightsManageRequest.RightGrant;

/**
 * Pins the parsing contract for {@code rights_manage} grants
 * ({@link RightsManageRequest#parseGrants}). Pure parsing only — resolving the
 * role/object and writing {@code ObjectRight} values runs against a live BM.
 *
 * <p>Background: codepilot1c-feedback 2026-05-30 (BF-12610) — no MCP path existed
 * for Role rights grants, forcing a raw {@code .rights} text edit.</p>
 */
public class RightsManageGrantParsingTest {

    private static final String FIELD = "grants"; //$NON-NLS-1$

    private static Map<String, Object> grant(String objKey, String obj, String rightKey, String right,
            String valueKey, Object value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(objKey, obj);
        map.put(rightKey, right);
        if (valueKey != null) {
            map.put(valueKey, value);
        }
        return map;
    }

    @Test
    public void nullPayloadYieldsNoGrants() {
        assertEquals(List.of(), RightsManageRequest.parseGrants(null, FIELD));
    }

    @Test
    public void valueDefaultsToSetWhenOmitted() {
        List<RightGrant> parsed = RightsManageRequest.parseGrants(
                List.of(grant("object_fqn", "Catalog.OutcomePaymentsTypes", "right", "DeletionMark", null, null)), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                FIELD);
        assertEquals(1, parsed.size());
        assertEquals("Catalog.OutcomePaymentsTypes", parsed.get(0).objectFqn()); //$NON-NLS-1$
        assertEquals("DeletionMark", parsed.get(0).right()); //$NON-NLS-1$
        assertEquals(RightsManageRequest.VALUE_SET, parsed.get(0).value());
    }

    @Test
    public void normalizesValueAliases() {
        assertEquals(RightsManageRequest.VALUE_SET, parseValue("grant")); //$NON-NLS-1$
        assertEquals(RightsManageRequest.VALUE_SET, parseValue("true")); //$NON-NLS-1$
        assertEquals(RightsManageRequest.VALUE_SET, parseValue("allow")); //$NON-NLS-1$
        assertEquals(RightsManageRequest.VALUE_UNSET, parseValue("unset")); //$NON-NLS-1$
        assertEquals(RightsManageRequest.VALUE_UNSET, parseValue("revoke")); //$NON-NLS-1$
        assertEquals(RightsManageRequest.VALUE_UNSET, parseValue("false")); //$NON-NLS-1$
        assertEquals(RightsManageRequest.VALUE_PROVIDED, parseValue("inherit")); //$NON-NLS-1$
        assertEquals(RightsManageRequest.VALUE_PROVIDED, parseValue("provided")); //$NON-NLS-1$
    }

    @Test
    public void acceptsBooleanValueLiteral() {
        assertEquals(RightsManageRequest.VALUE_SET, parseValueRaw(Boolean.TRUE));
        assertEquals(RightsManageRequest.VALUE_UNSET, parseValueRaw(Boolean.FALSE));
    }

    @Test
    public void acceptsObjectAndRightKeyAliases() {
        List<RightGrant> parsed = RightsManageRequest.parseGrants(
                List.of(grant("fqn", "DataProcessor.X", "name", "Use", "value", "set")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
                FIELD);
        assertEquals("DataProcessor.X", parsed.get(0).objectFqn()); //$NON-NLS-1$
        assertEquals("Use", parsed.get(0).right()); //$NON-NLS-1$
    }

    @Test
    public void acceptsSingleMapWithoutWrappingList() {
        List<RightGrant> parsed = RightsManageRequest.parseGrants(
                grant("object", "Catalog.X", "right", "Read", "value", "unset"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
                FIELD);
        assertEquals(1, parsed.size());
        assertEquals(RightsManageRequest.VALUE_UNSET, parsed.get(0).value());
    }

    @Test
    public void rejectsScalarPayload() {
        assertRejected(Boolean.TRUE);
        assertRejected("Catalog.X"); //$NON-NLS-1$
    }

    @Test
    public void rejectsEntryMissingObject() {
        Map<String, Object> noObj = new LinkedHashMap<>();
        noObj.put("right", "Read"); //$NON-NLS-1$ //$NON-NLS-2$
        assertRejected(List.of(noObj));
    }

    @Test
    public void rejectsEntryMissingRight() {
        Map<String, Object> noRight = new LinkedHashMap<>();
        noRight.put("object_fqn", "Catalog.X"); //$NON-NLS-1$ //$NON-NLS-2$
        assertRejected(List.of(noRight));
    }

    @Test
    public void rejectsInvalidValueToken() {
        assertRejected(List.of(grant("object_fqn", "Catalog.X", "right", "Read", "value", "maybe"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
    }

    @Test
    public void rejectsNonMapEntry() {
        assertRejected(List.of("Catalog.X")); //$NON-NLS-1$
    }

    private static String parseValue(String token) {
        return parseValueRaw(token);
    }

    private static String parseValueRaw(Object token) {
        return RightsManageRequest.parseGrants(
                List.of(grant("object_fqn", "Catalog.X", "right", "Read", "value", token)), FIELD) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                .get(0).value();
    }

    private static void assertRejected(Object payload) {
        try {
            RightsManageRequest.parseGrants(payload, FIELD);
            fail("Expected MetadataOperationException for payload: " + payload); //$NON-NLS-1$
        } catch (MetadataOperationException expected) {
            // expected
        }
    }
}
