package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.codepilot1c.core.edt.metadata.EdtMetadataService.RoleVisibility;

/**
 * Pins the parsing contract for per-role {@code userVisible} overrides
 * ({@code mutate_form_model set_item set:{uservisible:{common, for:[…]}}}).
 *
 * <p>Background: before this contract existed, {@code set_item}'s {@code uservisible}
 * handling only ever set the uniform {@code <common>} flag and explicitly cleared the
 * per-role {@code <for>} list — so the blacklist form (visible for most roles, hidden
 * for a few) could not be expressed via MCP and had to be hand-edited in the raw
 * {@code .form} XML (codepilot1c-feedback 2026-05-30).</p>
 *
 * <p>These tests cover the pure parsing half ({@link EdtMetadataService#parseForRoleEntries});
 * resolving the role name to a {@code Role} EObject and writing the {@code ForRoleType}
 * entries is exercised only against a live BM workspace.</p>
 */
public class UserVisibleForRoleParsingTest {

    private static final String FIELD = "uservisible"; //$NON-NLS-1$

    private static Map<String, Object> entry(String roleKey, String roleValue, String valueKey, Object value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(roleKey, roleValue);
        map.put(valueKey, value);
        return map;
    }

    @Test
    public void nullForPayloadYieldsNoOverrides() {
        assertTrue(EdtMetadataService.parseForRoleEntries(null, FIELD).isEmpty());
    }

    @Test
    public void blacklistEntryParsesRoleAndValue() {
        // {common:true, for:[{role:"Бухгалтер", value:false}]} — hide for that role.
        List<RoleVisibility> parsed = EdtMetadataService.parseForRoleEntries(
                List.of(entry("role", "Бухгалтер", "value", Boolean.FALSE)), FIELD); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(1, parsed.size());
        assertEquals("Бухгалтер", parsed.get(0).role()); //$NON-NLS-1$
        assertFalse(parsed.get(0).value());
    }

    @Test
    public void whitelistMultipleEntriesPreserveOrderAndValues() {
        List<RoleVisibility> parsed = EdtMetadataService.parseForRoleEntries(
                List.of(
                        entry("role", "Менеджер", "value", Boolean.TRUE), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        entry("role", "Кладовщик", "value", Boolean.TRUE)), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                FIELD);
        assertEquals(2, parsed.size());
        assertEquals("Менеджер", parsed.get(0).role()); //$NON-NLS-1$
        assertEquals("Кладовщик", parsed.get(1).role()); //$NON-NLS-1$
        assertTrue(parsed.get(0).value());
        assertTrue(parsed.get(1).value());
    }

    @Test
    public void acceptsRoleAndValueKeyAliases() {
        assertEquals("Админ", EdtMetadataService.parseForRoleEntries( //$NON-NLS-1$
                List.of(entry("role_name", "Админ", "visible", Boolean.FALSE)), FIELD) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                .get(0).role());
        assertEquals("Гость", EdtMetadataService.parseForRoleEntries( //$NON-NLS-1$
                List.of(entry("name", "Гость", "value", Boolean.TRUE)), FIELD) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                .get(0).role());
    }

    @Test
    public void acceptsStringBooleanLiterals() {
        List<RoleVisibility> parsed = EdtMetadataService.parseForRoleEntries(
                List.of(entry("role", "Оператор", "value", "false")), FIELD); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertFalse(parsed.get(0).value());
    }

    @Test
    public void acceptsSingleEntryMapWithoutWrappingList() {
        List<RoleVisibility> parsed = EdtMetadataService.parseForRoleEntries(
                entry("role", "Аудитор", "value", Boolean.FALSE), FIELD); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(1, parsed.size());
        assertEquals("Аудитор", parsed.get(0).role()); //$NON-NLS-1$
    }

    @Test
    public void rejectsScalarForPayload() {
        assertRejected(Boolean.TRUE);
        assertRejected("Бухгалтер"); //$NON-NLS-1$
    }

    @Test
    public void rejectsEntryMissingRole() {
        Map<String, Object> noRole = new LinkedHashMap<>();
        noRole.put("value", Boolean.FALSE); //$NON-NLS-1$
        assertRejected(List.of(noRole));
    }

    @Test
    public void rejectsEntryMissingValue() {
        Map<String, Object> noValue = new LinkedHashMap<>();
        noValue.put("role", "Бухгалтер"); //$NON-NLS-1$ //$NON-NLS-2$
        assertRejected(List.of(noValue));
    }

    @Test
    public void rejectsEntryWithUnparseableValue() {
        assertRejected(List.of(entry("role", "Бухгалтер", "value", "maybe"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    @Test
    public void rejectsNonMapEntry() {
        assertRejected(List.of("Бухгалтер")); //$NON-NLS-1$
    }

    private static void assertRejected(Object forPayload) {
        try {
            EdtMetadataService.parseForRoleEntries(forPayload, FIELD);
            fail("Expected MetadataOperationException for payload: " + forPayload); //$NON-NLS-1$
        } catch (MetadataOperationException expected) {
            // expected
        }
    }

    // -- flat per-role map {common, "RoleName":bool} (feedback 2026-07-16) -----------------------
    //
    // The tool description advertises userVisible as accepting a "per-role map". A flat
    // {common:false, "Role":true} used to silently drop the role key and apply only common=false,
    // wiping the default to hidden-for-everyone. parseFlatRoleEntries harvests those role keys.

    @Test
    public void flatMapHarvestsTheReportedWhitelistCase() {
        // The exact BF-12936 input that wiped the default.
        Map<String, Object> flat = new LinkedHashMap<>();
        flat.put("common", Boolean.FALSE); //$NON-NLS-1$
        flat.put("AddEditFinanceVerification", Boolean.TRUE); //$NON-NLS-1$
        List<RoleVisibility> parsed = EdtMetadataService.parseFlatRoleEntries(flat, FIELD);
        assertEquals(1, parsed.size());
        assertEquals("AddEditFinanceVerification", parsed.get(0).role()); //$NON-NLS-1$
        assertTrue(parsed.get(0).value());
    }

    @Test
    public void flatMapSkipsReservedCommonAliasKeys() {
        Map<String, Object> reservedOnly = new LinkedHashMap<>();
        reservedOnly.put("common", Boolean.TRUE); //$NON-NLS-1$
        reservedOnly.put("value", Boolean.FALSE); //$NON-NLS-1$
        reservedOnly.put("visible", Boolean.TRUE); //$NON-NLS-1$
        reservedOnly.put("enabled", Boolean.FALSE); //$NON-NLS-1$
        assertTrue("reserved common-alias keys are not roles", //$NON-NLS-1$
                EdtMetadataService.parseFlatRoleEntries(reservedOnly, FIELD).isEmpty());
    }

    @Test
    public void flatMapPreservesOrderAndMultipleRoles() {
        Map<String, Object> flat = new LinkedHashMap<>();
        flat.put("Менеджер", Boolean.TRUE); //$NON-NLS-1$
        flat.put("Кладовщик", Boolean.FALSE); //$NON-NLS-1$
        List<RoleVisibility> parsed = EdtMetadataService.parseFlatRoleEntries(flat, FIELD);
        assertEquals(2, parsed.size());
        assertEquals("Менеджер", parsed.get(0).role()); //$NON-NLS-1$
        assertTrue(parsed.get(0).value());
        assertEquals("Кладовщик", parsed.get(1).role()); //$NON-NLS-1$
        assertFalse(parsed.get(1).value());
    }

    @Test
    public void flatMapAcceptsStringBooleanLiterals() {
        Map<String, Object> flat = new LinkedHashMap<>();
        flat.put("Оператор", "false"); //$NON-NLS-1$ //$NON-NLS-2$
        List<RoleVisibility> parsed = EdtMetadataService.parseFlatRoleEntries(flat, FIELD);
        assertFalse(parsed.get(0).value());
    }

    @Test
    public void flatMapRejectsNonBooleanRoleValueRatherThanDropIt() {
        Map<String, Object> flat = new LinkedHashMap<>();
        flat.put("Бухгалтер", "maybe"); //$NON-NLS-1$ //$NON-NLS-2$
        try {
            EdtMetadataService.parseFlatRoleEntries(flat, FIELD);
            fail("a non-boolean role value must be rejected, not silently dropped"); //$NON-NLS-1$
        } catch (MetadataOperationException expected) {
            // expected — non-destructive: the caller learns the value is bad instead of a silent wipe
        }
    }

    @Test
    public void flatMapNullYieldsEmpty() {
        assertTrue(EdtMetadataService.parseFlatRoleEntries(null, FIELD).isEmpty());
    }
}
