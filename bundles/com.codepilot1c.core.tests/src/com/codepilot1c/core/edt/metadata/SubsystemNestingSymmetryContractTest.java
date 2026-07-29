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
                body.contains("configuration, subsystem, reference, null, transaction, coEditedTopObjectSink)")); //$NON-NLS-1$
    }

    // --- idempotency --------------------------------------------------------

    @Test
    public void repeatingTheSameRequestChangesNothing() {
        String source = readSource(SERVICE_PATH);
        String add = methodBody(source, "private boolean addSubsystemChild("); //$NON-NLS-1$
        assertTrue("membership must be tested before adding, or a re-run duplicates the entry", //$NON-NLS-1$
                add.contains("if (containsSubsystem(parent.getSubsystems(), child)) {")); //$NON-NLS-1$
        assertTrue("and the caller must be able to tell nothing changed", add.contains("return pruned;")); //$NON-NLS-1$ //$NON-NLS-2$

        String children = methodBody(source, "private void applySubsystemChildren("); //$NON-NLS-1$
        assertTrue("an unchanged list must not be cleared and rebuilt — that churns the .mdo", //$NON-NLS-1$
                children.contains("if (!sameSubsystemList(current, requested)) {")); //$NON-NLS-1$
        assertTrue("duplicates in the request must collapse", //$NON-NLS-1$
                children.contains("!containsSubsystem(requested, subsystem)")); //$NON-NLS-1$
    }

    @Test
    public void identityIsNotTheJavaInstance() {
        String body = methodBody(readSource(SERVICE_PATH), "private boolean sameSubsystem("); //$NON-NLS-1$
        // A value resolved inside the write transaction is not the same handle as the one already
        // in the list, so instance equality would make every write look like a move. What the
        // identity itself is made of — a name where one is readable, the proxy URI where it is not —
        // is decided by SubsystemIdentity and tested by result in SubsystemIdentityTest; asserting
        // its internals here would only pin one more copy of the rule to the source text.
        assertTrue(body.contains("SubsystemIdentity.same(")); //$NON-NLS-1$
    }

    /**
     * The third side: {@code Configuration.subsystems} lists the ROOTS only. Ground truth from
     * Accounting management (live 2026-07-29): 34 root entries in {@code Configuration.mdo}, and the
     * nested {@code AccessManagement} / {@code Calendar} / {@code Bonuses} appear in none of them,
     * while their root {@code StandardSubsystems} does. Gaining a parent therefore has to drop the
     * subsystem from the root, and losing one has to put it back — or the subsystem leaves the
     * configuration altogether.
     */
    @Test
    public void gainingOrLosingAParentUpdatesTheConfigurationRoot() {
        String source = readSource(SERVICE_PATH);
        String reparent = methodBody(source, "private void reparentSubsystem("); //$NON-NLS-1$
        assertTrue("gaining a parent must take the subsystem off the root", //$NON-NLS-1$
                reparent.contains("detachRootAndRelocate(configuration, child, newParent, transaction")); //$NON-NLS-1$
        assertTrue("and losing one must put it back", //$NON-NLS-1$
                reparent.contains("setConfigurationRootMembership(configuration, child, true)")); //$NON-NLS-1$

        String children = methodBody(source, "private void applySubsystemChildren("); //$NON-NLS-1$
        assertTrue("a child dropped from the list becomes a root again", //$NON-NLS-1$
                children.contains("setConfigurationRootMembership(configuration, dropped, true)")); //$NON-NLS-1$
        assertTrue("and a child added to the list stops being one", //$NON-NLS-1$
                children.contains("detachRootAndRelocate(configuration, child, parent, transaction")); //$NON-NLS-1$

        String remove = methodBody(source, "private void removeSubsystemLinks("); //$NON-NLS-1$
        assertTrue("deleting a subsystem must unlink it from whatever parent lists it, not just the root", //$NON-NLS-1$
                remove.contains("removeSubsystemByIdentity(subsystem.getSubsystems(), name)")); //$NON-NLS-1$
    }

    /**
     * The FOURTH side, and the one {@code ce4bf06} left out: a re-parented subsystem's own STORAGE
     * has to move too.
     *
     * <p>Decompiled from EDT 2025.2.3 ({@code MdRefactoringService.SubsystemMoveOperation}): the move
     * calls {@code updateTopObjectFqn} with the FQN of the new owner's slot before it touches the
     * owner's list, and EDT routes the whole thing through
     * {@code IRefactoringService.initiateRename} with the same name — a move IS an FQN rename. Drop
     * that step and the child stays registered as {@code Subsystem.<Name>} while its parent's
     * {@code .mdo} refers to it by bare name, which resolves against
     * {@code <parentFqn>.Subsystem.<Name>}: the down-link becomes a nameless stub, no name-based
     * lookup matches, and {@code update_metadata} failed post-verify on a mutation whose files were
     * right.</p>
     */
    @Test
    public void reParentingRelocatesTheSubsystemStorage() {
        String source = readSource(SERVICE_PATH);
        String relocate = methodBody(source, "private boolean relocateSubsystemStorage("); //$NON-NLS-1$
        assertTrue("the BM re-registration is the whole point", //$NON-NLS-1$
                relocate.contains("transaction.updateTopObjectFqn(bmChild, targetFqn)")); //$NON-NLS-1$
        assertTrue("the target slot must come from the shared FQN rule, not a local string build", //$NON-NLS-1$
                relocate.contains("SubsystemTree.qualifiedName(ownerFqn, child.getName())")); //$NON-NLS-1$
        assertTrue("a slot already taken must be refused with an actionable message, not swallowed", //$NON-NLS-1$
                relocate.contains("catch (BmFqnAlreadyInUseException e)")); //$NON-NLS-1$

        // Idempotency: an object already in the right slot must not be re-registered.
        int compare = relocate.indexOf("if (targetFqn.equals(currentFqn))"); //$NON-NLS-1$
        int update = relocate.indexOf("transaction.updateTopObjectFqn("); //$NON-NLS-1$
        assertTrue("the current FQN must be compared before any update", compare >= 0 && compare < update); //$NON-NLS-1$
    }

    /**
     * A move takes the whole subtree with it. {@code updateTopObjectFqn} re-registers the ONE object
     * it is handed, and a subsystem's children are separate top objects with chains of their own — so
     * moving only the subsystem the request named leaves every descendant keyed under a chain whose
     * root is gone.
     *
     * <p>Live-measured 2026-07-29 on the sandbox: {@code WaveR8P} holding {@code WaveR8C} moved under
     * {@code WaveParent}; afterwards {@code WaveR8P.subsystems} read back as a NAMELESS stub and
     * {@code Subsystem.WaveR8C} answered "Object not found" to {@code edt_metadata_details} AND to
     * {@code update_metadata} — an unaddressable object no tool could repair. Same class as the
     * half-linked move above, one level down.</p>
     *
     * <p>What only the source can show is the ORDER: the subtree's FQNs are bare-name down-links
     * resolved against the owner's FQN, so they stop resolving the moment the owner moves. Read the
     * plan after the move and it is empty — the cascade would silently do nothing. What the plan
     * CONTAINS is decided by {@link SubsystemTree#descendantRelocations} and tested by result.</p>
     */
    @Test
    public void aMoveReRegistersEverySubsystemBelowTheMovedOne() {
        String source = readSource(SERVICE_PATH);
        String relocate = methodBody(source, "private boolean relocateSubsystemStorage("); //$NON-NLS-1$
        int plan = relocate.indexOf("SubsystemTree.descendantRelocations("); //$NON-NLS-1$
        int move = relocate.indexOf("transaction.updateTopObjectFqn(bmChild, targetFqn)"); //$NON-NLS-1$
        int cascade = relocate.indexOf("relocateSubsystemDescendants(transaction, child, descendants"); //$NON-NLS-1$
        assertTrue("the subtree must be read while its down-links still resolve — before the move", //$NON-NLS-1$
                plan >= 0 && plan < move);
        assertTrue("and re-registered after it, against the owner's new chain", cascade > move); //$NON-NLS-1$

        String descendants = methodBody(source, "private void relocateSubsystemDescendants("); //$NON-NLS-1$
        assertTrue("each descendant must be re-registered under the slot the plan named", //$NON-NLS-1$
                descendants.contains("transaction.updateTopObjectFqn(bmDescendant, targetFqn)")); //$NON-NLS-1$
        assertTrue("its new FQN must reach the export, or the .mdo stays at the old path", //$NON-NLS-1$
                descendants.contains("reportCoEditedFqn(targetFqn, coEditedTopObjectSink)")); //$NON-NLS-1$
        assertTrue("and its vacated path recorded, or the old file survives as a duplicate", //$NON-NLS-1$
                descendants.contains("reportStorageRelocated(previousFqn, targetFqn, coEditedTopObjectSink)")); //$NON-NLS-1$
        assertTrue("a taken slot must abort the move, not leave the tree half re-registered", //$NON-NLS-1$
                descendants.contains("catch (BmFqnAlreadyInUseException e)")); //$NON-NLS-1$
    }

    /**
     * The fifth thing a move has to do, and the one the relocation alone left undone: drop the file
     * it vacated. The export writes the {@code .mdo} at the new path and leaves the old one where it
     * was, so the configuration carries the subsystem's definition twice (live 2026-07-29:
     * {@code src/Subsystems/WaveR6Child/WaveR6Child.mdo} survived a move under {@code WaveParent}).
     *
     * <p>Only the ORDER is pinned here — what may be deleted is decided by
     * {@link VacatedSubsystemStorage} and tested by result. The order is the safety property no unit
     * test can see: the cleanup's guard is "the new descriptor exists", and only the export creates
     * it, so running the cleanup before the export would delete the only copy.</p>
     */
    @Test
    public void theVacatedStorageIsCleanedUpAfterTheExportNotBefore() {
        String source = readSource(SERVICE_PATH);
        String relocate = methodBody(source, "private boolean relocateSubsystemStorage("); //$NON-NLS-1$
        assertTrue("the vacated FQN has to survive the transaction, and only the sink leaves it", //$NON-NLS-1$
                relocate.contains("reportStorageRelocated(currentFqn, targetFqn, coEditedTopObjectSink)")); //$NON-NLS-1$

        for (String flow : new String[] {
                "public MetadataOperationResult updateMetadata(", //$NON-NLS-1$
                "public MetadataOperationResult deleteMetadata(", //$NON-NLS-1$
                "public CreateMetadataOutcome createMetadataDetailed(" }) { //$NON-NLS-1$
            String body = methodBody(source, flow);
            int export = body.indexOf("forceExportTopLevelObjects(project,"); //$NON-NLS-1$
            int cleanup = body.indexOf("cleanupVacatedSubsystemStorage(project, coEditedSink.relocations(), opId)"); //$NON-NLS-1$
            assertTrue("every flow that can relocate storage must clean up after it: " + flow, //$NON-NLS-1$
                    cleanup >= 0);
            assertTrue("and only once the export has written the new descriptor: " + flow, //$NON-NLS-1$
                    export >= 0 && cleanup > export);
        }
    }

    /**
     * The root entry is the last thing keeping a subsystem addressable, so it may only be dropped
     * once the storage has really moved — and must come back when it has not. Double registration is
     * cosmetically wrong; no registration that resolves is a lost object.
     */
    @Test
    public void theRootEntryIsOnlyGivenUpAgainstASuccessfulRelocation() {
        String body = methodBody(readSource(SERVICE_PATH), "private boolean detachRootAndRelocate("); //$NON-NLS-1$
        int drop = body.indexOf("setConfigurationRootMembership(configuration, child, false)"); //$NON-NLS-1$
        int relocate = body.indexOf("relocateSubsystemStorage(transaction, child, newParent"); //$NON-NLS-1$
        assertTrue("EDT's own move detaches from the old owner before re-keying the FQN", //$NON-NLS-1$
                drop >= 0 && drop < relocate);
        assertTrue("a failed relocation must restore the root entry", //$NON-NLS-1$
                body.contains("setConfigurationRootMembership(configuration, child, true)")); //$NON-NLS-1$
        assertTrue("and say so, because the result is a double registration on purpose", //$NON-NLS-1$
                body.contains("stays registered at the configuration root")); //$NON-NLS-1$
    }

    /**
     * Deleting a subsystem edits its parents' {@code .mdo} files, which are separate top objects.
     * Sweeping BM alone left the dangling {@code <subsystems>} line on disk, because the only export
     * target was the object that had just ceased to exist.
     */
    @Test
    public void deleteExportsEveryParentItUnlinkedFrom() {
        String source = readSource(SERVICE_PATH);
        String delete = methodBody(source, "public MetadataOperationResult deleteMetadata("); //$NON-NLS-1$
        assertTrue("delete must collect co-edited FQNs like update does", //$NON-NLS-1$
                delete.contains("Set<String> coEditedFqns = new LinkedHashSet<>();")); //$NON-NLS-1$
        assertTrue("and hand the sink to the unlinker", //$NON-NLS-1$
                delete.contains("removeMetadataObject(txConfiguration, targetFqn, target, coEditedSink)")); //$NON-NLS-1$
        assertTrue("and export them in the same batch", //$NON-NLS-1$
                delete.contains("forceExportTopLevelObjects(project, topLevelFqn, coEditedFqns, opId);")); //$NON-NLS-1$
        assertTrue("the storage FQN must be captured while the object is still linked", //$NON-NLS-1$
                delete.contains("storageFqnHolder[0] = topObjectStorageFqn(target);")); //$NON-NLS-1$
        assertTrue("and drive the filesystem cleanup, which the request's flat FQN cannot locate", //$NON-NLS-1$
                delete.contains("cleanupRemovedFilesystemArtifacts(project, targetFqn, storageFqnHolder[0], opId)")); //$NON-NLS-1$

        String unlink = methodBody(source, "private void removeSubsystemLinks("); //$NON-NLS-1$
        assertTrue("every parent that loses a line must be reported", //$NON-NLS-1$
                unlink.contains("reportCoEditedSubsystem(subsystem, coEditedTopObjectSink)")); //$NON-NLS-1$
    }

    /**
     * One unresolvable target used to sink the whole export batch. After a relocation the old FQN IS
     * unresolvable, so the per-target retry is what keeps the co-edited far side reaching disk.
     */
    @Test
    public void theExportBatchFallsBackPerTargetNotJustToConfiguration() {
        String body = methodBody(readSource(SERVICE_PATH), "private void forceExportTopLevelObjects("); //$NON-NLS-1$
        assertTrue(body.contains("for (String target : targets) {")); //$NON-NLS-1$
        assertTrue(body.contains("exported |= modelManager.forceExport(dtProject, target);")); //$NON-NLS-1$
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
    public void theReportedFqnIsTheOneTheObjectIsRegisteredUnder() {
        String body = methodBody(readSource(SERVICE_PATH), "private void reportCoEditedSubsystem("); //$NON-NLS-1$
        // An export target is looked up in the BM FQN registry, and a NESTED subsystem is registered
        // under its owner chain — not under the flat form our resolvers accept. Reporting the flat
        // form for a nested parent named no top object, so its .mdo was never written.
        int registered = body.indexOf("subsystemStorageFqn(subsystem)"); //$NON-NLS-1$
        int flatFallback = body.indexOf("MetadataKind.SUBSYSTEM.getFqnPrefix()"); //$NON-NLS-1$
        assertTrue("the registered FQN must be preferred", registered >= 0); //$NON-NLS-1$
        assertTrue("the flat form stays only as the fallback for when BM cannot answer", //$NON-NLS-1$
                flatFallback > registered);
    }

    @Test
    public void createMetadataAlsoExportsTheParentItLinkedInto() {
        String body = methodBody(readSource(SERVICE_PATH), "public CreateMetadataOutcome createMetadataDetailed("); //$NON-NLS-1$
        assertTrue("a created subsystem given parentSubsystem rewrites the parent's .mdo too", //$NON-NLS-1$
                body.contains("forceExportTopLevelObjects(project, storageFqn, coEditedFqns, opId);")); //$NON-NLS-1$
    }

    /**
     * create_metadata with a {@code parentSubsystem} nests the object during the write, which moves
     * its FQN — so everything after the write has to follow it there.
     *
     * <p>Two traps, both hit only once nesting really works. The post-write BM lookups use the FQN
     * built from the request, which after a relocation resolves to nothing; and the relink sweeps the
     * object out of every collection and re-adds it at the ROOT, which would undo the nesting the same
     * write just established. (The relink was harmless only for as long as the sweep matched by name
     * and silently missed the parent's proxy entries.)</p>
     */
    @Test
    public void aCreatedNestedSubsystemIsNotRerootedAfterwards() {
        String source = readSource(SERVICE_PATH);
        String create = methodBody(source, "public CreateMetadataOutcome createMetadataDetailed("); //$NON-NLS-1$
        assertTrue("the FQN the object ended up registered under must be captured", //$NON-NLS-1$
                create.contains("storageFqnHolder[0] = topObjectStorageFqn(txObject);")); //$NON-NLS-1$
        assertTrue("and used for the BM post-verify", //$NON-NLS-1$
                create.contains("verifyTopLevelPersisted(project, storageFqn, opId);")); //$NON-NLS-1$
        assertTrue("the root-entry check only applies to an object that IS at the root", //$NON-NLS-1$
                create.contains("if (storageFqn.equals(fqn)) {")); //$NON-NLS-1$

        String relink = methodBody(source, "private void rebindTopLevelIntoConfiguration(\n            IProject project,\n" //$NON-NLS-1$
                + "            MetadataKind kind,\n            String objectName,\n            String fqn,\n" //$NON-NLS-1$
                + "            String storageFqn,"); //$NON-NLS-1$
        assertTrue("the relink must look the object up by the FQN it is registered under", //$NON-NLS-1$
                relink.contains("transaction.getTopObjectByFqn(namespace, storageFqn)")); //$NON-NLS-1$
        assertTrue("and leave a nested subsystem alone instead of re-rooting it", //$NON-NLS-1$
                relink.contains("if (isNestedSubsystem(txObject)) {")); //$NON-NLS-1$
        int guard = relink.indexOf("isNestedSubsystem(txObject)"); //$NON-NLS-1$
        int sweep = relink.indexOf("removeTopLevelObjectLinks(txConfiguration, kind, objectName)"); //$NON-NLS-1$
        assertTrue("the guard must come BEFORE the sweep, which is the destructive half", //$NON-NLS-1$
                guard >= 0 && sweep > guard);
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
