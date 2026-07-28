package com.codepilot1c.core.qa;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public class QaJUnitReport {

    public int tests;
    public int failures;
    public int errors;
    public int skipped;
    public double timeSeconds;
    public List<FailureDetail> failureDetails = new ArrayList<>();
    public List<Suite> suites = new ArrayList<>();
    public List<String> files = new ArrayList<>();

    /**
     * {@code true} when a preferred report file name was requested but not present, so the counts
     * above come from a directory-wide {@code *.xml} scan instead — i.e. from files that were never
     * proven to be this run's jUnit report. Callers must surface it rather than treat the totals as
     * authoritative. Always {@code false} for the plain directory-scan parse.
     */
    public boolean fallbackScan;

    /**
     * Tests that neither failed, errored, nor were skipped. Clamped at zero so a malformed report
     * (e.g. a suite that under-reports {@code tests}) can never yield a negative count.
     */
    public int passed() {
        int value = tests - failures - errors - skipped;
        return value < 0 ? 0 : value;
    }

    /**
     * Parses every {@code *.xml} found under {@code junitDir} and sums the counts. Suitable for a
     * dedicated report directory (Vanessa writes one file per feature there).
     */
    public static QaJUnitReport parseDirectory(File junitDir, int maxFailureDetails) throws IOException {
        return parseDirectory(junitDir, maxFailureDetails, null);
    }

    /**
     * Parses the jUnit report under {@code junitDir}, preferring the file named
     * {@code preferredFileName} (case-insensitive) when one is given.
     *
     * <p>The preferred-name form exists because a run directory may double as the 1C client's
     * working directory: any unrelated {@code *.xml} dropped there would otherwise be summed in as
     * "the report". When the preferred name is absent but other {@code *.xml} files are present, the
     * legacy directory-wide scan still runs and {@link #fallbackScan} is set so the caller can say
     * so out loud instead of silently trusting foreign counts. Passing {@code null} keeps the plain
     * directory-scan behaviour.</p>
     */
    public static QaJUnitReport parseDirectory(File junitDir, int maxFailureDetails, String preferredFileName)
            throws IOException {
        if (junitDir == null || !junitDir.exists() || !junitDir.isDirectory()) {
            return null;
        }
        List<File> discovered = new ArrayList<>();
        try (var stream = Files.walk(junitDir.toPath())) {
            stream.filter(path -> path.toString().toLowerCase().endsWith(".xml"))
                    .forEach(path -> discovered.add(path.toFile()));
        }
        if (discovered.isEmpty()) {
            return null;
        }
        List<File> selected = discovered;
        boolean fallback = false;
        if (preferredFileName != null && !preferredFileName.isBlank()) {
            List<File> preferred = new ArrayList<>();
            for (File file : discovered) {
                if (preferredFileName.equalsIgnoreCase(file.getName())) {
                    preferred.add(file);
                }
            }
            if (preferred.isEmpty()) {
                fallback = true;
            } else {
                selected = preferred;
            }
        }
        QaJUnitReport report = new QaJUnitReport();
        report.fallbackScan = fallback;
        for (File file : selected) {
            report.files.add(file.getAbsolutePath());
            parseFile(file, report, maxFailureDetails);
        }
        return report;
    }

    private static void parseFile(File file, QaJUnitReport report, int maxFailureDetails) throws IOException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        secureFactory(factory);
        try {
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(file);
            Element root = doc.getDocumentElement();
            if (root == null) {
                return;
            }
            String rootName = root.getTagName();
            if ("testsuite".equalsIgnoreCase(rootName)) {
                parseTestSuite(root, report, maxFailureDetails, file.getName());
            } else if ("testsuites".equalsIgnoreCase(rootName)) {
                NodeList children = root.getChildNodes();
                for (int i = 0; i < children.getLength(); i++) {
                    Node node = children.item(i);
                    if (node instanceof Element element && "testsuite".equalsIgnoreCase(element.getTagName())) {
                        parseTestSuite(element, report, maxFailureDetails, file.getName());
                    }
                }
            }
        } catch (Exception e) {
            throw new IOException("Failed to parse JUnit XML: " + file.getAbsolutePath() + ": " + e.getMessage(), e);
        }
    }

    private static void parseTestSuite(Element suite, QaJUnitReport report, int maxFailureDetails, String fileName) {
        Suite suiteSummary = new Suite();
        suiteSummary.name = suite.getAttribute("name");
        suiteSummary.tests = getIntAttr(suite, "tests");
        suiteSummary.failures = getIntAttr(suite, "failures");
        suiteSummary.errors = getIntAttr(suite, "errors");
        suiteSummary.skipped = getIntAttr(suite, "skipped");
        suiteSummary.timeSeconds = getDoubleAttr(suite, "time");
        report.suites.add(suiteSummary);

        report.tests += suiteSummary.tests;
        report.failures += suiteSummary.failures;
        report.errors += suiteSummary.errors;
        report.skipped += suiteSummary.skipped;
        report.timeSeconds += suiteSummary.timeSeconds;

        if (report.failureDetails.size() >= maxFailureDetails) {
            return;
        }
        NodeList cases = suite.getElementsByTagName("testcase");
        for (int i = 0; i < cases.getLength() && report.failureDetails.size() < maxFailureDetails; i++) {
            Node node = cases.item(i);
            if (!(node instanceof Element testcase)) {
                continue;
            }
            FailureDetail detail = extractFailure(testcase, fileName);
            if (detail != null) {
                report.failureDetails.add(detail);
            }
        }
    }

    private static FailureDetail extractFailure(Element testcase, String fileName) {
        NodeList failures = testcase.getElementsByTagName("failure");
        if (failures.getLength() == 0) {
            failures = testcase.getElementsByTagName("error");
        }
        if (failures.getLength() == 0) {
            return null;
        }
        Node node = failures.item(0);
        if (!(node instanceof Element element)) {
            return null;
        }
        FailureDetail detail = new FailureDetail();
        detail.name = testcase.getAttribute("name");
        detail.className = testcase.getAttribute("classname");
        detail.message = element.getAttribute("message");
        detail.type = element.getAttribute("type");
        detail.file = fileName;
        String text = element.getTextContent();
        if (text != null) {
            detail.details = text.trim();
        }
        return detail;
    }

    private static int getIntAttr(Element element, String name) {
        String value = element.getAttribute(name);
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double getDoubleAttr(Element element, String name) {
        String value = element.getAttribute(name);
        if (value == null || value.isBlank()) {
            return 0.0;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private static void secureFactory(DocumentBuilderFactory factory) {
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
        } catch (Exception e) {
            // Ignore if not supported.
        }
    }

    public static class FailureDetail {
        public String name;
        public String className;
        public String message;
        public String type;
        public String details;
        public String file;
    }

    /** Per-suite ({@code <testsuite>}) counts, in document order across all parsed report files. */
    public static class Suite {
        public String name;
        public int tests;
        public int failures;
        public int errors;
        public int skipped;
        public double timeSeconds;
    }
}
