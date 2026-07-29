/**
 * Copyright (c) 2025 codepilot1c contributors.
 */
package com.codepilot1c.core.edt.metadata;

/**
 * How a rendered spreadsheet cell maps onto the format table {@code render_template} writes.
 *
 * <p>A cell's content is not self-describing in the {@code .mxlx} format. The serializer decides what
 * to write by looking at the <b>format</b> the cell points at — decompiled from
 * {@code V8MoxelSerializer.writeCell} (EDT 2025.2.3):</p>
 *
 * <pre>
 * Format fmt = sheet.getFormats().get(cell.getFormatIndex());
 * if (fmt.getFillType() == FillType.PARAMETER) {
 *     if (isNotEmpty(cell.getParameter())) write("parameter", cell.getParameter());
 * } else if (cell.getText() != null) {
 *     write("tl" / "tfl", cell.getText());
 * }
 * </pre>
 *
 * <p>So a cell carrying {@code setParameter("Sum")} under a plain format serializes to nothing at all
 * — neither the parameter (wrong branch) nor any text (there is none). That was the defect: a
 * rendered {@code [Sum]} came back from {@code inspect_template} as an empty cell, while parameters in
 * real EDT-authored templates read fine. Hence the format table carries a parameter flavour of every
 * style, and a parameter cell must point at one.</p>
 *
 * <p>{@code detailParameter} needs none of this — the serializer writes it unconditionally.</p>
 *
 * <p>Deliberately free of any EMF/EDT type: the caller builds the format list from
 * {@link #FORMAT_COUNT} and {@link #isParameterFormat(int)}, so the table and the per-cell index come
 * from one place and a test can hold them to each other without an EDT runtime.</p>
 */
final class TemplateCellRendering {

    /** Plain text. */
    static final int FORMAT_TEXT = 0;

    /** Bold text — headers, totals, titles. */
    static final int FORMAT_BOLD_TEXT = 1;

    /** A parameter cell: {@code fillType=Parameter}. */
    static final int FORMAT_PARAMETER = 2;

    /** A bold parameter cell: {@code fillType=Parameter}. */
    static final int FORMAT_BOLD_PARAMETER = 3;

    /** How many formats {@code render_template} puts into {@code SpreadsheetDocument.formats}. */
    static final int FORMAT_COUNT = 4;

    private TemplateCellRendering() {
        // utility
    }

    /**
     * The index of the format a cell must point at. Every combination resolves to a real entry of the
     * table — the serializer indexes {@code formats} unguarded, so an out-of-range index would throw
     * instead of degrading.
     */
    static int formatIndex(boolean bold, boolean parameter) {
        if (parameter) {
            return bold ? FORMAT_BOLD_PARAMETER : FORMAT_PARAMETER;
        }
        return bold ? FORMAT_BOLD_TEXT : FORMAT_TEXT;
    }

    /** Whether the format at this index of the table must carry {@code fillType=Parameter}. */
    static boolean isParameterFormat(int index) {
        return index == FORMAT_PARAMETER || index == FORMAT_BOLD_PARAMETER;
    }

    /**
     * The parameter name a cell value binds to, or {@code null} when the value is not a binding and
     * should be written as static text.
     *
     * <p>A binding is {@code [Name]}. An empty or blank one ({@code []}, {@code [ ]}) is not a
     * binding: the serializer skips an empty parameter name, so treating it as one would produce a
     * cell that renders as nothing and reports as a parameter. It falls through to text instead.</p>
     */
    static String extractBinding(String cellValue) {
        if (cellValue == null || cellValue.length() < 2
                || !cellValue.startsWith("[") || !cellValue.endsWith("]")) { //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        }
        String binding = cellValue.substring(1, cellValue.length() - 1).trim();
        return binding.isEmpty() ? null : binding;
    }
}
