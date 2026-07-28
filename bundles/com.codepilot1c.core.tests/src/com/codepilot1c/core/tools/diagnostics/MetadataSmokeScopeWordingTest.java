package com.codepilot1c.core.tools.diagnostics;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Locale;

import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Wording-regression guard for {@code metadata_smoke}. The tool exercises the EDT metadata API against
 * the PROJECT MODEL only — it creates and deletes temporary objects and probes a read-only BM
 * transaction, and never opens the target infobase/database. The old phrasing ("use metadata_smoke for
 * headless verification") invited exactly the wrong conclusion: a green smoke report was read as "the
 * environment is verified" while the target database was in fact unusable. These assertions pin the
 * explicit non-goal so the wording cannot silently drift back.
 */
public class MetadataSmokeScopeWordingTest {

    @Test
    public void dispatcherDescriptionStatesTheNonGoal() {
        JsonObject schema = JsonParser.parseString(new EdtDiagnosticsTool().getParameterSchema())
                .getAsJsonObject();
        String description = schema.getAsJsonObject("properties") //$NON-NLS-1$
                .getAsJsonObject("command").get("description").getAsString(); //$NON-NLS-1$ //$NON-NLS-2$
        String lower = description.toLowerCase(Locale.ROOT);
        assertTrue("must state that metadata_smoke does NOT verify the target infobase", //$NON-NLS-1$
                lower.contains("does not verify the target infobase")); //$NON-NLS-1$
        assertFalse("the misleading 'headless verification' framing must be gone", //$NON-NLS-1$
                lower.contains("for headless verification")); //$NON-NLS-1$
    }

    @Test
    public void toolDescriptionStatesTheNonGoalAndPointsAtTheInfobaseTools() {
        String description = new EdtMetadataSmokeTool().getDescription();
        String lower = description.toLowerCase(Locale.ROOT);
        assertTrue("must state the non-goal", //$NON-NLS-1$
                lower.contains("does not verify the target infobase")); //$NON-NLS-1$
        assertTrue("must point at the tool that DOES answer infobase readiness", //$NON-NLS-1$
                description.contains("get_infobase_sync_state")); //$NON-NLS-1$
        assertTrue("must point at the tool that applies the configuration", //$NON-NLS-1$
                description.contains("update_infobase")); //$NON-NLS-1$
    }

    @Test
    public void reportHeaderCarriesTheScopeLine() {
        String lower = EdtMetadataSmokeTool.SCOPE_LINE.toLowerCase(Locale.ROOT);
        assertTrue(lower.startsWith("scope:")); //$NON-NLS-1$
        assertTrue("the report itself must say the project model is all that was checked", //$NON-NLS-1$
                lower.contains("project model only")); //$NON-NLS-1$
        assertTrue("…and that the target infobase was NOT verified", //$NON-NLS-1$
                lower.contains("target infobase not verified")); //$NON-NLS-1$
    }
}
