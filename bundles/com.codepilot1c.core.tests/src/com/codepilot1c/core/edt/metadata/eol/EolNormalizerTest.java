package com.codepilot1c.core.edt.metadata.eol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Optional;

import org.junit.Test;

/**
 * Pure-logic tests for {@link EolNormalizer} — the core of the fix for the
 * {@code 2026-06-01-bm-api-crlf-eol-rewrites-mdo-form} feedback: the EDT BM
 * serializer rewrites {@code .mdo}/{@code .form} files as CRLF regardless of
 * the file's existing EOL, flipping the whole file in a diff. These tests pin
 * the per-file detection, the byte-stable (terminator-only) re-application, and
 * the {@code .gitattributes}/{@code .editorconfig} default resolution.
 */
public class EolNormalizerTest {

    @Test
    public void detectsLf() {
        assertEquals(Optional.of(EolStyle.LF), EolNormalizer.detect("a\nb\nc\n"));
    }

    @Test
    public void detectsCrlf() {
        assertEquals(Optional.of(EolStyle.CRLF), EolNormalizer.detect("a\r\nb\r\n"));
    }

    @Test
    public void detectsDominantStyleInMixedContent() {
        // 2 CRLF vs 1 LF -> CRLF dominates.
        assertEquals(Optional.of(EolStyle.CRLF), EolNormalizer.detect("a\r\nb\r\nc\nd"));
    }

    @Test
    public void noTerminatorsYieldsEmpty() {
        assertEquals(Optional.empty(), EolNormalizer.detect("single line, no newline"));
        assertEquals(Optional.empty(), EolNormalizer.detect(""));
        assertEquals(Optional.empty(), EolNormalizer.detect(null));
    }

    @Test
    public void normalizeCrlfBackToLf() {
        // The exact regression: BM API emitted CRLF, repo wants LF.
        String emitted = "<mdo>\r\n  <x/>\r\n</mdo>\r\n";
        assertEquals("<mdo>\n  <x/>\n</mdo>\n", EolNormalizer.normalizeTo(emitted, EolStyle.LF));
    }

    @Test
    public void normalizeLfToCrlfPreservesCrlfFile() {
        // The secondary observation: a file that was CRLF at HEAD must stay CRLF.
        String emitted = "<mdo>\n  <x/>\n</mdo>\n";
        assertEquals("<mdo>\r\n  <x/>\r\n</mdo>\r\n", EolNormalizer.normalizeTo(emitted, EolStyle.CRLF));
    }

    @Test
    public void normalizeIsIdempotent() {
        String lf = "a\nb\nc\n";
        assertEquals(lf, EolNormalizer.normalizeTo(EolNormalizer.normalizeTo(lf, EolStyle.LF), EolStyle.LF));
        String crlf = "a\r\nb\r\n";
        assertEquals(crlf, EolNormalizer.normalizeTo(EolNormalizer.normalizeTo(crlf, EolStyle.CRLF), EolStyle.CRLF));
    }

    @Test
    public void normalizeIsByteStableWhenAlreadyTarget() {
        // Re-serializing an unchanged LF file to LF produces zero diff (ask #3).
        String lf = "line1\nline2\n";
        assertEquals(lf, EolNormalizer.normalizeTo(lf, EolStyle.LF));
    }

    @Test
    public void normalizePreservesTrailingNewlineAbsence() {
        assertEquals("a\nb", EolNormalizer.normalizeTo("a\r\nb", EolStyle.LF));
        assertEquals("a\r\nb", EolNormalizer.normalizeTo("a\nb", EolStyle.CRLF));
    }

    @Test
    public void roundTripFromDetectedStylePreservesContent() {
        // The integration contract: detect existing style, re-apply -> no churn.
        String original = "<form>\r\n  <item/>\r\n</form>\r\n";
        EolStyle detected = EolNormalizer.detect(original).orElseThrow();
        String reEmittedByBm = EolNormalizer.normalizeTo(original, EolStyle.CRLF); // BM always CRLF
        assertEquals(original, EolNormalizer.normalizeTo(reEmittedByBm, detected));
    }

    @Test
    public void gitattributesEolLf() {
        assertEquals(Optional.of(EolStyle.LF),
                EolNormalizer.fromGitattributes("*.mdo text eol=lf\n", "PaymentSystems.mdo"));
    }

    @Test
    public void gitattributesEolCrlf() {
        assertEquals(Optional.of(EolStyle.CRLF),
                EolNormalizer.fromGitattributes("*.form text eol=crlf\n", "Form.form"));
    }

    @Test
    public void gitattributesLastMatchWins() {
        String content = "* text eol=crlf\n*.mdo text eol=lf\n";
        assertEquals(Optional.of(EolStyle.LF),
                EolNormalizer.fromGitattributes(content, "Configuration.mdo"));
    }

    @Test
    public void gitattributesNoMatchOrNoEol() {
        assertEquals(Optional.empty(),
                EolNormalizer.fromGitattributes("*.txt text eol=lf\n", "Form.form"));
        assertEquals(Optional.empty(),
                EolNormalizer.fromGitattributes("*.mdo text\n", "X.mdo"));
        assertEquals(Optional.empty(),
                EolNormalizer.fromGitattributes("# comment only\n", "X.mdo"));
    }

    @Test
    public void gitattributesMatchesPathOnlyForSlashPatterns() {
        String content = "src/Configuration/Configuration.mdo eol=crlf\n";
        assertEquals(Optional.of(EolStyle.CRLF),
                EolNormalizer.fromGitattributes(content, "src/Configuration/Configuration.mdo"));
        // Same basename, different path -> the anchored pattern must not match.
        assertEquals(Optional.empty(),
                EolNormalizer.fromGitattributes(content, "src/Other/Configuration.mdo"));
    }

    @Test
    public void editorconfigEndOfLine() {
        String content = "root = true\n\n[*.mdo]\nend_of_line = lf\n";
        assertEquals(Optional.of(EolStyle.LF),
                EolNormalizer.fromEditorconfig(content, "X.mdo"));
    }

    @Test
    public void editorconfigBraceList() {
        String content = "[*.{mdo,form,bsl}]\nend_of_line = crlf\n";
        assertEquals(Optional.of(EolStyle.CRLF),
                EolNormalizer.fromEditorconfig(content, "Form.form"));
        assertEquals(Optional.of(EolStyle.CRLF),
                EolNormalizer.fromEditorconfig(content, "Module.bsl"));
    }

    @Test
    public void editorconfigSectionScoping() {
        String content = "[*.txt]\nend_of_line = crlf\n[*.mdo]\nend_of_line = lf\n";
        assertEquals(Optional.of(EolStyle.LF),
                EolNormalizer.fromEditorconfig(content, "X.mdo"));
    }

    @Test
    public void starMatchesEveryBaseName() {
        assertTrue(globVisible("*", "anything.mdo"));
    }

    // Exercises the glob via the public gitattributes entry point.
    private static boolean globVisible(String pattern, String fileName) {
        return EolNormalizer.fromGitattributes(pattern + " eol=lf\n", fileName).isPresent();
    }

    @Test
    public void starDoesNotLeakAcrossEntryPoints() {
        assertFalse(EolNormalizer.fromGitattributes("nomatch eol=lf\n", "X.mdo").isPresent());
    }
}
