package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

/**
 * Source-contract test for {@code ExchangePlan.content} (B2).
 *
 * <p>What was broken: the registration list of an exchange plan was unwritable by any tool.
 * {@code content} is a containment reference whose entries are {@code ExchangePlanContentItem}s —
 * a flat EClass that is neither an {@code MdObject} nor named — so every generic child shape missed
 * it: {@code findNestedChild} skips non-{@code MdObject} values and matches on {@code getName()},
 * {@code buildChildOpsFromContainmentSet} needs a {@code name} per entry and produces no ops
 * without one, and the containment arm of {@code applyReferenceValue} then rejected the write with
 * "Containment reference updates are not supported in set".</p>
 *
 * <p>{@code MdClassFactory}, {@code MdClassPackage} and the BM write transaction resolve only in
 * the OSGi runtime, so the wiring is pinned by reading the source.</p>
 */
public class ExchangePlanContentContractTest {

    private static final String SERVICE_PATH =
            "bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/metadata/EdtMetadataService.java"; //$NON-NLS-1$

    // --- the containment refusal no longer swallows content -----------------

    @Test
    public void contentIsHandledBeforeTheContainmentRefusal() {
        String body = methodBody(readSource(SERVICE_PATH), "private void applyReferenceValue("); //$NON-NLS-1$
        int handled = body.indexOf("if (isExchangePlanContentReference(reference)) {"); //$NON-NLS-1$
        int refused = body.indexOf("Containment reference updates are not supported in set."); //$NON-NLS-1$
        assertTrue("the content branch must exist", handled >= 0); //$NON-NLS-1$
        assertTrue("the generic containment refusal must still exist for everything else", //$NON-NLS-1$
                refused >= 0);
        assertTrue("the content branch must run BEFORE the refusal, or nothing changes", //$NON-NLS-1$
                handled < refused);
        assertTrue(body.contains("applyExchangePlanContent(configuration, target, reference, value);")); //$NON-NLS-1$
    }

    @Test
    public void theBranchIsRecognisedByTheItemEClassNotByFieldName() {
        String body = methodBody(readSource(SERVICE_PATH), "private boolean isExchangePlanContentReference("); //$NON-NLS-1$
        // A name-based check would also claim Subsystem.content, which is a plain MdObject list.
        assertTrue("the branch must key on the item EClass", //$NON-NLS-1$
                body.contains("MdClassPackage.Literals.EXCHANGE_PLAN_CONTENT_ITEM.isSuperTypeOf(referenceType)")); //$NON-NLS-1$
        assertFalse("no field-name matching here", body.contains("\"content\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // --- both request shapes ------------------------------------------------

    @Test
    public void bothShapesAreAccepted() {
        String body = methodBody(readSource(SERVICE_PATH), "private void applyExchangePlanContent("); //$NON-NLS-1$
        assertTrue("a list of entries must be read as such", body.contains("entries = list;")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("a single entry must be tolerated too", body.contains("entries = List.of(value);")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("every entry must get its OWN item", //$NON-NLS-1$
                body.contains("built.add(buildExchangePlanContentItem(configuration, entry));")); //$NON-NLS-1$

        String item = methodBody(readSource(SERVICE_PATH), "private ExchangePlanContentItem buildExchangePlanContentItem("); //$NON-NLS-1$
        // Shape 1: content:["Catalog.Foo"] — the entry IS the FQN.
        assertTrue("a bare FQN entry must be used directly", item.contains("Object mdObjectValue = entry;")); //$NON-NLS-1$ //$NON-NLS-2$
        // Shape 2: content:[{mdObject|object|fqn, autoRecord}].
        assertTrue(item.contains("map.get(\"mdObject\")")); //$NON-NLS-1$
        assertTrue(item.contains("map.get(\"object\")")); //$NON-NLS-1$
        assertTrue(item.contains("map.get(\"fqn\")")); //$NON-NLS-1$
        assertTrue(item.contains("map.get(\"autoRecord\")")); //$NON-NLS-1$
    }

    @Test
    public void mdObjectIsResolvedAgainstTheItemsOwnReference() {
        String item = methodBody(readSource(SERVICE_PATH),
                "private ExchangePlanContentItem buildExchangePlanContentItem("); //$NON-NLS-1$
        // Resolving against the owning `content` reference would type-check the resolved Catalog
        // against ExchangePlanContentItem and refuse every legitimate entry.
        assertTrue("resolution must use ExchangePlanContentItem.mdObject", //$NON-NLS-1$
                item.contains("MdClassPackage.Literals.EXCHANGE_PLAN_CONTENT_ITEM__MD_OBJECT, mdObjectValue)")); //$NON-NLS-1$
        assertTrue("and it must go through the shared resolver, for FQN + compatibility + not-found", //$NON-NLS-1$
                item.contains("resolveSingleReferenceValue(")); //$NON-NLS-1$
    }

    // --- autoRecord default -------------------------------------------------

    @Test
    public void autoRecordDefaultsToAllow() {
        String body = methodBody(readSource(SERVICE_PATH), "private AutoRegistrationChanges resolveAutoRegistrationChanges("); //$NON-NLS-1$
        int blankCheck = body.indexOf("if (value == null || String.valueOf(value).isBlank()) {"); //$NON-NLS-1$
        assertTrue("an absent autoRecord must be a declared default, not an error", blankCheck >= 0); //$NON-NLS-1$
        assertTrue("that default must be Allow", //$NON-NLS-1$
                body.indexOf("return AutoRegistrationChanges.ALLOW;", blankCheck) > blankCheck); //$NON-NLS-1$
        assertTrue("Deny must be reachable", body.contains("AutoRegistrationChanges.DENY")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void anUnknownAutoRecordValueFailsLoudInsteadOfDefaulting() {
        String body = methodBody(readSource(SERVICE_PATH),
                "private AutoRegistrationChanges resolveAutoRegistrationChanges("); //$NON-NLS-1$
        // Silently falling back to Allow would turn a typo into "register every change".
        assertTrue(body.contains("Unknown autoRecord value '")); //$NON-NLS-1$
        assertTrue("the refusal must list the valid literals", body.contains("Valid values: Allow, Deny.")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // --- an unresolvable entry is never dropped -----------------------------

    @Test
    public void anUnresolvableEntryIsRefusedLoud() {
        String item = methodBody(readSource(SERVICE_PATH),
                "private ExchangePlanContentItem buildExchangePlanContentItem("); //$NON-NLS-1$
        assertTrue("a non-MdObject resolution must throw METADATA_NOT_FOUND", //$NON-NLS-1$
                item.contains("MetadataOperationCode.METADATA_NOT_FOUND")); //$NON-NLS-1$
        assertTrue(item.contains("ExchangePlan content entry does not resolve to a metadata object: ")); //$NON-NLS-1$
        assertTrue("an entry map without an object slot must be refused, not skipped", //$NON-NLS-1$
                item.contains("ExchangePlan content entry must carry mdObject/object/fqn: ")); //$NON-NLS-1$
        // "continue" past a bad entry is exactly how a list write loses members while reporting
        // success; only an explicit null placeholder may be skipped, and that happens in the caller.
        assertFalse("the item builder must not continue past anything", item.contains("continue;")); //$NON-NLS-1$ //$NON-NLS-2$
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
