package com.codepilot1c.core.edt.lang;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.Test;

/**
 * Tests for {@link BslDocSeeChain}.
 *
 * <p>Pins the doc-comment {@code См.} / {@code See} contract that the reconnaissance note got
 * backwards. The resolver inside EDT is <em>transitive</em> (recursion plus an
 * {@code alreadyProcessingMethods} loop guard); what makes it look like a single hop is the guard on
 * that recursion — the chain runs only through comments that are a bare trailing link, and the first
 * hop declaring its own {@code Параметры:} / {@code Возвращаемое значение:} section ends it. These
 * tests exist so nobody writes a transitive resolver on top of a resolver that already is one.</p>
 */
public class BslDocSeeChainTest {

    // --- link extraction -----------------------------------------------------

    @Test
    public void findsRussianLink() {
        Optional<BslDocSeeChain.SeeLink> link = BslDocSeeChain.parse("См. ОбработатьЗаказ"); //$NON-NLS-1$

        assertTrue(link.isPresent());
        assertEquals("ОбработатьЗаказ", link.get().target()); //$NON-NLS-1$
        assertTrue(link.get().trailing());
        assertFalse(link.get().typeSectionPresent());
        assertTrue(link.get().followed());
    }

    @Test
    public void findsEnglishLink() {
        assertEquals(Optional.of("ProcessOrder"), //$NON-NLS-1$
                BslDocSeeChain.effectiveTarget("See ProcessOrder")); //$NON-NLS-1$
    }

    @Test
    public void keywordMatchIsCaseInsensitiveInBothLanguages() {
        assertEquals(Optional.of("ОбработатьЗаказ"), //$NON-NLS-1$
                BslDocSeeChain.effectiveTarget("СМ. ОбработатьЗаказ")); //$NON-NLS-1$
        assertEquals(Optional.of("ОбработатьЗаказ"), //$NON-NLS-1$
                BslDocSeeChain.effectiveTarget("см. ОбработатьЗаказ")); //$NON-NLS-1$
        assertEquals(Optional.of("ProcessOrder"), //$NON-NLS-1$
                BslDocSeeChain.effectiveTarget("SEE ProcessOrder")); //$NON-NLS-1$
        assertEquals(Optional.of("ProcessOrder"), //$NON-NLS-1$
                BslDocSeeChain.effectiveTarget("see ProcessOrder")); //$NON-NLS-1$
    }

    @Test
    public void acceptsCommentMarkersAndMultipleLines() {
        assertEquals(Optional.of("ОбщегоНазначения.ЗначениеРеквизита"), //$NON-NLS-1$
                BslDocSeeChain.effectiveTarget("// Возвращает реквизит объекта.\n// См. ОбщегоНазначения.ЗначениеРеквизита")); //$NON-NLS-1$
    }

    @Test
    public void recognizesBracketedLink() {
        Optional<BslDocSeeChain.SeeLink> link =
                BslDocSeeChain.parse("Заполняет движения документа (См. ОбработатьЗаказ)"); //$NON-NLS-1$

        assertTrue(link.isPresent());
        assertEquals("ОбработатьЗаказ", link.get().target()); //$NON-NLS-1$
        assertTrue("the closing bracket belongs to the link, so the link is still last", //$NON-NLS-1$
                link.get().trailing());
    }

    @Test
    public void linkThatIsNotTheLastDescriptionPartIsNotFollowed() {
        Optional<BslDocSeeChain.SeeLink> link =
                BslDocSeeChain.parse("См. ОбработатьЗаказ и обработчик проведения"); //$NON-NLS-1$

        assertTrue(link.isPresent());
        assertEquals("ОбработатьЗаказ", link.get().target()); //$NON-NLS-1$
        assertFalse(link.get().trailing());
        assertFalse(link.get().followed());
        assertEquals(Optional.empty(), BslDocSeeChain.effectiveTarget("См. ОбработатьЗаказ и обработчик")); //$NON-NLS-1$
    }

    @Test
    public void textOnALaterDescriptionLineAlsoUnseatsTheLink() {
        Optional<BslDocSeeChain.SeeLink> link =
                BslDocSeeChain.parse("// См. ОбработатьЗаказ\n// Работает только на сервере."); //$NON-NLS-1$

        assertTrue(link.isPresent());
        assertFalse(link.get().trailing());
    }

    @Test
    public void blankLinesAfterTheLinkKeepItLast() {
        assertTrue(BslDocSeeChain.parse("// См. ОбработатьЗаказ\n//\n").orElseThrow().trailing()); //$NON-NLS-1$
    }

    @Test
    public void lastLinkOfTheDescriptionWins() {
        assertEquals(Optional.of("Второй"), //$NON-NLS-1$
                BslDocSeeChain.effectiveTarget("// См. Первый, а также\n// См. Второй")); //$NON-NLS-1$
    }

    @Test
    public void noLinkYieldsEmpty() {
        assertEquals(Optional.empty(), BslDocSeeChain.parse("Проводит документ по регистрам.")); //$NON-NLS-1$
        assertEquals(Optional.empty(), BslDocSeeChain.parse("Parameters:\n  Ссылка - ДокументСсылка")); //$NON-NLS-1$
    }

    @Test
    public void nullAndBlankInputYieldEmpty() {
        assertEquals(Optional.empty(), BslDocSeeChain.parse(null));
        assertEquals(Optional.empty(), BslDocSeeChain.parse("")); //$NON-NLS-1$
        assertEquals(Optional.empty(), BslDocSeeChain.parse("   \n\t\n")); //$NON-NLS-1$
        assertEquals(Optional.empty(), BslDocSeeChain.effectiveTarget(null));
    }

    @Test
    public void keywordGluedToAWordIsNotALink() {
        assertEquals(Optional.empty(), BslDocSeeChain.parse("Просм. ОбработатьЗаказ")); //$NON-NLS-1$
        assertEquals(Optional.empty(), BslDocSeeChain.parse("Foreseen ProcessOrder")); //$NON-NLS-1$
    }

    @Test
    public void keywordWithoutATargetIsNotALink() {
        assertEquals(Optional.empty(), BslDocSeeChain.parse("Устарел, см.")); //$NON-NLS-1$
    }

    @Test
    public void tagLineIsNotScannedForLinks() {
        assertEquals(Optional.empty(), BslDocSeeChain.parse("@deprecated См. ОбработатьЗаказ")); //$NON-NLS-1$
    }

    // --- what breaks the chain ----------------------------------------------

    @Test
    public void ownReturnSectionEndsTheChainAtThisComment() {
        String doc = "Проводит заказ.\nСм. ОбработатьЗаказ\nВозвращаемое значение:\n  Булево"; //$NON-NLS-1$

        BslDocSeeChain.SeeLink link = BslDocSeeChain.parse(doc).orElseThrow();

        assertEquals("ОбработатьЗаказ", link.target()); //$NON-NLS-1$
        assertTrue(link.trailing());
        assertTrue(link.typeSectionPresent());
        assertFalse("EDT takes the types from this comment and drops its link", link.followed()); //$NON-NLS-1$
    }

    @Test
    public void ownParametersSectionEndsTheChainAtThisComment() {
        String doc = "See ProcessOrder\nParameters:\n  Ref - DocumentRef"; //$NON-NLS-1$

        assertFalse(BslDocSeeChain.parse(doc).orElseThrow().followed());
    }

    @Test
    public void exampleAndDeprecatedSectionsDoNotBreakTheChain() {
        assertTrue(BslDocSeeChain.parse("См. ОбработатьЗаказ\nПример:\n  ОбработатьЗаказ(Ссылка);") //$NON-NLS-1$
                .orElseThrow().followed());
        assertTrue(BslDocSeeChain.parse("См. ОбработатьЗаказ\nВарианты вызова:\n  На сервере") //$NON-NLS-1$
                .orElseThrow().followed());
    }

    // --- chain walk ----------------------------------------------------------

    @Test
    public void walksThroughBareLinkCommentsUntilTheRoot() {
        BslDocSeeChain.ModuleDocs docs = docs(
                "Обёртка", "См. Промежуточный", //$NON-NLS-1$ //$NON-NLS-2$
                "Промежуточный", "См. Корень", //$NON-NLS-1$ //$NON-NLS-2$
                "Корень", "Возвращаемое значение:\n  Булево"); //$NON-NLS-1$ //$NON-NLS-2$

        BslDocSeeChain.ChainResult result =
                BslDocSeeChain.resolveChain("Обёртка", "См. Промежуточный", docs); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(List.of("Промежуточный", "Корень"), result.chain()); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(result.truncated());
        assertFalse(result.crossModule());
    }

    @Test
    public void stopsAtTheFirstHopThatDeclaresItsOwnSections() {
        BslDocSeeChain.ModuleDocs docs = docs(
                "Обёртка", "См. Промежуточный", //$NON-NLS-1$ //$NON-NLS-2$
                "Промежуточный", "Возвращаемое значение:\n  Булево\nСм. Корень", //$NON-NLS-1$ //$NON-NLS-2$
                "Корень", "См. Дальше"); //$NON-NLS-1$ //$NON-NLS-2$

        BslDocSeeChain.ChainResult result =
                BslDocSeeChain.resolveChain("Обёртка", "См. Промежуточный", docs); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("the middleman's own See is never walked", //$NON-NLS-1$
                List.of("Промежуточный"), result.chain()); //$NON-NLS-1$
    }

    @Test
    public void chainIsEmptyWhenEdtWouldNotFollowTheLink() {
        BslDocSeeChain.ModuleDocs docs = docs("Цель", "Возвращаемое значение:\n  Булево"); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(BslDocSeeChain.resolveChain("Метод", "См. Цель и ещё текст", docs).isEmpty()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(BslDocSeeChain
                .resolveChain("Метод", "См. Цель\nВозвращаемое значение:\n  Число", docs) //$NON-NLS-1$ //$NON-NLS-2$
                .isEmpty());
        assertTrue(BslDocSeeChain.resolveChain("Метод", "Просто описание.", docs).isEmpty()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void cyclicChainTerminatesAndShowsTheLoop() {
        BslDocSeeChain.ModuleDocs docs = docs(
                "А", "См. Б", //$NON-NLS-1$ //$NON-NLS-2$
                "Б", "См. А"); //$NON-NLS-1$ //$NON-NLS-2$

        BslDocSeeChain.ChainResult result = BslDocSeeChain.resolveChain("А", "См. Б", docs); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(List.of("Б", "А"), result.chain()); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(result.truncated());
        assertFalse(result.crossModule());
    }

    @Test
    public void selfReferencingCommentTerminates() {
        BslDocSeeChain.ModuleDocs docs = docs("Метод", "См. Метод"); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(List.of("Метод"), //$NON-NLS-1$
                BslDocSeeChain.resolveChain("Метод", "См. Метод", docs).chain()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void chainDeeperThanTheLimitIsTruncated() {
        Map<String, String> module = new LinkedHashMap<>();
        for (int i = 1; i <= 12; i++) {
            module.put("Метод" + i, "См. Метод" + (i + 1)); //$NON-NLS-1$ //$NON-NLS-2$
        }
        BslDocSeeChain.ModuleDocs docs = BslDocSeeChain.ModuleDocs.of(module);

        BslDocSeeChain.ChainResult result =
                BslDocSeeChain.resolveChain("Метод1", module.get("Метод1"), docs); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(BslDocSeeChain.DEFAULT_MAX_DEPTH, result.chain().size());
        assertEquals("Метод2", result.chain().get(0)); //$NON-NLS-1$
        assertTrue(result.truncated());
        assertFalse(result.crossModule());
    }

    @Test
    public void depthLimitIsConfigurable() {
        Map<String, String> module = new LinkedHashMap<>();
        for (int i = 1; i <= 6; i++) {
            module.put("Метод" + i, "См. Метод" + (i + 1)); //$NON-NLS-1$ //$NON-NLS-2$
        }

        BslDocSeeChain.ChainResult result = BslDocSeeChain.resolveChain(
                "Метод1", module.get("Метод1"), BslDocSeeChain.ModuleDocs.of(module), 2); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(List.of("Метод2", "Метод3"), result.chain()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(result.truncated());
    }

    @Test
    public void qualifiedTargetLeavesTheModule() {
        BslDocSeeChain.ChainResult result = BslDocSeeChain.resolveChain(
                "ЗначениеРеквизита", //$NON-NLS-1$
                "См. ОбщегоНазначения.ЗначениеРеквизитаОбъекта", //$NON-NLS-1$
                docs("ЗначениеРеквизита", "См. ОбщегоНазначения.ЗначениеРеквизитаОбъекта")); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(List.of("ОбщегоНазначения.ЗначениеРеквизитаОбъекта"), result.chain()); //$NON-NLS-1$
        assertTrue(result.crossModule());
        assertFalse(result.truncated());
    }

    @Test
    public void targetThisModuleDoesNotDeclareLeavesTheModule() {
        BslDocSeeChain.ChainResult result =
                BslDocSeeChain.resolveChain("Метод", "См. НетТакого", docs("Метод", "См. НетТакого")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals(List.of("НетТакого"), result.chain()); //$NON-NLS-1$
        assertTrue(result.crossModule());
    }

    @Test
    public void trailingPeriodMakesTheTargetUnresolvable() {
        // EDT keeps the period inside the link text and then splits on ".", so the link dangles.
        BslDocSeeChain.ChainResult result =
                BslDocSeeChain.resolveChain("Метод", "См. ОбработатьЗаказ.", //$NON-NLS-1$ //$NON-NLS-2$
                        docs("Метод", "См. ОбработатьЗаказ.", "ОбработатьЗаказ", null)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals(List.of("ОбработатьЗаказ."), result.chain()); //$NON-NLS-1$
        assertTrue(result.crossModule());
    }

    @Test
    public void moduleLookupIsCaseInsensitive() {
        BslDocSeeChain.ModuleDocs docs = docs(
                "ОбработатьЗаказ", "Возвращаемое значение:\n  Булево"); //$NON-NLS-1$ //$NON-NLS-2$

        BslDocSeeChain.ChainResult result =
                BslDocSeeChain.resolveChain("Метод", "См. обработатьзаказ", docs); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(List.of("обработатьзаказ"), result.chain()); //$NON-NLS-1$
        assertFalse("resolved inside the module despite the casing", result.crossModule()); //$NON-NLS-1$
    }

    @Test
    public void undocumentedTargetIsStillAModuleMember() {
        BslDocSeeChain.ChainResult result = BslDocSeeChain.resolveChain(
                "Метод", "См. Помощник", docs("Метод", "См. Помощник", "Помощник", null)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertEquals(List.of("Помощник"), result.chain()); //$NON-NLS-1$
        assertFalse("declared without a comment is not the same as declared elsewhere", //$NON-NLS-1$
                result.crossModule());
    }

    @Test
    public void missingModuleIndexIsTolerated() {
        BslDocSeeChain.ChainResult result = BslDocSeeChain.resolveChain(null, "См. Цель", null); //$NON-NLS-1$

        assertEquals(List.of("Цель"), result.chain()); //$NON-NLS-1$
        assertTrue(result.crossModule());
        assertTrue(BslDocSeeChain.resolveChain(null, null, BslDocSeeChain.ModuleDocs.empty()).isEmpty());
    }

    @Test
    public void chainResultIsImmutable() {
        BslDocSeeChain.ChainResult result =
                BslDocSeeChain.resolveChain("Метод", "См. Цель", BslDocSeeChain.ModuleDocs.empty()); //$NON-NLS-1$ //$NON-NLS-2$

        try {
            result.chain().add("Ещё"); //$NON-NLS-1$
            throw new AssertionError("chain must not be mutable"); //$NON-NLS-1$
        } catch (UnsupportedOperationException expected) {
            // expected
        }
    }

    private static BslDocSeeChain.ModuleDocs docs(String... nameThenDoc) {
        Map<String, String> module = new LinkedHashMap<>();
        for (int i = 0; i + 1 < nameThenDoc.length; i += 2) {
            module.put(nameThenDoc[i], nameThenDoc[i + 1]);
        }
        return BslDocSeeChain.ModuleDocs.of(module);
    }
}
