package com.codepilot1c.core.edt.dcs;

import static org.junit.Assert.assertEquals;

import java.util.List;

import org.junit.Test;

/**
 * Behavioural test for the export target list of the DCS mutation path.
 *
 * <p>What was broken: the DCS service committed its BM transaction and stopped there, so nothing
 * was ever exported. A data composition schema is a SEPARATE top-object serialized into its own
 * {@code Templates/&lt;name&gt;/Template.dcs}, so even exporting the owning {@code Report} writes
 * {@code Report.mdo} alone and leaves the schema unwritten — the tool reported success with no
 * artifact. The fix passes the schema's external-property FQN as {@code extraFqn} so both fragments
 * are in ONE export batch.</p>
 *
 * <p>These assertions call {@code buildExportTargets} and check the list it returns, rather than
 * asserting that the source contains a call — the list content (both FQNs present, no duplicate
 * that would make the batch reject itself, {@code Configuration} always last) is the thing the live
 * export depends on. The gateway is never touched by this method, hence the {@code null}.</p>
 */
public class DcsExportTargetsTest {

    private final DcsExportSupport support = new DcsExportSupport(null);

    @Test
    public void theSchemaFragmentTravelsWithTheOwnerInOneBatch() {
        assertEquals(
                List.of("Report.Sales", "Report.Sales.Template.MainSchema.Template", "Configuration"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                support.buildExportTargets("Report.Sales", "Report.Sales.Template.MainSchema.Template")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void withoutASchemaFqnOnlyTheOwnerAndConfigurationAreExported() {
        // The read-only / no-op path cannot always compute the schema FQN; the batch must stay valid.
        assertEquals(List.of("Report.Sales", "Configuration"), //$NON-NLS-1$ //$NON-NLS-2$
                support.buildExportTargets("Report.Sales", null)); //$NON-NLS-1$
        assertEquals(List.of("Report.Sales", "Configuration"), //$NON-NLS-1$ //$NON-NLS-2$
                support.buildExportTargets("Report.Sales", "   ")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aRepeatedFqnAppearsOnceRatherThanTwiceInTheBatch() {
        assertEquals(List.of("Report.Sales", "Configuration"), //$NON-NLS-1$ //$NON-NLS-2$
                support.buildExportTargets("Report.Sales", "Report.Sales")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void configurationIsNotListedTwiceWhenItIsTheOwner() {
        assertEquals(List.of("Configuration"), //$NON-NLS-1$
                support.buildExportTargets("Configuration", null)); //$NON-NLS-1$
    }

    @Test
    public void configurationIsAlwaysPresentEvenWithoutAnOwnerFqn() {
        // forceExport falls back to Configuration; an empty batch would export nothing at all.
        assertEquals(List.of("Report.Sales.Template.MainSchema.Template", "Configuration"), //$NON-NLS-1$ //$NON-NLS-2$
                support.buildExportTargets(null, "Report.Sales.Template.MainSchema.Template")); //$NON-NLS-1$
        assertEquals(List.of("Configuration"), support.buildExportTargets(null, null)); //$NON-NLS-1$
        assertEquals(List.of("Configuration"), support.buildExportTargets("  ", "")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void theOwnerComesBeforeItsSchemaFragment() {
        // Order is not cosmetic: the owner .mdo must carry the <templates> entry the schema
        // fragment's path is derived from.
        List<String> targets =
                support.buildExportTargets("DataProcessor.Loader", "DataProcessor.Loader.Template.Schema.Template"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(0, targets.indexOf("DataProcessor.Loader")); //$NON-NLS-1$
        assertEquals(1, targets.indexOf("DataProcessor.Loader.Template.Schema.Template")); //$NON-NLS-1$
    }
}
