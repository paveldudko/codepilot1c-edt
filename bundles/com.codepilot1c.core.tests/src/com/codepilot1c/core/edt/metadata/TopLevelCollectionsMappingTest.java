package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

/**
 * Tests for the pure half of {@link TopLevelCollections} (B-bonus).
 *
 * <p>The kind→collection mapping used to exist in four divergent copies, and every kind absent
 * from a copy silently became "does not exist" for the tool that read it — most visibly
 * {@code edt_metadata_details}, whose nine-kind switch answered {@code exists:false} for
 * Subsystem, Role, ExchangePlan, DefinedType and most registers. What is guarded here is
 * <em>totality</em>: every {@code MetadataKind} maps to a distinct, non-blank token, so a kind
 * added to the enum can no longer be quietly missing.</p>
 *
 * <p>{@code forKind} itself needs the EMF {@code Configuration}, which does not resolve in the
 * plain Maven test bundle — its wiring is pinned by
 * {@link SubsystemFqnResolutionContractTest} instead.</p>
 */
public class TopLevelCollectionsMappingTest {

    @Test
    public void everyKindHasAConfigurationTag() {
        for (MetadataKind kind : MetadataKind.values()) {
            String tag = TopLevelCollections.configurationTag(kind);
            assertTrue("configurationTag must be non-blank for " + kind, //$NON-NLS-1$
                    tag != null && !tag.isBlank());
        }
    }

    @Test
    public void everyKindHasAnIndexScopeToken() {
        for (MetadataKind kind : MetadataKind.values()) {
            String token = TopLevelCollections.indexScopeToken(kind);
            assertTrue("indexScopeToken must be non-blank for " + kind, //$NON-NLS-1$
                    token != null && !token.isBlank());
        }
    }

    @Test
    public void configurationTagsAreDistinct() {
        // Two kinds sharing a tag would make the post-create Configuration.mdo verification
        // pass for the wrong collection.
        Map<String, MetadataKind> byTag = new HashMap<>();
        for (MetadataKind kind : MetadataKind.values()) {
            String tag = TopLevelCollections.configurationTag(kind);
            MetadataKind clash = byTag.put(tag, kind);
            assertTrue("configuration tag '" + tag + "' is shared by " + clash + " and " + kind, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    clash == null);
        }
        assertEquals(MetadataKind.values().length, byTag.size());
    }

    @Test
    public void indexScopeTokensAreDistinct() {
        Set<String> tokens = new LinkedHashSet<>();
        for (MetadataKind kind : MetadataKind.values()) {
            assertTrue("index scope token repeated for " + kind, //$NON-NLS-1$
                    tokens.add(TopLevelCollections.indexScopeToken(kind)));
        }
        assertEquals(MetadataKind.values().length, tokens.size());
    }

    @Test
    public void kindsMissingFromTheOldInspectorSwitchAreMapped() {
        // These are exactly the kinds edt_metadata_details reported as exists:false.
        assertEquals("subsystems", TopLevelCollections.configurationTag(MetadataKind.SUBSYSTEM)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("roles", TopLevelCollections.configurationTag(MetadataKind.ROLE)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("exchangePlans", //$NON-NLS-1$
                TopLevelCollections.configurationTag(MetadataKind.EXCHANGE_PLAN));
        assertEquals("definedTypes", //$NON-NLS-1$
                TopLevelCollections.configurationTag(MetadataKind.DEFINED_TYPE));
        assertEquals("accountingRegisters", //$NON-NLS-1$
                TopLevelCollections.configurationTag(MetadataKind.ACCOUNTING_REGISTER));
        assertEquals("calculationRegisters", //$NON-NLS-1$
                TopLevelCollections.configurationTag(MetadataKind.CALCULATION_REGISTER));
        assertEquals("settingsStorages", //$NON-NLS-1$
                TopLevelCollections.configurationTag(MetadataKind.SETTINGS_STORAGE));
    }

    @Test
    public void kindTokensResolveFromEveryFqnPrefixSpelling() {
        // The inspector now derives the kind through MetadataKind.fromString, so the FQN prefix,
        // its plural and its Russian name must all reach the same collection.
        for (MetadataKind kind : MetadataKind.values()) {
            assertEquals(kind, MetadataKind.fromString(kind.getFqnPrefix()));
            assertEquals(kind, MetadataKind.fromString(kind.getRuName()));
        }
    }

    @Test
    public void indexTokensKeepTheHistoricalChartSpelling() {
        // scan_metadata_index has always reported "chartofaccounts" (singular chart) and its
        // scope-alias table keys off that spelling — deriving the token from the camelCase
        // configuration tag would silently rename the reported collection.
        assertEquals("chartofaccounts", //$NON-NLS-1$
                TopLevelCollections.indexScopeToken(MetadataKind.CHART_OF_ACCOUNTS));
        assertEquals("chartofcharacteristictypes", //$NON-NLS-1$
                TopLevelCollections.indexScopeToken(MetadataKind.CHART_OF_CHARACTERISTIC_TYPES));
        assertEquals("chartofcalculationtypes", //$NON-NLS-1$
                TopLevelCollections.indexScopeToken(MetadataKind.CHART_OF_CALCULATION_TYPES));
        assertNotEquals("chartsOfAccounts is the .mdo tag, not the index token", //$NON-NLS-1$
                TopLevelCollections.configurationTag(MetadataKind.CHART_OF_ACCOUNTS)
                        .toLowerCase(Locale.ROOT),
                TopLevelCollections.indexScopeToken(MetadataKind.CHART_OF_ACCOUNTS));
    }

    @Test
    public void indexTokensAreLowercase() {
        for (MetadataKind kind : MetadataKind.values()) {
            String token = TopLevelCollections.indexScopeToken(kind);
            assertEquals("index scope token must be lowercase for " + kind, //$NON-NLS-1$
                    token.toLowerCase(Locale.ROOT), token);
        }
    }
}
