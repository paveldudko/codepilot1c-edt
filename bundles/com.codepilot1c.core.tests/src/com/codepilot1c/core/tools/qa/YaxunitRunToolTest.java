package com.codepilot1c.core.tools.qa;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.codepilot1c.core.qa.QaJUnitReport;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * Pure-helper tests for {@link YaxunitRunTool}: the YAxUnit run-config JSON built from filter
 * params, the lenient array/string coercion, the verdict matrix ({@code classify} /
 * {@code classifyNoReport}) and the {@link QaJUnitReport} parsing the tool relies on for its
 * structured output. The spawn/poll path needs a live EDT and is not covered here.
 */
public class YaxunitRunToolTest {

    private static final Gson GSON = new Gson();

    private static File f(String name) {
        return new File(System.getProperty("java.io.tmpdir"), name); //$NON-NLS-1$
    }

    /** A report with the given totals, as if parsed from junit.xml. */
    private static QaJUnitReport report(int tests, int failures, int errors) {
        QaJUnitReport report = new QaJUnitReport();
        report.tests = tests;
        report.failures = failures;
        report.errors = errors;
        return report;
    }

    /** A finished run whose YAxUnit exit code is {@code exitCode} (null = no exitcode.txt). */
    private static YaxunitRunTool.RunOutcome finished(Integer exitCode) {
        return new YaxunitRunTool.RunOutcome(true, 0, exitCode, exitCode != null, false);
    }

    @Test
    public void configCarriesYaxunitContractFields() {
        String json = YaxunitRunTool.buildRunConfigJson(Map.of(), f("junit.xml"), f("exitcode.txt"), f("y.log")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        JsonObject config = GSON.fromJson(json, JsonObject.class);

        assertEquals("jUnit", config.get("reportFormat").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("reportPath must point at junit.xml", //$NON-NLS-1$
                config.get("reportPath").getAsString().endsWith("junit.xml")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(config.get("closeAfterTests").getAsBoolean()); //$NON-NLS-1$
        assertTrue("exitCode must be a file path (deterministic completion signal)", //$NON-NLS-1$
                config.get("exitCode").getAsString().endsWith("exitcode.txt")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(config.has("logging")); //$NON-NLS-1$
        // No filter params → no filter object at all (YAxUnit then runs every test).
        assertFalse("empty filter must be omitted", config.has("filter")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void filterMapsArrayParamsOneToOne() {
        Map<String, Object> params = Map.of(
                "modules", List.of("CM_Treasury", "CM_Ledger"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "tags", List.of("smoke"), //$NON-NLS-1$ //$NON-NLS-2$
                "tests", List.of("CM_Treasury.TestPay")); //$NON-NLS-1$ //$NON-NLS-2$

        JsonObject config = GSON.fromJson(
                YaxunitRunTool.buildRunConfigJson(params, f("junit.xml"), f("exitcode.txt"), f("y.log")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                JsonObject.class);

        assertTrue(config.has("filter")); //$NON-NLS-1$
        JsonObject filter = config.getAsJsonObject("filter"); //$NON-NLS-1$
        assertEquals(2, filter.getAsJsonArray("modules").size()); //$NON-NLS-1$
        assertEquals("smoke", filter.getAsJsonArray("tags").get(0).getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("CM_Treasury.TestPay", filter.getAsJsonArray("tests").get(0).getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        // Unprovided filter keys must not leak in as empty arrays.
        assertFalse(filter.has("suites")); //$NON-NLS-1$
        assertFalse(filter.has("contexts")); //$NON-NLS-1$
    }

    @Test
    public void asStringListAcceptsListAndCommaString() {
        assertEquals(List.of("a", "b"), YaxunitRunTool.asStringList(List.of("a", "b"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertEquals(List.of("a", "b"), YaxunitRunTool.asStringList("a, b")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(YaxunitRunTool.asStringList(null).isEmpty());
        assertTrue(YaxunitRunTool.asStringList("").isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void readExitCodeStripsUtf8BomAndTrailingNewline() throws Exception {
        // YAxUnit writes exitcode.txt as EF BB BF <digit> CRLF — the smoke run surfaced an empty
        // yaxunit_exit_code because the BOM defeated parsing. Pin the strip here.
        File dir = Files.createTempDirectory("yaxunit-exit").toFile(); //$NON-NLS-1$
        File ec = new File(dir, "exitcode.txt"); //$NON-NLS-1$

        Files.write(ec.toPath(), new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, '0', 0x0D, 0x0A});
        assertEquals(Integer.valueOf(0), YaxunitRunTool.readExitCode(ec));

        Files.write(ec.toPath(), new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, '1'});
        assertEquals(Integer.valueOf(1), YaxunitRunTool.readExitCode(ec));

        // Plain content without BOM still parses.
        Files.write(ec.toPath(), "0".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
        assertEquals(Integer.valueOf(0), YaxunitRunTool.readExitCode(ec));

        assertNull(YaxunitRunTool.readExitCode(new File(dir, "missing.txt"))); //$NON-NLS-1$
    }

    @Test
    public void reportParsesPerSuiteCountsAndPassed() throws Exception {
        File dir = Files.createTempDirectory("yaxunit-test").toFile(); //$NON-NLS-1$
        String xml = "<testsuites>" //$NON-NLS-1$
                + "<testsuite name=\"CM_Treasury\" tests=\"3\" failures=\"1\" errors=\"0\" skipped=\"0\" time=\"0.5\">" //$NON-NLS-1$
                + "<testcase name=\"ok1\"/>" //$NON-NLS-1$
                + "<testcase name=\"bad\"><failure message=\"boom\" type=\"assert\">trace</failure></testcase>" //$NON-NLS-1$
                + "<testcase name=\"ok2\"/></testsuite>" //$NON-NLS-1$
                + "<testsuite name=\"CM_Ledger\" tests=\"2\" failures=\"0\" errors=\"0\" skipped=\"1\" time=\"0.2\">" //$NON-NLS-1$
                + "<testcase name=\"ok3\"/></testsuite>" //$NON-NLS-1$
                + "</testsuites>"; //$NON-NLS-1$
        Files.write(new File(dir, "junit.xml").toPath(), xml.getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$

        QaJUnitReport report = QaJUnitReport.parseDirectory(dir, 50);
        assertNotNull(report);
        assertEquals(5, report.tests);
        assertEquals(1, report.failures);
        assertEquals(1, report.skipped);
        // passed = tests - failures - errors - skipped = 5 - 1 - 0 - 1 = 3
        assertEquals(3, report.passed());
        assertEquals(2, report.suites.size());
        assertEquals("CM_Treasury", report.suites.get(0).name); //$NON-NLS-1$
        assertEquals(3, report.suites.get(0).tests);
        assertEquals(1, report.suites.get(1).skipped);
    }

    // --- verdict matrix (classify) -----------------------------------------------------------

    @Test
    public void classify_zeroTestsWithoutFilterIsNoTestsFound() {
        // The false-green input: 0 tests, 0 failures, exit 0 satisfied the old green predicate
        // identically and surfaced as status=passed on the success channel.
        YaxunitRunTool.Verdict verdict = YaxunitRunTool.classify(report(0, 0, 0), finished(Integer.valueOf(0)),
                false, "EQUAL"); //$NON-NLS-1$

        assertEquals("no_tests_found", verdict.status()); //$NON-NLS-1$
        assertEquals("no_tests_in_infobase", verdict.reason()); //$NON-NLS-1$
        assertFalse("zero resolved work carries no verdict -> error channel", verdict.ok()); //$NON-NLS-1$
        assertTrue("must point at the safe-mode / not-attached remediation", //$NON-NLS-1$
                verdict.message().contains("Configuration > Extensions")); //$NON-NLS-1$
    }

    @Test
    public void classify_zeroTestsWithFilterAndStaleInfobaseBlamesTheInfobase() {
        for (String state : new String[] {"NOT_EQUAL", "LOADING"}) { //$NON-NLS-1$ //$NON-NLS-2$
            YaxunitRunTool.Verdict verdict = YaxunitRunTool.classify(report(0, 0, 0),
                    finished(Integer.valueOf(0)), true, state);

            assertEquals("no_tests_matched", verdict.status()); //$NON-NLS-1$
            assertEquals("infobase_stale", verdict.reason()); //$NON-NLS-1$
            assertFalse(verdict.ok());
            assertTrue(verdict.message().contains("update_infobase")); //$NON-NLS-1$
        }
    }

    @Test
    public void classify_zeroTestsWithFilterOnCurrentInfobaseBlamesTheFilter() {
        YaxunitRunTool.Verdict verdict = YaxunitRunTool.classify(report(0, 0, 0), finished(Integer.valueOf(0)),
                true, "EQUAL"); //$NON-NLS-1$

        assertEquals("no_tests_matched", verdict.status()); //$NON-NLS-1$
        assertEquals("filter_matched_nothing", verdict.reason()); //$NON-NLS-1$
        assertFalse(verdict.ok());
        assertTrue("must hand over the filter hints", //$NON-NLS-1$
                verdict.message().contains("registered in the module")); //$NON-NLS-1$
    }

    @Test
    public void classify_zeroTestsWithUnreadableEqualityStateKeepsBothCausesOpen() {
        // readInfobaseEqualityState is best-effort and returns null on a cold EDT — that must degrade
        // softly into an unverified verdict, never into a failure of the tool.
        YaxunitRunTool.Verdict verdict = YaxunitRunTool.classify(report(0, 0, 0), finished(Integer.valueOf(0)),
                true, null);

        assertEquals("no_tests_matched", verdict.status()); //$NON-NLS-1$
        assertEquals("no_match_unverified", verdict.reason()); //$NON-NLS-1$
        assertFalse(verdict.ok());
        assertTrue(verdict.message().contains("update_infobase")); //$NON-NLS-1$
        assertTrue(verdict.message().contains("registered in the module")); //$NON-NLS-1$
        assertTrue("the stale hint must come first — it is the cheaper cause to rule out", //$NON-NLS-1$
                verdict.message().indexOf("update_infobase") //$NON-NLS-1$
                        < verdict.message().indexOf("registered in the module")); //$NON-NLS-1$
    }

    @Test
    public void classify_redTestsAreASuccessfulVerdict() {
        // Contract pin (feedback 2026-07-03): a completed run with red tests carries a verdict, so it
        // travels on the SUCCESS channel — the caller reads the report instead of re-diagnosing infra.
        YaxunitRunTool.Verdict failures = YaxunitRunTool.classify(report(57, 2, 0), finished(Integer.valueOf(1)),
                true, "EQUAL"); //$NON-NLS-1$
        YaxunitRunTool.Verdict errors = YaxunitRunTool.classify(report(4, 0, 3), finished(Integer.valueOf(1)),
                false, null);

        assertEquals("tests_failed", failures.status()); //$NON-NLS-1$
        assertTrue("red tests must NOT use the error channel", failures.ok()); //$NON-NLS-1$
        assertEquals("tests_failed", errors.status()); //$NON-NLS-1$
        assertTrue(errors.ok());
    }

    @Test
    public void classify_greenRunPasses() {
        YaxunitRunTool.Verdict verdict = YaxunitRunTool.classify(report(21, 0, 0), finished(Integer.valueOf(0)),
                true, "EQUAL"); //$NON-NLS-1$

        assertEquals("passed", verdict.status()); //$NON-NLS-1$
        assertTrue(verdict.ok());
        assertEquals("", verdict.reason()); //$NON-NLS-1$
    }

    @Test
    public void classify_nonZeroExitWithGreenReportIsInconclusive() {
        // The runner says "failed", the report shows nothing red: no coherent verdict exists.
        YaxunitRunTool.Verdict verdict = YaxunitRunTool.classify(report(21, 0, 0), finished(Integer.valueOf(1)),
                true, "EQUAL"); //$NON-NLS-1$

        assertFalse("must not be reported as passed", "passed".equals(verdict.status())); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("report_exit_mismatch", verdict.status()); //$NON-NLS-1$
        assertEquals("exit_code_nonzero_report_green", verdict.reason()); //$NON-NLS-1$
        assertFalse(verdict.ok());
    }

    @Test
    public void classify_timeoutWithPartialReportIsAnError() {
        YaxunitRunTool.RunOutcome timedOut =
                new YaxunitRunTool.RunOutcome(false, -1, Integer.valueOf(0), true, true);

        YaxunitRunTool.Verdict verdict = YaxunitRunTool.classify(report(9, 0, 0), timedOut, true, "EQUAL"); //$NON-NLS-1$

        assertEquals("timeout", verdict.status()); //$NON-NLS-1$
        assertFalse(verdict.ok());
    }

    // --- no-report diagnosis (classifyNoReport) ---------------------------------------------

    @Test
    public void classifyNoReport_detectsSafeModeMarkersInBothLanguages() {
        String ru = YaxunitRunTool.classifyNoReport(
                "Error: Защита от опасных действий не отключена для расширения", //$NON-NLS-1$
                new YaxunitRunTool.RunOutcome(true, 0, null, false, false));
        String en = YaxunitRunTool.classifyNoReport("client aborted: safe mode is on", //$NON-NLS-1$
                new YaxunitRunTool.RunOutcome(true, 0, Integer.valueOf(1), true, false));

        assertTrue(ru.contains("safe mode / dangerous-action")); //$NON-NLS-1$
        assertTrue(ru.contains("Configuration > Extensions")); //$NON-NLS-1$
        assertEquals("the safe-mode diagnosis wins regardless of the exitCode file", ru, en); //$NON-NLS-1$
    }

    @Test
    public void classifyNoReport_withoutExitCodeBlamesTheExtensionWiring() {
        String hint = YaxunitRunTool.classifyNoReport("", //$NON-NLS-1$
                new YaxunitRunTool.RunOutcome(true, 0, null, false, false));

        assertTrue(hint.contains("neither a jUnit report nor an exitCode file")); //$NON-NLS-1$
    }

    @Test
    public void classifyNoReport_withExitCodeBlamesTheFilterOrReportPath() {
        String hint = YaxunitRunTool.classifyNoReport("clean run", //$NON-NLS-1$
                new YaxunitRunTool.RunOutcome(true, 0, Integer.valueOf(0), true, false));

        assertTrue(hint.contains("exitCode file but no jUnit report")); //$NON-NLS-1$
    }

    // --- report resolution ------------------------------------------------------------------

    @Test
    public void reportParsesEmptyRootAsZeroTests() throws Exception {
        // Both shapes of a zero report reach classify() as report != null, tests == 0 — the exact
        // false-green input. Pin both: the failing live run left no artifact to copy from.
        File dir = Files.createTempDirectory("yaxunit-empty-root").toFile(); //$NON-NLS-1$
        Files.write(new File(dir, "junit.xml").toPath(), //$NON-NLS-1$
                "<testsuites/>".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$

        QaJUnitReport report = QaJUnitReport.parseDirectory(dir, 50, "junit.xml"); //$NON-NLS-1$

        assertNotNull("an empty root is still a parsed report", report); //$NON-NLS-1$
        assertEquals(0, report.tests);
        assertFalse(report.fallbackScan);
    }

    @Test
    public void reportParsesZeroTestSuiteAndMissingTestsAttribute() throws Exception {
        File dir = Files.createTempDirectory("yaxunit-empty-suite").toFile(); //$NON-NLS-1$
        Files.write(new File(dir, "junit.xml").toPath(), //$NON-NLS-1$
                "<testsuite name=\"CM_Empty\" tests=\"0\" failures=\"0\" errors=\"0\"/>" //$NON-NLS-1$
                        .getBytes(StandardCharsets.UTF_8));
        QaJUnitReport zeroed = QaJUnitReport.parseDirectory(dir, 50, "junit.xml"); //$NON-NLS-1$
        assertNotNull(zeroed);
        assertEquals(0, zeroed.tests);

        // No `tests` attribute at all: getIntAttr defaults to 0, same false-green input.
        Files.write(new File(dir, "junit.xml").toPath(), //$NON-NLS-1$
                "<testsuite name=\"CM_NoAttrs\"/>".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
        QaJUnitReport attributeless = QaJUnitReport.parseDirectory(dir, 50, "junit.xml"); //$NON-NLS-1$
        assertNotNull(attributeless);
        assertEquals(0, attributeless.tests);
        assertEquals(1, attributeless.suites.size());
    }

    @Test
    public void reportPrefersJunitXmlOverStrayXmlInTheRunDir() throws Exception {
        // The run dir is also the client's working directory, so foreign *.xml can land there.
        File dir = Files.createTempDirectory("yaxunit-stray").toFile(); //$NON-NLS-1$
        Files.write(new File(dir, "junit.xml").toPath(), //$NON-NLS-1$
                "<testsuite name=\"Real\" tests=\"2\" failures=\"0\" errors=\"0\"/>" //$NON-NLS-1$
                        .getBytes(StandardCharsets.UTF_8));
        Files.write(new File(dir, "1cv8-dump.xml").toPath(), //$NON-NLS-1$
                "<testsuite name=\"Foreign\" tests=\"99\" failures=\"7\" errors=\"0\"/>" //$NON-NLS-1$
                        .getBytes(StandardCharsets.UTF_8));

        QaJUnitReport report = QaJUnitReport.parseDirectory(dir, 50, "junit.xml"); //$NON-NLS-1$

        assertNotNull(report);
        assertEquals("only junit.xml counts", 2, report.tests); //$NON-NLS-1$
        assertEquals(0, report.failures);
        assertEquals(1, report.files.size());
        assertFalse(report.fallbackScan);
    }

    @Test
    public void reportFlagsFallbackWhenOnlyStrayXmlIsPresent() throws Exception {
        // Back-compat: a foreign *.xml is still parsed (old behaviour) but never silently — the flag
        // is what lets the tool say the counts are not proven to be this run's.
        File dir = Files.createTempDirectory("yaxunit-fallback").toFile(); //$NON-NLS-1$
        Files.write(new File(dir, "1cv8-dump.xml").toPath(), //$NON-NLS-1$
                "<testsuite name=\"Foreign\" tests=\"99\" failures=\"7\" errors=\"0\"/>" //$NON-NLS-1$
                        .getBytes(StandardCharsets.UTF_8));

        QaJUnitReport report = QaJUnitReport.parseDirectory(dir, 50, "junit.xml"); //$NON-NLS-1$

        assertNotNull(report);
        assertTrue("a stray *.xml must be flagged, not trusted", report.fallbackScan); //$NON-NLS-1$
        assertEquals(99, report.tests);

        // Without a preferred name (qa_run's dedicated report directory) nothing changes.
        QaJUnitReport scanned = QaJUnitReport.parseDirectory(dir, 50);
        assertNotNull(scanned);
        assertFalse(scanned.fallbackScan);
        assertEquals(99, scanned.tests);
    }
}
