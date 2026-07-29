package com.codepilot1c.core.edt.validation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import org.junit.Test;

import com.codepilot1c.core.edt.dcs.DcsCreateMainSchemaRequest;
import com.codepilot1c.core.edt.dcs.DcsSchemaSupport;

/**
 * Payload contract of {@code dcs_manage command=create_schema} normalization.
 *
 * <p>The point of these tests: an explicit {@code template_name} that happens to equal the default
 * must stay distinguishable from no {@code template_name} at all. Materializing the default into the
 * validated payload used to collapse the two, and the payload is exactly what the validation token
 * carries into the mutation — so the distinction has to survive the token round-trip too.</p>
 */
public class MetadataRequestValidationServiceDcsCreateMainSchemaTest {

    private final MetadataRequestValidationService service = new MetadataRequestValidationService();

    @Test
    public void anExplicitDefaultNameIsRecordedInThePayload() {
        Map<String, Object> payload = normalize(DcsSchemaSupport.DEFAULT_TEMPLATE_NAME);

        assertTrue("an explicitly passed name must land in the payload", //$NON-NLS-1$
                payload.containsKey("template_name")); //$NON-NLS-1$
        assertEquals(DcsSchemaSupport.DEFAULT_TEMPLATE_NAME, payload.get("template_name")); //$NON-NLS-1$
    }

    @Test
    public void anAbsentNameLeavesNoTemplateNameKey() {
        assertFalse("the default must not be materialized into the payload", //$NON-NLS-1$
                normalize(null).containsKey("template_name")); //$NON-NLS-1$
        assertFalse("a blank name is the same as no name", //$NON-NLS-1$
                normalize("").containsKey("template_name")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("whitespace is the same as no name", //$NON-NLS-1$
                normalize("   ").containsKey("template_name")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void explicitDefaultAndAbsentNameProduceDifferentPayloads() {
        Map<String, Object> explicit = normalize(DcsSchemaSupport.DEFAULT_TEMPLATE_NAME);
        Map<String, Object> implicit = normalize(null);

        assertFalse("explicit and defaulted must not be the same payload", explicit.equals(implicit)); //$NON-NLS-1$
        // ... while still naming the very same template.
        assertEquals(fromPayload(explicit).effectiveTemplateName(), fromPayload(implicit).effectiveTemplateName());
    }

    @Test
    public void anAbsentNameStillAppliesTheDefault() {
        DcsCreateMainSchemaRequest request = fromPayload(normalize(null));

        assertEquals(DcsSchemaSupport.DEFAULT_TEMPLATE_NAME, request.effectiveTemplateName());
        assertFalse("a defaulted name must not claim to be explicit", request.hasExplicitTemplateName()); //$NON-NLS-1$
    }

    @Test
    public void anExplicitNameIsPreservedVerbatimAndStillMatchesCaseInsensitively() {
        Map<String, Object> payload = normalize("  maindatacompositionschema  "); //$NON-NLS-1$

        // Trimmed, but never case-folded: the stored template name is the caller's spelling.
        assertEquals("maindatacompositionschema", payload.get("template_name")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the name-slot match must stay case-insensitive", //$NON-NLS-1$
                DcsSchemaSupport.nameMatches(
                        String.valueOf(payload.get("template_name")), //$NON-NLS-1$
                        DcsSchemaSupport.DEFAULT_TEMPLATE_NAME));
        assertTrue(fromPayload(payload).hasExplicitTemplateName());
    }

    @Test
    public void otherFieldsKeepTheirNormalization() {
        Map<String, Object> payload = service.normalizeDcsCreateMainSchemaPayload(
                " Demo ", //$NON-NLS-1$
                " Report.Sales ", //$NON-NLS-1$
                null,
                Boolean.TRUE);

        assertEquals("Demo", payload.get("project")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Report.Sales", payload.get("owner_fqn")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Boolean.TRUE, payload.get("force_replace")); //$NON-NLS-1$
    }

    // --- token round-trip ----------------------------------------------------

    @Test
    public void theTokenCarriesTheDistinctionForAnExplicitName() {
        DcsCreateMainSchemaRequest applied =
                fromPayload(roundTrip(normalize(DcsSchemaSupport.DEFAULT_TEMPLATE_NAME)));

        assertTrue("an explicit name must survive the token", applied.hasExplicitTemplateName()); //$NON-NLS-1$
        assertEquals(DcsSchemaSupport.DEFAULT_TEMPLATE_NAME, applied.effectiveTemplateName());
    }

    @Test
    public void theTokenIsNotSilentlyUpgradedWhenTheNameWasAbsent() {
        Map<String, Object> consumed = roundTrip(normalize(null));

        assertFalse("consuming a token must not invent a template_name", //$NON-NLS-1$
                consumed.containsKey("template_name")); //$NON-NLS-1$
        DcsCreateMainSchemaRequest applied = fromPayload(consumed);
        assertFalse(applied.hasExplicitTemplateName());
        // What was validated is what is applied: the default resolves in one shared place.
        assertEquals(DcsSchemaSupport.DEFAULT_TEMPLATE_NAME, applied.effectiveTemplateName());
    }

    // --- Helpers ------------------------------------------------------------

    private Map<String, Object> normalize(String templateName) {
        return service.normalizeDcsCreateMainSchemaPayload(
                "Demo", //$NON-NLS-1$
                "Report.Sales", //$NON-NLS-1$
                templateName,
                null);
    }

    /** Issues and consumes a real token, i.e. the exact payload trip a mutation makes. */
    private Map<String, Object> roundTrip(Map<String, Object> payload) {
        ValidationTokenStore store = ValidationTokenStore.getInstance();
        ValidationTokenStore.TokenIssue issue = store.issueToken(
                ValidationOperation.DCS_CREATE_MAIN_SCHEMA, "Demo", payload); //$NON-NLS-1$
        return store.consumeToken(
                issue.token(), ValidationOperation.DCS_CREATE_MAIN_SCHEMA, "Demo"); //$NON-NLS-1$
    }

    /** Rebuilds the request the way the tools do — optional fields read back as {@code null}. */
    private DcsCreateMainSchemaRequest fromPayload(Map<String, Object> payload) {
        Object templateName = payload.get("template_name"); //$NON-NLS-1$
        return new DcsCreateMainSchemaRequest(
                String.valueOf(payload.get("project")), //$NON-NLS-1$
                String.valueOf(payload.get("owner_fqn")), //$NON-NLS-1$
                templateName == null ? null : String.valueOf(templateName),
                (Boolean) payload.get("force_replace")); //$NON-NLS-1$
    }
}
