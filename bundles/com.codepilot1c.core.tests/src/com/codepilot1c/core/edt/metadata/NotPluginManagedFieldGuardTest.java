package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

/**
 * Source-contract test for the generic {@code EDataType} fallback and the not-plugin-managed
 * deny-list (B3).
 *
 * <p>What was broken: {@code convertAttributeValue} knew String / Integer / Long / Double / Float /
 * Boolean / enum and then threw "Unsupported value type", so EVERY exotic model data type was
 * unwritable — {@code Uuid} ({@code ExchangePlan.thisNode} is a {@code java.util.UUID}), and with it
 * {@code QName}, {@code Shortcut}, {@code Version}. Each of those is an {@code EDataType} whose own
 * {@code EFactory} parses its literal, so delegating to it NARROWS the unsupported surface.</p>
 *
 * <p>The one field that must stay unwritable is {@code thisNode} itself: it is the identity of the
 * exchange plan's own node, assigned by EDT on first load, and rewriting it against a live infobase
 * re-identifies the local node for every peer. It therefore moves into an explicit deny-list beside
 * the {@code uuid} guard — with an honest message instead of the old, misleading "Unsupported value
 * type" — and that guard must be consulted BEFORE conversion, or the new fallback would quietly
 * make it writable again.</p>
 */
public class NotPluginManagedFieldGuardTest {

    private static final String SERVICE_PATH =
            "bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/metadata/EdtMetadataService.java"; //$NON-NLS-1$

    // --- generic EDataType fallback -----------------------------------------

    @Test
    public void conversionFallsBackToTheDataTypesOwnFactory() {
        String body = methodBody(readSource(SERVICE_PATH), "private Object convertAttributeValue("); //$NON-NLS-1$
        assertTrue("the fallback must delegate to the EDataType itself", //$NON-NLS-1$
                body.contains("EcoreUtil.createFromString(dataType, raw)")); //$NON-NLS-1$
        assertTrue("a parse failure must not escape as an EMF exception", //$NON-NLS-1$
                body.contains("convertAttributeValue: EDataType fallback failed for field=%s")); //$NON-NLS-1$
    }

    @Test
    public void theFallbackRunsLastAndKeepsTheLoudRefusal() {
        String body = methodBody(readSource(SERVICE_PATH), "private Object convertAttributeValue("); //$NON-NLS-1$
        int enumLadder = body.indexOf("if (dataType instanceof EEnum eEnum) {"); //$NON-NLS-1$
        int fallback = body.indexOf("EcoreUtil.createFromString(dataType, raw)"); //$NON-NLS-1$
        int refusal = body.indexOf("Unsupported value type for field "); //$NON-NLS-1$
        assertTrue("the typed ladder must stay first", enumLadder >= 0 && enumLadder < fallback); //$NON-NLS-1$
        assertTrue("a data type that cannot parse its literal must still be refused", //$NON-NLS-1$
                fallback < refusal);
    }

    // --- thisNode deny-list -------------------------------------------------

    @Test
    public void thisNodeIsDeniedWithAnHonestReason() {
        String source = readSource(SERVICE_PATH);
        int table = source.indexOf("NOT_PLUGIN_MANAGED_FIELDS = Map.of("); //$NON-NLS-1$
        assertTrue("the deny-list must exist", table >= 0); //$NON-NLS-1$
        String entry = source.substring(table, source.indexOf(";", table)); //$NON-NLS-1$
        assertTrue("it must be keyed on the normalized field name", entry.contains("\"thisnode\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the reason must say who owns the value", entry.contains("assigned by")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("and that it is not ours", entry.contains("not plugin-managed")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("the old lie must be gone from this refusal", //$NON-NLS-1$
                entry.contains("Unsupported value type")); //$NON-NLS-1$
    }

    @Test
    public void theDenyListIsConsultedBeforeAnyConversion() {
        String body = methodBody(readSource(SERVICE_PATH), "private void setFeatureValue("); //$NON-NLS-1$
        int guard = body.indexOf("rejectNotPluginManagedField(fieldName);"); //$NON-NLS-1$
        int conversion = body.indexOf("convertAttributeValue(attribute, value)"); //$NON-NLS-1$
        int reference = body.indexOf("applyReferenceValue(configuration, target, reference, value, transaction)"); //$NON-NLS-1$
        assertTrue("setFeatureValue must consult the deny-list", guard >= 0); //$NON-NLS-1$
        assertTrue("…before the attribute conversion, whose fallback can now parse a Uuid", //$NON-NLS-1$
                guard < conversion);
        assertTrue("…and before any reference write", guard < reference); //$NON-NLS-1$
    }

    @Test
    public void unsetIsDeniedToo() {
        String body = methodBody(readSource(SERVICE_PATH), "private void unsetFeatureValue("); //$NON-NLS-1$
        // eUnset on thisNode drops the identity instead of overwriting it — same damage.
        assertTrue(body.contains("rejectNotPluginManagedField(fieldName);")); //$NON-NLS-1$
    }

    @Test
    public void theGuardOnlyRefusesListedFieldsAndSaysSoLoudly() {
        String body = methodBody(readSource(SERVICE_PATH), "private void rejectNotPluginManagedField("); //$NON-NLS-1$
        assertTrue("lookup must be normalized, so ThisNode / this_node are caught too", //$NON-NLS-1$
                body.contains("NOT_PLUGIN_MANAGED_FIELDS.get(normalizeToken(fieldName))")); //$NON-NLS-1$
        assertTrue("an unlisted field must pass through silently", body.contains("if (reason == null) {")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the refusal must be logged, not just thrown", body.contains("LOG.warn(")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(body.contains("MetadataOperationCode.INVALID_METADATA_CHANGE")); //$NON-NLS-1$
    }

    // --- Helpers ------------------------------------------------------------

    private String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue("method not found in source: " + signature, start >= 0); //$NON-NLS-1$
        int end = source.indexOf("\n    }", start); //$NON-NLS-1$
        assertTrue("method end not found for: " + signature, end > start); //$NON-NLS-1$
        return source.substring(start, end);
    }

    private String readSource(String relativePath) {
        try {
            return Files.readString(findRepoRoot().resolve(relativePath), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot read " + relativePath, e); //$NON-NLS-1$
        }
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
