package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

/**
 * Source-contract test for the orphaned-registration path of {@code create_metadata}
 * (BF-13405 / B5).
 *
 * <p>The behaviour needs a live BM transaction — {@code IBmPlatformTransaction},
 * {@code IBmNamespace} and the mdclass model resolve only inside the OSGi runtime — so the
 * structure is pinned by reading the source, in the manner of {@link RemoveCommandContractTest}.
 * The pure parts (flag default, token payload, schema) are covered by
 * {@link CreateMetadataAdoptExistingTest}.</p>
 *
 * <p>Root cause being guarded: {@code create_metadata} checked only the configuration
 * composition (index A) and then called {@code attachTopObject}, which consults the BM FQN
 * registry (index B). When the two disagreed the caller saw {@code exists:false} from
 * {@code edt_metadata_details} and "FQN already in use" from creation — reported as
 * {@code EDT_TRANSACTION_FAILED}, because {@code BmFqnAlreadyInUseException} and
 * {@code BmNameAlreadyInUseException} are siblings with no common base class and only the
 * latter had a catch arm.</p>
 */
public class AdoptExistingTopObjectContractTest {

    private static final String SERVICE_PATH =
            "bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/metadata/EdtMetadataService.java"; //$NON-NLS-1$
    private static final String TOOL_PATH =
            "bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/metadata/CreateMetadataTool.java"; //$NON-NLS-1$

    // --- probing the second index -------------------------------------------

    @Test
    public void createProbesTheFqnRegistryBeforeCreating() throws Exception {
        String source = readSource(SERVICE_PATH);
        assertTrue("createMetadata must probe the BM FQN registry before creating", //$NON-NLS-1$
                source.contains("MdObject orphan = findAttachedTopObject(transaction, project, fqn, opId);")); //$NON-NLS-1$
        assertTrue("the probe must resolve by FQN in the BM namespace", //$NON-NLS-1$
                source.contains("transaction.getTopObjectByFqn(namespace, fqn)")); //$NON-NLS-1$
    }

    @Test
    public void probeFailureDoesNotAbortCreation() throws Exception {
        String probe = methodBody(readSource(SERVICE_PATH), "private MdObject findAttachedTopObject("); //$NON-NLS-1$
        assertTrue("the probe must be best-effort", probe.contains("catch (RuntimeException e)")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("a failed probe must degrade to null, not throw", probe.contains("return null;")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // --- default is a loud refusal ------------------------------------------

    @Test
    public void adoptIsOffByDefaultAndRefusalIsActionable() throws Exception {
        String adopt = methodBody(readSource(SERVICE_PATH), "private void adoptAttachedTopObject("); //$NON-NLS-1$
        assertTrue("adoption must be gated on the request flag", //$NON-NLS-1$
                adopt.contains("if (!request.shouldAdoptExisting())")); //$NON-NLS-1$
        assertTrue("refusal must use METADATA_ALREADY_EXISTS", //$NON-NLS-1$
                adopt.contains("MetadataOperationCode.METADATA_ALREADY_EXISTS")); //$NON-NLS-1$
        assertTrue("refusal must name the index disagreement", //$NON-NLS-1$
                adopt.contains("the two indexes disagree")); //$NON-NLS-1$
        assertTrue("refusal must state that nothing was changed", //$NON-NLS-1$
                adopt.contains("Nothing was changed")); //$NON-NLS-1$
        assertTrue("refusal must name the cure", adopt.contains("adopt_existing=true")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void adoptRefusesAKindMismatchInsteadOfCastingBlindly() throws Exception {
        String adopt = methodBody(readSource(SERVICE_PATH), "private void adoptAttachedTopObject("); //$NON-NLS-1$
        // addTopLevelObject casts to the kind's EClass; a foreign object must fail with a
        // message, not a ClassCastException.
        assertTrue("adoption must compare the resolved EClass against the requested kind", //$NON-NLS-1$
                adopt.contains("String expectedClass = request.kind().getFqnPrefix();") //$NON-NLS-1$
                        && adopt.contains("if (!expectedClass.equals(actualClass))")); //$NON-NLS-1$
    }

    // --- adoption is strictly additive --------------------------------------

    @Test
    public void adoptOnlyRegistersIntoTheTypedCollection() throws Exception {
        String adopt = methodBody(readSource(SERVICE_PATH), "private void adoptAttachedTopObject("); //$NON-NLS-1$
        assertTrue("adoption must link the existing object into its typed collection", //$NON-NLS-1$
                adopt.contains("addTopLevelObject(txConfiguration, request.kind(), orphan);")); //$NON-NLS-1$
        assertFalse("adoption must not create a new object", adopt.contains("createTopLevelObject(")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("adoption must not re-attach an already attached top object", //$NON-NLS-1$
                adopt.contains("attachTopLevelObject(")); //$NON-NLS-1$
        assertFalse("adoption must not rewrite uuids of a foreign object", //$NON-NLS-1$
                adopt.contains("ensureUuidsRecursively(")); //$NON-NLS-1$
        assertFalse("adoption must not apply properties to a pre-existing object", //$NON-NLS-1$
                adopt.contains("applyTopLevelProperties(")); //$NON-NLS-1$
    }

    @Test
    public void adoptRefusesPropertiesInsteadOfDroppingThemSilently() throws Exception {
        String adopt = methodBody(readSource(SERVICE_PATH), "private void adoptAttachedTopObject("); //$NON-NLS-1$
        assertTrue("properties passed together with adopt must be rejected", //$NON-NLS-1$
                adopt.contains("if (request.properties() != null && !request.properties().isEmpty())")); //$NON-NLS-1$
        assertTrue("the rejection must point at update_metadata", //$NON-NLS-1$
                adopt.contains("apply them with update_metadata")); //$NON-NLS-1$
    }

    @Test
    public void outcomeCarriesTheAdoptedFlag() throws Exception {
        String source = readSource(SERVICE_PATH);
        assertTrue("createMetadataDetailed must be the reporting entry point", //$NON-NLS-1$
                source.contains("public CreateMetadataOutcome createMetadataDetailed(CreateMetadataRequest request)")); //$NON-NLS-1$
        assertTrue("the adopt branch must be observable in the result", //$NON-NLS-1$
                source.contains("return new CreateMetadataOutcome(result, adopted, collectionTag);")); //$NON-NLS-1$
        assertTrue("the success message must say properties were not applied", //$NON-NLS-1$
                source.contains("Properties were NOT applied")); //$NON-NLS-1$

        String tool = readSource(TOOL_PATH);
        assertTrue("the tool must surface the adopted flag to the caller", //$NON-NLS-1$
                tool.contains("outcome.formatForLlm()")); //$NON-NLS-1$
    }

    @Test
    public void adoptFlagCannotBeWidenedPastTheValidationToken() throws Exception {
        String tool = readSource(TOOL_PATH);
        assertTrue("the token payload must decide whether adoption happens", //$NON-NLS-1$
                tool.contains("boolean validatedAdopt = Boolean.TRUE.equals(validatedPayload.get(\"adopt_existing\"))")); //$NON-NLS-1$
        assertTrue("a token issued without the flag must be refused, not silently downgraded", //$NON-NLS-1$
                tool.contains("MetadataOperationCode.INVALID_VALIDATION_TOKEN")); //$NON-NLS-1$
        assertTrue("the alias must be accepted on input", tool.contains("\"adopt\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // --- error mapping (honest backstop, independent of the adopt verb) -----

    @Test
    public void fqnAlreadyInUseMapsToMetadataAlreadyExists() throws Exception {
        String source = readSource(SERVICE_PATH);
        assertTrue("BmFqnAlreadyInUseException must be imported", //$NON-NLS-1$
                source.contains("import com._1c.g5.v8.bm.core.BmFqnAlreadyInUseException;")); //$NON-NLS-1$
        assertTrue("the sibling exception needs its own catch arm (no common base class)", //$NON-NLS-1$
                source.contains("catch (BmFqnAlreadyInUseException e)")); //$NON-NLS-1$

        String executeWrite = methodBody(source, "private <T> T executeWrite("); //$NON-NLS-1$
        assertTrue("executeWrite must map it to METADATA_ALREADY_EXISTS", //$NON-NLS-1$
                executeWrite.contains("catch (BmFqnAlreadyInUseException e)") //$NON-NLS-1$
                        && executeWrite.contains("MetadataOperationCode.METADATA_ALREADY_EXISTS")); //$NON-NLS-1$
        assertTrue("the mapped message must name the two-index disagreement", //$NON-NLS-1$
                executeWrite.contains("the two metadata indexes disagree")); //$NON-NLS-1$
    }

    @Test
    public void attachAlsoReportsTheDisagreementWithTheFqn() throws Exception {
        String attach = methodBody(readSource(SERVICE_PATH), "private MdObject attachTopLevelObject("); //$NON-NLS-1$
        assertTrue("attachTopLevelObject must translate the FQN clash itself", //$NON-NLS-1$
                attach.contains("catch (BmFqnAlreadyInUseException e)")); //$NON-NLS-1$
        assertTrue("the message must carry the FQN and the cure", //$NON-NLS-1$
                attach.contains("MetadataOperationCode.METADATA_ALREADY_EXISTS") //$NON-NLS-1$
                        && attach.contains("adopt_existing=true")); //$NON-NLS-1$
    }

    // --- Helpers ------------------------------------------------------------

    /**
     * Returns the source text from a method signature up to the start of the next member,
     * so assertions about "this method does not call X" cannot be satisfied by a neighbour.
     */
    private String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue("method not found in source: " + signature, start >= 0); //$NON-NLS-1$
        int end = source.indexOf("\n    }", start); //$NON-NLS-1$
        assertTrue("method end not found for: " + signature, end > start); //$NON-NLS-1$
        return source.substring(start, end);
    }

    private String readSource(String relativePath) throws Exception {
        Path repoRoot = findRepoRoot();
        return Files.readString(repoRoot.resolve(relativePath), StandardCharsets.UTF_8);
    }

    private Path findRepoRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize(); //$NON-NLS-1$
        while (current != null) {
            if (Files.isDirectory(current.resolve("bundles")) //$NON-NLS-1$
                    && Files.isRegularFile(current.resolve("pom.xml")) //$NON-NLS-1$
                    && Files.isRegularFile(current.resolve("LICENSE"))) { //$NON-NLS-1$
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Cannot locate repository root"); //$NON-NLS-1$
    }
}
