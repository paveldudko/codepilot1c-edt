package com.codepilot1c.core.tools.qa;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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
 * params, the lenient array/string coercion, and the {@link QaJUnitReport} per-suite extension the
 * tool relies on for its structured output. The spawn/poll path needs a live EDT and is not covered
 * here.
 */
public class YaxunitRunToolTest {

    private static final Gson GSON = new Gson();

    private static File f(String name) {
        return new File(System.getProperty("java.io.tmpdir"), name); //$NON-NLS-1$
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
}
