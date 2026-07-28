package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * Tests for {@link TypeValueSplitter} — the pure half of the composite-type fix (B1).
 *
 * <p>What was broken: {@code type: ["CatalogRef.A", "CatalogRef.B"]} wrote only
 * {@code CatalogRef.A} and reported success. Every {@code type} shape the tools accept was
 * funnelled through a first-element-wins normalizer, so the second and later types of a
 * composite request were dropped without a single error — on {@code update_metadata} of any
 * BasicFeature (a Dimension of any register, a Resource, an Attribute), on the
 * {@code add_metadata_child} create path, and on form attributes / parameters / columns.</p>
 *
 * <p>Splitting is the part that decides <em>how many</em> types were asked for and what
 * qualifiers each one carries; turning a carrier into a type and resolving it needs the EDT
 * runtime and is pinned by {@link CompositeTypeContractTest}.</p>
 *
 * <p>The identity assertions below are the regression guard for the hot path: every attribute,
 * dimension and form-attribute write goes through here, and the overwhelming majority of them
 * ask for exactly one type. Those requests must reach the normalizer as the very same object
 * they always did.</p>
 */
public class TypeValueSplitterTest {

    // --- single type: byte-identical carrier (regression guard) --------------

    @Test
    public void aBareTypeStringIsPassedThroughByIdentity() {
        String value = "CatalogRef.Goods"; //$NON-NLS-1$
        List<Object> carriers = TypeValueSplitter.split(value);
        assertEquals(1, carriers.size());
        assertSame("a scalar request must reach the normalizer untouched", value, carriers.get(0)); //$NON-NLS-1$
    }

    @Test
    public void anInlineQualifiedTypeStringIsNotReshaped() {
        // The inline form is parsed downstream off the String itself; wrapping it in anything
        // would lose the length.
        String value = "String(100)"; //$NON-NLS-1$
        List<Object> carriers = TypeValueSplitter.split(value);
        assertEquals(1, carriers.size());
        assertSame(value, carriers.get(0));
    }

    @Test
    public void aSingleTypeMapIsPassedThroughByIdentity() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("type", "String"); //$NON-NLS-1$ //$NON-NLS-2$
        value.put("length", 100); //$NON-NLS-1$
        List<Object> carriers = TypeValueSplitter.split(value);
        assertEquals(1, carriers.size());
        assertSame("a single-type map must not be copied or rewritten", value, carriers.get(0)); //$NON-NLS-1$
    }

    @Test
    public void aOneElementTypesListInAMapIsPassedThroughByIdentity() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("types", List.of("CatalogRef.Goods")); //$NON-NLS-1$ //$NON-NLS-2$
        value.put("stringQualifiers", Map.of("length", 20)); //$NON-NLS-1$ //$NON-NLS-2$
        List<Object> carriers = TypeValueSplitter.split(value);
        assertEquals(1, carriers.size());
        assertSame(value, carriers.get(0));
    }

    @Test
    public void aMapWithoutATypePayloadIsPassedThroughByIdentity() {
        // e.g. {catalog:"Goods"} or {fqn:"CatalogRef.Goods"} — the downstream lookup knows these
        // shapes; the splitter has nothing to split.
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("catalog", "Goods"); //$NON-NLS-1$ //$NON-NLS-2$
        List<Object> carriers = TypeValueSplitter.split(value);
        assertEquals(1, carriers.size());
        assertSame(value, carriers.get(0));
    }

    @Test
    public void aSingleElementListYieldsTheElement() {
        // The one deliberate change on the single-type path: ["String(100)"] hands over the
        // element, so its inline qualifier is readable exactly like the bare "String(100)" form.
        // Handing over the list instead would make a one-element list lose a length that a
        // two-element list keeps.
        String element = "String(100)"; //$NON-NLS-1$
        List<Object> carriers = TypeValueSplitter.split(List.of(element));
        assertEquals(1, carriers.size());
        assertSame(element, carriers.get(0));
    }

    // --- composite type: one carrier per element ----------------------------

    @Test
    public void aListOfTypesYieldsOneCarrierPerElementInOrder() {
        List<Object> carriers = TypeValueSplitter.split(List.of("CatalogRef.A", "CatalogRef.B")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(2, carriers.size());
        assertEquals("CatalogRef.A", carriers.get(0)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("CatalogRef.B", carriers.get(1)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void listElementsAreHandedOverUnwrappedSoInlineQualifiersSurvive() {
        List<Object> carriers = TypeValueSplitter.split(List.of("String(100)", "Number(10,2)")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(2, carriers.size());
        assertEquals("String(100)", carriers.get(0)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Number(10,2)", carriers.get(1)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aTypesListInsideAMapYieldsOneCarrierPerElement() {
        // This is the shape update_metadata produces from set:{"type.types":[…]}.
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("types", List.of("CatalogRef.A", "DocumentRef.B")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        List<Object> carriers = TypeValueSplitter.split(value);
        assertEquals(2, carriers.size());
        assertEquals("CatalogRef.A", asMap(carriers.get(0)).get("type")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("DocumentRef.B", asMap(carriers.get(1)).get("type")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void aTypeKeyHoldingAListIsSplitToo() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("type", List.of("CatalogRef.A", "CatalogRef.B")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        List<Object> carriers = TypeValueSplitter.split(value);
        assertEquals(2, carriers.size());
        assertEquals("CatalogRef.A", asMap(carriers.get(0)).get("type")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("CatalogRef.B", asMap(carriers.get(1)).get("type")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void nestedListsAreFlattened() {
        List<Object> carriers = TypeValueSplitter.split(
                List.of("CatalogRef.A", List.of("CatalogRef.B", "CatalogRef.C"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(3, carriers.size());
    }

    @Test
    public void nullAndBlankElementsAreDroppedNotCounted() {
        List<Object> carriers = TypeValueSplitter.split(
                Arrays.asList("CatalogRef.A", null, "  ", "CatalogRef.B")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(2, carriers.size());
        assertEquals("CatalogRef.A", carriers.get(0)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("CatalogRef.B", carriers.get(1)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void theTypeKeyIsPreferredOverTypesWhenBothArePresent() {
        // The downstream lookup reads "type" first; the splitter must not disagree with it and
        // split on a "types" key the lookup will never look at.
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("type", "String"); //$NON-NLS-1$ //$NON-NLS-2$
        value.put("types", List.of("CatalogRef.A", "CatalogRef.B")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        List<Object> carriers = TypeValueSplitter.split(value);
        assertEquals(1, carriers.size());
        assertSame(value, carriers.get(0));
    }

    @Test
    public void payloadKeysAreMatchedIgnoringCase() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("Types", List.of("CatalogRef.A", "CatalogRef.B")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        List<Object> carriers = TypeValueSplitter.split(value);
        assertEquals(2, carriers.size());
        assertEquals("CatalogRef.A", asMap(carriers.get(0)).get("type")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    // --- composite type with qualifiers -------------------------------------

    @Test
    public void surroundingQualifiersAreInheritedByEveryElement() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("types", List.of("String", "CatalogRef.A")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        value.put("stringQualifiers", Map.of("length", 100)); //$NON-NLS-1$ //$NON-NLS-2$
        List<Object> carriers = TypeValueSplitter.split(value);
        assertEquals(2, carriers.size());
        for (Object carrier : carriers) {
            assertEquals("every element must see the description's qualifiers", //$NON-NLS-1$
                    Map.of("length", Integer.valueOf(100)), //$NON-NLS-1$
                    asMap(carrier).get("stringQualifiers")); //$NON-NLS-1$
        }
    }

    @Test
    public void anElementsOwnQualifiersWinOverTheSurroundingOnes() {
        Map<String, Object> firstElement = new LinkedHashMap<>();
        firstElement.put("type", "String"); //$NON-NLS-1$ //$NON-NLS-2$
        firstElement.put("length", 50); //$NON-NLS-1$
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("types", Arrays.asList(firstElement, "CatalogRef.A")); //$NON-NLS-1$ //$NON-NLS-2$
        value.put("length", 100); //$NON-NLS-1$
        List<Object> carriers = TypeValueSplitter.split(value);
        assertEquals(2, carriers.size());
        assertEquals("an element that names its own length keeps it", //$NON-NLS-1$
                Integer.valueOf(50), asMap(carriers.get(0)).get("length")); //$NON-NLS-1$
        assertEquals("an element that names none inherits the outer one", //$NON-NLS-1$
                Integer.valueOf(100), asMap(carriers.get(1)).get("length")); //$NON-NLS-1$
    }

    @Test
    public void unrelatedSiblingKeysAreCarriedOverButNeverTheTypePayload() {
        // The create path hands over a whole "properties" payload: "name" must ride along (a
        // later reader may need it) while "types" must not, or the carrier would look composite
        // again and the element could be resolved as the attribute's own name.
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("name", "Owner"); //$NON-NLS-1$ //$NON-NLS-2$
        value.put("types", List.of("CatalogRef.A", "CatalogRef.B")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        List<Object> carriers = TypeValueSplitter.split(value);
        assertEquals(2, carriers.size());
        for (Object carrier : carriers) {
            Map<String, Object> map = asMap(carrier);
            assertEquals("Owner", map.get("name")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            assertFalse("the composite payload key must not survive into a carrier", //$NON-NLS-1$
                    map.containsKey("types")); //$NON-NLS-1$
            assertTrue("the element must be addressable under the canonical key", //$NON-NLS-1$
                    map.containsKey("type")); //$NON-NLS-1$
        }
    }

    @Test
    public void anElementMapKeepsItsOwnTypeUnderTheCanonicalKey() {
        Map<String, Object> element = new LinkedHashMap<>();
        element.put("fqn", "CatalogRef.Goods"); //$NON-NLS-1$ //$NON-NLS-2$
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("types", Arrays.asList(element, "CatalogRef.Other")); //$NON-NLS-1$ //$NON-NLS-2$
        List<Object> carriers = TypeValueSplitter.split(value);
        assertEquals(2, carriers.size());
        assertSame("the element itself must be reachable, not a flattened copy of it", //$NON-NLS-1$
                element, asMap(carriers.get(0)).get("type")); //$NON-NLS-1$
    }

    // --- nothing requested --------------------------------------------------

    @Test
    public void nothingRequestedYieldsNoCarriers() {
        assertTrue(TypeValueSplitter.split(null).isEmpty());
        assertTrue(TypeValueSplitter.split("").isEmpty()); //$NON-NLS-1$
        assertTrue(TypeValueSplitter.split("   ").isEmpty()); //$NON-NLS-1$
        assertTrue(TypeValueSplitter.split(List.of()).isEmpty());
        assertTrue(TypeValueSplitter.split(Arrays.asList(null, "", "  ")).isEmpty()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void anEmptyTypePayloadLeavesTheMapForTheNormalizerToReject() {
        // The normalizer owns the "empty or invalid type" message; the splitter must not swallow
        // the request and turn a loud refusal into a silent no-op.
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("type", List.of()); //$NON-NLS-1$
        List<Object> carriers = TypeValueSplitter.split(value);
        assertEquals(1, carriers.size());
        assertSame(value, carriers.get(0));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        assertTrue("expected a synthetic carrier map, got: " + value, value instanceof Map); //$NON-NLS-1$
        return (Map<String, Object>) value;
    }
}
