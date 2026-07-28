package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

/**
 * Source-contract test for nested-subsystem addressing (B4) and for the single kind→collection
 * mapping the three consumers now share (B-bonus).
 *
 * <p>{@code Configuration}, {@code Subsystem} and the BM transaction types resolve only in the
 * OSGi runtime, so the wiring is pinned by reading the source; the traversal semantics
 * themselves are real unit tests in {@link SubsystemTreeTest} and
 * {@link TopLevelCollectionsMappingTest}.</p>
 *
 * <p>What was broken: a subsystem's canonical FQN is flat at every depth
 * ({@code Subsystem.PaymentCalendar}) because both subsystem collections are non-containment,
 * yet resolution scanned only {@code Configuration.getSubsystems()}. The flat form therefore
 * failed for nested subsystems, the nested form failed too (the nested-child walker iterates
 * containment references only), and {@code scan_metadata_index} never listed them at all.</p>
 */
public class SubsystemFqnResolutionContractTest {

    private static final String SERVICE_PATH =
            "bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/metadata/EdtMetadataService.java"; //$NON-NLS-1$
    private static final String COLLECTIONS_PATH =
            "bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/metadata/TopLevelCollections.java"; //$NON-NLS-1$
    private static final String INSPECTOR_PATH =
            "bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/ast/EdtMetadataInspectorService.java"; //$NON-NLS-1$
    private static final String INDEX_PATH =
            "bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/ast/EdtMetadataIndexService.java"; //$NON-NLS-1$

    // --- flat FQN resolves at any depth -------------------------------------

    @Test
    public void flatSubsystemFqnIsResolvedRecursively() throws Exception {
        String source = readSource(SERVICE_PATH);
        assertTrue("findTopLevel must route SUBSYSTEM through the recursive resolver", //$NON-NLS-1$
                source.contains("if (kind == MetadataKind.SUBSYSTEM) {") //$NON-NLS-1$
                        && source.contains("return findSubsystemAnywhere(configuration, name);")); //$NON-NLS-1$
        assertTrue("the recursive resolver must walk the forest via SubsystemTree", //$NON-NLS-1$
                source.contains("SubsystemTree.locateByName(")); //$NON-NLS-1$
    }

    @Test
    public void ambiguousSubsystemNameFailsLoudNamingBothParents() throws Exception {
        String resolver = methodBody(readSource(SERVICE_PATH),
                "private MdObject findSubsystemAnywhere("); //$NON-NLS-1$
        assertTrue("more than one hit must be refused, not resolved arbitrarily", //$NON-NLS-1$
                resolver.contains("if (hits.size() > 1)")); //$NON-NLS-1$
        assertTrue("the refusal must list every colliding parent", //$NON-NLS-1$
                resolver.contains("SubsystemTree.describeAmbiguity(")); //$NON-NLS-1$
        assertTrue("the refusal must be an exception, not a silent first-match", //$NON-NLS-1$
                resolver.contains("throw new MetadataOperationException(")); //$NON-NLS-1$
    }

    // --- nested FQN is a tolerant alias -------------------------------------

    @Test
    public void nestedSubsystemFqnIsAcceptedAsAnAlias() throws Exception {
        String source = readSource(SERVICE_PATH);
        assertTrue("findNestedChild must fall back to the nested-subsystem alias", //$NON-NLS-1$
                source.contains("return findNestedSubsystemAlias(parent, normalizedMarker, childName);")); //$NON-NLS-1$

        String alias = methodBody(source, "private MdObject findNestedSubsystemAlias("); //$NON-NLS-1$
        assertTrue("the alias must only apply to a Subsystem parent", //$NON-NLS-1$
                alias.contains("if (!(parent instanceof Subsystem subsystem))")); //$NON-NLS-1$
        assertTrue("the alias must only follow the subsystems feature", //$NON-NLS-1$
                alias.contains("matchesMarker(normalizedMarker, \"subsystems\", \"Subsystem\")")); //$NON-NLS-1$
        assertTrue("the alias must read the non-containment child list", //$NON-NLS-1$
                alias.contains("subsystem.getSubsystems()")); //$NON-NLS-1$
    }

    @Test
    public void aliasStaysNarrowAndDoesNotOpenContentReferences() throws Exception {
        String alias = methodBody(readSource(SERVICE_PATH), "private MdObject findNestedSubsystemAlias("); //$NON-NLS-1$
        // Subsystem.content is also a non-containment many reference: a generic
        // "scan non-containment references too" rule would make every content member an
        // addressable child.
        assertFalse("the alias must not generically scan non-containment references", //$NON-NLS-1$
                alias.contains("getEAllStructuralFeatures()")); //$NON-NLS-1$
        assertFalse("the alias must not touch content", alias.contains("getContent()")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // --- one shared mapping for all consumers -------------------------------

    @Test
    public void subsystemCollectionIsFlattenedInTheSharedMapping() throws Exception {
        String forKind = methodBody(readSource(COLLECTIONS_PATH),
                "public static List<? extends MdObject> forKind("); //$NON-NLS-1$
        assertTrue("forKind must flatten the subsystem forest", //$NON-NLS-1$
                forKind.contains("case SUBSYSTEM -> SubsystemTree.<Subsystem>flatten(")); //$NON-NLS-1$
        // An exhaustive switch over MetadataKind turns a newly added kind into a compile error;
        // a default arm would silently reinstate the "unknown kind means empty" defect.
        assertFalse("the mapping switch must stay exhaustive (no default arm to hide a new kind)", //$NON-NLS-1$
                forKind.contains("default ->")); //$NON-NLS-1$
    }

    @Test
    public void serviceUsesTheSharedMapping() throws Exception {
        String source = readSource(SERVICE_PATH);
        assertFalse("the private per-kind switch must be gone", //$NON-NLS-1$
                source.contains("private List<? extends MdObject> topLevelCollection(")); //$NON-NLS-1$
        assertTrue("findTopLevel must read the shared mapping", //$NON-NLS-1$
                source.contains("for (MdObject object : TopLevelCollections.forKind(configuration, kind))")); //$NON-NLS-1$
        assertTrue("existsTopLevel must read the shared mapping (so SUBSYSTEM is recursive)", //$NON-NLS-1$
                source.contains("return containsMdObjectName(TopLevelCollections.forKind(configuration, kind), name);")); //$NON-NLS-1$
        assertTrue("the Configuration.mdo tag mapping must be shared too", //$NON-NLS-1$
                source.contains("return TopLevelCollections.configurationTag(kind);")); //$NON-NLS-1$
    }

    @Test
    public void inspectorNoLongerHardcodesNineKinds() throws Exception {
        String inspector = readSource(INSPECTOR_PATH);
        assertTrue("edt_metadata_details must resolve the kind through MetadataKind", //$NON-NLS-1$
                inspector.contains("kind = MetadataKind.fromString(parts[0]);")); //$NON-NLS-1$
        assertTrue("edt_metadata_details must read the shared mapping", //$NON-NLS-1$
                inspector.contains("TopLevelCollections.forKind(config, kind)")); //$NON-NLS-1$
        assertFalse("the nine-kind switch must be gone", //$NON-NLS-1$
                inspector.contains("case \"catalog\", \"catalogs\" -> config.getCatalogs();")); //$NON-NLS-1$
        assertFalse("the silent empty-list fallback must be gone", //$NON-NLS-1$
                inspector.contains("default -> List.of();")); //$NON-NLS-1$
    }

    @Test
    public void indexScanCoversEveryKindIncludingNestedSubsystems() throws Exception {
        String index = readSource(INDEX_PATH);
        assertTrue("scan_metadata_index must iterate every kind", //$NON-NLS-1$
                index.contains("for (MetadataKind kind : MetadataKind.values())")); //$NON-NLS-1$
        assertTrue("scan_metadata_index must read the shared mapping", //$NON-NLS-1$
                index.contains("TopLevelCollections.forKind(configuration, kind)")); //$NON-NLS-1$
        assertTrue("the reported collection token must come from the shared mapping", //$NON-NLS-1$
                index.contains("TopLevelCollections.indexScopeToken(kind)")); //$NON-NLS-1$
        assertFalse("the hand-written 48-line list must be gone", //$NON-NLS-1$
                index.contains("configuration.getWebSocketClients())")); //$NON-NLS-1$
    }

    // --- Helpers ------------------------------------------------------------

    private String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue("method not found in source: " + signature, start >= 0); //$NON-NLS-1$
        int end = source.indexOf("\n    }", start); //$NON-NLS-1$
        assertTrue("method end not found for: " + signature, end > start); //$NON-NLS-1$
        return source.substring(start, end);
    }

    private String readSource(String relativePath) throws Exception {
        Path repoRoot = findRepoRoot();
        return Files.readString(repoRoot.resolve(relativePath), StandardCharsets.UTF_8);
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
