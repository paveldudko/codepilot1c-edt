package com.codepilot1c.core.tools.workspace;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.AbstractTool;

import java.io.File;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;

import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com.codepilot1c.core.edt.runtime.EdtProjectResolver;
import com.codepilot1c.core.edt.runtime.EdtToolErrorCode;
import com.codepilot1c.core.edt.runtime.EdtToolException;
import com.codepilot1c.core.edt.runtime.EdtRuntimeService;
import com.codepilot1c.core.internal.VibeCorePlugin;
import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.logging.VibeLogger;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

/**
 * Updates an EDT project's associated infobase.
 */
@ToolMeta(
        name = "edt_update_infobase",
        category = "diagnostics",
        surfaceCategory = "smoke_runtime_recovery",
        mutating = true,
        tags = {"workspace", "edt"})
public class EdtUpdateInfobaseTool extends AbstractTool {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(EdtUpdateInfobaseTool.class);

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "project_name": {
                  "type": "string",
                  "description": "EDT project name"
                },
                "keep_connected": {
                  "type": "boolean",
                  "description": "Keep infobase connected after EDT update (default: true)"
                },
                "runtime_version": {
                  "type": "string",
                  "description": "Версия платформы 1С: линия ('8.3.27' — новейший установленный билд) или точный билд ('8.3.27.2074'). Пинит выбор платформы для project+infobase в настройках EDT (persistent, его же использует EDT UI) перед обновлением. Без пина auto = НОВЕЙШАЯ установленная платформа, включая пре-релизные билды — проверяйте runtime_used в dry_run."
                },
                "dry_run": {
                  "type": "boolean",
                  "description": "Resolve project and infobase without updating"
                },
                "async": {
                  "type": "boolean",
                  "description": "Fire-and-forget; returns jobId to poll via update_infobase_status (default: false)"
                }
              },
              "required": ["project_name"]
            }
            """; //$NON-NLS-1$

    private final EdtProjectResolver projectResolver;
    private final EdtRuntimeService runtimeService;

    public EdtUpdateInfobaseTool() {
        this(new EdtProjectResolver(), new EdtRuntimeService());
    }

    public EdtUpdateInfobaseTool(EdtProjectResolver projectResolver, EdtRuntimeService runtimeService) {
        this.projectResolver = projectResolver;
        this.runtimeService = runtimeService;
    }

    @Override
    public String getDescription() {
        return "Обновляет инфобазу, связанную с EDT проектом, через EDT runtime. " //$NON-NLS-1$
                + "Версия платформы: pin в EDT (runtime_version пинит persistent) > auto " //$NON-NLS-1$
                + "(НОВЕЙШАЯ установленная, включая пре-релизы) — проверяйте runtime_used в dry_run."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    public boolean requiresConfirmation() {
        return true;
    }

    @Override
    public boolean isDestructive() {
        return true;
    }

    @Override
    protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        Map<String, Object> parameters = params.getRaw();
        String opId = LogSanitizer.newId("edt-update"); //$NON-NLS-1$
        File workspaceRoot = getWorkspaceRoot();
        String projectName = asString(parameters == null ? null : parameters.get("project_name")); //$NON-NLS-1$
        // Lenient: callers reaching this tool through edt_diagnostics may ship
        // these as strings (the parent schema doesn't advertise their types).
        boolean keepConnected = asBoolean(parameters == null ? null : parameters.get("keep_connected"), true); //$NON-NLS-1$
        boolean dryRun = asBoolean(parameters == null ? null : parameters.get("dry_run"), false); //$NON-NLS-1$
        boolean async = asBoolean(parameters == null ? null : parameters.get("async"), false); //$NON-NLS-1$
        String runtimeVersionRaw = asString(parameters == null ? null : parameters.get("runtime_version")); //$NON-NLS-1$
        String runtimeVersion = runtimeVersionRaw == null || runtimeVersionRaw.isBlank() ? null : runtimeVersionRaw;

        if (async && dryRun) {
            // Dry-run is fast and deterministic; running it synchronously avoids
            // roundtripping through the background registry. Surface an explicit
            // flag so the caller knows the async request was intentionally ignored.
            LOG.info("[%s] edt_update_infobase async+dry_run: running sync (async_ignored)", opId); //$NON-NLS-1$
        } else if (async) {
            try {
                String jobId = BackgroundJobRegistry.getInstance().startJob(
                        "edt_update_infobase", //$NON-NLS-1$
                        () -> runUpdateAndRenderResult(opId, projectName, keepConnected, workspaceRoot,
                                runtimeVersion));
                LOG.info("[%s] edt_update_infobase scheduled async job=%s", opId, jobId); //$NON-NLS-1$
                JsonObject accepted = basePayload(opId, "scheduled", projectName, false, workspaceRoot); //$NON-NLS-1$
                accepted.addProperty("async", true); //$NON-NLS-1$
                accepted.addProperty("job_id", jobId); //$NON-NLS-1$
                accepted.addProperty("state", BackgroundJobRegistry.JobState.RUNNING.name());
                accepted.addProperty("updated", false); //$NON-NLS-1$
                accepted.add("details", new JsonObject()); //$NON-NLS-1$
                return CompletableFuture.completedFuture(
                        ToolResult.success(pretty(accepted), ToolResult.ToolResultType.CODE));
            } catch (RejectedExecutionException e) {
                LOG.warn("[%s] edt_update_infobase async rejected: %s", opId, e.getMessage()); //$NON-NLS-1$
                JsonObject rejected = basePayload(opId, "error", projectName, false, workspaceRoot); //$NON-NLS-1$
                rejected.addProperty("updated", false); //$NON-NLS-1$
                rejected.addProperty("error", "queue_saturated"); //$NON-NLS-1$ //$NON-NLS-2$
                rejected.addProperty("message", "Background job queue full; retry later"); //$NON-NLS-1$ //$NON-NLS-2$
                rejected.add("details", new JsonObject()); //$NON-NLS-1$
                return CompletableFuture.completedFuture(
                        ToolResult.failure(pretty(rejected)));
            }
        }

        final boolean asyncIgnored = async && dryRun;

        return CompletableFuture.supplyAsync(() -> {
            LOG.info("[%s] START edt_update_infobase", opId); //$NON-NLS-1$
            try {
                InfobaseReference infobase = projectResolver.resolveInfobase(projectName, workspaceRoot);
                JsonObject result = basePayload(opId, dryRun ? "dry_run" : "updated", projectName, dryRun, //$NON-NLS-1$ //$NON-NLS-2$
                        workspaceRoot);
                if (asyncIgnored) {
                    result.addProperty("async_ignored", true); //$NON-NLS-1$
                    result.addProperty("async_ignored_reason", //$NON-NLS-1$
                            "dry_run completes synchronously"); //$NON-NLS-1$
                }
                JsonObject details = new JsonObject();
                if (infobase != null && infobase.getConnectionString() != null) {
                    details.addProperty("infobase_connection",
                            infobase.getConnectionString().asConnectionString()); //$NON-NLS-1$
                }
                result.add("details", details); //$NON-NLS-1$
                applyRuntimeControls(result, projectName, runtimeVersion, dryRun);
                if (dryRun) {
                    result.addProperty("updated", false); //$NON-NLS-1$
                    return ToolResult.success(pretty(result), ToolResult.ToolResultType.CODE);
                }
                EdtRuntimeService.UpdateInfobaseStatus status =
                        runUpdateWithGuiProgress(projectName, keepConnected);
                result.addProperty("updated", status.updated()); //$NON-NLS-1$
                if (status.dynamicOnly()) {
                    // EDT could not acquire an exclusive lock (existing client/test sessions hold
                    // the infobase). The platform fell back to a dynamic-mode update, which does
                    // not apply schema changes (new handlers, new metadata). Surface the flag so
                    // callers know to close TC sessions and rerun, or verify via inspect_metadata.
                    result.addProperty("dynamic_only", true); //$NON-NLS-1$
                    result.addProperty("dynamic_only_reason", //$NON-NLS-1$
                            "Could not acquire exclusive lock; existing client/test " //$NON-NLS-1$
                                    + "sessions blocked the update. Schema changes are NOT live."); //$NON-NLS-1$
                }
                if (!status.updated()) {
                    throw new EdtToolException(EdtToolErrorCode.UPDATE_FAILED,
                            "EDT update returned false for project: " + projectName); //$NON-NLS-1$
                }
                return ToolResult.success(pretty(result), ToolResult.ToolResultType.CODE);
            } catch (EdtToolException e) {
                return ToolResult.failure(pretty(errorPayload(opId, projectName, workspaceRoot, e.getCode(), e.getMessage())));
            } catch (Exception e) {
                if (isBlockedByLockedIB(e)) {
                    JsonObject payload = errorPayload(opId, projectName, workspaceRoot,
                            EdtToolErrorCode.IB_LOCKED,
                            "Infobase is locked by another process (Apache wsap publication, Designer session, or another client holds exclusive access). Stop the blocking process, then retry update_infobase."); //$NON-NLS-1$
                    payload.addProperty("hint", //$NON-NLS-1$
                            "Stop all processes holding the infobase open (httpd/wsap, running thin clients, Designer agents), then retry. Use Stop-PhantomDesigner if a Designer agent is stuck."); //$NON-NLS-1$
                    return ToolResult.failure(pretty(payload));
                } else if (isBlockedByHttpClients(e)) {
                    JsonObject error = errorPayload(opId, projectName, workspaceRoot,
                            EdtToolErrorCode.UPDATE_BLOCKED_BY_HTTP_CLIENTS, e.getMessage());
                    error.addProperty("hint", HTTP_CLIENTS_HINT); //$NON-NLS-1$
                    return ToolResult.failure(pretty(error));
                }
                return ToolResult.failure(pretty(errorPayload(opId, projectName, workspaceRoot,
                        EdtToolErrorCode.UPDATE_FAILED, e.getMessage())));
            }
        });
    }

    /**
     * Runs the non-dry update synchronously and renders a pretty JSON payload.
     * Used by the background job path so the job result mirrors the regular
     * synchronous output.
     */
    private String runUpdateAndRenderResult(String opId, String projectName, boolean keepConnected,
            File workspaceRoot, String runtimeVersion) {
        LOG.info("[%s] START edt_update_infobase (async)", opId); //$NON-NLS-1$
        try {
            InfobaseReference infobase = projectResolver.resolveInfobase(projectName, workspaceRoot);
            JsonObject result = basePayload(opId, "updated", projectName, false, workspaceRoot); //$NON-NLS-1$
            JsonObject details = new JsonObject();
            if (infobase != null && infobase.getConnectionString() != null) {
                details.addProperty("infobase_connection", //$NON-NLS-1$
                        infobase.getConnectionString().asConnectionString());
            }
            result.add("details", details); //$NON-NLS-1$
            applyRuntimeControls(result, projectName, runtimeVersion, false);
            EdtRuntimeService.UpdateInfobaseStatus status =
                    runUpdateWithGuiProgress(projectName, keepConnected);
            boolean updated = status.updated();
            result.addProperty("updated", updated); //$NON-NLS-1$
            if (status.dynamicOnly()) {
                result.addProperty("dynamic_only", true); //$NON-NLS-1$
                result.addProperty("dynamic_only_reason", //$NON-NLS-1$
                        "Could not acquire exclusive lock; existing client/test " //$NON-NLS-1$
                                + "sessions blocked the update. Schema changes are NOT live."); //$NON-NLS-1$
            }
            if (!updated) {
                JsonObject error = errorPayload(opId, projectName, workspaceRoot,
                        EdtToolErrorCode.UPDATE_FAILED,
                        "EDT update returned false for project: " + projectName); //$NON-NLS-1$
                return pretty(error);
            }
            return pretty(result);
        } catch (EdtToolException e) {
            return pretty(errorPayload(opId, projectName, workspaceRoot, e.getCode(), e.getMessage()));
        } catch (Exception e) {
            if (isBlockedByLockedIB(e)) {
                JsonObject payload = errorPayload(opId, projectName, workspaceRoot,
                        EdtToolErrorCode.IB_LOCKED,
                        "Infobase is locked by another process (Apache wsap publication, Designer session, or another client holds exclusive access). Stop the blocking process, then retry update_infobase."); //$NON-NLS-1$
                payload.addProperty("hint", //$NON-NLS-1$
                        "Stop all processes holding the infobase open (httpd/wsap, running thin clients, Designer agents), then retry. Use Stop-PhantomDesigner if a Designer agent is stuck."); //$NON-NLS-1$
                return pretty(payload);
            } else if (isBlockedByHttpClients(e)) {
                JsonObject error = errorPayload(opId, projectName, workspaceRoot,
                        EdtToolErrorCode.UPDATE_BLOCKED_BY_HTTP_CLIENTS, e.getMessage());
                error.addProperty("hint", HTTP_CLIENTS_HINT); //$NON-NLS-1$
                return pretty(error);
            }
            return pretty(errorPayload(opId, projectName, workspaceRoot,
                    EdtToolErrorCode.UPDATE_FAILED, e.getMessage()));
        }
    }

    /**
     * Detects the platform's "Cannot perform dynamic database update because clients that run
     * over HTTP are connected" failure (wsap publication sessions hold a file infobase). Matched
     * across the cause chain and in both EN/RU platform locales. Feedback
     * {@code 2026-06-04-web-publication-tool-input.md}: agents previously got an opaque EDT
     * exception and had to know to stop Apache manually.
     */
    private static boolean isBlockedByHttpClients(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase(java.util.Locale.ROOT);
                boolean mentionsHttp = lower.contains("http"); //$NON-NLS-1$
                boolean mentionsClients = lower.contains("client") || lower.contains("клиент"); //$NON-NLS-1$ //$NON-NLS-2$
                if (mentionsHttp && mentionsClients) {
                    return true;
                }
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }

    private static final String HTTP_CLIENTS_HINT =
            "Web-publication (wsap) sessions hold this infobase. Stop the web server " //$NON-NLS-1$
                    + "(web_publication action=restart restarts it; or stop httpd), rerun " //$NON-NLS-1$
                    + "edt_update_infobase, then restart the publication and re-probe it."; //$NON-NLS-1$

    /**
     * Applies the optional runtime pin and reports the platform installation EDT will use.
     *
     * <p>When {@code runtimeVersion} is set and this is not a dry run, the version is pinned
     * EDT-natively ({@code IInfobaseAccessManager.updateSelectedInstallation}) BEFORE the update —
     * persistent for this project+infobase, also honoured by the EDT UI in auto mode. Dry runs
     * never mutate the pin store; they only report what a real run would do.</p>
     *
     * <p>Always (incl. dry_run) decorates {@code result} with {@code runtime_used} —
     * version/location/pinned of the installation EDT's auto-resolution picks. Auto resolves to
     * the NEWEST installed platform, including pre-release builds (feedback
     * {@code 2026-06-03-edt-diagnostics-runtime-version-uncontrollable.md}); when there is no pin,
     * an explicit {@code runtime_auto_resolved=true} flags it.</p>
     *
     * @throws EdtToolException when the requested pin cannot be applied (unknown version, store
     *                          failure) — failing the call is better than silently updating the
     *                          infobase with the wrong platform
     */
    private void applyRuntimeControls(JsonObject result, String projectName, String runtimeVersion,
            boolean dryRun) {
        if (runtimeVersion != null && !dryRun) {
            try {
                EdtRuntimeService.ResolvedRuntimeInfo pinnedTo =
                        runtimeService.pinRuntimeVersion(projectName, runtimeVersion);
                result.addProperty("runtime_pinned_to", pinnedTo.version()); //$NON-NLS-1$
            } catch (IllegalArgumentException | IllegalStateException e) {
                throw new EdtToolException(EdtToolErrorCode.RUNTIME_NOT_RESOLVED,
                        "Failed to pin runtime_version '" + runtimeVersion + "': " + e.getMessage(), e); //$NON-NLS-1$ //$NON-NLS-2$
            }
        } else if (runtimeVersion != null) {
            result.addProperty("runtime_version_requested", runtimeVersion); //$NON-NLS-1$
            result.addProperty("runtime_pin_applied", false); //$NON-NLS-1$
            result.addProperty("runtime_pin_note", //$NON-NLS-1$
                    "dry_run never mutates the EDT pin store; a real run pins before updating"); //$NON-NLS-1$
        }
        EdtRuntimeService.ResolvedRuntimeInfo info = runtimeService.describeUpdateRuntime(projectName);
        if (info != null) {
            JsonObject runtimeUsed = new JsonObject();
            runtimeUsed.addProperty("version", info.version() == null ? "" : info.version()); //$NON-NLS-1$ //$NON-NLS-2$
            runtimeUsed.addProperty("location", info.location() == null ? "" : info.location()); //$NON-NLS-1$ //$NON-NLS-2$
            runtimeUsed.addProperty("pinned", info.pinned()); //$NON-NLS-1$
            result.add("runtime_used", runtimeUsed); //$NON-NLS-1$
            if (!info.pinned()) {
                result.addProperty("runtime_auto_resolved", true); //$NON-NLS-1$
            }
        }
    }

    /**
     * Runs the EDT infobase update inside an Eclipse {@link Job} so its progress surfaces in the
     * workbench progress area (status bar + Progress view) — the platform forwards the job's
     * {@link IProgressMonitor} into EDT's update flow, which reports its sub-tasks ("Designer agent
     * apply…") against it. Addresses feedback 2026-05-29-update-infobase-progress-visibility:
     * previously the update ran with a {@code NullProgressMonitor}, so a 1-3 minute bind was
     * completely silent in the GUI.
     *
     * <p>The job is non-user (no modal popup) and non-system (visible), matching how
     * indexing/build progress shows. We schedule it and {@link Job#join() join} on the current
     * worker thread (the tool already runs off the UI thread, in {@code supplyAsync} or the
     * background-job pool), so the synchronous/async contract and the rendered result are
     * unchanged — only a GUI affordance is added. Headless (no workbench) the job still runs
     * normally; there is simply no progress UI to populate.</p>
     */
    private EdtRuntimeService.UpdateInfobaseStatus runUpdateWithGuiProgress(String projectName,
            boolean keepConnected) throws Exception {
        AtomicReference<EdtRuntimeService.UpdateInfobaseStatus> statusRef = new AtomicReference<>();
        AtomicReference<Exception> errorRef = new AtomicReference<>();
        String taskName = "Updating infobase: " + projectName + "…"; //$NON-NLS-1$ //$NON-NLS-2$
        Job job = new Job(taskName) {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                monitor.beginTask(taskName, IProgressMonitor.UNKNOWN);
                try {
                    statusRef.set(runtimeService.updateInfobaseWithStatus(projectName, keepConnected, monitor));
                    return Status.OK_STATUS;
                } catch (Exception e) {
                    errorRef.set(e);
                    String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    return new Status(IStatus.ERROR, VibeCorePlugin.PLUGIN_ID,
                            "update_infobase failed for project: " + projectName + " (" + detail + ")", e); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                } finally {
                    monitor.done();
                }
            }
        };
        job.setUser(false);   // status-bar/Progress-view affordance, not a modal dialog
        job.setSystem(false); // keep it visible to the user
        job.setPriority(Job.LONG);
        job.schedule();
        try {
            job.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            job.cancel();
            throw e;
        }
        Exception failure = errorRef.get();
        if (failure != null) {
            throw failure;
        }
        return statusRef.get();
    }

    private static JsonObject basePayload(String opId, String status, String projectName, boolean dryRun,
            File workspaceRoot) {
        JsonObject result = new JsonObject();
        result.addProperty("op_id", opId); //$NON-NLS-1$
        result.addProperty("status", status); //$NON-NLS-1$
        result.addProperty("project_name", projectName); //$NON-NLS-1$
        result.addProperty("dry_run", dryRun); //$NON-NLS-1$
        result.addProperty("workspace_root", workspaceRoot == null ? "" : workspaceRoot.getAbsolutePath()); //$NON-NLS-1$ //$NON-NLS-2$
        return result;
    }

    private static JsonObject errorPayload(String opId, String projectName, File workspaceRoot,
            EdtToolErrorCode code, String message) {
        JsonObject result = basePayload(opId, "error", projectName, false, workspaceRoot); //$NON-NLS-1$
        result.addProperty("updated", false); //$NON-NLS-1$
        result.addProperty("error_code", code.name()); //$NON-NLS-1$
        result.addProperty("message", message == null ? "" : message); //$NON-NLS-1$ //$NON-NLS-2$
        result.add("details", new JsonObject()); //$NON-NLS-1$
        return result;
    }

    protected File getWorkspaceRoot() {
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace() == null ? null : ResourcesPlugin.getWorkspace().getRoot();
        return root == null || root.getLocation() == null ? null : root.getLocation().toFile();
    }

    private static String pretty(JsonObject object) {
        return new GsonBuilder().setPrettyPrinting().create().toJson(object);
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    /**
     * Coerces a JSON-decoded value into a boolean. Accepts {@link Boolean},
     * canonical strings ({@code "true"}, {@code "false"}, case-insensitive,
     * with surrounding whitespace ignored), and {@link Number} (non-zero ->
     * true). Falls back to {@code defaultValue} on null/unrecognized input.
     */
    static boolean asBoolean(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean b) {
            return b.booleanValue();
        }
        if (value instanceof Number n) {
            return n.doubleValue() != 0d;
        }
        String s = String.valueOf(value).trim();
        if (s.isEmpty()) {
            return defaultValue;
        }
        if ("true".equalsIgnoreCase(s) || "1".equals(s)) { //$NON-NLS-1$ //$NON-NLS-2$
            return true;
        }
        if ("false".equalsIgnoreCase(s) || "0".equals(s)) { //$NON-NLS-1$ //$NON-NLS-2$
            return false;
        }
        return defaultValue;
    }

    /**
     * Returns {@code true} when the exception chain contains a message that
     * indicates the infobase is locked by an external process. The platform's
     * config-export step fails with a "file not found" for a temp xml.zip when
     * it cannot obtain exclusive access to the infobase (e.g. Apache wsap
     * holds the file open). "xml.zip" in the message is the reliable
     * discriminator for this class of failure.
     */
    private static boolean isBlockedByLockedIB(Throwable error) {
        Throwable t = error;
        while (t != null) {
            String msg = t.getMessage();
            if (msg != null && msg.contains("xml.zip")) { //$NON-NLS-1$
                return true;
            }
            t = t.getCause();
        }
        return false;
    }
}
