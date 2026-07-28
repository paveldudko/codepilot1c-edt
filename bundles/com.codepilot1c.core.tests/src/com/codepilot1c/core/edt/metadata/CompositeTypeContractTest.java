package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

/**
 * Source-contract test for the composite-type fix (B1).
 *
 * <p>What was broken: a request for more than one type — {@code type:["CatalogRef.A",
 * "CatalogRef.B"]} or {@code {types:[…]}} — wrote only the first one and reported success.
 * Three call sites shared one first-element-wins normalizer, so the collapse had to be fixed in
 * all three at once or the defect would simply move: {@code setFeatureValue} →
 * {@code setAttributeType} ({@code update_metadata} on any BasicFeature — a Dimension of any
 * register, a Resource, an Attribute), {@code applyDefaultTypeIfNeeded} (the
 * {@code add_metadata_child} create path) and {@code applyFormAttributeType} (form attributes,
 * parameters and table columns, which validated the whole list and then applied one element of
 * it). The pre-resolve stage collapsed the request too, so the later types were never even
 * looked up.</p>
 *
 * <p>The resolution ladders need the BM write transaction and the EMF metamodel, neither of
 * which resolves in the plain Maven test bundle, so the wiring is pinned by reading the source;
 * the split semantics themselves are real unit tests in {@link TypeValueSplitterTest}.</p>
 */
public class CompositeTypeContractTest {

    private static final String SERVICE_PATH =
            "bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/metadata/EdtMetadataService.java"; //$NON-NLS-1$

    // --- the first-only normalizer is gone ----------------------------------

    @Test
    public void noCallerCanReachAFirstOnlyTypeNormalizerAnyMore() {
        String source = readSource(SERVICE_PATH);
        assertFalse("normalizeTypeSpec(...) collapsed a composite request — it must not exist", //$NON-NLS-1$
                source.contains("normalizeTypeSpec(")); //$NON-NLS-1$
        assertTrue("the per-element normalizer must be named for what it takes: one carrier", //$NON-NLS-1$
                source.contains("private TypeSpec normalizeSingleTypeSpec(")); //$NON-NLS-1$
        assertTrue("raw caller input must go through the list normalizer", //$NON-NLS-1$
                source.contains("private List<TypeSpec> normalizeTypeSpecList(")); //$NON-NLS-1$
    }

    @Test
    public void theListNormalizerProducesOneSpecPerElement() {
        String body = methodBody(readSource(SERVICE_PATH), "private List<TypeSpec> normalizeTypeSpecList("); //$NON-NLS-1$
        assertTrue("the element split must be delegated to the pure splitter", //$NON-NLS-1$
                body.contains("TypeValueSplitter.split(value)")); //$NON-NLS-1$
        assertTrue("every carrier must yield its own spec, with its own qualifiers", //$NON-NLS-1$
                body.contains("specs.add(normalizeSingleTypeSpec(carrier))")); //$NON-NLS-1$
        assertTrue("an empty request must stay a loud refusal, not become a no-op", //$NON-NLS-1$
                body.contains("Type query is empty or invalid: ")); //$NON-NLS-1$
    }

    // --- one shared builder, fail-loud per element --------------------------

    @Test
    public void theSharedBuilderAddsEveryResolvedElement() {
        String body = methodBody(readSource(SERVICE_PATH), "private BuiltTypeDescription buildTypeDescription("); //$NON-NLS-1$
        assertTrue("the builder must iterate the whole requested list", //$NON-NLS-1$
                body.contains("for (TypeSpec typeSpec : typeSpecs)")); //$NON-NLS-1$
        assertTrue("every resolved element must land in the description", //$NON-NLS-1$
                body.contains("typeDesc.getTypes().add(resolved.txTypeItem())")); //$NON-NLS-1$
    }

    @Test
    public void anUnresolvedElementIsNeverSkipped() {
        String body = methodBody(readSource(SERVICE_PATH), "private BuiltTypeDescription buildTypeDescription("); //$NON-NLS-1$
        assertTrue("an unresolvable element must throw", //$NON-NLS-1$
                body.contains("throw new MetadataOperationException(")); //$NON-NLS-1$
        assertTrue("a resolver returning null must be refused, not treated as 'skip me'", //$NON-NLS-1$
                body.contains("if (resolved == null || resolved.txTypeItem() == null)")); //$NON-NLS-1$
        // "continue" past a bad element is exactly how a composite request lost types while
        // still reporting success.
        assertFalse("the builder must not continue past an element", body.contains("continue;")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void qualifiersAreResolvedPerElementKind() {
        String body = methodBody(readSource(SERVICE_PATH), "private BuiltTypeDescription buildTypeDescription("); //$NON-NLS-1$
        // A TypeDescription holds one qualifier block per kind, so the first String / Number /
        // Date element owns its kind's block and a later one cannot fight over it.
        assertTrue(body.contains("if (!numberApplied &&")); //$NON-NLS-1$
        assertTrue(body.contains("if (!stringApplied &&")); //$NON-NLS-1$
        assertTrue(body.contains("if (!dateApplied &&")); //$NON-NLS-1$
    }

    // --- call site 1: update_metadata on a BasicFeature ---------------------

    @Test
    public void updateMetadataOnABasicFeatureAppliesTheWholeList() {
        String body = methodBody(readSource(SERVICE_PATH), "private void setFeatureValue("); //$NON-NLS-1$
        assertTrue("the type field must be normalized as a list", //$NON-NLS-1$
                body.contains("normalizeTypeSpecList(value)")); //$NON-NLS-1$
        assertFalse("no single-spec shortcut may survive here", //$NON-NLS-1$
                body.contains("String typeString = typeSpec.typeQuery();")); //$NON-NLS-1$
    }

    @Test
    public void theAttributeSetterTakesTheListAndWritesTheDescriptionOnce() {
        String body = methodBody(readSource(SERVICE_PATH), "private void setAttributeType("); //$NON-NLS-1$
        assertTrue("the setter must accept the full requested list", //$NON-NLS-1$
                body.contains("List<TypeSpec> typeSpecs")); //$NON-NLS-1$
        assertTrue("it must build through the shared builder", //$NON-NLS-1$
                body.contains("buildTypeDescription(")); //$NON-NLS-1$
        assertEquals("the description must be attached exactly once, after the whole list resolved", //$NON-NLS-1$
                1, countOccurrences(body, "feature.setType(")); //$NON-NLS-1$
        assertTrue("a type the project does not have must still fail loud, per element", //$NON-NLS-1$
                body.contains("notFoundMessagePrefix + typeSpec.typeQuery()")); //$NON-NLS-1$
    }

    // --- call site 2: add_metadata_child create path ------------------------

    @Test
    public void addMetadataChildAppliesTheWholeList() {
        String body = methodBody(readSource(SERVICE_PATH), "private void applyDefaultTypeIfNeeded("); //$NON-NLS-1$
        assertTrue("the create path must normalize as a list", //$NON-NLS-1$
                body.contains("requestedSpecs = normalizeTypeSpecList(properties)")); //$NON-NLS-1$
        assertTrue("the whole list must reach the setter", //$NON-NLS-1$
                body.contains("effectiveSpecs")); //$NON-NLS-1$
        assertTrue("an absent type must still fall back to the single default", //$NON-NLS-1$
                body.contains("List.of(TypeSpec.of(typeToApply))")); //$NON-NLS-1$
    }

    // --- call site 3: form attributes / parameters / columns ----------------

    @Test
    public void formAttributesApplyEveryTypeTheyValidate() {
        String source = readSource(SERVICE_PATH);
        String body = methodBody(source, "private void applyFormAttributeType("); //$NON-NLS-1$
        assertTrue("the form path must normalize as a list", //$NON-NLS-1$
                body.contains("normalizeTypeSpecList(typeValue)")); //$NON-NLS-1$
        assertTrue("and build through the shared builder", body.contains("buildTypeDescription(")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("the value type must be attached exactly once", //$NON-NLS-1$
                1, countOccurrences(body, "setTypeDescriptionOnEObject(")); //$NON-NLS-1$

        // The validator has always recursed into the whole list; it is the apply side that used
        // to keep one element. Both must now cover the same list.
        String validator = methodBody(source, "private void validateFormAttributeType("); //$NON-NLS-1$
        assertTrue("the validator must stay recursive over the list", //$NON-NLS-1$
                validator.contains("validateFormAttributeType(item)")); //$NON-NLS-1$
    }

    @Test
    public void theFormResolverKeepsItsBuiltInTypeGuidance() {
        // The long built-in-type message is the only actionable output when EDT has not indexed
        // the form yet; moving the ladder into a per-element resolver must not lose it.
        String body = methodBody(readSource(SERVICE_PATH), "private ResolvedTypeItem resolveFormAttributeTypeItem("); //$NON-NLS-1$
        assertTrue(body.contains("canonicalPlatformBuiltInTypeName(typeQuery)")); //$NON-NLS-1$
        assertTrue(body.contains("resolveFormAttributeTypeViaTypeProvider(")); //$NON-NLS-1$
        assertTrue("an unresolvable element must throw, never return null", //$NON-NLS-1$
                body.contains("Type value cannot be resolved for form attribute: ")); //$NON-NLS-1$
    }

    // --- pre-resolve collects all elements ----------------------------------

    @Test
    public void preResolveCollectsEveryRequestedType() {
        String source = readSource(SERVICE_PATH);
        assertTrue("there must be a collect-all counterpart of the lookup normalizer", //$NON-NLS-1$
                source.contains("private List<String> normalizeTypeLookupQueries(")); //$NON-NLS-1$

        String updateCollector = methodBody(source, "private Set<String> collectTypeStrings("); //$NON-NLS-1$
        assertTrue("update_metadata pre-resolve must collect all", //$NON-NLS-1$
                updateCollector.contains("normalizeTypeLookupQueries(")); //$NON-NLS-1$
        assertFalse("no first-only reader may remain in the update collector", //$NON-NLS-1$
                updateCollector.contains("normalizeTypeLookupQuery(")); //$NON-NLS-1$

        String childCollector = methodBody(source, "private Set<String> collectChildTypeStrings("); //$NON-NLS-1$
        assertTrue("add_metadata_child pre-resolve must collect all", //$NON-NLS-1$
                childCollector.contains("normalizeTypeLookupQueries(")); //$NON-NLS-1$
        assertFalse("no first-only reader may remain in the child collector", //$NON-NLS-1$
                childCollector.contains("normalizeTypeLookupQuery(")); //$NON-NLS-1$
    }

    @Test
    public void formPreResolveCollectsEveryRequestedType() {
        String source = readSource(SERVICE_PATH);
        String helper = methodBody(source, "private void addTypeSpecQueries("); //$NON-NLS-1$
        assertTrue("the form/column collector must iterate the whole list", //$NON-NLS-1$
                helper.contains("for (TypeSpec spec : normalizeTypeSpecList(typeValue))")); //$NON-NLS-1$
        assertTrue("attribute descriptors must use it", //$NON-NLS-1$
                methodBody(source, "private Set<String> collectFormAttributeTypeStrings(") //$NON-NLS-1$
                        .contains("addTypeSpecQueries(typeStrings, typeValue)")); //$NON-NLS-1$
        assertTrue("column descriptors must use it too", //$NON-NLS-1$
                methodBody(source, "private void collectColumnTypeStrings(") //$NON-NLS-1$
                        .contains("addTypeSpecQueries(typeStrings, columnType)")); //$NON-NLS-1$
    }

    // --- DefinedType / commandParameterType: qualifiers too -----------------

    @Test
    public void typeDescriptionReferencesShareTheBuilderAndKeepQualifiers() {
        String source = readSource(SERVICE_PATH);
        String body = methodBody(source, "private void applyTypeDescriptionReference("); //$NON-NLS-1$
        assertTrue("this path was already multi-type but dropped every qualifier", //$NON-NLS-1$
                body.contains("buildTypeDescription(")); //$NON-NLS-1$
        assertTrue("it must read the requested types as specs, not as bare strings", //$NON-NLS-1$
                body.contains("extractTypeSpecList(value)")); //$NON-NLS-1$
        assertTrue("qualifier inheritance needs the description being replaced", //$NON-NLS-1$
                body.contains("currentTypeDescription(target, reference)")); //$NON-NLS-1$
        assertFalse("the query-string-only extractor must be gone", //$NON-NLS-1$
                source.contains("extractTypeQueryList(")); //$NON-NLS-1$
    }

    @Test
    public void qualifiersOnAReferenceAreAdditiveOnly() {
        String source = readSource(SERVICE_PATH);
        // On a TypeDescription-valued reference a qualifier block must appear only if it was
        // asked for or was already there: materialising a platform default would start writing
        // <stringQualifiers> into .mdo files that never had one.
        assertTrue("the reference path must opt out of default qualifier blocks", //$NON-NLS-1$
                methodBody(source, "private void applyTypeDescriptionReference(") //$NON-NLS-1$
                        .contains("false,")); //$NON-NLS-1$
        assertTrue("the attribute path must keep materialising them, as it always has", //$NON-NLS-1$
                methodBody(source, "private void setAttributeType(").contains("true,")); //$NON-NLS-1$ //$NON-NLS-2$
        String builder = methodBody(source, "private BuiltTypeDescription buildTypeDescription("); //$NON-NLS-1$
        assertTrue(builder.contains("qualifierDefaults || hasStringQualifierInput(typeSpec, existingType)")); //$NON-NLS-1$
        assertTrue(builder.contains("qualifierDefaults || hasNumberQualifierInput(typeSpec, existingType)")); //$NON-NLS-1$
        assertTrue(builder.contains("qualifierDefaults || hasDateQualifierInput(typeSpec, existingType)")); //$NON-NLS-1$
    }

    // --- Helpers ------------------------------------------------------------

    private int countOccurrences(String haystack, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int at = haystack.indexOf(needle, from);
            if (at < 0) {
                return count;
            }
            count++;
            from = at + needle.length();
        }
    }

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
