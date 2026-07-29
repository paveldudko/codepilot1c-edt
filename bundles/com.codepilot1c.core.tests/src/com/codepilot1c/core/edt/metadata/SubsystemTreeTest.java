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
 * real one, which is the chain {@code Subsystem.<Parent>.Subsystem.<Child>}. The last block pins
 * what a move has to do to that chain for every subsystem BELOW the one being moved.</p>
 *
 * <p>The traversal is generic over a node accessor precisely so it can be exercised here:
 * EMF model classes do not resolve in the plain Maven test bundle.</p>
 */
public class SubsystemTreeTest {

    /** Stand-in for {@code mdclass.Subsystem}: a name plus a non-containment child list. */
    private static final class Node {
        private final String name;
        private final List<Node> children = new ArrayList<>();
        /** Stands in for the live {@code bmGetFqn()} — the FQN the object is REGISTERED under. */
        private String storageFqn;

        Node(String name) {
            this.name = name;
        }

        String name() {
            return name;
        }

        List<Node> children() {
            return children;
        }

        String storageFqn() {
            return storageFqn;
        }

        Node at(String fqn) {
            this.storageFqn = fqn;
            return this;
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
        // Two spellings of one address: walk the chain parent-then-child, or resolve the flat name
        // directly. Both must land on the same node — the chain is what the object is REGISTERED
        // under, the flat name is the alias this walk provides on top of it.
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

    // --- descendantRelocations ----------------------------------------------
    //
    // What this exists for, live-measured 2026-07-29 on the sandbox: updateTopObjectFqn moves the ONE
    // object it is given. WaveR8P holding WaveR8C moved under WaveParent, and afterwards
    // WaveR8P.subsystems read back as a NAMELESS stub while Subsystem.WaveR8C answered "Object not
    // found" to edt_metadata_details AND to update_metadata — unaddressable, and unrepairable through
    // any tool. Every descendant has to follow its owner.

    private static List<SubsystemTree.Relocation<Node>> plan(String ownerFqn, Node owner) {
        return SubsystemTree.descendantRelocations(
                ownerFqn, owner.children(), Node::name, Node::storageFqn, Node::children);
    }

    @Test
    public void everyDescendantIsRePointedAtTheOwnersNewChain() {
        Node grandChild = new Node("Debts").at("Subsystem.Finance.Subsystem.Calendar.Subsystem.Debts"); //$NON-NLS-1$ //$NON-NLS-2$
        Node child = new Node("Calendar").at("Subsystem.Finance.Subsystem.Calendar").with(grandChild); //$NON-NLS-1$ //$NON-NLS-2$
        Node owner = new Node("Finance").at("Subsystem.Finance").with(child); //$NON-NLS-1$ //$NON-NLS-2$

        List<SubsystemTree.Relocation<Node>> plan = plan("Subsystem.Group.Subsystem.Finance", owner); //$NON-NLS-1$

        assertEquals(2, plan.size());
        assertSame(child, plan.get(0).node());
        assertEquals("Subsystem.Finance.Subsystem.Calendar", plan.get(0).previousFqn()); //$NON-NLS-1$
        assertEquals("Subsystem.Group.Subsystem.Finance.Subsystem.Calendar", plan.get(0).targetFqn()); //$NON-NLS-1$
        assertSame(grandChild, plan.get(1).node());
        assertEquals("Subsystem.Finance.Subsystem.Calendar.Subsystem.Debts", plan.get(1).previousFqn()); //$NON-NLS-1$
        assertEquals("Subsystem.Group.Subsystem.Finance.Subsystem.Calendar.Subsystem.Debts", //$NON-NLS-1$
                plan.get(1).targetFqn());
    }

    @Test
    public void parentsComeBeforeTheirOwnChildren() {
        // Order is a correctness property, not cosmetics: a child's target slot is built from the
        // chain its parent has just been re-registered under, so the parent has to be moved first.
        Node leaf = new Node("Debts"); //$NON-NLS-1$
        Node middle = new Node("Calendar").with(leaf); //$NON-NLS-1$
        Node owner = new Node("Finance").with(middle); //$NON-NLS-1$

        List<SubsystemTree.Relocation<Node>> plan = plan("Subsystem.Finance", owner); //$NON-NLS-1$

        assertEquals(List.of("Calendar", "Debts"), //$NON-NLS-1$ //$NON-NLS-2$
                plan.stream().map(entry -> entry.node().name()).toList());
    }

    @Test
    public void movingToTheConfigurationRootShortensEveryChain() {
        Node grandChild = new Node("Debts").at("Subsystem.Group.Subsystem.Finance.Subsystem.Calendar.Subsystem.Debts"); //$NON-NLS-1$ //$NON-NLS-2$
        Node child = new Node("Calendar").at("Subsystem.Group.Subsystem.Finance.Subsystem.Calendar") //$NON-NLS-1$ //$NON-NLS-2$
                .with(grandChild);
        Node owner = new Node("Finance").with(child); //$NON-NLS-1$

        List<SubsystemTree.Relocation<Node>> plan = plan("Subsystem.Finance", owner); //$NON-NLS-1$

        assertEquals("Subsystem.Finance.Subsystem.Calendar", plan.get(0).targetFqn()); //$NON-NLS-1$
        assertEquals("Subsystem.Finance.Subsystem.Calendar.Subsystem.Debts", plan.get(1).targetFqn()); //$NON-NLS-1$
    }

    @Test
    public void aDescendantAlreadyInItsSlotIsStillReported() {
        // The planner states the facts; skipping a no-op re-registration is the caller's call, and it
        // still has to report the FQN as co-edited so the export writes the .mdo.
        Node child = new Node("Calendar").at("Subsystem.Finance.Subsystem.Calendar"); //$NON-NLS-1$ //$NON-NLS-2$
        Node owner = new Node("Finance").with(child); //$NON-NLS-1$

        List<SubsystemTree.Relocation<Node>> plan = plan("Subsystem.Finance", owner); //$NON-NLS-1$

        assertEquals(1, plan.size());
        assertEquals(plan.get(0).previousFqn(), plan.get(0).targetFqn());
    }

    @Test
    public void anUnnameableDescendantIsReportedAndItsSubtreeLeftAlone() {
        // No name means no slot, and nothing below it can be named either — its children's chains are
        // built from the slot the unnameable node would have had. Reported rather than dropped, so
        // the caller can say out loud that a subtree kept its old registration.
        Node hidden = new Node("Debts").at("Subsystem.Finance.Subsystem..Subsystem.Debts"); //$NON-NLS-1$ //$NON-NLS-2$
        Node unnamed = new Node(null).with(hidden);
        Node named = new Node("Calendar").at("Subsystem.Finance.Subsystem.Calendar"); //$NON-NLS-1$ //$NON-NLS-2$
        Node owner = new Node("Finance").with(unnamed, named); //$NON-NLS-1$

        List<SubsystemTree.Relocation<Node>> plan = plan("Subsystem.Group.Subsystem.Finance", owner); //$NON-NLS-1$

        assertEquals(2, plan.size());
        assertSame(unnamed, plan.get(0).node());
        assertNull("an unnameable node has no target slot", plan.get(0).targetFqn()); //$NON-NLS-1$
        assertSame("the unnameable node's own child must not be planned", named, plan.get(1).node()); //$NON-NLS-1$
    }

    @Test
    public void aCyclicChildLinkCannotHangThePlanner() {
        Node a = new Node("A"); //$NON-NLS-1$
        Node b = new Node("B"); //$NON-NLS-1$
        a.with(b);
        b.with(a);
        Node owner = new Node("Finance").with(a); //$NON-NLS-1$

        List<SubsystemTree.Relocation<Node>> plan = plan("Subsystem.Finance", owner); //$NON-NLS-1$

        assertEquals(List.of("A", "B"), plan.stream().map(entry -> entry.node().name()).toList()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aChildlessOwnerPlansNothing() {
        assertTrue(plan("Subsystem.Finance", new Node("Finance")).isEmpty()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(SubsystemTree.descendantRelocations(
                "Subsystem.Finance", null, Node::name, Node::storageFqn, Node::children).isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void aDescendantWhoseLiveFqnIsUnreadableStillGetsATarget() {
        // BM answering null must not cost the descendant its re-registration: without a target it
        // would be the unaddressable object this whole plan exists to prevent. Only the vacated-path
        // cleanup loses out, and that is the cosmetic half.
        Node child = new Node("Calendar"); //$NON-NLS-1$
        Node owner = new Node("Finance").with(child); //$NON-NLS-1$

        SubsystemTree.Relocation<Node> entry = plan("Subsystem.Group.Subsystem.Finance", owner).get(0); //$NON-NLS-1$

        assertNull(entry.previousFqn());
        assertEquals("Subsystem.Group.Subsystem.Finance.Subsystem.Calendar", entry.targetFqn()); //$NON-NLS-1$
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
