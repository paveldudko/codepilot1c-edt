package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * Tests for {@link SubsystemTree} (BF-12936 / B4).
 *
 * <p>The flat form {@code Subsystem.PaymentCalendar} addresses a subsystem at every depth, because
 * both subsystem collections are non-containment and the lookup therefore walks the forest. Two
 * consequences are pinned here: the flat name must resolve at any depth (the old lookup only
 * scanned {@code Configuration.getSubsystems()} and rejected every nested subsystem), and a name
 * shared by two subsystems under different parents must be reported as ambiguous with both parents
 * named — never silently resolved to whichever came first in the traversal.</p>
 *
 * <p>The flat form is an ALIAS, though, not the storage FQN — the second block of tests pins the
 * real one, which is the chain {@code Subsystem.<Parent>.Subsystem.<Child>}.</p>
 *
 * <p>The traversal is generic over a node accessor precisely so it can be exercised here:
 * EMF model classes do not resolve in the plain Maven test bundle.</p>
 */
public class SubsystemTreeTest {

    /** Stand-in for {@code mdclass.Subsystem}: a name plus a non-containment child list. */
    private static final class Node {
        private final String name;
        private final List<Node> children = new ArrayList<>();

        Node(String name) {
            this.name = name;
        }

        String name() {
            return name;
        }

        List<Node> children() {
            return children;
        }

        Node with(Node... kids) {
            for (Node kid : kids) {
                children.add(kid);
            }
            return this;
        }
    }

    private static List<SubsystemTree.Located<Node>> locate(List<Node> roots, String name) {
        return SubsystemTree.locateByName(roots, name, Node::name, Node::children);
    }

    // --- flatten -------------------------------------------------------------

    @Test
    public void flattenWalksTheWholeForestParentsFirst() {
        Node child = new Node("PaymentCalendar"); //$NON-NLS-1$
        Node grandChild = new Node("Reports"); //$NON-NLS-1$
        child.with(grandChild);
        Node root = new Node("Finance").with(child); //$NON-NLS-1$
        Node other = new Node("Sales"); //$NON-NLS-1$

        List<Node> flat = SubsystemTree.flatten(List.of(root, other), Node::children);

        assertEquals(List.of("Finance", "PaymentCalendar", "Reports", "Sales"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                flat.stream().map(Node::name).toList());
    }

    @Test
    public void flattenTerminatesOnCyclicChildLink() {
        // subsystems is non-containment, so nothing in the model structurally prevents a cycle.
        Node a = new Node("A"); //$NON-NLS-1$
        Node b = new Node("B"); //$NON-NLS-1$
        a.with(b);
        b.with(a);

        List<Node> flat = SubsystemTree.flatten(List.of(a), Node::children);

        assertEquals(List.of("A", "B"), flat.stream().map(Node::name).toList()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void flattenToleratesNullInput() {
        assertTrue(SubsystemTree.flatten(null, Node::children).isEmpty());
    }

    // --- locateByName --------------------------------------------------------

    @Test
    public void flatNameResolvesForANestedSubsystem() {
        Node nested = new Node("PaymentCalendar"); //$NON-NLS-1$
        Node root = new Node("Finance").with(nested); //$NON-NLS-1$

        List<SubsystemTree.Located<Node>> hits = locate(List.of(root), "PaymentCalendar"); //$NON-NLS-1$

        assertEquals(1, hits.size());
        assertSame(nested, hits.get(0).node());
        assertEquals(List.of("Finance"), hits.get(0).parentPath()); //$NON-NLS-1$
    }

    @Test
    public void nestedAndFlatFormsReachTheSameObject() {
        // Nested alias: resolve the parent, then its child. Flat canonical form: resolve
        // directly. Both must land on the same node, which is what makes the flat form the
        // documented spelling and the nested chain a tolerant alias.
        Node nested = new Node("PaymentCalendar"); //$NON-NLS-1$
        Node root = new Node("Finance").with(nested); //$NON-NLS-1$
        List<Node> forest = List.of(root);

        Node viaFlat = locate(forest, "PaymentCalendar").get(0).node(); //$NON-NLS-1$
        Node parent = locate(forest, "Finance").get(0).node(); //$NON-NLS-1$
        Node viaNested = locate(parent.children(), "PaymentCalendar").get(0).node(); //$NON-NLS-1$

        assertSame(viaFlat, viaNested);
    }

    @Test
    public void lookupIsCaseInsensitiveAndTrims() {
        Node nested = new Node("PaymentCalendar"); //$NON-NLS-1$
        Node root = new Node("Finance").with(nested); //$NON-NLS-1$

        assertSame(nested, locate(List.of(root), "  paymentcalendar  ").get(0).node()); //$NON-NLS-1$
    }

    @Test
    public void unknownNameYieldsNoHits() {
        Node root = new Node("Finance"); //$NON-NLS-1$

        assertTrue(locate(List.of(root), "Nope").isEmpty()); //$NON-NLS-1$
        assertTrue(locate(List.of(root), null).isEmpty());
        assertTrue(locate(List.of(root), "  ").isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void topLevelHitReportsTheConfigurationAsItsParent() {
        Node root = new Node("Finance"); //$NON-NLS-1$

        SubsystemTree.Located<Node> hit = locate(List.of(root), "Finance").get(0); //$NON-NLS-1$

        assertTrue(hit.parentPath().isEmpty());
        assertEquals(SubsystemTree.CONFIGURATION_ROOT, hit.describePath());
    }

    @Test
    public void deepHitReportsTheWholeParentChain() {
        Node leaf = new Node("Debts"); //$NON-NLS-1$
        Node middle = new Node("PaymentCalendar").with(leaf); //$NON-NLS-1$
        Node root = new Node("Finance").with(middle); //$NON-NLS-1$

        SubsystemTree.Located<Node> hit = locate(List.of(root), "Debts").get(0); //$NON-NLS-1$

        assertEquals(List.of("Finance", "PaymentCalendar"), hit.parentPath()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Finance > PaymentCalendar", hit.describePath()); //$NON-NLS-1$
    }

    // --- ambiguity -----------------------------------------------------------

    @Test
    public void duplicateNameUnderDifferentParentsReportsEveryHit() {
        Node underFinance = new Node("Reports"); //$NON-NLS-1$
        Node underSales = new Node("Reports"); //$NON-NLS-1$
        Node finance = new Node("Finance").with(underFinance); //$NON-NLS-1$
        Node sales = new Node("Sales").with(underSales); //$NON-NLS-1$

        List<SubsystemTree.Located<Node>> hits = locate(List.of(finance, sales), "Reports"); //$NON-NLS-1$

        assertEquals(2, hits.size());
        assertEquals(List.of("Finance"), hits.get(0).parentPath()); //$NON-NLS-1$
        assertEquals(List.of("Sales"), hits.get(1).parentPath()); //$NON-NLS-1$
    }

    @Test
    public void duplicateAcrossTopLevelAndNestedIsAmbiguousToo() {
        Node nested = new Node("Reports"); //$NON-NLS-1$
        Node finance = new Node("Finance").with(nested); //$NON-NLS-1$
        Node topLevel = new Node("Reports"); //$NON-NLS-1$

        List<SubsystemTree.Located<Node>> hits = locate(List.of(finance, topLevel), "Reports"); //$NON-NLS-1$

        assertEquals(2, hits.size());
    }

    @Test
    public void ambiguityMessageNamesBothParentsAndTheFlatFqnRule() {
        Node finance = new Node("Finance").with(new Node("Reports")); //$NON-NLS-1$ //$NON-NLS-2$
        Node sales = new Node("Sales").with(new Node("Reports")); //$NON-NLS-1$ //$NON-NLS-2$
        List<SubsystemTree.Located<Node>> hits = locate(List.of(finance, sales), "Reports"); //$NON-NLS-1$

        String message = SubsystemTree.describeAmbiguity("Subsystem", "Reports", hits); //$NON-NLS-1$ //$NON-NLS-2$

        assertNotNull(message);
        assertTrue("both colliding parents must be named: " + message, //$NON-NLS-1$
                message.contains("Finance") && message.contains("Sales")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("message must say the FQN is flat: " + message, //$NON-NLS-1$
                message.contains("flat")); //$NON-NLS-1$
        assertTrue("message must point at the nested alias: " + message, //$NON-NLS-1$
                message.contains("Subsystem.<Parent>.Subsystem.<Name>")); //$NON-NLS-1$
    }

    @Test
    public void locatedParentPathIsDefensivelyCopied() {
        // The walker reuses one mutable path buffer while descending; every hit must keep its own.
        Node leafA = new Node("X"); //$NON-NLS-1$
        Node leafB = new Node("X"); //$NON-NLS-1$
        Node first = new Node("First").with(leafA); //$NON-NLS-1$
        Node second = new Node("Second").with(leafB); //$NON-NLS-1$

        List<SubsystemTree.Located<Node>> hits = locate(List.of(first, second), "X"); //$NON-NLS-1$

        assertEquals(List.of("First"), hits.get(0).parentPath()); //$NON-NLS-1$
        assertEquals(List.of("Second"), hits.get(1).parentPath()); //$NON-NLS-1$
    }

    // --- storage FQN ---------------------------------------------------------
    //
    // Ground truth, decompiled from EDT 2025.2.3
    // (MdTopObjectFqnGeneratorDelegate.generateNamedExternalPropertyFqnInternal): a subsystem owned
    // by Configuration.subsystems is registered as QualifiedName.create("Subsystem", name); one
    // owned by Subsystem.subsystems is registered as ownerFqn.append("Subsystem").append(name).
    // Confirmed live: edt_metadata_details on Subsystem.Accounting in an EDT-authored configuration
    // renders its children as Subsystem.Accounting.Subsystem.<Child>, which is bmGetFqn().

    @Test
    public void aRootSubsystemIsStoredUnderTheFlatFqn() {
        assertEquals("Subsystem.Finance", SubsystemTree.qualifiedName(null, "Finance")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Subsystem.Finance", SubsystemTree.qualifiedName("", "Finance")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("Subsystem.Finance", SubsystemTree.qualifiedName("  ", " Finance ")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void aNestedSubsystemIsStoredUnderTheOwnerChain() {
        // The regression this guards: re-parenting used to leave the child at Subsystem.Finance,
        // so the parent's bare-name <subsystems>PaymentCalendar</subsystems> resolved to nothing.
        assertEquals("Subsystem.Finance.Subsystem.PaymentCalendar", //$NON-NLS-1$
                SubsystemTree.qualifiedName("Subsystem.Finance", "PaymentCalendar")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void theOwnerChainGrowsWithEveryLevel() {
        String level1 = SubsystemTree.qualifiedName(null, "Finance"); //$NON-NLS-1$
        String level2 = SubsystemTree.qualifiedName(level1, "PaymentCalendar"); //$NON-NLS-1$
        String level3 = SubsystemTree.qualifiedName(level2, "Debts"); //$NON-NLS-1$

        assertEquals("Subsystem.Finance.Subsystem.PaymentCalendar.Subsystem.Debts", level3); //$NON-NLS-1$
    }

    @Test
    public void anUnnamedSubsystemHasNoSlotAtAll() {
        // Callers must then leave the object where it is rather than move it somewhere unnameable.
        assertNull(SubsystemTree.qualifiedName("Subsystem.Finance", null)); //$NON-NLS-1$
        assertNull(SubsystemTree.qualifiedName("Subsystem.Finance", "  ")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(SubsystemTree.qualifiedName(null, null));
    }

    // --- nameChain ----------------------------------------------------------

    @Test
    public void nameChainReadsTheNamesOutermostFirst() {
        assertEquals(List.of("Finance"), SubsystemTree.nameChain("Subsystem.Finance")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(List.of("Finance", "PaymentCalendar"), //$NON-NLS-1$ //$NON-NLS-2$
                SubsystemTree.nameChain("Subsystem.Finance.Subsystem.PaymentCalendar")); //$NON-NLS-1$
        assertEquals(List.of("Finance", "PaymentCalendar", "Debts"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                SubsystemTree.nameChain("Subsystem.Finance.Subsystem.PaymentCalendar.Subsystem.Debts")); //$NON-NLS-1$
    }

    @Test
    public void nameChainRoundTripsWithQualifiedName() {
        String fqn = SubsystemTree.qualifiedName(
                SubsystemTree.qualifiedName(null, "Finance"), "PaymentCalendar"); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(List.of("Finance", "PaymentCalendar"), SubsystemTree.nameChain(fqn)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void nameChainRefusesAnythingThatIsNotASubsystemChain() {
        // Strict on purpose: the chain names files, so a half-understood FQN must yield no path.
        assertTrue(SubsystemTree.nameChain("Catalog.Products").isEmpty()); //$NON-NLS-1$
        assertTrue(SubsystemTree.nameChain("Subsystem.Finance.PaymentCalendar").isEmpty()); //$NON-NLS-1$
        assertTrue(SubsystemTree.nameChain("Subsystem.Finance.Form.ListForm").isEmpty()); //$NON-NLS-1$
        assertTrue(SubsystemTree.nameChain("Subsystem").isEmpty()); //$NON-NLS-1$
        assertTrue(SubsystemTree.nameChain(null).isEmpty());
        assertTrue(SubsystemTree.nameChain("   ").isEmpty()); //$NON-NLS-1$
    }
}
