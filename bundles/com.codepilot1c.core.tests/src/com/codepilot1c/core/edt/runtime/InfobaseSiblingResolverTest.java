package com.codepilot1c.core.edt.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.codepilot1c.core.edt.runtime.InfobaseSiblingResolver.Sibling;
import com.codepilot1c.core.edt.runtime.InfobaseSiblingResolver.SiblingCandidate;

/**
 * Hermetic tests for the pure half of {@link InfobaseSiblingResolver} — the aggregation that turns
 * "every open project with its infobase identity, relation and equality state" into the shared-infobase
 * verdict. No EDT/Eclipse runtime is touched.
 *
 * <p>The classification is the part that must not regress: a configuration and its extension(s) MUST
 * converge, so a NOT_EQUAL sibling there is a genuine stale signal; two independent CONFIGURATION
 * projects on one infobase are mutually exclusive by construction (both cannot be EQUAL), so treating
 * their NOT_EQUAL as staleness would be a permanent false alarm. LOADING/absent is "unknown", never
 * stale — EDT recomputes the comparison right after an update.</p>
 */
public class InfobaseSiblingResolverTest {

    private static final String IB = "File=\"c:\\1C\\db\\am\";"; //$NON-NLS-1$
    private static final String OTHER_IB = "File=\"c:\\1C\\db\\other\";"; //$NON-NLS-1$

    private static SiblingCandidate candidate(String project, String identity, String relation, String state) {
        return new SiblingCandidate(project, identity, relation, state);
    }

    private static Sibling only(List<Sibling> siblings) {
        assertEquals("expected exactly one sibling: " + siblings, 1, siblings.size()); //$NON-NLS-1$
        return siblings.get(0);
    }

    // -- F1: shared infobase over 2 and 3 projects -------------------------------------------------

    @Test
    public void twoProjectsOneInfobase_notEqualExtensionIsStale() {
        List<Sibling> siblings = InfobaseSiblingResolver.aggregate("Config", IB, List.of( //$NON-NLS-1$
                candidate("Ext", IB, InfobaseSiblingResolver.RELATION_EXTENSION_OF, "NOT_EQUAL"))); //$NON-NLS-1$ //$NON-NLS-2$
        Sibling sibling = only(siblings);
        assertEquals("Ext", sibling.project()); //$NON-NLS-1$
        assertEquals("NOT_EQUAL", sibling.equalityState()); //$NON-NLS-1$
        assertTrue("an extension that must converge and reports NOT_EQUAL is stale", sibling.stale()); //$NON-NLS-1$
        assertEquals(List.of("Ext"), InfobaseSiblingResolver.staleProjects(siblings)); //$NON-NLS-1$
        assertFalse("the aggregate verdict must not be work-ready while a sibling diverges", //$NON-NLS-1$
                InfobaseSiblingResolver.allWorkReady("EQUAL", siblings)); //$NON-NLS-1$
    }

    @Test
    public void threeProjectsOneInfobase_onlyTheDivergedOneIsReported() {
        List<Sibling> siblings = InfobaseSiblingResolver.aggregate("Config", IB, List.of( //$NON-NLS-1$
                candidate("ExtA", IB, InfobaseSiblingResolver.RELATION_EXTENSION_OF, "EQUAL"), //$NON-NLS-1$ //$NON-NLS-2$
                candidate("ExtB", IB, InfobaseSiblingResolver.RELATION_EXTENSION_OF, "NOT_EQUAL"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(2, siblings.size());
        assertEquals(List.of("ExtB"), InfobaseSiblingResolver.staleProjects(siblings)); //$NON-NLS-1$
    }

    @Test
    public void allEqual_yieldsNoStaleProjectsAndAnAggregateGreen() {
        List<Sibling> siblings = InfobaseSiblingResolver.aggregate("Config", IB, List.of( //$NON-NLS-1$
                candidate("ExtA", IB, InfobaseSiblingResolver.RELATION_EXTENSION_OF, "EQUAL"), //$NON-NLS-1$ //$NON-NLS-2$
                candidate("ExtB", IB, InfobaseSiblingResolver.RELATION_EXTENSION_OF, "EQUAL"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(InfobaseSiblingResolver.staleProjects(siblings).isEmpty());
        assertTrue(InfobaseSiblingResolver.allWorkReady("EQUAL", siblings)); //$NON-NLS-1$
    }

    @Test
    public void anchorNotEqual_neverAggregatesToWorkReady() {
        List<Sibling> siblings = InfobaseSiblingResolver.aggregate("Config", IB, List.of( //$NON-NLS-1$
                candidate("Ext", IB, InfobaseSiblingResolver.RELATION_EXTENSION_OF, "EQUAL"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(InfobaseSiblingResolver.allWorkReady("NOT_EQUAL", siblings)); //$NON-NLS-1$
        assertFalse(InfobaseSiblingResolver.allWorkReady(null, siblings));
    }

    // -- F1: LOADING / absent is "unknown", NOT stale ----------------------------------------------

    @Test
    public void loadingStateIsUnknownAndNotStale() {
        Sibling sibling = only(InfobaseSiblingResolver.aggregate("Config", IB, List.of( //$NON-NLS-1$
                candidate("Ext", IB, InfobaseSiblingResolver.RELATION_EXTENSION_OF, "LOADING")))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(InfobaseSiblingResolver.STATE_UNKNOWN, sibling.equalityState());
        assertFalse("EDT recomputes right after an update — LOADING must not read as diverged", //$NON-NLS-1$
                sibling.stale());
    }

    @Test
    public void nullStateIsUnknownAndNotStale() {
        Sibling sibling = only(InfobaseSiblingResolver.aggregate("Config", IB, List.of( //$NON-NLS-1$
                candidate("Ext", IB, InfobaseSiblingResolver.RELATION_EXTENSION_OF, null)))); //$NON-NLS-1$
        assertEquals(InfobaseSiblingResolver.STATE_UNKNOWN, sibling.equalityState());
        assertFalse(sibling.stale());
        assertFalse("an unknown sibling state must never count as ready either", //$NON-NLS-1$
                InfobaseSiblingResolver.allWorkReady("EQUAL", List.of(sibling))); //$NON-NLS-1$
    }

    // -- F1: same_ib_configuration is info-only ----------------------------------------------------

    @Test
    public void sameIbConfigurationNotEqualIsInfoOnly() {
        List<Sibling> siblings = InfobaseSiblingResolver.aggregate("ConfigA", IB, List.of( //$NON-NLS-1$
                candidate("ConfigB", IB, //$NON-NLS-1$
                        InfobaseSiblingResolver.RELATION_SAME_IB_CONFIGURATION, "NOT_EQUAL"))); //$NON-NLS-1$
        Sibling sibling = only(siblings);
        assertFalse("two independent configurations on one IB are mutually exclusive — never stale", //$NON-NLS-1$
                sibling.stale());
        assertTrue(InfobaseSiblingResolver.staleProjects(siblings).isEmpty());
        assertTrue("and they must not pin the aggregate verdict to false forever", //$NON-NLS-1$
                InfobaseSiblingResolver.allWorkReady("EQUAL", siblings)); //$NON-NLS-1$
    }

    @Test
    public void mustConvergeCoversExtensionRelationsOnly() {
        assertTrue(InfobaseSiblingResolver.mustConverge(InfobaseSiblingResolver.RELATION_EXTENSION_OF));
        assertTrue(InfobaseSiblingResolver.mustConverge(InfobaseSiblingResolver.RELATION_PARENT_OF));
        assertTrue(InfobaseSiblingResolver.mustConverge(InfobaseSiblingResolver.RELATION_CO_EXTENSION));
        assertFalse(InfobaseSiblingResolver.mustConverge(
                InfobaseSiblingResolver.RELATION_SAME_IB_CONFIGURATION));
        assertFalse(InfobaseSiblingResolver.mustConverge(null));
    }

    @Test
    public void parentOfNotEqualIsStale() {
        Sibling sibling = only(InfobaseSiblingResolver.aggregate("Ext", IB, List.of( //$NON-NLS-1$
                candidate("Config", IB, InfobaseSiblingResolver.RELATION_PARENT_OF, "NOT_EQUAL")))); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(sibling.stale());
    }

    @Test
    public void coExtensionNotEqualIsStale() {
        Sibling sibling = only(InfobaseSiblingResolver.aggregate("ExtA", IB, List.of( //$NON-NLS-1$
                candidate("ExtB", IB, InfobaseSiblingResolver.RELATION_CO_EXTENSION, "NOT_EQUAL")))); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(sibling.stale());
    }

    // -- F1: neighbour matching goes through InfobaseIdentity --------------------------------------

    @Test
    public void cosmeticConnectionStringDifferencesStillMatchTheSameInfobase() {
        // Slash direction, case and a trailing separator: EDT normalizes these, so raw equality would
        // miss the sibling entirely and the shared IB would look unshared.
        List<Sibling> siblings = InfobaseSiblingResolver.aggregate("Config", //$NON-NLS-1$
                "File=\"C:/1C/DB/AM\";", List.of( //$NON-NLS-1$
                        candidate("Ext", "File=\"c:\\1c\\db\\am\\\";", //$NON-NLS-1$ //$NON-NLS-2$
                                InfobaseSiblingResolver.RELATION_EXTENSION_OF, "NOT_EQUAL"))); //$NON-NLS-1$
        assertEquals(List.of("Ext"), InfobaseSiblingResolver.staleProjects(siblings)); //$NON-NLS-1$
    }

    @Test
    public void projectsOnADifferentInfobaseAreNotSiblings() {
        assertTrue(InfobaseSiblingResolver.aggregate("Config", IB, List.of( //$NON-NLS-1$
                candidate("Unrelated", OTHER_IB, //$NON-NLS-1$
                        InfobaseSiblingResolver.RELATION_SAME_IB_CONFIGURATION, "NOT_EQUAL"))) //$NON-NLS-1$
                .isEmpty());
    }

    @Test
    public void anchorItselfIsNeverItsOwnSibling() {
        assertTrue(InfobaseSiblingResolver.aggregate("Config", IB, List.of( //$NON-NLS-1$
                candidate("Config", IB, //$NON-NLS-1$
                        InfobaseSiblingResolver.RELATION_SAME_IB_CONFIGURATION, "NOT_EQUAL"))) //$NON-NLS-1$
                .isEmpty());
    }

    @Test
    public void unresolvedAnchorInfobaseYieldsNoSiblings() {
        assertTrue(InfobaseSiblingResolver.aggregate("Config", null, List.of( //$NON-NLS-1$
                candidate("Ext", IB, InfobaseSiblingResolver.RELATION_EXTENSION_OF, "NOT_EQUAL"))) //$NON-NLS-1$ //$NON-NLS-2$
                .isEmpty());
    }

    @Test
    public void siblingsAreSortedByProjectName() {
        List<Sibling> siblings = InfobaseSiblingResolver.aggregate("Config", IB, List.of( //$NON-NLS-1$
                candidate("zeta", IB, InfobaseSiblingResolver.RELATION_EXTENSION_OF, "EQUAL"), //$NON-NLS-1$ //$NON-NLS-2$
                candidate("alpha", IB, InfobaseSiblingResolver.RELATION_EXTENSION_OF, "EQUAL"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(List.of("alpha", "zeta"), //$NON-NLS-1$ //$NON-NLS-2$
                List.of(siblings.get(0).project(), siblings.get(1).project()));
    }

    // -- F1: relation classification from the extension -> parent map -------------------------------

    @Test
    public void classifyRelation_extensionOfTheAnchor() {
        assertEquals(InfobaseSiblingResolver.RELATION_EXTENSION_OF,
                InfobaseSiblingResolver.classifyRelation("Config", "Ext", //$NON-NLS-1$ //$NON-NLS-2$
                        Map.of("Ext", "Config"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void classifyRelation_parentOfTheAnchorExtension() {
        assertEquals(InfobaseSiblingResolver.RELATION_PARENT_OF,
                InfobaseSiblingResolver.classifyRelation("Ext", "Config", //$NON-NLS-1$ //$NON-NLS-2$
                        Map.of("Ext", "Config"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void classifyRelation_coExtensionsOfOneParent() {
        assertEquals(InfobaseSiblingResolver.RELATION_CO_EXTENSION,
                InfobaseSiblingResolver.classifyRelation("ExtA", "ExtB", //$NON-NLS-1$ //$NON-NLS-2$
                        Map.of("ExtA", "Config", "ExtB", "Config"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    @Test
    public void classifyRelation_twoConfigurationProjectsAreInfoOnly() {
        assertEquals(InfobaseSiblingResolver.RELATION_SAME_IB_CONFIGURATION,
                InfobaseSiblingResolver.classifyRelation("ConfigA", "ConfigB", Map.of())); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(InfobaseSiblingResolver.RELATION_SAME_IB_CONFIGURATION,
                InfobaseSiblingResolver.classifyRelation("ConfigA", "ConfigB", //$NON-NLS-1$ //$NON-NLS-2$
                        Map.of("Ext", "ConfigA"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void normalizeStatePassesEdtEnumNamesThrough() {
        assertEquals("EQUAL", InfobaseSiblingResolver.normalizeState(" EQUAL ")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("NOT_EQUAL", InfobaseSiblingResolver.normalizeState("NOT_EQUAL")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(InfobaseSiblingResolver.STATE_UNKNOWN,
                InfobaseSiblingResolver.normalizeState("   ")); //$NON-NLS-1$
    }
}
