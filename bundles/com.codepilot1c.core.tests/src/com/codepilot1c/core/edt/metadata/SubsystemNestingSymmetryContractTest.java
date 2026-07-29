package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

/**
 * Source-contract test for two-sided subsystem nesting (F1).
 *
 * <p>Ground truth from an EDT-authored configuration (live 2026-07-28, 132 {@code .mdo}: 98 with
 * {@code parentSubsystem}, 22 with {@code subsystems}): EDT writes nesting on BOTH sides — the
 * parent lists {@code <subsystems>WaveChild</subsystems>} by BARE NAME, the child carries
 * {@code <parentSubsystem>Subsystem.WaveParent</parentSubsystem>} as a FLAT FQN. They are two
 * independent non-containment references with no {@code EOpposite}.</p>
 *
 * <p>What was broken: {@code update_metadata set.parentSubsystem} wrote only the child side, so
 * {@code WaveParent.subsystems} stayed empty — the nesting was HALF-LINKED and the parent could not
 * see its child, while the metadata tree and the command interface both read the parent side. The
 * far side also lives in a DIFFERENT {@code .mdo}, so it needs its own export target and its own
 * EOL snapshot or the change never reaches disk.</p>
 *
 * <p>{@code Subsystem} and the BM write transaction resolve only in the OSGi runtime, so the wiring
 * is pinned by reading the source.</p>
 */
public class SubsystemNestingSymmetryContractTest {

    private static final String SERVICE_PATH =
            "bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/metadata/EdtMetadataService.java"; //$NON-NLS-1$

    // --- both nesting features are intercepted ------------------------------

    @Test
    public void bothNestingFeaturesAreRoutedThroughTheSymmetricWriter() {
        String source = readSource(SERVICE_PATH);
        String detector = methodBody(source, "private boolean isSubsystemNestingFeature("); //$NON-NLS-1$
        assertTrue(detector.contains("\"parentsubsystem\".equals(token)")); //$NON-NLS-1$
        assertTrue(detector.contains("\"subsystems\".equals(token)")); //$NON-NLS-1$

        String setter = methodBody(source, "private void setFeatureValue("); //$NON-NLS-1$
        int intercept = setter.indexOf(
                "if (target instanceof Subsystem subsystem && isSubsystemNestingFeature(reference)) {"); //$NON-NLS-1$
        int generic = setter.indexOf("applyReferenceValue(configuration, target, reference, value, transaction)"); //$NON-NLS-1$
        assertTrue("the nesting interception must exist", intercept >= 0); //$NON-NLS-1$
        assertTrue("it must run BEFORE the generic reference write, which writes one side only", //$NON-NLS-1$
                intercept < generic);
    }

    // --- child side written when the parent list is set ---------------------

    @Test
    public void settingSubsystemsRepointsEveryListedChild() {
        String body = methodBody(readSource(SERVICE_PATH), "private void applySubsystemChildren("); //$NON-NLS-1$
        assertTrue("every listed child must get the parent pointer", //$NON-NLS-1$
                body.contains("child.setParentSubsystem(parent);")); //$NON-NLS-1$
        assertTrue("a child dropped from the list must lose its pointer, not keep a dangling one", //$NON-NLS-1$
                body.contains("dropped.setParentSubsystem(null);")); //$NON-NLS-1$
        assertTrue("a child taken from another parent must leave that parent's list", //$NON-NLS-1$
                body.contains("removeSubsystemChild(previous, child)")); //$NON-NLS-1$
    }

    // --- parent side written when the child pointer is set ------------------

    @Test
    public void settingParentSubsystemAlsoAddsTheChildToTheParentList() {
        String body = methodBody(readSource(SERVICE_PATH), "private void reparentSubsystem("); //$NON-NLS-1$
        assertTrue("the parent list is the side that used to be missed", //$NON-NLS-1$
                body.contains("addSubsystemChild(newParent, child)")); //$NON-NLS-1$
        assertTrue("a move must drop the child from the old parent", //$NON-NLS-1$
                body.contains("removeSubsystemChild(oldParent, child)")); //$NON-NLS-1$
        // Membership is ensured even when the pointer already matched: that IS the half-linked
        // state left behind by every earlier write, and a re-run must repair it.
        int pointerWrite = body.indexOf("child.setParentSubsystem(newParent);"); //$NON-NLS-1$
        int membership = body.indexOf("addSubsystemChild(newParent, child)"); //$NON-NLS-1$
        assertTrue(pointerWrite >= 0 && membership > pointerWrite);
    }

    @Test
    public void aBlankParentValueDetachesInsteadOfFailing() {
        String body = methodBody(readSource(SERVICE_PATH), "private Subsystem resolveSubsystemValue("); //$NON-NLS-1$
        assertTrue("null means detach", body.contains("if (value == null) {")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("so does a blank FQN — resolveSingleReferenceValue would refuse it", //$NON-NLS-1$
                body.contains("if (fqn != null && fqn.isBlank()) {")); //$NON-NLS-1$
        assertTrue("a resolved non-Subsystem must be refused, not silently ignored", //$NON-NLS-1$
                body.contains("Referenced object is not a Subsystem for field ")); //$NON-NLS-1$
    }

    @Test
    public void unsettingEitherSideClearsBoth() {
        String body = methodBody(readSource(SERVICE_PATH), "private void unsetFeatureValue("); //$NON-NLS-1$
        assertTrue("unset must route through the symmetric writer, not eUnset one side", //$NON-NLS-1$
                body.contains("applySubsystemNesting(configuration, subsystem, reference, null, coEditedTopObjectSink)")); //$NON-NLS-1$
    }

    // --- idempotency --------------------------------------------------------

    @Test
    public void repeatingTheSameRequestChangesNothing() {
        String source = readSource(SERVICE_PATH);
        String add = methodBody(source, "private boolean addSubsystemChild("); //$NON-NLS-1$
        assertTrue("membership must be tested before adding, or a re-run duplicates the entry", //$NON-NLS-1$
                add.contains("if (containsSubsystem(parent.getSubsystems(), child)) {")); //$NON-NLS-1$
        assertTrue("and the caller must be able to tell nothing changed", add.contains("return false;")); //$NON-NLS-1$ //$NON-NLS-2$

        String children = methodBody(source, "private void applySubsystemChildren("); //$NON-NLS-1$
        assertTrue("an unchanged list must not be cleared and rebuilt — that churns the .mdo", //$NON-NLS-1$
                children.contains("if (!sameSubsystemList(current, requested)) {")); //$NON-NLS-1$
        assertTrue("duplicates in the request must collapse", //$NON-NLS-1$
                children.contains("!containsSubsystem(requested, subsystem)")); //$NON-NLS-1$
    }

    @Test
    public void identityIsTheFlatNameNotTheJavaInstance() {
        String body = methodBody(readSource(SERVICE_PATH), "private boolean sameSubsystem("); //$NON-NLS-1$
        // A value resolved inside the write transaction is not the same handle as the one already
        // in the list, so instance equality would make every write look like a move.
        assertTrue(body.contains("leftName.equalsIgnoreCase(rightName)")); //$NON-NLS-1$
    }

    // --- the far side reaches disk ------------------------------------------

    @Test
    public void theCoEditedTopObjectIsExportedAndEolGuarded() {
        String source = readSource(SERVICE_PATH);
        String update = methodBody(source, "public MetadataOperationResult updateMetadata("); //$NON-NLS-1$
        assertTrue("update_metadata must collect the co-edited FQNs", //$NON-NLS-1$
                update.contains("Set<String> coEditedFqns = new LinkedHashSet<>();")); //$NON-NLS-1$
        assertTrue("and hand the sink to the change applier", //$NON-NLS-1$
                update.contains("transaction, capturedTypes, coEditedSink)")); //$NON-NLS-1$
        assertTrue("and export them in the same batch as the target", //$NON-NLS-1$
                update.contains("forceExportTopLevelObjects(project, topLevelFqn, coEditedFqns, opId);")); //$NON-NLS-1$
        assertTrue("the EOL of the far side must be snapshotted as soon as it is known", //$NON-NLS-1$
                update.contains("eolGuard.addCoEditedFqn(coEditedFqn)")); //$NON-NLS-1$

        String targets = methodBody(source, "private List<String> buildExportTargets(String fqn, Collection<String> extraFqns)"); //$NON-NLS-1$
        assertTrue("every extra FQN must land in the export target set", //$NON-NLS-1$
                targets.contains("targets.add(extraFqn);")); //$NON-NLS-1$
        assertTrue("Configuration must stay in the set", targets.contains("targets.add(\"Configuration\")")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("the guard must restore every FQN it was extended with, not just the first", //$NON-NLS-1$
                source.contains("for (String guardedFqn : guardedFqns) {")); //$NON-NLS-1$
    }

    @Test
    public void theReportedFqnIsFlat() {
        String body = methodBody(readSource(SERVICE_PATH), "private void reportCoEditedSubsystem("); //$NON-NLS-1$
        // A nested subsystem is its own top object under the flat FQN; anything dotted would not
        // name an export target at all.
        assertTrue(body.contains("MetadataKind.SUBSYSTEM.getFqnPrefix() + \".\" + subsystem.getName()")); //$NON-NLS-1$
    }

    @Test
    public void createMetadataAlsoExportsTheParentItLinkedInto() {
        String body = methodBody(readSource(SERVICE_PATH), "public CreateMetadataOutcome createMetadataDetailed("); //$NON-NLS-1$
        assertTrue("a created subsystem given parentSubsystem rewrites the parent's .mdo too", //$NON-NLS-1$
                body.contains("forceExportTopLevelObjects(project, fqn, coEditedFqns, opId);")); //$NON-NLS-1$
    }

    // --- Helpers ------------------------------------------------------------

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
