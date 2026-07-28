package com.codepilot1c.core.edt.dcs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;

import com._1c.g5.v8.derived.IDerivedDataManager;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;
import com._1c.g5.v8.dt.core.platform.IExternalObjectProject;
import com.codepilot1c.core.edt.metadata.EdtMetadataGateway;
import com.codepilot1c.core.edt.metadata.MetadataOperationCode;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;
import com.codepilot1c.core.edt.metadata.eol.EolNormalizer;
import com.codepilot1c.core.edt.metadata.eol.EolStyle;
import com.codepilot1c.core.logging.VibeLogger;

/**
 * Export + EOL plumbing for the DCS mutation path.
 *
 * <p>A BM transaction commit alone does NOT write the touched artifacts to disk — every mutator
 * in {@code EdtMetadataService} follows its transaction with a {@code forceExport} plus a
 * derived-data flush. The DCS service used to skip that entirely, so a schema could be created in
 * the model and never reach the working tree.</p>
 *
 * <p>The {@code extraFqn} slot matters especially here: a data composition schema is a SEPARATE
 * top-object serialized into its own {@code Templates/&lt;name&gt;/Template.dcs} file, so exporting
 * only the owning {@code Report} writes {@code Report.mdo} and nothing else — the same class of
 * defect as a role's {@code Rights.rights} fragment.</p>
 *
 * <p><b>Deliberate duplication.</b> This mirrors {@code EdtMetadataService.forceExportTopLevelObject}
 * / {@code beginEolGuard} instead of reusing them: hoisting those into a shared helper would touch
 * the hot path of ~30 call sites in the metadata service. De-duplication is a follow-up refactor.</p>
 */
final class DcsExportSupport {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(DcsExportSupport.class);

    private static final long EXPORT_DERIVED_WAIT_MS =
            Long.getLong("codepilot1c.edt.export.wait.ms", 120_000L).longValue(); //$NON-NLS-1$
    private static final String EXPORT_SEGMENT_OBJECTS = "EXP_O"; //$NON-NLS-1$
    private static final String EXPORT_SEGMENT_BLOBS = "EXP_B"; //$NON-NLS-1$

    /**
     * Extensions whose EOL is snapshotted around a DCS mutation. {@code dcs} is included on top of
     * the metadata-service set because the DCS path is the one that rewrites those files.
     */
    private static final Set<String> EOL_MANAGED_EXTENSIONS =
            Set.of("mdo", "form", "bsl", "dcs"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

    private final EdtMetadataGateway gateway;

    DcsExportSupport(EdtMetadataGateway gateway) {
        this.gateway = gateway;
    }

    /**
     * Force-exports the owner top-object (+ {@code Configuration}) and, when non-null, the
     * schema's own external top-object FQN in the SAME batch.
     */
    void forceExport(IProject project, String fqn, String extraFqn, String opId) {
        IBmModelManager modelManager = gateway.getBmModelManager();
        IDtProjectManager projectManager = gateway.getDtProjectManager();
        IDtProject dtProject = projectManager.getDtProject(project);
        if (dtProject == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_SERVICE_UNAVAILABLE,
                    "Cannot resolve DT project for force export: " + project.getName(), false); //$NON-NLS-1$
        }

        List<String> targets = buildExportTargets(fqn, extraFqn);
        // Dual-purpose diagnostic: a live run must show BOTH the owner and the schema fragment in
        // the target list, otherwise the schema silently stays in the model only.
        LOG.info("[dcs][%s] forceExport targets=%s", opId, targets); //$NON-NLS-1$
        boolean exported = false;
        try {
            exported = modelManager.forceExport(dtProject, targets);
        } catch (RuntimeException e) {
            LOG.warn("[dcs][%s] forceExport(List) failed for %s: %s", opId, targets, e.getMessage()); //$NON-NLS-1$
        }
        if (!exported) {
            try {
                exported = modelManager.forceExport(dtProject, fqn);
            } catch (RuntimeException e) {
                LOG.warn("[dcs][%s] forceExport(String) failed for %s: %s", opId, fqn, e.getMessage()); //$NON-NLS-1$
            }
        }
        if (!exported) {
            try {
                exported = modelManager.forceExport(dtProject, "Configuration"); //$NON-NLS-1$
            } catch (RuntimeException e) {
                LOG.warn("[dcs][%s] forceExport(String) failed for Configuration: %s", opId, e.getMessage()); //$NON-NLS-1$
            }
        }
        if (!exported) {
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_TRANSACTION_FAILED,
                    "forceExport did not schedule export tasks for " + fqn, true); //$NON-NLS-1$
        }

        waitExportDerivedData(dtProject, opId, fqn);
        flushDerivedDataPipeline(dtProject, opId, fqn);
        modelManager.waitModelSynchronization(project);
        LOG.debug("[dcs][%s] waitModelSynchronization completed for project=%s", opId, project.getName()); //$NON-NLS-1$
    }

    List<String> buildExportTargets(String fqn, String extraFqn) {
        LinkedHashSet<String> targets = new LinkedHashSet<>();
        if (fqn != null && !fqn.isBlank()) {
            targets.add(fqn);
        }
        if (extraFqn != null && !extraFqn.isBlank()) {
            targets.add(extraFqn);
        }
        targets.add("Configuration"); //$NON-NLS-1$
        return List.copyOf(targets);
    }

    private void waitExportDerivedData(IDtProject dtProject, String opId, String fqn) {
        IDerivedDataManager ddManager = gateway.getDerivedDataManagerProvider().get(dtProject);
        if (ddManager == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_SERVICE_UNAVAILABLE,
                    "Cannot resolve derived-data manager for project: " + dtProject.getName(), false); //$NON-NLS-1$
        }
        try {
            boolean done = ddManager.waitComputation(
                    EXPORT_DERIVED_WAIT_MS,
                    true,
                    EXPORT_SEGMENT_OBJECTS,
                    EXPORT_SEGMENT_BLOBS);
            LOG.debug("[dcs][%s] waitComputation(EXP_O,EXP_B) for %s: %s", opId, fqn, Boolean.valueOf(done)); //$NON-NLS-1$
            if (!done) {
                throw new MetadataOperationException(
                        MetadataOperationCode.EDT_TRANSACTION_FAILED,
                        "Timed out waiting export derived-data for " + fqn //$NON-NLS-1$
                                + " in " + EXPORT_DERIVED_WAIT_MS + "ms", //$NON-NLS-1$ //$NON-NLS-2$
                        true);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_TRANSACTION_FAILED,
                    "Interrupted while waiting export derived-data for " + fqn, true, e); //$NON-NLS-1$
        }
    }

    private void flushDerivedDataPipeline(IDtProject dtProject, String opId, String fqn) {
        IDerivedDataManager ddManager = gateway.getDerivedDataManagerProvider().get(dtProject);
        if (ddManager == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_SERVICE_UNAVAILABLE,
                    "Cannot resolve derived-data manager for project: " + dtProject.getName(), false); //$NON-NLS-1$
        }
        try {
            boolean importantDone = ddManager.waitImportantDataComputations(EXPORT_DERIVED_WAIT_MS);
            LOG.debug("[dcs][%s] waitImportantDataComputations for %s: %s", opId, fqn, //$NON-NLS-1$
                    Boolean.valueOf(importantDone));
            if (!importantDone) {
                LOG.warn("[dcs][%s] waitImportantDataComputations timed out for %s in %dms", opId, fqn, //$NON-NLS-1$
                        Long.valueOf(EXPORT_DERIVED_WAIT_MS));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_TRANSACTION_FAILED,
                    "Interrupted while flushing derived-data pipeline for " + fqn, true, e); //$NON-NLS-1$
        }
    }

    /**
     * Snapshots the per-file EOL of the artifacts a DCS export may rewrite. MUST be called BEFORE
     * the mutating transaction — once the export pipeline has run the original EOL is gone.
     */
    EolGuard beginEolGuard(IProject project, String ownerTopLevelFqn, String opId) {
        Map<Path, EolStyle> snapshot = new HashMap<>();
        if (project != null && !isExternalProject(project)) {
            for (Path file : collectEolCandidateFiles(project, ownerTopLevelFqn)) {
                try {
                    String content = Files.readString(file, StandardCharsets.UTF_8);
                    EolNormalizer.detect(content).ifPresent(style -> snapshot.put(file, style));
                } catch (IOException | RuntimeException e) {
                    // unreadable or binary — nothing to preserve
                }
            }
        }
        return new EolGuard(project, ownerTopLevelFqn, opId, snapshot);
    }

    void refreshProjectSafely(IProject project) {
        if (project == null || !project.exists()) {
            return;
        }
        try {
            project.refreshLocal(IResource.DEPTH_INFINITE, null);
        } catch (CoreException e) {
            LOG.warn("[dcs] refreshProjectSafely failed for %s: %s", project.getName(), e.getMessage()); //$NON-NLS-1$
        }
    }

    boolean isExternalProject(IProject project) {
        if (project == null || !project.exists()) {
            return false;
        }
        try {
            return gateway.getV8ProjectManager().getProject(project) instanceof IExternalObjectProject;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private List<Path> collectEolCandidateFiles(IProject project, String ownerTopLevelFqn) {
        List<Path> files = new ArrayList<>();
        if (project == null || project.getLocation() == null) {
            return files;
        }
        Path base = project.getLocation().toFile().toPath();
        Path configMdo = base.resolve("src").resolve("Configuration").resolve("Configuration.mdo"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (Files.isRegularFile(configMdo)) {
            files.add(configMdo);
        }
        Path objectDir = ownerSourceDirectory(base, ownerTopLevelFqn);
        if (objectDir != null && Files.isDirectory(objectDir)) {
            try (Stream<Path> walk = Files.walk(objectDir)) {
                walk.filter(Files::isRegularFile)
                    .filter(DcsExportSupport::isEolManagedFile)
                    .forEach(files::add);
            } catch (IOException | RuntimeException e) {
                // best-effort enumeration
            }
        }
        return files;
    }

    /** {@code <base>/src/Reports/Sales} for {@code Report.Sales}; {@code null} without a src tree. */
    private Path ownerSourceDirectory(Path base, String ownerTopLevelFqn) {
        String[] parts = ownerTopLevelFqn == null ? new String[0] : ownerTopLevelFqn.trim().split("\\."); //$NON-NLS-1$
        if (parts.length < 2) {
            return null;
        }
        String folder = DcsSchemaSupport.ownerFolder(parts[0]);
        if (folder == null || parts[1].isBlank()) {
            return null;
        }
        return base.resolve("src").resolve(folder).resolve(parts[1]); //$NON-NLS-1$
    }

    private static boolean isEolManagedFile(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot >= 0 && EOL_MANAGED_EXTENSIONS.contains(name.substring(dot + 1));
    }

    /** Re-applies a captured EOL snapshot once serialization has settled. Never throws. */
    final class EolGuard {

        private final IProject project;
        private final String ownerTopLevelFqn;
        private final String opId;
        private final Map<Path, EolStyle> snapshot;

        EolGuard(IProject project, String ownerTopLevelFqn, String opId, Map<Path, EolStyle> snapshot) {
            this.project = project;
            this.ownerTopLevelFqn = ownerTopLevelFqn;
            this.opId = opId;
            this.snapshot = snapshot;
        }

        void restore() {
            if (project == null || isExternalProject(project)) {
                return;
            }
            EolDefaults defaults = loadEolDefaults(project);
            int fixed = 0;
            try {
                for (Path file : collectEolCandidateFiles(project, ownerTopLevelFqn)) {
                    EolStyle target = snapshot.get(file);
                    if (target == null) {
                        // A file created by this operation — follow the project convention.
                        target = defaults.resolve(file.getFileName().toString());
                    }
                    if (normalizeFileEol(file, target)) {
                        fixed++;
                    }
                }
            } catch (RuntimeException e) {
                LOG.warn("[dcs][%s] EOL preservation failed for %s: %s", opId, ownerTopLevelFqn, e.getMessage()); //$NON-NLS-1$
                return;
            }
            if (fixed > 0) {
                LOG.debug("[dcs][%s] EOL preservation normalized %d file(s) for %s", opId, //$NON-NLS-1$
                        Integer.valueOf(fixed), ownerTopLevelFqn);
                refreshProjectSafely(project);
            }
        }
    }

    private boolean normalizeFileEol(Path file, EolStyle target) {
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            String normalized = EolNormalizer.normalizeTo(content, target);
            if (!normalized.equals(content)) {
                Files.writeString(file, normalized, StandardCharsets.UTF_8);
                return true;
            }
        } catch (IOException | RuntimeException e) {
            // binary / unreadable / concurrently changed — skip
        }
        return false;
    }

    private EolDefaults loadEolDefaults(IProject project) {
        List<String> gitattributes = new ArrayList<>();
        List<String> editorconfigs = new ArrayList<>();
        if (project != null && project.getLocation() != null) {
            Path base = project.getLocation().toFile().toPath();
            Path parent = base.getParent();
            for (Path dir : parent != null ? List.of(base, parent) : List.of(base)) {
                String ga = readTextSafely(dir.resolve(".gitattributes")); //$NON-NLS-1$
                if (ga != null) {
                    gitattributes.add(ga);
                }
                String ec = readTextSafely(dir.resolve(".editorconfig")); //$NON-NLS-1$
                if (ec != null) {
                    editorconfigs.add(ec);
                }
            }
        }
        return new EolDefaults(gitattributes, editorconfigs);
    }

    private String readTextSafely(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return null;
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Resolves the EOL convention for newly created files from repo config, default LF. */
    private static final class EolDefaults {

        private final List<String> gitattributes;
        private final List<String> editorconfigs;

        EolDefaults(List<String> gitattributes, List<String> editorconfigs) {
            this.gitattributes = gitattributes;
            this.editorconfigs = editorconfigs;
        }

        EolStyle resolve(String fileName) {
            for (String content : gitattributes) {
                EolStyle style = EolNormalizer.fromGitattributes(content, fileName).orElse(null);
                if (style != null) {
                    return style;
                }
            }
            for (String content : editorconfigs) {
                EolStyle style = EolNormalizer.fromEditorconfig(content, fileName).orElse(null);
                if (style != null) {
                    return style;
                }
            }
            return EolStyle.LF; // EDT's default for new projects on this codebase
        }
    }
}
