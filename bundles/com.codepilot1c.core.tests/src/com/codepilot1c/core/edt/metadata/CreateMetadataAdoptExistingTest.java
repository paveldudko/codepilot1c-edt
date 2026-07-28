package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import org.junit.Test;

import com.codepilot1c.core.edt.validation.MetadataRequestValidationService;
import com.codepilot1c.core.tools.metadata.CreateMetadataTool;

/**
 * Unit coverage for the {@code adopt_existing} verb of {@code create_metadata} (BF-13405).
 *
 * <p>Context: the configuration composition and the BM top-object FQN registry are two
 * independent indexes. A {@code .mdo} on disk is imported and its FQN registered even when
 * {@code Configuration.mdo} never listed it, which produced the reported pair "{@code
 * exists:false}" from {@code edt_metadata_details} and "FQN already in use" from
 * {@code create_metadata}. Adoption registers such an orphan into the typed collection.</p>
 *
 * <p>The flag defaults to {@code false} deliberately — taking over a pre-existing object by
 * default would hide the disagreement instead of reporting it — and it must travel through the
 * validation token, since the token payload is authoritative for every field of the mutation.</p>
 */
public class CreateMetadataAdoptExistingTest {

    private final MetadataRequestValidationService service = new MetadataRequestValidationService();

    // --- request defaults ----------------------------------------------------

    @Test
    public void historicalConstructorNeverAdopts() {
        CreateMetadataRequest request = new CreateMetadataRequest(
                "Demo", MetadataKind.COMMON_MODULE, "CM_Test", null, null, Map.of()); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse(request.shouldAdoptExisting());
    }

    @Test
    public void nullFlagMeansNoAdoption() {
        CreateMetadataRequest request = new CreateMetadataRequest(
                "Demo", MetadataKind.COMMON_MODULE, "CM_Test", null, null, Map.of(), null); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse(request.shouldAdoptExisting());
    }

    @Test
    public void explicitTrueEnablesAdoption() {
        CreateMetadataRequest request = new CreateMetadataRequest(
                "Demo", MetadataKind.COMMON_MODULE, "CM_Test", null, null, Map.of(), Boolean.TRUE); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(request.shouldAdoptExisting());
    }

    // --- validation payload --------------------------------------------------

    @Test
    public void normalizedPayloadAlwaysCarriesTheFlag() {
        // Always present, so the tool payload and the token payload compare equal and the flag
        // is visible in the edt_validate_request report.
        Map<String, Object> payload = service.normalizeCreatePayload(
                "Demo", "CommonModule", "CM_Test", null, null, Map.of()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertTrue(payload.containsKey("adopt_existing")); //$NON-NLS-1$
        assertEquals(Boolean.FALSE, payload.get("adopt_existing")); //$NON-NLS-1$
    }

    @Test
    public void normalizedPayloadPropagatesAdoptTrue() {
        Map<String, Object> payload = service.normalizeCreatePayload(
                "Demo", "CommonModule", "CM_Test", null, null, Map.of(), Boolean.TRUE); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals(Boolean.TRUE, payload.get("adopt_existing")); //$NON-NLS-1$
    }

    @Test
    public void normalizedPayloadKeepsTheRestOfTheContractIntact() {
        Map<String, Object> payload = service.normalizeCreatePayload(
                "Demo", "Подсистема", "Finance", "Финансы", "note", Map.of(), Boolean.FALSE); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        assertEquals("Demo", payload.get("project")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("SUBSYSTEM", payload.get("kind")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Finance", payload.get("name")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Финансы", payload.get("synonym")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("note", payload.get("comment")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("empty properties must stay absent", payload.containsKey("properties")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // --- tool schema ---------------------------------------------------------

    @Test
    public void toolSchemaAdvertisesAdoptExisting() {
        String schema = new CreateMetadataTool().getParameterSchema();

        assertTrue("create_metadata schema must expose adopt_existing", //$NON-NLS-1$
                schema.contains("\"adopt_existing\"")); //$NON-NLS-1$
        assertTrue("schema must state the default is false", //$NON-NLS-1$
                schema.contains("Default false")); //$NON-NLS-1$
        assertTrue("schema must say the flag has to reach edt_validate_request too", //$NON-NLS-1$
                schema.contains("edt_validate_request payload too")); //$NON-NLS-1$
        assertFalse("adopt_existing must stay optional", //$NON-NLS-1$
                schema.contains("\"required\": [\"project\", \"kind\", \"name\", \"adopt_existing\"")); //$NON-NLS-1$
    }
}
