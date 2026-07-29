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
 * <p>What was broken (live 2026-07-28): NO dotted subsystem FQN resolves, yet the refusal pointed at
 * one. {@code Subsystem.<Parent>.<Child>} was rejected with "Nested FQN segments must be
 * marker/name pairs", which reads as "you forgot the marker" and sends the caller to
 * {@code Subsystem.<Parent>.Subsystem.<Child>} — a dead end that fails again, with a different code.
 * A subsystem's canonical FQN is FLAT at any depth, because both subsystem collections are
 * non-containment and every nested subsystem is its own top object, so the fix is to DROP the parent
 * segments — not to add a marker.</p>
 *
 * <p>The general marker/name-pair rule is correct for every kind that owns containment children and
 * is deliberately left alone.</p>
 */
public class SubsystemFqnRejectionMessageTest {

    private static final String SERVICE_PATH =
            "bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/metadata/EdtMetadataService.java"; //$NON-NLS-1$

    // --- pure message semantics ---------------------------------------------

    @Test
    public void aSubsystemHeadIsToldToGoFlat() {
        String message = SubsystemTree.nestedFqnRejectionMessage("Subsystem.WaveParent.WaveChild", true); //$NON-NLS-1$
        assertTrue("the flat canonical form must be named", message.contains("Subsystem.<Name>")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("and it must say the form holds at any depth", //$NON-NLS-1$
                message.contains("FLAT at any nesting depth")); //$NON-NLS-1$
        assertTrue("with the reason, so the caller can generalize", //$NON-NLS-1$
                message.contains("each subsystem is its own top object")); //$NON-NLS-1$
        assertTrue("the offending FQN must still be quoted back", //$NON-NLS-1$
                message.contains("Subsystem.WaveParent.WaveChild")); //$NON-NLS-1$
    }

    @Test
    public void aSubsystemHeadIsNeverSentToThePairedForm() {
        String message = SubsystemTree.nestedFqnRejectionMessage("Subsystem.WaveParent.WaveChild", true); //$NON-NLS-1$
        // The old text's advice; following it produces a second failure, so it must be gone.
        assertFalse("the marker/name-pair advice must not appear for a subsystem", //$NON-NLS-1$
                message.contains("marker/name pairs")); //$NON-NLS-1$
        assertFalse("nor may a dotted alias be suggested", //$NON-NLS-1$
                message.contains("Subsystem.<Parent>.Subsystem")); //$NON-NLS-1$
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
