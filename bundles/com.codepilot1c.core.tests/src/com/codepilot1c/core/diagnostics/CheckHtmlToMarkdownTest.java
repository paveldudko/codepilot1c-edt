/*
 * Copyright (c) 2026 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests for {@link CheckHtmlToMarkdown}. Fixtures are the real
 * {@code check.descriptions/*.html} payload shipped by the
 * {@code com.e1c.v8codestyle.bsl} bundle, so the test pins the converter
 * against the actual shape EDT emits.
 */
public class CheckHtmlToMarkdownTest {

    // Verbatim copy from com.e1c.v8codestyle.bsl_0.7.0.../check.descriptions/manager-module-named-self-reference.html
    private static final String SAMPLE_EN = """
            <!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.0 Transitional//EN">

            <html lang="${lang}">
            <head>
                <title> Excessive named self reference in manager module</title>
                <meta charset="utf-8" />
                <link rel="stylesheet" href="./css/default.css">
                <link rel="stylesheet" href="./css/prism.css" />
            </head>
            <body>
            <script src="./js/prism.js"></script>
            <h1><a href="#excessive-named-self-reference-in-manager-module" id="excessive-named-self-reference-in-manager-module"></a>Excessive named self reference in manager module</h1>
            <p>Excessive usage of named self reference in manager module (when referencing method, property or attribute).</p>
            <h2><a href="#noncompliant-code-example" id="noncompliant-code-example"></a>Noncompliant Code Example</h2>
            <p>Inside Catalog manager module named &quot;MyCatalog&quot;:</p>
            <pre><code class="language-bsl">Var myParam;

            Function test() Export
                // code here
            EndFunction

            Catalog.MyCatalog.myParam = Catalog.MyCatalog.test();
            </code></pre>
            <h2><a href="#compliant-solution" id="compliant-solution"></a>Compliant Solution</h2>
            <p>Inside Catalog manager module named &quot;MyCatalog&quot;:</p>
            <pre><code class="language-bsl">Var myParam;

            Function test() Export
                // code here
            EndFunction

            myParam = test();
            </code></pre>
            <h2><a href="#see" id="see"></a>See</h2>
             <footer>
                </footer>
            </body>
            </html>
            """;

    private static final String SAMPLE_RU = """
            <!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.0 Transitional//EN">

            <html lang="${lang}">
            <head>
                <title> Избыточное обращение по собственному имени внутри модуля менеджера</title>
            </head>
            <body>
            <script src="./../js/prism.js"></script>
            <h1><a href="#x" id="x"></a>Избыточное обращение по собственному имени внутри модуля менеджера</h1>
            <p>Избыточное обращение по собственному имени внутри модуля менеджера (к методу, свойству или реквизиту)</p>
            <h2><a href="#x" id="x"></a>Неправильно</h2>
            <pre><code class="language-bsl">Справочники.МойСправочник.мояПеременная = Справочники.МойСправочник.тест();
            </code></pre>
            <h2><a href="#x" id="x"></a>Правильно</h2>
            <pre><code class="language-bsl">мояПеременная = тест();
            </code></pre>
            <h2><a href="#x" id="x"></a>См.</h2>
             <footer></footer>
            </body>
            </html>
            """;

    @Test
    public void renderingPreservesH1AsTitle() {
        String md = CheckHtmlToMarkdown.convert(SAMPLE_EN);
        assertTrue("expected H1 title at top, got:\n" + md,
                md.contains("# Excessive named self reference in manager module"));
    }

    @Test
    public void noncompliantAndCompliantSectionsBecomeH2() {
        String md = CheckHtmlToMarkdown.convert(SAMPLE_EN);
        assertTrue(md.contains("## Noncompliant Code Example"));
        assertTrue(md.contains("## Compliant Solution"));
    }

    @Test
    public void emptyTrailingSectionsAreDropped() {
        String md = CheckHtmlToMarkdown.convert(SAMPLE_EN);
        assertFalse("empty 'See' section must be dropped", md.contains("## See"));
        assertFalse("footer must be stripped", md.toLowerCase().contains("<footer"));
    }

    @Test
    public void scriptAndCssAreStripped() {
        String md = CheckHtmlToMarkdown.convert(SAMPLE_EN);
        assertFalse(md.contains("prism.js"));
        assertFalse(md.contains("prism.css"));
        assertFalse(md.contains("default.css"));
    }

    @Test
    public void codeBlocksGetBslLanguageFence() {
        String md = CheckHtmlToMarkdown.convert(SAMPLE_EN);
        assertTrue("expected ```bsl fence, got:\n" + md, md.contains("```bsl"));
        assertTrue(md.contains("Catalog.MyCatalog.myParam = Catalog.MyCatalog.test();"));
        assertTrue("compliant code must survive verbatim", md.contains("myParam = test();"));
    }

    @Test
    public void htmlEntitiesAreDecoded() {
        String md = CheckHtmlToMarkdown.convert(SAMPLE_EN);
        assertTrue("expected decoded quotes, got:\n" + md,
                md.contains("Inside Catalog manager module named \"MyCatalog\":"));
        assertFalse(md.contains("&quot;"));
    }

    @Test
    public void cyrillicSampleRendersWithoutMojibake() {
        String md = CheckHtmlToMarkdown.convert(SAMPLE_RU);
        assertTrue(md.contains("# Избыточное обращение по собственному имени внутри модуля менеджера"));
        assertTrue(md.contains("## Неправильно"));
        assertTrue(md.contains("## Правильно"));
        assertFalse("empty 'См.' section must be dropped", md.contains("## См."));
        assertTrue(md.contains("Справочники.МойСправочник.мояПеременная"));
    }

    @Test
    public void blankInputReturnsEmptyString() {
        assertEquals("", CheckHtmlToMarkdown.convert(null));
        assertEquals("", CheckHtmlToMarkdown.convert(""));
        assertEquals("", CheckHtmlToMarkdown.convert("   \n  "));
    }

    @Test
    public void anchorChildrenInsideHeadingsAreStripped() {
        String md = CheckHtmlToMarkdown.convert(SAMPLE_EN);
        assertFalse("anchor markup must not leak", md.contains("<a href"));
        assertFalse(md.contains("id=\""));
    }

    @Test
    public void outputEndsWithSingleNewline() {
        String md = CheckHtmlToMarkdown.convert(SAMPLE_EN);
        assertTrue(md.endsWith("\n"));
        assertFalse("no trailing blank line block", md.endsWith("\n\n\n"));
    }
}
