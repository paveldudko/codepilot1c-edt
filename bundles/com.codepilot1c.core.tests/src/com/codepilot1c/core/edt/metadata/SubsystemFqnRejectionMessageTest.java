package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

/**
 * Unit + source-contract test for the dotted-subsystem-FQN refusal (F2).
 *
 * <p>What was broken (live 2026-07-28): {@code Subsystem.<Parent>.<Child>} was rejected with "Nested
 * FQN segments must be marker/name pairs", which reads as "you forgot the marker" — while the
 * refusal said nothing about the flat form that does resolve.</p>
 *
 * <p>What the first repair then got wrong: it answered with "Subsystem FQNs are FLAT at any nesting
 * depth … no dotted form built from the parent resolves, so never pass one." Both halves are false.
 * {@code Subsystem.<Parent>.Subsystem.<Child>} is the FQN a nested subsystem is REGISTERED under —
 * decompiled from {@code MdTopObjectFqnGeneratorDelegate}, and live-confirmed 2026-07-29:
 * {@code update_metadata} accepted {@code Subsystem.WaveParent.Subsystem.WaveR8P} and answered with
 * that FQN. The flat form is a name-based ALIAS our resolvers walk the tree for, not the canonical
 * address. The denial also contradicted {@code SubsystemTree.describeAmbiguity}, which resolves an
 * ambiguous flat name by pointing at exactly the chain this message called nonexistent.</p>
 *
 * <p>So the message names BOTH working forms, and these tests pin that neither the original
 * marker/name-pair dead end nor the over-corrected flat-only claim comes back.</p>
 *
 * <p>The general marker/name-pair rule is correct for every kind that owns containment children and
 * is deliberately left alone.</p>
 */
public class SubsystemFqnRejectionMessageTest {

    private static final String SERVICE_PATH =
            "bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/metadata/EdtMetadataService.java"; //$NON-NLS-1$

    // --- pure message semantics ---------------------------------------------

    @Test
    public void aSubsystemHeadIsGivenBothFormsThatResolve() {
        String message = SubsystemTree.nestedFqnRejectionMessage("Subsystem.WaveParent.WaveChild", true); //$NON-NLS-1$
        assertTrue("the flat alias must be named", message.contains("Subsystem.<Name>")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("and so must the registered chain, which also resolves", //$NON-NLS-1$
                message.contains("Subsystem.<Parent>.Subsystem.<Name>")); //$NON-NLS-1$
        assertTrue("the offending FQN must still be quoted back", //$NON-NLS-1$
                message.contains("Subsystem.WaveParent.WaveChild")); //$NON-NLS-1$
    }

    @Test
    public void aSubsystemHeadIsNotLeftWithTheGenericPairRule() {
        String message = SubsystemTree.nestedFqnRejectionMessage("Subsystem.WaveParent.WaveChild", true); //$NON-NLS-1$
        // The original text: "marker/name pairs" alone told the caller nothing about the flat form.
        assertFalse("the bare marker/name-pair advice must not be the whole answer", //$NON-NLS-1$
                message.contains("marker/name pairs")); //$NON-NLS-1$
    }

    @Test
    public void theRegisteredChainIsNotDeniedAsAnAddress() {
        String message = SubsystemTree.nestedFqnRejectionMessage("Subsystem.WaveParent.WaveChild", true); //$NON-NLS-1$
        // The over-correction, refuted live 2026-07-29 — update_metadata takes the dotted chain.
        assertFalse("the flat-only claim must be gone", message.contains("FLAT at any nesting depth")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("and so must the denial that any dotted form resolves", //$NON-NLS-1$
                message.contains("no dotted form")); //$NON-NLS-1$
        assertFalse("in either wording", message.contains("never pass one")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void everyOtherKindKeepsTheGeneralRule() {
        String message = SubsystemTree.nestedFqnRejectionMessage("Catalog.Foo.Attribute", false); //$NON-NLS-1$
        assertEquals("Nested FQN segments must be marker/name pairs: Catalog.Foo.Attribute", message); //$NON-NLS-1$
    }

    // --- wiring -------------------------------------------------------------

    @Test
    public void bothFqnWalkersUseTheSharedMessage() {
        String source = readSource(SERVICE_PATH);
        assertEquals("the configuration walker and the external-object walker must agree", //$NON-NLS-1$
                2, countOccurrences(source,
                        "SubsystemTree.nestedFqnRejectionMessage(fqn, isSubsystemFqnHead(parts[0]))")); //$NON-NLS-1$
        assertFalse("no walker may keep an inlined copy of the general text", //$NON-NLS-1$
                source.contains("\"Nested FQN segments must be marker/name pairs: \" + fqn")); //$NON-NLS-1$
    }

    @Test
    public void theSubsystemHeadIsDetectedThroughMetadataKind() {
        String body = methodBody(readSource(SERVICE_PATH), "private boolean isSubsystemFqnHead("); //$NON-NLS-1$
        assertTrue("detection must reuse the kind parser, not a hand-rolled token list", //$NON-NLS-1$
                body.contains("MetadataKind.fromString(typeToken) == MetadataKind.SUBSYSTEM")); //$NON-NLS-1$
        // fromString throws on an unknown token; an unrecognised head must simply keep the general
        // message rather than turn a resolution failure into a kind error.
        assertTrue(body.contains("return false;")); //$NON-NLS-1$
    }

    // --- Helpers ------------------------------------------------------------

    private int countOccurrences(String haystack, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int at = haystack.indexOf(needle, from);
            if (at < 0) {
                return count;
            }
            count++;
            from = at + needle.length();
        }
    }

    private String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue("method not found in source: " + signature, start >= 0); //$NON-NLS-1$
        int end = source.indexOf("\n    }", start); //$NON-NLS-1$
        assertTrue("method end not found for: " + signature, end > start); //$NON-NLS-1$
        return source.substring(start, end);
    }

    private String readSource(String relativePath) {
        try {
            return Files.readString(findRepoRoot().resolve(relativePath), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot read " + relativePath, e); //$NON-NLS-1$
        }
    }

    private Path findRepoRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize(); //$NON-NLS-1$
        while (current != null) {
            if (Files.isDirectory(current.resolve("bundles")) //$NON-NLS-1$
                    && Files.isRegularFile(current.resolve("pom.xml")) //$NON-NLS-1$
                    && Files.isRegularFile(current.resolve("LICENSE"))) { //$NON-NLS-1$
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Cannot locate repository root"); //$NON-NLS-1$
    }
}
