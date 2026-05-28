package com.codepilot1c.core.tools.qa;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.codepilot1c.core.tools.qa.QaRunTool.TestClientCreds;

/**
 * Tests {@link QaRunTool#injectTestClientBackground(String, com.codepilot1c.core.tools.qa.QaRunTool.TestClientCreds)}
 * — the pure helper that turns a Gherkin/Vanessa feature into one carrying an "open TestClient
 * with creds" Background. Regression target: codepilot1c-feedback/2026-05-28-qa-run-auto-inject-bug.md,
 * where the previous {@code startsWith("функционал:")} check failed to detect the modern
 * {@code Функциональность:} header and the helper silently returned the original content.
 */
public class QaRunFeatureInjectionTest {

    private static final TestClientCreds CREDS = new TestClientCreds("Tester", "secret"); //$NON-NLS-1$ //$NON-NLS-2$

    @Test
    public void injectsContextBlockForModernFunktsionalnostHeader() {
        // The exact form Vanessa-Automation generates today — caught us with a silent no-op
        // before because "Функциональность:".startsWith("функционал:") is false.
        String original = "# language: ru\n" //$NON-NLS-1$
                + "@tag\n" //$NON-NLS-1$
                + "Функциональность: BF-12493 Demo\n" //$NON-NLS-1$
                + "  Как разработчик\n" //$NON-NLS-1$
                + "  Я хочу X\n" //$NON-NLS-1$
                + "\n" //$NON-NLS-1$
                + "Сценарий: First\n" //$NON-NLS-1$
                + "  Дано Y\n"; //$NON-NLS-1$

        String injected = QaRunTool.injectTestClientBackground(original, CREDS);

        assertNotEquals("must mutate the content when header is recognized", original, injected); //$NON-NLS-1$
        assertTrue("must add Контекст: block", injected.contains("Контекст:")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must include the Дано step with caller-provided creds", //$NON-NLS-1$
                injected.contains("Дано Я открыл сеанс TestClient от имени \"Tester\" с паролем \"secret\"")); //$NON-NLS-1$
        int kontextIdx = injected.indexOf("Контекст:"); //$NON-NLS-1$
        int scenarioIdx = injected.indexOf("Сценарий:"); //$NON-NLS-1$
        int descriptionIdx = injected.indexOf("Как разработчик"); //$NON-NLS-1$
        assertTrue("description must precede Контекст:", descriptionIdx < kontextIdx); //$NON-NLS-1$
        assertTrue("Контекст: must precede first Сценарий:", kontextIdx < scenarioIdx); //$NON-NLS-1$
    }

    @Test
    public void injectsContextBlockForLegacyFunktsionalHeader() {
        String original = "Функционал: Legacy demo\n\nСценарий: Test\n  Дано X\n"; //$NON-NLS-1$
        String injected = QaRunTool.injectTestClientBackground(original, CREDS);
        assertNotEquals(original, injected);
        assertTrue(injected.contains("Контекст:")); //$NON-NLS-1$
        assertTrue(injected.contains("Дано Я открыл сеанс TestClient от имени")); //$NON-NLS-1$
    }

    @Test
    public void injectsContextBlockForEnglishFeatureHeader() {
        String original = "Feature: English demo\n\nScenario: Test\n  Given Y\n"; //$NON-NLS-1$
        String injected = QaRunTool.injectTestClientBackground(original, CREDS);
        assertNotEquals(original, injected);
        assertTrue(injected.contains("Контекст:")); //$NON-NLS-1$
        assertTrue(injected.contains("Дано Я открыл сеанс TestClient от имени")); //$NON-NLS-1$
    }

    @Test
    public void appendsToExistingKontextBlockWithoutAddingASecondOne() {
        String original = "Функциональность: With existing context\n" //$NON-NLS-1$
                + "  Контекст:\n" //$NON-NLS-1$
                + "    Дано Some prior step\n" //$NON-NLS-1$
                + "\n" //$NON-NLS-1$
                + "  Сценарий: T\n" //$NON-NLS-1$
                + "    Дано Z\n"; //$NON-NLS-1$
        String injected = QaRunTool.injectTestClientBackground(original, CREDS);

        long kontextCount = injected.lines()
                .filter(l -> l.trim().startsWith("Контекст:")) //$NON-NLS-1$
                .count();
        assertEquals("must not add a second Контекст: block", 1L, kontextCount); //$NON-NLS-1$
        assertTrue("must contain our Дано step", //$NON-NLS-1$
                injected.contains("Дано Я открыл сеанс TestClient от имени \"Tester\"")); //$NON-NLS-1$
        assertTrue("must preserve the existing prior step", //$NON-NLS-1$
                injected.contains("Дано Some prior step")); //$NON-NLS-1$
    }

    @Test
    public void appendsToPredystoriaAliasOfBackground() {
        // Vanessa-Automation accepts "Предыстория:" as a Background alias — the helper must
        // recognize it as such and not double up with a fresh "Контекст:" block.
        String original = "Функционал: Demo\n\nПредыстория:\n  Дано Some\n\nСценарий: T\n  Дано X\n"; //$NON-NLS-1$
        String injected = QaRunTool.injectTestClientBackground(original, CREDS);

        long blockCount = injected.lines()
                .filter(l -> {
                    String t = l.trim();
                    return t.startsWith("Контекст:") || t.startsWith("Предыстория:"); //$NON-NLS-1$ //$NON-NLS-2$
                })
                .count();
        assertEquals("must not add Контекст: when Предыстория: already exists", 1L, blockCount); //$NON-NLS-1$
        assertTrue(injected.contains("Дано Я открыл сеанс TestClient от имени")); //$NON-NLS-1$
    }

    @Test
    public void returnsSameReferenceWhenNoFeatureHeader() {
        // The wrapper's "no-write" path keys off identity equality of the returned reference; the
        // helper must NOT defensively copy when there's nothing to do.
        String original = "# just a comment\n\nrandom text without any gherkin headers\n"; //$NON-NLS-1$
        String injected = QaRunTool.injectTestClientBackground(original, CREDS);
        assertSame("must return the same content reference when no header is found", original, injected); //$NON-NLS-1$
    }

    @Test
    public void putsContextAtColumnZeroAndMatchesStepIndentWithTabs() {
        // Regression for codepilot1c-feedback/2026-05-28-qa-run-inject-indent-bug.md:
        // emitting "  Контекст:" (any leading whitespace on a top-level keyword line) makes
        // Gherkin treat the line as feature description, not as a Background block. The Дано
        // step must match the surrounding scenario's indentation (tab here) so it parses as a
        // step rather than as description.
        String original = "Функциональность: Test\n" //$NON-NLS-1$
                + "\tDescription line\n" //$NON-NLS-1$
                + "\n" //$NON-NLS-1$
                + "Сценарий: One\n" //$NON-NLS-1$
                + "\tДано шаг\n"; //$NON-NLS-1$

        String injected = QaRunTool.injectTestClientBackground(original, CREDS);

        assertTrue("Контекст: must sit at column 0 (no leading whitespace)", //$NON-NLS-1$
                injected.lines().anyMatch(l -> l.equals("Контекст:"))); //$NON-NLS-1$
        assertTrue("Контекст: line must NOT have any leading indentation", //$NON-NLS-1$
                injected.lines().noneMatch(l -> !l.equals("Контекст:") && l.endsWith("Контекст:"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("Дано must use the same tab indent as surrounding scenario steps", //$NON-NLS-1$
                injected.lines().anyMatch(l -> l.startsWith("\tДано Я открыл сеанс TestClient"))); //$NON-NLS-1$
    }

    @Test
    public void putsContextAtColumnZeroAndMatchesStepIndentWithFourSpaces() {
        // Same contract, four-space-indented variant — confirms detection adapts (not just
        // "tab-or-nothing").
        String original = "Feature: Test\n" //$NON-NLS-1$
                + "\n" //$NON-NLS-1$
                + "Scenario: One\n" //$NON-NLS-1$
                + "    Given x\n"; //$NON-NLS-1$

        String injected = QaRunTool.injectTestClientBackground(original, CREDS);

        assertTrue("Контекст: must sit at column 0", //$NON-NLS-1$
                injected.lines().anyMatch(l -> l.equals("Контекст:"))); //$NON-NLS-1$
        assertTrue("Дано must use the same 4-space indent as surrounding scenario steps", //$NON-NLS-1$
                injected.lines().anyMatch(l -> l.startsWith("    Дано Я открыл сеанс TestClient"))); //$NON-NLS-1$
    }

    @Test
    public void escapesEmbeddedQuotesInCredentialsViaOneCDoubling() {
        TestClientCreds tricky = new TestClientCreds("User \"with\" quotes", "Pw\"d"); //$NON-NLS-1$ //$NON-NLS-2$
        String original = "Функциональность: q\n\nСценарий: T\n  Дано X\n"; //$NON-NLS-1$
        String injected = QaRunTool.injectTestClientBackground(original, tricky);
        // 1C convention doubles embedded quotes; the Gherkin string remains parseable as quoted.
        assertTrue(injected.contains("от имени \"User \"\"with\"\" quotes\"")); //$NON-NLS-1$
        assertTrue(injected.contains("с паролем \"Pw\"\"d\"")); //$NON-NLS-1$
    }
}
