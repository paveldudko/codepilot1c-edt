package com.codepilot1c.core.tools.workspace;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Locale;

import org.junit.Test;

import com.codepilot1c.core.tools.workspace.InfobaseProcessScanner.LockKind;

/**
 * Unit tests for the pure classification / path-matching logic of {@link InfobaseProcessScanner}.
 * The {@link ProcessHandle}-based scan/kill is environment dependent and not exercised here.
 */
public class InfobaseProcessScannerTest {

    // --- fileIbPath -------------------------------------------------------------------------

    @Test
    public void fileIbPath_extractsDoubleQuotedFileToken() {
        assertEquals("C:\\db\\demo", //$NON-NLS-1$
                InfobaseProcessScanner.fileIbPath("File=\"C:\\db\\demo\";")); //$NON-NLS-1$
    }

    @Test
    public void fileIbPath_extractsSingleQuotedFileToken() {
        assertEquals("/home/user/ib", //$NON-NLS-1$
                InfobaseProcessScanner.fileIbPath("File='/home/user/ib';")); //$NON-NLS-1$
    }

    @Test
    public void fileIbPath_isCaseInsensitiveOnKey() {
        assertEquals("C:\\ib", InfobaseProcessScanner.fileIbPath("file = \"C:\\ib\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void fileIbPath_returnsNullForServerConnectionString() {
        assertNull(InfobaseProcessScanner.fileIbPath("Srvr=\"host:1541\";Ref=\"demo\";")); //$NON-NLS-1$
    }

    @Test
    public void fileIbPath_returnsNullForNullOrBlank() {
        assertNull(InfobaseProcessScanner.fileIbPath(null));
        assertNull(InfobaseProcessScanner.fileIbPath("File=\"\";")); //$NON-NLS-1$
    }

    // --- normalizePath ----------------------------------------------------------------------

    @Test
    public void normalizePath_lowercasesUnifiesSeparatorsAndStripsTrailing() {
        assertEquals("c:/1c/repos/ib", //$NON-NLS-1$
                InfobaseProcessScanner.normalizePath("C:\\1C\\Repos\\IB\\")); //$NON-NLS-1$
    }

    @Test
    public void normalizePath_returnsNullForNullOrEmpty() {
        assertNull(InfobaseProcessScanner.normalizePath(null));
        assertNull(InfobaseProcessScanner.normalizePath("   ")); //$NON-NLS-1$
    }

    // --- classify ---------------------------------------------------------------------------

    @Test
    public void classify_designerWithAgentModeIsPhantom() {
        String cmd = "\"c:\\program files\\1cv8\\8.3.27.2074\\bin\\1cv8.exe\" designer /f\"c:\\ib\" /agentmode" //$NON-NLS-1$
                .toLowerCase(Locale.ROOT);
        assertEquals(LockKind.DESIGNER_AGENT, InfobaseProcessScanner.classify(cmd));
    }

    @Test
    public void classify_interactiveDesignerIsNotPhantom() {
        String cmd = "\"c:\\1cv8\\bin\\1cv8.exe\" designer /f\"c:\\ib\"".toLowerCase(Locale.ROOT); //$NON-NLS-1$
        assertEquals(LockKind.DESIGNER, InfobaseProcessScanner.classify(cmd));
    }

    @Test
    public void classify_thinClientIsClient() {
        String cmd = "\"c:\\1cv8\\bin\\1cv8c.exe\" enterprise /f\"c:\\ib\"".toLowerCase(Locale.ROOT); //$NON-NLS-1$
        assertEquals(LockKind.CLIENT, InfobaseProcessScanner.classify(cmd));
    }

    @Test
    public void classify_apacheIsWebserver() {
        assertEquals(LockKind.WEBSERVER,
                InfobaseProcessScanner.classify("c:\\apache24\\bin\\httpd.exe -k runservice")); //$NON-NLS-1$
        assertEquals(LockKind.WEBSERVER,
                InfobaseProcessScanner.classify("/usr/lib/wsap24/wsap.so")); //$NON-NLS-1$
    }

    @Test
    public void classify_nullIsOther() {
        assertEquals(LockKind.OTHER_1C, InfobaseProcessScanner.classify(null));
    }

    // --- matchesIb --------------------------------------------------------------------------

    @Test
    public void matchesIb_matchesAcrossSeparatorStyles() {
        String cmd = "\"c:\\1cv8\\bin\\1cv8.exe\" designer /f\"c:\\1c\\repos\\ib\" /agentmode" //$NON-NLS-1$
                .toLowerCase(Locale.ROOT);
        String norm = InfobaseProcessScanner.normalizePath("C:\\1C\\Repos\\IB"); //$NON-NLS-1$
        assertTrue(InfobaseProcessScanner.matchesIb(cmd, norm));
    }

    @Test
    public void matchesIb_doesNotMatchOtherInfobase() {
        String cmd = "1cv8.exe designer /f\"c:\\1c\\repos\\other_ib\" /agentmode".toLowerCase(Locale.ROOT); //$NON-NLS-1$
        String norm = InfobaseProcessScanner.normalizePath("C:\\1C\\Repos\\IB"); //$NON-NLS-1$
        assertFalse(InfobaseProcessScanner.matchesIb(cmd, norm));
    }

    @Test
    public void matchesIb_doesNotMatchSiblingWithLongerName() {
        // Regression: an IB named "ib" must not match a sibling "ib2" under the same parent.
        String cmd = "1cv8.exe designer /f\"c:\\1c\\repos\\ib2\" /agentmode".toLowerCase(Locale.ROOT); //$NON-NLS-1$
        String norm = InfobaseProcessScanner.normalizePath("C:\\1C\\Repos\\IB"); //$NON-NLS-1$
        assertFalse(InfobaseProcessScanner.matchesIb(cmd, norm));
    }

    @Test
    public void matchesIb_matchesWhenPathFollowedBySubfile() {
        String cmd = "1cv8.exe designer /f\"c:\\1c\\repos\\ib\\1cv8.1cd\"".toLowerCase(Locale.ROOT); //$NON-NLS-1$
        String norm = InfobaseProcessScanner.normalizePath("C:\\1C\\Repos\\IB"); //$NON-NLS-1$
        assertTrue(InfobaseProcessScanner.matchesIb(cmd, norm));
    }

    @Test
    public void matchesIb_falseWhenPathUnknown() {
        assertFalse(InfobaseProcessScanner.matchesIb("anything", null)); //$NON-NLS-1$
        assertFalse(InfobaseProcessScanner.matchesIb(null, "c:/ib")); //$NON-NLS-1$
    }

    // --- leaked test clients ----------------------------------------------------------------

    /** A thin-client command line for {@code ibPath}, with or without our YAxUnit parameter. */
    private static String thinClient(String ibPath, boolean unitTests) {
        return ("\"c:\\program files\\1cv8\\8.3.27.2074\\bin\\1cv8c.exe\" enterprise /IBConnectionString " //$NON-NLS-1$
                + "\"File=\"\"" + ibPath + "\"\";\"" //$NON-NLS-1$ //$NON-NLS-2$
                + (unitTests ? " /C\"RunUnitTests=c:\\ws\\.codepilot\\runs\\yaxunit_run\\op\\config.json\"" : "")) //$NON-NLS-1$ //$NON-NLS-2$
                        .toLowerCase(Locale.ROOT);
    }

    @Test
    public void isLeakedTestClient_matchesOurRunnerOnTheTargetInfobase() {
        String norm = InfobaseProcessScanner.normalizePath("C:\\1C\\Repos\\IB"); //$NON-NLS-1$
        assertTrue(InfobaseProcessScanner.isLeakedTestClient(
                thinClient("C:\\1C\\Repos\\IB", true), norm)); //$NON-NLS-1$
    }

    @Test
    public void isLeakedTestClient_sparesTheSameRunnerOnAnotherInfobase() {
        // The multi-stand safety case: a neighbouring stand's YAxUnit client must never be killed.
        String norm = InfobaseProcessScanner.normalizePath("C:\\1C\\Repos\\IB"); //$NON-NLS-1$
        assertFalse(InfobaseProcessScanner.isLeakedTestClient(
                thinClient("C:\\1C\\Repos\\other_ib", true), norm)); //$NON-NLS-1$
    }

    @Test
    public void isLeakedTestClient_sparesAnInteractiveClientOfTheTargetInfobase() {
        // No RunUnitTests parameter → it is somebody's open session, not a leak of ours.
        String norm = InfobaseProcessScanner.normalizePath("C:\\1C\\Repos\\IB"); //$NON-NLS-1$
        assertFalse(InfobaseProcessScanner.isLeakedTestClient(
                thinClient("C:\\1C\\Repos\\IB", false), norm)); //$NON-NLS-1$
    }

    @Test
    public void isLeakedTestClient_sparesEverythingWhenTheInfobasePathIsUnknown() {
        // Server infobase / unresolved association: attribution is impossible → never kill.
        assertFalse(InfobaseProcessScanner.isLeakedTestClient(thinClient("C:\\1C\\Repos\\IB", true), null)); //$NON-NLS-1$
    }

    @Test
    public void isLeakedTestClient_sparesTheThickClientAndTheDesigner() {
        String norm = InfobaseProcessScanner.normalizePath("C:\\1C\\Repos\\IB"); //$NON-NLS-1$
        String thick = "\"c:\\1cv8\\bin\\1cv8.exe\" enterprise /f\"c:\\1c\\repos\\ib\" /c\"rununittests=c:\\cfg\"" //$NON-NLS-1$
                .toLowerCase(Locale.ROOT);
        String designer = "\"c:\\1cv8\\bin\\1cv8.exe\" designer /f\"c:\\1c\\repos\\ib\" /agentmode" //$NON-NLS-1$
                .toLowerCase(Locale.ROOT);

        assertFalse(InfobaseProcessScanner.isLeakedTestClient(thick, norm));
        assertFalse(InfobaseProcessScanner.isLeakedTestClient(designer, norm));
    }

    @Test
    public void isThinClientAndUnitTestRunner_areNullSafe() {
        assertFalse(InfobaseProcessScanner.isThinClient(null));
        assertFalse(InfobaseProcessScanner.isUnitTestRunner(null));
        assertTrue(InfobaseProcessScanner.isThinClient("c:\\1cv8\\bin\\1cv8c.exe")); //$NON-NLS-1$
        assertFalse("1cv8.exe is the thick client, not 1cv8c", //$NON-NLS-1$
                InfobaseProcessScanner.isThinClient("c:\\1cv8\\bin\\1cv8.exe")); //$NON-NLS-1$
    }

    @Test
    public void hasReadableCommandLine_falseWhenOnlyTheExeIsVisible() {
        // Windows/WMI blindness: no arguments → no infobase attribution → fail loud, never kill.
        assertFalse(InfobaseProcessScanner.hasReadableCommandLine("c:\\1cv8\\bin\\1cv8c.exe", null)); //$NON-NLS-1$
        assertFalse(InfobaseProcessScanner.hasReadableCommandLine("c:\\1cv8\\bin\\1cv8c.exe", "   ")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(InfobaseProcessScanner.hasReadableCommandLine("c:\\1cv8\\bin\\1cv8c.exe", //$NON-NLS-1$
                "C:\\1CV8\\bin\\1cv8c.exe")); //$NON-NLS-1$
        assertTrue(InfobaseProcessScanner.hasReadableCommandLine("c:\\1cv8\\bin\\1cv8c.exe", //$NON-NLS-1$
                "c:\\1cv8\\bin\\1cv8c.exe enterprise /f\"c:\\ib\"")); //$NON-NLS-1$
    }
}
