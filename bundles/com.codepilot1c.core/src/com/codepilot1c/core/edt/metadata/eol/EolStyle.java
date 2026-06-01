package com.codepilot1c.core.edt.metadata.eol;

/**
 * Line-ending convention of a text artifact.
 *
 * <p>Used by {@link EolNormalizer} to detect the existing convention of a
 * {@code .mdo}/{@code .form}/{@code .bsl} file and to re-serialize it without
 * flipping its end-of-line style. The EDT BM serializer emits {@code CRLF}
 * regardless of the target file's existing EOL, which turns a 1-line logical
 * change into a whole-file diff on {@code LF} repositories — see the
 * {@code 2026-06-01-bm-api-crlf-eol-rewrites-mdo-form} feedback note.</p>
 */
public enum EolStyle {

    LF("\n"), //$NON-NLS-1$
    CRLF("\r\n"); //$NON-NLS-1$

    private final String sequence;

    EolStyle(String sequence) {
        this.sequence = sequence;
    }

    /** The literal terminator emitted for this style. */
    public String sequence() {
        return sequence;
    }
}
