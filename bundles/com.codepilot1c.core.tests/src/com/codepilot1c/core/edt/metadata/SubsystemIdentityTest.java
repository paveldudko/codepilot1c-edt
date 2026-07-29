package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

/**
 * Behavioural test for subsystem membership identity (F1).
 *
 * <p>What was broken: repeating the same {@code update_metadata set.parentSubsystem} appended another
 * {@code <subsystems>} line to the parent's {@code .mdo} every time. Live on the sandbox
 * 2026-07-29, {@code Subsystem.WaveParent} ended up listing {@code WaveChild} twice and
 * {@code WaveChild2} twice. The membership test compared {@code getName()}, and a diagnostic build
 * showed why that can never match: the entries of the parent's collection are unresolved proxies
 * with {@code name=null} and {@code bmFqn=transient}, carrying their identity only in the URI
 * {@code bm://TestConfiguration/Subsystem.WaveParent.Subsystem.WaveChild#/}.</p>
 *
 * <p>These assertions call the rule and check its answers; the sandbox {@code .mdo} confirms the
 * end-to-end effect.</p>
 */
public class SubsystemIdentityTest {

    /** Exactly what the live diagnostic printed for an entry of {@code WaveParent.subsystems}. */
    private static final String PROXY_URI =
            "bm://TestConfiguration/Subsystem.WaveParent.Subsystem.WaveChild#/"; //$NON-NLS-1$

    // --- the proxy that used to be invisible ---------------------------------

    @Test
    public void aNamelessProxyIsIdentifiedByItsUri() {
        assertEquals("wavechild", SubsystemIdentity.of(null, PROXY_URI)); //$NON-NLS-1$
    }

    @Test
    public void theLiveChildAndTheProxyEntryAreTheSameSubsystem() {
        // The regression itself: child arrives live and named, the existing entry is a bare proxy.
        String live = SubsystemIdentity.of("WaveChild", "bm://TestConfiguration/Subsystem.WaveChild#/"); //$NON-NLS-1$ //$NON-NLS-2$
        String existing = SubsystemIdentity.of(null, PROXY_URI);
        assertTrue(SubsystemIdentity.same(live, existing));
    }

    @Test
    public void aDifferentChildUnderTheSameParentDoesNotMatch() {
        String child = SubsystemIdentity.of("WaveChild", null); //$NON-NLS-1$
        String sibling = SubsystemIdentity.of(null,
                "bm://TestConfiguration/Subsystem.WaveParent.Subsystem.WaveChild2#/"); //$NON-NLS-1$
        assertFalse(SubsystemIdentity.same(child, sibling));
    }

    @Test
    public void aNameThatIsAPrefixOfAnotherDoesNotMatchIt() {
        // "Child" must not match ".Subsystem.WaveChild" — a substring reading of the URI would.
        String child = SubsystemIdentity.of("Child", null); //$NON-NLS-1$
        assertFalse(SubsystemIdentity.same(child, SubsystemIdentity.of(null, PROXY_URI)));
    }

    @Test
    public void theParentItselfIsNotOneOfItsChildren() {
        String parent = SubsystemIdentity.of("WaveParent", null); //$NON-NLS-1$
        assertFalse(SubsystemIdentity.same(parent, SubsystemIdentity.of(null, PROXY_URI)));
    }

    // --- a readable name still wins ------------------------------------------

    @Test
    public void aReadableNameIsUsedEvenWhenTheUriSaysSomethingElse() {
        assertEquals("live", SubsystemIdentity.of("Live", PROXY_URI)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void namesAreComparedCaseInsensitively() {
        assertTrue(SubsystemIdentity.same(
                SubsystemIdentity.of("WaveChild", null), SubsystemIdentity.of("wavechild", null))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aBlankNameFallsThroughToTheUri() {
        assertEquals("wavechild", SubsystemIdentity.of("   ", PROXY_URI)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // --- the strictness that keeps a wrong match impossible ------------------

    @Test
    public void aTopLevelSubsystemUriStillYieldsItsName() {
        assertEquals("wavechild", //$NON-NLS-1$
                SubsystemIdentity.nameFromUri("bm://TestConfiguration/Subsystem.WaveChild#/")); //$NON-NLS-1$
    }

    @Test
    public void aDeeperNestingChainYieldsTheLastElement() {
        assertEquals("c", SubsystemIdentity.nameFromUri( //$NON-NLS-1$
                "bm://Proj/Subsystem.A.Subsystem.B.Subsystem.C#/")); //$NON-NLS-1$
    }

    @Test
    public void aFilePathUriYieldsNothingRatherThanAFileExtension() {
        // "Foo.mdo" must not read as a subsystem named "mdo".
        assertNull(SubsystemIdentity.nameFromUri(
                "platform:/resource/Proj/src/Subsystems/Foo/Foo.mdo#/")); //$NON-NLS-1$
    }

    @Test
    public void anotherMetadataKindYieldsNothing() {
        assertNull(SubsystemIdentity.nameFromUri("bm://Proj/Catalog.Catalog#/")); //$NON-NLS-1$
    }

    @Test
    public void malformedOrEmptyUrisYieldNothing() {
        assertNull(SubsystemIdentity.nameFromUri(null));
        assertNull(SubsystemIdentity.nameFromUri("")); //$NON-NLS-1$
        assertNull(SubsystemIdentity.nameFromUri("   ")); //$NON-NLS-1$
        assertNull(SubsystemIdentity.nameFromUri("bm://Proj/Subsystem.#/")); //$NON-NLS-1$
        assertNull(SubsystemIdentity.nameFromUri("bm://Proj/WaveChild#/")); //$NON-NLS-1$
        assertNull(SubsystemIdentity.nameFromUri("bm://Proj/.WaveChild#/")); //$NON-NLS-1$
    }

    @Test
    public void aUriWithoutAFragmentIsStillRead() {
        assertEquals("wavechild", //$NON-NLS-1$
                SubsystemIdentity.nameFromUri("bm://Proj/Subsystem.WaveParent.Subsystem.WaveChild")); //$NON-NLS-1$
    }

    @Test
    public void anUnidentifiableEntryMatchesNothingIncludingAnotherUnidentifiableOne() {
        // Two entries we cannot name must not collapse into each other, or a prune would drop a
        // real child. This is why an absent identity is not equal to an absent identity.
        String unknownA = SubsystemIdentity.of(null, "bm://Proj/Catalog.A#/"); //$NON-NLS-1$
        String unknownB = SubsystemIdentity.of(null, "bm://Proj/Catalog.B#/"); //$NON-NLS-1$
        assertNull(unknownA);
        assertNull(unknownB);
        assertFalse(SubsystemIdentity.same(unknownA, unknownB));
        assertFalse(SubsystemIdentity.same(unknownA, "wavechild")); //$NON-NLS-1$
        assertFalse(SubsystemIdentity.same("wavechild", unknownB)); //$NON-NLS-1$
    }

    @Test
    public void noNameAndNoUriYieldsNothing() {
        assertNull(SubsystemIdentity.of(null, null));
    }

    // --- what the service does with those answers ----------------------------

    /**
     * The repair applied to an already-duplicated collection, replaying the prune rule over the
     * exact state the sandbox {@code WaveParent.mdo} was left in.
     */
    @Test
    public void pruningTheLiveDuplicatedCollectionKeepsOneOfEach() {
        List<String> collection = new ArrayList<>(List.of(
                "bm://TestConfiguration/Subsystem.WaveParent.Subsystem.WaveChild2#/", //$NON-NLS-1$
                "bm://TestConfiguration/Subsystem.WaveParent.Subsystem.WaveChild2#/", //$NON-NLS-1$
                "bm://TestConfiguration/Subsystem.WaveParent.Subsystem.WaveChild#/", //$NON-NLS-1$
                "bm://TestConfiguration/Subsystem.WaveParent.Subsystem.WaveChild#/")); //$NON-NLS-1$

        List<String> pruned = prune(collection);

        assertEquals(2, pruned.size());
        assertEquals("wavechild2", SubsystemIdentity.nameFromUri(pruned.get(0))); //$NON-NLS-1$
        assertEquals("wavechild", SubsystemIdentity.nameFromUri(pruned.get(1))); //$NON-NLS-1$
    }

    @Test
    public void pruningLeavesUnidentifiableEntriesAloneEvenWhenIdentical() {
        List<String> collection = new ArrayList<>(List.of(
                "bm://Proj/Catalog.Mystery#/", //$NON-NLS-1$
                "bm://Proj/Catalog.Mystery#/")); //$NON-NLS-1$

        assertEquals(2, prune(collection).size());
    }

    @Test
    public void aCleanCollectionSurvivesPruningUnchanged() {
        List<String> collection = new ArrayList<>(List.of(
                "bm://Proj/Subsystem.P.Subsystem.A#/", //$NON-NLS-1$
                "bm://Proj/Subsystem.P.Subsystem.B#/")); //$NON-NLS-1$

        assertEquals(collection, prune(collection));
    }

    /** Mirrors {@code EdtMetadataService.pruneDuplicateSubsystems} over URIs instead of EObjects. */
    private static List<String> prune(List<String> uris) {
        List<String> siblings = new ArrayList<>(uris);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < siblings.size(); i++) {
            String identity = SubsystemIdentity.of(null, siblings.get(i));
            if (identity == null) {
                continue;
            }
            if (!seen.add(identity)) {
                siblings.remove(i);
                i--;
            }
        }
        return siblings;
    }
}
