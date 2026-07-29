package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Behavioural test for the format a rendered cell points at.
 *
 * <p>What was broken: {@code render_template} wrote {@code [Название]} / {@code [Сумма]} and
 * {@code inspect_template} read the row back as two empty cells, while parameters in real
 * EDT-authored templates (AM {@code PF_MXL_BusinessCalendar}) read fine. The rendered
 * {@code Template.mxlx} held {@code <c><f>0</f></c>} — no {@code <parameter>}, no {@code <tl>}. The
 * cause is in {@code V8MoxelSerializer.writeCell}: the {@code <parameter>} branch is taken only when
 * the format at {@code cell.getFormatIndex()} carries {@code fillType=Parameter}, and every format
 * {@code render_template} produced was plain, so a parameter cell fell into the text branch with no
 * text to write.</p>
 *
 * <p>These assertions hold the per-cell index and the format table to each other — the pairing the
 * serializer depends on — rather than asserting that the source says something.</p>
 */
public class TemplateCellRenderingTest {

    // --- the pairing the serializer depends on -------------------------------

    @Test
    public void aParameterCellPointsAtAFormatCarryingParameterFill() {
        assertTrue(TemplateCellRendering.isParameterFormat(
                TemplateCellRendering.formatIndex(false, true)));
    }

    @Test
    public void aBoldParameterCellAlsoPointsAtAParameterFormat() {
        // The regression would come back if bold styling silently dropped the parameter flavour.
        assertTrue(TemplateCellRendering.isParameterFormat(
                TemplateCellRendering.formatIndex(true, true)));
    }

    @Test
    public void aTextCellPointsAtAFormatWithoutParameterFill() {
        assertFalse(TemplateCellRendering.isParameterFormat(
                TemplateCellRendering.formatIndex(false, false)));
        assertFalse(TemplateCellRendering.isParameterFormat(
                TemplateCellRendering.formatIndex(true, false)));
    }

    @Test
    public void boldAndPlainAreDistinctFormatsInBothFlavours() {
        assertFalse(TemplateCellRendering.formatIndex(true, false)
                == TemplateCellRendering.formatIndex(false, false));
        assertFalse(TemplateCellRendering.formatIndex(true, true)
                == TemplateCellRendering.formatIndex(false, true));
    }

    @Test
    public void everyCellKindResolvesInsideTheTableTheCallerBuilds() {
        // The serializer indexes formats unguarded — an index past the table throws, it does not degrade.
        for (boolean bold : new boolean[] {false, true}) {
            for (boolean parameter : new boolean[] {false, true}) {
                int index = TemplateCellRendering.formatIndex(bold, parameter);
                assertTrue("bold=" + bold + " parameter=" + parameter, //$NON-NLS-1$ //$NON-NLS-2$
                        index >= 0 && index < TemplateCellRendering.FORMAT_COUNT);
            }
        }
    }

    @Test
    public void theTableHasNoEntryTheCellMappingCannotReach() {
        // Otherwise a format flavour exists that nothing ever points at — dead weight in the .mxlx.
        for (int index = 0; index < TemplateCellRendering.FORMAT_COUNT; index++) {
            boolean reached = false;
            for (boolean bold : new boolean[] {false, true}) {
                for (boolean parameter : new boolean[] {false, true}) {
                    reached |= TemplateCellRendering.formatIndex(bold, parameter) == index;
                }
            }
            assertTrue("format " + index + " is unreachable", reached); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    // --- what counts as a binding -------------------------------------------

    @Test
    public void aBracketedNameIsABinding() {
        assertEquals("Сумма", TemplateCellRendering.extractBinding("[Сумма]")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void surroundingWhitespaceIsNotPartOfTheName() {
        assertEquals("Сумма", TemplateCellRendering.extractBinding("[ Сумма ]")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void plainTextIsNotABinding() {
        assertNull(TemplateCellRendering.extractBinding("Товар")); //$NON-NLS-1$
        assertNull(TemplateCellRendering.extractBinding("[Товар")); //$NON-NLS-1$
        assertNull(TemplateCellRendering.extractBinding("Товар]")); //$NON-NLS-1$
    }

    @Test
    public void anEmptyBindingIsTextRatherThanAParameter() {
        // The serializer skips an empty parameter name: as a binding this would render as nothing
        // while reporting itself a parameter.
        assertNull(TemplateCellRendering.extractBinding("[]")); //$NON-NLS-1$
        assertNull(TemplateCellRendering.extractBinding("[   ]")); //$NON-NLS-1$
    }

    @Test
    public void aBareBracketIsNotABinding() {
        assertNull(TemplateCellRendering.extractBinding("[")); //$NON-NLS-1$
        assertNull(TemplateCellRendering.extractBinding("]")); //$NON-NLS-1$
    }

    @Test
    public void noValueAtAllIsNotABinding() {
        assertNull(TemplateCellRendering.extractBinding(null));
        assertNull(TemplateCellRendering.extractBinding("")); //$NON-NLS-1$
    }
}
