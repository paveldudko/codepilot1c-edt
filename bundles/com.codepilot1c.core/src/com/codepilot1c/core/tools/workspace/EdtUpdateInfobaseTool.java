package com.codepilot1c.core.tools.workspace;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.AbstractTool;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
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
import com.codepilot1c.core.edt.runtime.InfobaseIdentity;
import com.codepilot1c.core.edt.runtime.InfobaseSiblingResolver;
import com.codepilot1c.core.internal.VibeCorePlugin;
import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.logging.VibeLogger;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
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

    /**
     * Default hard cap on the platform update (seconds) so a held infobase / EDT modal can never hang
     * the call forever. Caller-overridable via {@code timeout_s}: a full-schema exclusive update on a
     * multi-hundred-MB+ file infobase can genuinely need longer than the default (feedback
     * 2026-07-16-update-infobase-process-timeout-300s-ceiling-reproducible).
     */
    private static final int DEFAULT_UPDATE_TIMEOUT_S = 300;

    /** Lower/upper clamp for a caller-supplied {@code timeout_s}. */
    private static final int MIN_UPDATE_TIMEOUT_S = 60;
    private static final int MAX_UPDATE_TIMEOUT_S = 1800;

    /**
     * In-flight-slot sentinels. A synchronous run ({@code "sync"}) and the transient async-registration
     * window ({@code "starting"}) hold the guard slot but have NO pollable {@code job_id} —
     * {@code update_infobase_status} cannot resolve them (feedback
     * 2026-07-16-update-infobase-sync-job-id-unpollable). Only a real async job upgrades the slot to a
     * {@link BackgroundJobRegistry} job id.
     */
    private static final String SLOT_SENTINEL_SYNC = "sync"; //$NON-NLS-1$
    private static final String SLOT_SENTINEL_STARTING = "starting"; //$NON-NLS-1$

    /**
     * Process-wide guard against a second concurrent (schema) update of the SAME project while one is
     * in flight. EDT applies an update through a Designer thick-client session that is single-connection
     * per infobase — a re-fired update on the same IB does not run twice, it collides ("Infobase … is
     * already connected") and can wedge the platform. Live finding BF-12705 (2026-07-10): a caller
     * re-fired an async update while the first job was still RUNNING; the two Designer sessions contended
     * and the update hung ~51 min.
     *
     * <p>Keyed by the CANONICAL INFOBASE IDENTITY (not the project name): the Designer connection is
     * single-per-INFOBASE, so two DIFFERENT projects bound to the same infobase — a configuration and
     * its extension, or two configuration projects on one {@code .1CD} — collide in exactly the same
     * way, and under the old per-project key both were let through. Falls back to the project name when
     * the infobase does not resolve. The slot value carries the in-flight {@code job_id} (or a sentinel
     * while it is being registered / for a synchronous run) plus the owning project, so a rejected
     * caller learns which job to poll and which project holds this infobase instead of piling on.
     * Static so it is shared across tool instances. Dry runs never acquire it (no Designer session).</p>
     */
    private static final ConcurrentMap<String, UpdateSlot> IN_FLIGHT_UPDATES =
            new ConcurrentHashMap<>();

    /** Max characters of a raw cause chain echoed into a payload / the bundle log. */
    private static final int MAX_RAW_ERROR_CHARS = 1200;

    /** EDT {@code InfobaseEqualityState.EQUAL} constant name — the skip_if_current trigger. */
    private static final String EQUALITY_EQUAL = "EQUAL"; //$NON-NLS-1$

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
                },
                "kill_agent_mode": {
                  "type": "boolean",
                  "description": "Узкий случай: убить phantom-Designer'ы (1cv8 DESIGNER /AgentMode) этой ИБ перед взятием lock. ВАЖНО: чаще всего эксклюзив держит НЕ phantom, а веб-сервер (Apache wsap) — тогда это НЕ поможет; останавливайте Apache (см. allow_webserver_running / web_publication restart). Алиас auto_kill_phantoms (default: false)."
                },
                "allow_webserver_running": {
                  "type": "boolean",
                  "description": "По умолчанию update_infobase для ФАЙЛОВОЙ ИБ отказывается работать, если запущен веб-сервер (Apache wsap/httpd): эксклюзивный (схемный) апдейт завис бы намертво на удержанной ИБ. Поставьте true, чтобы всё равно попробовать — безопасно для динамического BSL-апдейта или если веб-сервер публикует ДРУГУЮ ИБ (default: false → fail-fast с подсказкой остановить Apache)."
                },
                "skip_if_current": {
                  "type": "boolean",
                  "description": "Skip the update when the infobase already equals the project configuration (EDT getEqualityState == EQUAL, an in-memory state query — no configurator/DESIGNER spawned, no lease taken). Default: false. If the state cannot be determined the tool falls back to a normal update."
                },
                "timeout_s": {
                  "type": "integer",
                  "description": "Жёсткий предел (секунды) на применение обновления, после которого вызов прерывается. По умолчанию 300. Полное схемное обновление большой файловой ИБ (сотни МБ+, несколько новых объектов метаданных) реально может не уложиться в 300с — поднимайте это значение (макс 1800). PROCESS_TIMEOUT сообщает истёкший timeout_s и PID(ы) Designer'а, которые могли остаться держать блокировку. Диапазон 60..1800."
                }
              },
              "required": ["project_name"]
            }
            """; //$NON-NLS-1$

    private final EdtProjectResolver projectResolver;
    private final EdtRuntimeService runtimeService;
    private final InfobaseSiblingResolver siblingResolver;

    public EdtUpdateInfobaseTool() {
        this(new EdtProjectResolver(), new EdtRuntimeService());
    }

    public EdtUpdateInfobaseTool(EdtProjectResolver projectResolver, EdtRuntimeService runtimeService) {
        this(projectResolver, runtimeService, new InfobaseSiblingResolver());
    }

    public EdtUpdateInfobaseTool(EdtProjectResolver projectResolver, EdtRuntimeService runtimeService,
            InfobaseSiblingResolver siblingResolver) {
        this.projectResolver = projectResolver;
        this.runtimeService = runtimeService;
        this.siblingResolver = siblingResolver;
    }

    @Override
    public String getDescription() {
        return "Updates the infobase linked to an EDT project via the EDT runtime. " //$NON-NLS-1$
                + "Platform version: EDT pin (runtime_version pins persistently) > auto " //$NON-NLS-1$
                + "(NEWEST installed, including pre-releases) — check runtime_used in dry_run. " //$NON-NLS-1$
                + "Gate on schema_applied, not on status: a non-exclusive apply commits code but " //$NON-NLS-1$
                + "defers the schema and answers status=partial, updated=false."; //$NON-NLS-1$
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
        boolean killAgentMode = asBoolean(parameters == null ? null : parameters.get("kill_agent_mode"), false) //$NON-NLS-1$
                || asBoolean(parameters == null ? null : parameters.get("auto_kill_phantoms"), false); //$NON-NLS-1$
        boolean allowWebserverRunning = asBoolean(parameters == null ? null : parameters.get("allow_webserver_running"), false); //$NON-NLS-1$
        boolean skipIfCurrent = asBoolean(parameters == null ? null : parameters.get("skip_if_current"), false); //$NON-NLS-1$
        String runtimeVersionRaw = asString(parameters == null ? null : parameters.get("runtime_version")); //$NON-NLS-1$
        String runtimeVersion = runtimeVersionRaw == null || runtimeVersionRaw.isBlank() ? null : runtimeVersionRaw;
        int timeoutSeconds = clampTimeoutSeconds(
                asInt(parameters == null ? null : parameters.get("timeout_s"), DEFAULT_UPDATE_TIMEOUT_S)); //$NON-NLS-1$
        long timeoutMs = timeoutSeconds * 1000L;

        if (async && dryRun) {
            // Dry-run is fast and deterministic; running it synchronously avoids
            // roundtripping through the background registry. Surface an explicit
            // flag so the caller knows the async request was intentionally ignored.
            LOG.info("[%s] edt_update_infobase async+dry_run: running sync (async_ignored)", opId); //$NON-NLS-1$
        } else if (async) {
            String updateKey = resolveUpdateKey(projectName);
            UpdateSlot slot = new UpdateSlot(projectName, SLOT_SENTINEL_STARTING);
            UpdateSlot inFlight = tryAcquireUpdate(updateKey, slot);
            if (inFlight != null) {
                LOG.warn("[%s] edt_update_infobase async rejected: an update is already in flight on this " //$NON-NLS-1$
                        + "infobase (requested by %s, held by %s)", opId, projectName, inFlight.project()); //$NON-NLS-1$
                return CompletableFuture.completedFuture(
                        ToolResult.failure(pretty(alreadyRunningPayload(opId, projectName, workspaceRoot, inFlight))));
            }
            try {
                String jobId = BackgroundJobRegistry.getInstance().startJob(
                        "edt_update_infobase", //$NON-NLS-1$
                        () -> {
                            try {
                                return runUpdateAndRenderResult(opId, projectName, keepConnected, workspaceRoot,
                                        runtimeVersion, killAgentMode, allowWebserverRunning, skipIfCurrent,
                                        timeoutMs);
                            } finally {
                                // Release only our own slot (identity-checked) so a fast job that finished
                                // before the caller upgraded the value below can never leak the key.
                                releaseUpdate(updateKey, slot);
                            }
                        });
                slot.setJobId(jobId); // upgrade "starting" -> real job_id so a concurrent caller polls it
                LOG.info("[%s] edt_update_infobase scheduled async job=%s", opId, jobId); //$NON-NLS-1$
                JsonObject accepted = basePayload(opId, "scheduled", projectName, false, workspaceRoot); //$NON-NLS-1$
                accepted.addProperty("async", true); //$NON-NLS-1$
                accepted.addProperty("job_id", jobId); //$NON-NLS-1$
                accepted.addProperty("state", BackgroundJobRegistry.JobState.RUNNING.name());
                accepted.addProperty("updated", false); //$NON-NLS-1$
                // Not a verdict — the job has only been accepted. Present so that "no schema_applied
                // field" never has to be read as "an old build", which is what made ask #2 ambiguous.
                accepted.addProperty("schema_applied", false); //$NON-NLS-1$
                accepted.add("details", new JsonObject()); //$NON-NLS-1$
                return CompletableFuture.completedFuture(
                        ToolResult.success(pretty(accepted), ToolResult.ToolResultType.CODE));
            } catch (RejectedExecutionException e) {
                releaseUpdate(updateKey, slot);
                LOG.warn("[%s] edt_update_infobase async rejected: %s", opId, e.getMessage()); //$NON-NLS-1$
                JsonObject rejected = basePayload(opId, "error", projectName, false, workspaceRoot); //$NON-NLS-1$
                rejected.addProperty("updated", false); //$NON-NLS-1$
                rejected.addProperty("schema_applied", false); //$NON-NLS-1$
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
            String ibPath = null;
            // Guard a synchronous (schema) update the same way as the async path: refuse a second
            // concurrent update of this INFOBASE rather than let two Designer sessions collide. Dry runs
            // spawn no Designer session, so they neither acquire the guard nor pay for its key
            // resolution. BF-12705 (2026-07-10).
            String updateKey = dryRun ? null : resolveUpdateKey(projectName);
            UpdateSlot slot = null;
            if (!dryRun) {
                slot = new UpdateSlot(projectName, SLOT_SENTINEL_SYNC);
                UpdateSlot inFlight = tryAcquireUpdate(updateKey, slot);
                if (inFlight != null) {
                    LOG.warn("[%s] edt_update_infobase rejected: an update is already in flight on this " //$NON-NLS-1$
                            + "infobase (requested by %s, held by %s)", opId, projectName, inFlight.project()); //$NON-NLS-1$
                    return ToolResult.failure(
                            pretty(alreadyRunningPayload(opId, projectName, workspaceRoot, inFlight)));
                }
            }
            try {
                InfobaseReference infobase = projectResolver.resolveInfobase(projectName, workspaceRoot);
                ibPath = fileIbPath(infobase);
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
                // Opt-in equality pre-check: short-circuit a redundant update BEFORE any pin/
                // lease/webserver side effect. getEqualityState is an in-memory EDT query.
                String equalityState = null;
                if (!dryRun && skipIfCurrent) {
                    equalityState = runtimeService.readInfobaseEqualityState(projectName);
                    if (EQUALITY_EQUAL.equals(equalityState)) {
                        fillSkippedEqual(result);
                        // "skipped because EQUAL" is the strongest false-green on a shared infobase:
                        // this project matches, a sibling extension may not. Name them here.
                        annotateSiblings(result, projectName, true);
                        LOG.info("[%s] edt_update_infobase skip_if_current: EQUAL, update skipped", opId); //$NON-NLS-1$
                        return ToolResult.success(pretty(result), ToolResult.ToolResultType.CODE);
                    }
                }
                applyRuntimeControls(result, projectName, runtimeVersion, dryRun);
                if (dryRun) {
                    result.addProperty("updated", false); //$NON-NLS-1$
                    result.addProperty("schema_applied", false); //$NON-NLS-1$
                    return ToolResult.success(pretty(result), ToolResult.ToolResultType.CODE);
                }
                // Lease first: on live pools a web server is ALWAYS up, so the webserver guard
                // would mask the more specific EDT_LEASE_HELD from a non-holder.
                runtimeService.checkUpdateLease(projectName);
                preflightWebserverGuard(allowWebserverRunning, ibPath);
                killPhantomsIfRequested(result, killAgentMode, ibPath);
                EdtRuntimeService.UpdateInfobaseStatus status =
                        runUpdateWithGuiProgress(projectName, keepConnected, timeoutMs, ibPath);
                fillAppliedOutcome(result, status.updated(), status.dynamicOnly());
                if (skipIfCurrent) {
                    annotateEqualityProceeding(result, equalityState);
                }
                annotateWebserverConsistency(result, ibPath, status);
                annotateDynamicOnly(result, status);
                annotatePostUpdateEquality(result, projectName, status);
                annotateSiblings(result, projectName, false);
                if (!status.updated()) {
                    throw new EdtToolException(EdtToolErrorCode.UPDATE_FAILED,
                            "EDT update returned false for project: " + projectName); //$NON-NLS-1$
                }
                return ToolResult.success(pretty(result), ToolResult.ToolResultType.CODE);
            } catch (EdtToolException e) {
                return ToolResult.failure(pretty(errorPayloadFrom(opId, projectName, workspaceRoot, e)));
            } catch (Exception e) {
                // Damaged-target-DB FIRST: isBlockedByLockedIB matches a bare "xml.zip" substring
                // anywhere in the chain, which a broken database's config-export failure also carries —
                // it would mask the real cause as IB_LOCKED and send the caller hunting for a holder.
                if (isTargetDbDamaged(e)) {
                    return ToolResult.failure(
                            pretty(damagedTargetDbPayload(opId, projectName, workspaceRoot, ibPath, e)));
                } else if (isBlockedByLockedIB(e)) {
                    return ToolResult.failure(pretty(lockedIbPayload(opId, projectName, workspaceRoot, ibPath)));
                } else if (isBlockedByHttpClients(e)) {
                    JsonObject error = errorPayload(opId, projectName, workspaceRoot,
                            EdtToolErrorCode.UPDATE_BLOCKED_BY_HTTP_CLIENTS, e.getMessage());
                    error.addProperty("hint", HTTP_CLIENTS_HINT); //$NON-NLS-1$
                    return ToolResult.failure(pretty(error));
                }
                JsonObject failure = errorPayload(opId, projectName, workspaceRoot,
                        EdtToolErrorCode.UPDATE_FAILED, e.getMessage());
                attachCauseChain(failure, e, "UPDATE_FAILED (unclassified)"); //$NON-NLS-1$
                return ToolResult.failure(pretty(failure));
            } finally {
                if (slot != null) {
                    releaseUpdate(updateKey, slot);
                }
            }
        });
    }

    /**
     * Name-based in-flight-guard key (null/blank tolerated) — the fallback used when the project's
     * infobase cannot be resolved. Package-private for tests.
     */
    static String updateKey(String projectName) {
        return projectName == null ? "" : projectName.trim(); //$NON-NLS-1$
    }

    /**
     * The in-flight-guard key for a project + its infobase connection string: the CANONICAL infobase
     * identity when one is available ({@link InfobaseIdentity#canonical}, the plugin's single "same
     * infobase" rule, tolerant of slash direction / case / a trailing separator), else the project
     * name. Two different projects on ONE infobase therefore map to ONE slot — which is the point:
     * they share the single Designer connection. Pure; package-private for unit tests.
     */
    static String updateKey(String projectName, String infobaseConnectionString) {
        String canonical = InfobaseIdentity.canonical(infobaseConnectionString);
        if (canonical == null || canonical.isBlank()) {
            return updateKey(projectName);
        }
        return "ib:" + canonical; //$NON-NLS-1$
    }

    /**
     * Resolves the project's default infobase (best-effort) and derives the guard key from it. A
     * resolution failure is not fatal — the guard degrades to the old per-project key and the update
     * path surfaces its own canonical error for a missing project/infobase.
     */
    private String resolveUpdateKey(String projectName) {
        String connection = null;
        try {
            connection = InfobaseIdentity.identityOf(runtimeService.resolveDefaultInfobase(projectName));
        } catch (RuntimeException | LinkageError e) {
            // LinkageError: no EDT/Eclipse runtime at all (headless unit tests) — the guard still works,
            // just per project name.
            LOG.debug("in-flight guard: infobase unresolved for %s, keying by project name (%s)", //$NON-NLS-1$
                    projectName, e.getMessage());
        }
        return updateKey(projectName, connection);
    }

    /**
     * Reserves the single in-flight-update slot for {@code key}. Returns {@code null} when the slot was
     * reserved (the caller now owns {@code mySlot} and MUST {@link #releaseUpdate release} it), otherwise
     * the holding slot — meaning a concurrent update is already running on this infobase and
     * {@code mySlot} was NOT registered. Package-private for unit tests.
     */
    static UpdateSlot tryAcquireUpdate(String key, UpdateSlot mySlot) {
        return IN_FLIGHT_UPDATES.putIfAbsent(key, mySlot);
    }

    /**
     * Releases the in-flight-update slot, but only when {@code mySlot} is still the registered holder
     * (identity check) — so a caller can never evict another update's slot. Package-private for tests.
     */
    static void releaseUpdate(String key, UpdateSlot mySlot) {
        IN_FLIGHT_UPDATES.remove(key, mySlot);
    }

    /**
     * The in-flight-update reservation: the {@code job_id} of the running update (or a
     * {@code "sync"}/{@code "starting"} sentinel) plus the project that started it. The project name is
     * carried because the slot is keyed by INFOBASE identity — a rejected caller must be told WHICH
     * project holds this infobase, otherwise "already running" reads as nonsense for a project that has
     * no update of its own. Package-private for unit tests.
     */
    static final class UpdateSlot {

        private final String project;
        private final AtomicReference<String> jobId;

        UpdateSlot(String project, String jobId) {
            this.project = project == null ? "" : project.trim(); //$NON-NLS-1$
            this.jobId = new AtomicReference<>(jobId);
        }

        /** Upgrades the {@code "starting"} sentinel to the real registry job id once it is known. */
        void setJobId(String value) {
            jobId.set(value);
        }

        String jobId() {
            return jobId.get();
        }

        String project() {
            return project;
        }
    }

    /** Visible for testing: true when an update is currently registered as in-flight for the project. */
    static boolean hasInFlightUpdate(String projectName) {
        return IN_FLIGHT_UPDATES.containsKey(updateKey(projectName));
    }

    /** Visible for testing: drop all in-flight-update reservations (isolation between test cases). */
    static void clearInFlightUpdatesForTest() {
        IN_FLIGHT_UPDATES.clear();
    }

    /**
     * Builds the {@code UPDATE_ALREADY_RUNNING} rejection payload: a second concurrent update of the
     * same INFOBASE was refused because one is already in flight. Carries the in-flight {@code job_id}
     * (when known) so the caller polls the existing job with {@code update_infobase_status} instead of
     * re-firing — the re-fire is what wedged BF-12705 — and {@code in_flight_project}, because on a
     * shared infobase the holder can be a DIFFERENT project than the one being refused.
     */
    private static JsonObject alreadyRunningPayload(String opId, String projectName, File workspaceRoot,
            UpdateSlot held) {
        String inFlightSlot = held == null ? null : held.jobId();
        String holderProject = held == null ? null : held.project();
        boolean foreignHolder = holderProject != null && !holderProject.isBlank()
                && !holderProject.equals(updateKey(projectName));
        JsonObject json = errorPayload(opId, projectName, workspaceRoot,
                EdtToolErrorCode.UPDATE_ALREADY_RUNNING,
                (foreignHolder
                        ? "An infobase update is already in progress for project '" + holderProject //$NON-NLS-1$
                                + "', which shares THIS infobase with '" + projectName + "'. " //$NON-NLS-1$ //$NON-NLS-2$
                        : "An infobase update is already in progress for project '" + projectName + "'. ") //$NON-NLS-1$ //$NON-NLS-2$
                        + "EDT applies updates through a single-connection Designer session per INFOBASE, " //$NON-NLS-1$
                        + "so a second concurrent update on the same infobase would collide and can wedge " //$NON-NLS-1$
                        + "the platform. Do NOT start another update against this infobase."); //$NON-NLS-1$
        json.addProperty("error", "update_already_running"); //$NON-NLS-1$ //$NON-NLS-2$
        if (holderProject != null && !holderProject.isBlank()) {
            json.addProperty("in_flight_project", holderProject); //$NON-NLS-1$
        }
        // Only a real async job has a pollable id. A synchronous run (or the transient async-registration
        // window) holds the guard slot with a sentinel that update_infobase_status cannot resolve — telling
        // the caller to poll "sync"/"starting" produced the "Unknown job: sync" dead end (feedback
        // 2026-07-16-update-infobase-sync-job-id-unpollable).
        if (isPollableJobId(inFlightSlot)) {
            json.addProperty("in_flight_job_id", inFlightSlot); //$NON-NLS-1$
            json.addProperty("in_flight_pollable", true); //$NON-NLS-1$
            json.addProperty("hint", //$NON-NLS-1$
                    "Poll update_infobase_status(job_id=\"" + inFlightSlot //$NON-NLS-1$
                            + "\", wait_for_completion=true) until it reaches a terminal state, then re-check."); //$NON-NLS-1$
        } else if (SLOT_SENTINEL_SYNC.equals(inFlightSlot)) {
            json.addProperty("in_flight_mode", "sync"); //$NON-NLS-1$ //$NON-NLS-2$
            json.addProperty("in_flight_pollable", false); //$NON-NLS-1$
            json.addProperty("hint", //$NON-NLS-1$
                    "A synchronous update is in flight on this infobase's single Designer connection; it has " //$NON-NLS-1$
                            + "no pollable job_id (\"sync\" is a sentinel, not a job). Wait for the blocking " //$NON-NLS-1$
                            + "call to return. If you suspect it wedged, check get_infobase_sync_state and scan " //$NON-NLS-1$
                            + "for a phantom Designer, then retry once it clears. For a pollable job next time, " //$NON-NLS-1$
                            + "call update_infobase with async=true."); //$NON-NLS-1$
        } else {
            json.addProperty("in_flight_mode", "starting"); //$NON-NLS-1$ //$NON-NLS-2$
            json.addProperty("in_flight_pollable", false); //$NON-NLS-1$
            json.addProperty("hint", //$NON-NLS-1$
                    "An async update for this project is being registered (transient). Retry the poll in a " //$NON-NLS-1$
                            + "moment; a real job_id will be available shortly."); //$NON-NLS-1$
        }
        return json;
    }

    /**
     * True when {@code slot} is a real {@link BackgroundJobRegistry} job id — i.e. not blank and not one
     * of the {@link #SLOT_SENTINEL_SYNC}/{@link #SLOT_SENTINEL_STARTING} sentinels that
     * {@code update_infobase_status} cannot resolve. Package-private for unit tests.
     */
    static boolean isPollableJobId(String slot) {
        return slot != null && !slot.isBlank()
                && !SLOT_SENTINEL_SYNC.equals(slot) && !SLOT_SENTINEL_STARTING.equals(slot);
    }

    /**
     * Runs the non-dry update synchronously and renders a pretty JSON payload.
     * Used by the background job path so the job result mirrors the regular
     * synchronous output.
     */
    private String runUpdateAndRenderResult(String opId, String projectName, boolean keepConnected,
            File workspaceRoot, String runtimeVersion, boolean killAgentMode, boolean allowWebserverRunning,
            boolean skipIfCurrent, long timeoutMs) {
        LOG.info("[%s] START edt_update_infobase (async)", opId); //$NON-NLS-1$
        String ibPath = null;
        try {
            InfobaseReference infobase = projectResolver.resolveInfobase(projectName, workspaceRoot);
            ibPath = fileIbPath(infobase);
            JsonObject result = basePayload(opId, "updated", projectName, false, workspaceRoot); //$NON-NLS-1$
            JsonObject details = new JsonObject();
            if (infobase != null && infobase.getConnectionString() != null) {
                details.addProperty("infobase_connection", //$NON-NLS-1$
                        infobase.getConnectionString().asConnectionString());
            }
            result.add("details", details); //$NON-NLS-1$
            // Opt-in equality pre-check — mirrors the synchronous path.
            String equalityState = null;
            if (skipIfCurrent) {
                equalityState = runtimeService.readInfobaseEqualityState(projectName);
                if (EQUALITY_EQUAL.equals(equalityState)) {
                    fillSkippedEqual(result);
                    annotateSiblings(result, projectName, true);
                    LOG.info("[%s] edt_update_infobase (async) skip_if_current: EQUAL, update skipped", opId); //$NON-NLS-1$
                    return pretty(result);
                }
            }
            applyRuntimeControls(result, projectName, runtimeVersion, false);
            // Lease first — see the synchronous path for why this precedes the webserver guard.
            runtimeService.checkUpdateLease(projectName);
            preflightWebserverGuard(allowWebserverRunning, ibPath);
            killPhantomsIfRequested(result, killAgentMode, ibPath);
            EdtRuntimeService.UpdateInfobaseStatus status =
                    runUpdateWithGuiProgress(projectName, keepConnected, timeoutMs, ibPath);
            boolean updated = status.updated();
            fillAppliedOutcome(result, updated, status.dynamicOnly());
            if (skipIfCurrent) {
                annotateEqualityProceeding(result, equalityState);
            }
            annotateWebserverConsistency(result, ibPath, status);
            annotateDynamicOnly(result, status);
            annotatePostUpdateEquality(result, projectName, status);
            annotateSiblings(result, projectName, false);
            if (!updated) {
                JsonObject error = errorPayload(opId, projectName, workspaceRoot,
                        EdtToolErrorCode.UPDATE_FAILED,
                        "EDT update returned false for project: " + projectName); //$NON-NLS-1$
                return pretty(error);
            }
            return pretty(result);
        } catch (EdtToolException e) {
            return pretty(errorPayloadFrom(opId, projectName, workspaceRoot, e));
        } catch (Exception e) {
            // Damaged target DB before the locked-IB check — see the synchronous path for why.
            if (isTargetDbDamaged(e)) {
                return pretty(damagedTargetDbPayload(opId, projectName, workspaceRoot, ibPath, e));
            } else if (isBlockedByLockedIB(e)) {
                return pretty(lockedIbPayload(opId, projectName, workspaceRoot, ibPath));
            } else if (isBlockedByHttpClients(e)) {
                JsonObject error = errorPayload(opId, projectName, workspaceRoot,
                        EdtToolErrorCode.UPDATE_BLOCKED_BY_HTTP_CLIENTS, e.getMessage());
                error.addProperty("hint", HTTP_CLIENTS_HINT); //$NON-NLS-1$
                return pretty(error);
            }
            JsonObject failure = errorPayload(opId, projectName, workspaceRoot,
                    EdtToolErrorCode.UPDATE_FAILED, e.getMessage());
            attachCauseChain(failure, e, "UPDATE_FAILED (unclassified, async)"); //$NON-NLS-1$
            return pretty(failure);
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
            boolean keepConnected, long timeoutMs, String ibPath) throws Exception {
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
            // Bounded join: never block forever. A held infobase (web server / client) or an EDT
            // modal can wedge the platform update; without a cap the caller just hangs until the
            // MCP transport times out and the worker stays stuck. Abort with a clear error instead.
            boolean completed = job.join(timeoutMs, null);
            if (!completed) {
                job.cancel();
                long timeoutSeconds = timeoutMs / 1000L;
                List<Long> designerPids = scanUpdateDesignerPids(ibPath);
                throw new EdtToolException(EdtToolErrorCode.PROCESS_TIMEOUT,
                        processTimeoutMessage(timeoutSeconds, designerPids),
                        processTimeoutDetails(timeoutSeconds, designerPids));
            }
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

    /**
     * Best-effort scan for the Designer process(es) EDT spawned for this file infobase that are still
     * holding it at abort time. Surfaced (not killed) in the {@code PROCESS_TIMEOUT} payload: killing a
     * Designer that is genuinely mid-restructure would corrupt the in-progress update, so the caller
     * decides. Returns an empty list for a server infobase (null {@code ibPath}) or on any scan failure.
     */
    private static List<Long> scanUpdateDesignerPids(String ibPath) {
        if (ibPath == null || ibPath.isBlank()) {
            return List.of();
        }
        try {
            List<Long> pids = new java.util.ArrayList<>();
            for (InfobaseProcessScanner.LockingProcess p : InfobaseProcessScanner.scan(ibPath)) {
                if (p.targetIb() && (p.kind() == InfobaseProcessScanner.LockKind.DESIGNER
                        || p.kind() == InfobaseProcessScanner.LockKind.DESIGNER_AGENT)) {
                    pids.add(p.pid());
                }
            }
            return pids;
        } catch (Exception e) {
            LOG.debug("scanUpdateDesignerPids failed for %s: %s", ibPath, e.getMessage()); //$NON-NLS-1$
            return List.of();
        }
    }

    /**
     * The {@code PROCESS_TIMEOUT} message. Reworked (feedback
     * 2026-07-16-update-infobase-process-timeout-300s-ceiling-reproducible): the old text asserted "held
     * by another process" as the sole cause, which was misleading when the real cause was a large IB that
     * simply needed more than the ceiling. Now it names both possibilities, points at {@code timeout_s},
     * and surfaces the still-alive Designer PID(s) so the caller can clean up before retrying.
     */
    static String processTimeoutMessage(long timeoutSeconds, List<Long> designerPids) {
        StringBuilder sb = new StringBuilder();
        sb.append("Infobase update did not complete within ").append(timeoutSeconds) //$NON-NLS-1$
                .append("s and was aborted. This can mean the update is genuinely still restructuring a ") //$NON-NLS-1$
                .append("large infobase — raise timeout_s (max ").append(MAX_UPDATE_TIMEOUT_S) //$NON-NLS-1$
                .append(") and retry — OR the infobase is held by another process (a running web server / ") //$NON-NLS-1$
                .append("wsap publication, or an open client/Designer). "); //$NON-NLS-1$
        if (designerPids != null && !designerPids.isEmpty()) {
            sb.append("The Designer process(es) EDT spawned for this update (pid ") //$NON-NLS-1$
                    .append(joinPids(designerPids))
                    .append(") are STILL RUNNING — the tool did NOT terminate them (aborting a Designer ") //$NON-NLS-1$
                    .append("that is genuinely mid-restructure would corrupt the update), so they may ") //$NON-NLS-1$
                    .append("still hold the infobase file lock and may still be doing real work. If the ") //$NON-NLS-1$
                    .append("update is genuinely wedged (NOT still progressing), retry with ") //$NON-NLS-1$
                    .append("kill_agent_mode=true to terminate them right before the lock is taken. "); //$NON-NLS-1$
        } else {
            sb.append("The scan found NO still-running Designer bound to this infobase, so there is ") //$NON-NLS-1$
                    .append("probably nothing to sweep before a retry — but note that on Windows a ") //$NON-NLS-1$
                    .append("process whose command line cannot be read cannot be attributed to an ") //$NON-NLS-1$
                    .append("infobase at all, so one manual Get-CimInstance Win32_Process check is ") //$NON-NLS-1$
                    .append("still worth doing before ruling a phantom out. "); //$NON-NLS-1$
        }
        sb.append("Stop any real holder (web_publication action=restart, or close the client) or raise ") //$NON-NLS-1$
                .append("timeout_s, then retry."); //$NON-NLS-1$
        return sb.toString();
    }

    /**
     * Structured detail fields for a {@code PROCESS_TIMEOUT}, rendered by {@link #errorPayloadFrom}.
     * The PID field is keyed {@code designer_pids_still_holding} (NOT {@code aborted_designer_pids}):
     * the tool surfaces but does NOT kill these — the earlier {@code aborted_*} name wrongly implied a
     * kill and let a caller race a still-live process (infra feedback 2026-07-16). Package-private for
     * the ergonomics unit test.
     */
    static Map<String, String> processTimeoutDetails(long timeoutSeconds, List<Long> designerPids) {
        Map<String, String> details = new java.util.LinkedHashMap<>();
        details.put("update_timeout_s", Long.toString(timeoutSeconds)); //$NON-NLS-1$
        if (designerPids != null && !designerPids.isEmpty()) {
            details.put("designer_pids_still_holding", joinPids(designerPids)); //$NON-NLS-1$
        } else {
            // An OMITTED key was the only signal for "found nothing", and it reads exactly like a build
            // that never had the feature. That ambiguity is how a 2026-07-29 retest concluded the PID is
            // "still absent from the payload" for a build that had been surfacing it since 3ca38df. Say
            // the empty result out loud so the next report is decidable either way.
            details.put("designer_scan", "no_designer_bound_to_this_infobase"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return details;
    }

    private static String joinPids(List<Long> pids) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pids.size(); i++) {
            if (i > 0) {
                sb.append(','); //$NON-NLS-1$
            }
            sb.append(pids.get(i));
        }
        return sb.toString();
    }

    /**
     * Rewrites {@code result} into an EQUAL-skip outcome: {@code status=skipped},
     * {@code skipped=true}, {@code updated=false}, {@code equality_state=EQUAL}, plus a human
     * message. Used by both the synchronous and async paths when {@code skip_if_current} matched.
     * Package-private so the sibling-annotation test can build the exact skip payload.
     */
    static void fillSkippedEqual(JsonObject result) {
        result.addProperty("status", "skipped"); //$NON-NLS-1$ //$NON-NLS-2$
        result.addProperty("skipped", true); //$NON-NLS-1$
        result.addProperty("updated", false); //$NON-NLS-1$
        // Nothing was applied, yet the schema IS live: the infobase already equals the project. That is
        // the one outcome where the two fields legitimately disagree, and why schema_applied cannot be
        // read off updated — see fillAppliedOutcome.
        result.addProperty("schema_applied", true); //$NON-NLS-1$
        result.addProperty("equality_state", EQUALITY_EQUAL); //$NON-NLS-1$
        result.addProperty("message", //$NON-NLS-1$
                "Infobase already equals the project configuration (getEqualityState=EQUAL); " //$NON-NLS-1$
                        + "update skipped because skip_if_current=true. No configurator was spawned."); //$NON-NLS-1$
    }

    /**
     * Annotates a proceeding (non-skip) update payload with the opt-in {@code skip_if_current}
     * fields: {@code skipped=false} and the observed {@code equality_state} (the enum name, or JSON
     * {@code null} when EDT could not determine it and the tool fell back to a normal update).
     */
    private static void annotateEqualityProceeding(JsonObject result, String equalityState) {
        result.addProperty("skipped", false); //$NON-NLS-1$
        result.addProperty("equality_state", equalityState); //$NON-NLS-1$
    }

    /**
     * Annotates every proceeding update with the equality state read AFTER the apply, unconditionally
     * — {@code equality_state} above is the opt-in PRE-check and is absent unless the caller passed
     * {@code skip_if_current}, which is exactly the caller who then reads {@code updated=true} as
     * "infobase now matches" and runs tests against stale code (feedback
     * {@code 2026-07-24-yaxunit-run-silent-zero-tests-looks-like-passed.md}). The read is the same
     * in-memory EDT query as the pre-check — no DESIGNER spawned — so making the result
     * self-describing costs nothing.
     *
     * <p>Advisory only: a failure to read never fails an otherwise successful update, and the field is
     * simply omitted. When the apply reported success yet the state is still NOT_EQUAL, this is the
     * documented non-convergence mode — say so here instead of making the caller issue a second call
     * and guess. The dynamic-only case already carries its own richer warning, so stay quiet there.</p>
     */
    private void annotatePostUpdateEquality(JsonObject result, String projectName,
            EdtRuntimeService.UpdateInfobaseStatus status) {
        String after;
        try {
            after = runtimeService.readInfobaseEqualityState(projectName);
        } catch (RuntimeException | LinkageError e) {
            // The service contract is "never throws"; LinkageError still covers a missing EDT runtime.
            LOG.debug("update_infobase: post-update equality read failed for %s: %s", //$NON-NLS-1$
                    projectName, e.getMessage());
            return;
        }
        annotatePostUpdateEquality(result, after, status != null && status.updated(),
                status != null && status.dynamicOnly());
    }

    /**
     * Pure half of {@link #annotatePostUpdateEquality(JsonObject, String, EdtRuntimeService.UpdateInfobaseStatus)}
     * — package-private so the payload shape is unit-testable without an EDT runtime.
     */
    static void annotatePostUpdateEquality(JsonObject result, String after, boolean applied,
            boolean dynamicOnly) {
        if (after == null || after.isBlank()) {
            return;
        }
        result.addProperty("equality_state_after", after); //$NON-NLS-1$
        if (applied && !dynamicOnly && !EQUALITY_EQUAL.equals(after)) {
            result.addProperty("equality_state_after_warning", //$NON-NLS-1$
                    "The update reported success yet the infobase still differs from the project. Do NOT " //$NON-NLS-1$
                            + "re-run the same update expecting convergence — it will not converge. Apply " //$NON-NLS-1$
                            + "one EXCLUSIVE update with no other client/Designer session holding the " //$NON-NLS-1$
                            + "infobase, or treat this as advisory for a change with no schema impact."); //$NON-NLS-1$
        }
    }

    /**
     * Writes the pair a caller gates on — {@code updated} and {@code schema_applied} — and downgrades
     * {@code status} to {@code partial} when the apply was dynamic.
     *
     * <p>Owner-ruled contract change 2026-07-29 (feedback
     * {@code 2026-07-29-update-infobase-timeout-s-accepted-retest-and-dynamic-only-masking.md} §3).
     * A run that could not take the exclusive lock used to answer {@code updated:true} NEXT TO
     * {@code dynamic_only:true}: the top-level success flag said "done" while the restructure had not
     * happened. Measured live on a 1.51 GB file infobase — an agent gating on {@code updated:true},
     * the documented happy path, accepted an infobase whose schema was not live, and it was caught
     * only because a human read the whole payload.</p>
     *
     * <p>On an apply the two fields coincide — {@code updated} is the legacy name kept truthful for
     * existing callers, {@code schema_applied} the self-describing one that answers "is the schema live"
     * without inferring it from the absence of {@code dynamic_only}. They are not redundant: an
     * EQUAL-skip applies nothing ({@code updated:false}) while the schema IS live
     * ({@code schema_applied:true}), which is the case a caller most needs to tell from a dynamic apply.
     * The field is present on every outcome of this tool, so no caller has to read absence as a value.</p>
     *
     * <p>The CALL stays successful. A dynamic apply does commit non-schema changes — BSL code lands —
     * so for a code-only update this is a complete result, and routing it to the error channel would
     * be a lie in the other direction. The hard failure stays bound to EDT's own verdict.</p>
     */
    static void fillAppliedOutcome(JsonObject result, boolean edtUpdated, boolean dynamicOnly) {
        boolean schemaApplied = edtUpdated && !dynamicOnly;
        result.addProperty("updated", schemaApplied); //$NON-NLS-1$
        result.addProperty("schema_applied", schemaApplied); //$NON-NLS-1$
        if (edtUpdated && dynamicOnly) {
            result.addProperty("status", "partial"); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * Flags a dynamic (non-exclusive) apply, with the forward-warning the read side cannot infer on its
     * own: EDT compares the STORED configuration, which a dynamic update DOES commit, while deferring
     * the physical restructure — so a later {@code get_infobase_sync_state} can legitimately answer
     * EQUAL for a schema that is not live (issue
     * {@code issues/deceptive-equal-after-dynamic-only-update.md}). No-op when the apply was exclusive.
     */
    private static void annotateDynamicOnly(JsonObject result, EdtRuntimeService.UpdateInfobaseStatus status) {
        if (status == null || !status.dynamicOnly()) {
            return;
        }
        // EDT could not acquire an exclusive lock (existing client/test sessions hold the infobase).
        // The platform fell back to a dynamic-mode update, which does not apply schema changes (new
        // handlers, new metadata). Surface the flag so callers close TC sessions and rerun.
        result.addProperty("dynamic_only", true); //$NON-NLS-1$
        result.addProperty("dynamic_only_reason", //$NON-NLS-1$
                "Could not acquire exclusive lock; existing client/test " //$NON-NLS-1$
                        + "sessions blocked the update. Schema changes are NOT live."); //$NON-NLS-1$
        result.addProperty("dynamic_only_forward_warning", //$NON-NLS-1$
                "A subsequent get_infobase_sync_state may report EQUAL anyway — it compares the STORED " //$NON-NLS-1$
                        + "configuration, which this dynamic update DID commit, while the physical " //$NON-NLS-1$
                        + "restructure was deferred. Do NOT read that EQUAL as 'schema applied': re-apply " //$NON-NLS-1$
                        + "with an exclusive lock (close client/test sessions, or kill_agent_mode=true) and " //$NON-NLS-1$
                        + "re-verify before trusting it."); //$NON-NLS-1$
    }

    /**
     * Annotates a payload with the shared-infobase fan-out: the OTHER open projects bound to this
     * infobase that must converge with it but currently report NOT_EQUAL. Additive OUTPUT only — no new
     * input parameter, so the dispatcher schema and the tool contract are untouched.
     */
    private void annotateSiblings(JsonObject result, String projectName, boolean skippedEqual) {
        List<InfobaseSiblingResolver.Sibling> siblings;
        try {
            siblings = siblingResolver.siblingsOf(projectName);
        } catch (RuntimeException | LinkageError e) {
            // Advisory only — never fail an otherwise successful update over the fan-out (LinkageError
            // covers a missing EDT/Eclipse runtime, e.g. headless unit tests).
            LOG.debug("update_infobase: sibling fan-out failed for %s: %s", projectName, e.getMessage()); //$NON-NLS-1$
            return;
        }
        annotateSiblings(result, siblings, skippedEqual);
    }

    /**
     * Pure half of {@link #annotateSiblings(JsonObject, String, boolean)}: emits
     * {@code sibling_projects_stale} whenever the infobase is shared (possibly an empty array — proof
     * the check ran) and {@code sibling_warning} when a must-converge sibling has diverged.
     * Package-private for unit tests.
     */
    static void annotateSiblings(JsonObject result, List<InfobaseSiblingResolver.Sibling> siblings,
            boolean skippedEqual) {
        if (siblings == null || siblings.isEmpty()) {
            return;
        }
        List<String> stale = InfobaseSiblingResolver.staleProjects(siblings);
        JsonArray staleArray = new JsonArray();
        for (String project : stale) {
            staleArray.add(project);
        }
        result.add("sibling_projects_stale", staleArray); //$NON-NLS-1$
        if (!stale.isEmpty()) {
            result.addProperty("sibling_warning", siblingWarning(stale, skippedEqual)); //$NON-NLS-1$
        }
    }

    /**
     * The shared-infobase warning text. The {@code skippedEqual} variant is the important one: "skipped
     * because EQUAL" is exactly the payload an operator/agent reads as "the infobase is current", while
     * a sibling extension may still be unapplied. Package-private for unit tests.
     */
    static String siblingWarning(List<String> staleProjects, boolean skippedEqual) {
        String projects = String.join(", ", staleProjects); //$NON-NLS-1$
        StringBuilder sb = new StringBuilder();
        if (skippedEqual) {
            sb.append("The update was skipped because THIS project already equals the infobase — but the ") //$NON-NLS-1$
                    .append("infobase is SHARED with project(s) ").append(projects) //$NON-NLS-1$
                    .append(" that report NOT_EQUAL, so the skip does NOT mean the infobase is current. "); //$NON-NLS-1$
        } else {
            sb.append("This infobase is SHARED with project(s) ").append(projects) //$NON-NLS-1$
                    .append(" that report NOT_EQUAL. "); //$NON-NLS-1$
        }
        sb.append("EDT's equality state is per (project, infobase) pair: run update_infobase separately ") //$NON-NLS-1$
                .append("for each of those projects (project_name=<that project>) before treating the ") //$NON-NLS-1$
                .append("infobase as up to date."); //$NON-NLS-1$
        return sb.toString();
    }

    /** Platform wording for "the target database's configuration structure is damaged" (EN + RU). */
    private static final String[] TARGET_DB_DAMAGED_TOKENS = {
        "integrity of configuration structure", //$NON-NLS-1$
        "целостность структуры конфигурации", //$NON-NLS-1$
    };

    private static final String TARGET_DB_DAMAGED_HINT =
            "The TARGET DATABASE is damaged — this is NOT a problem with the configuration in git and NOT " //$NON-NLS-1$
                    + "a lock/holder problem. The platform refused the update because the infobase's own " //$NON-NLS-1$
                    + "configuration structure is broken, so no update or restructure can be applied until " //$NON-NLS-1$
                    + "the database itself is repaired: run chdbfl.exe -s \"<ib-dir>\\1Cv8.1CD\" for a file " //$NON-NLS-1$
                    + "infobase, or Designer -> Administration -> Testing and repair. NB: tools that read " //$NON-NLS-1$
                    + "only the EDT model (get_diagnostics, metadata_smoke) report GREEN here by design — " //$NON-NLS-1$
                    + "they never open the target database, so their success says nothing about it."; //$NON-NLS-1$

    /**
     * True when the failure chain says the TARGET database structure is damaged. Must be checked BEFORE
     * {@link #isBlockedByLockedIB}: that predicate matches a bare {@code "xml.zip"} substring anywhere
     * in the chain, and a damaged database fails its config-export step with the very same temp-file
     * message — so the more specific cause has to win, or the caller is sent hunting for a lock holder
     * that does not exist. Package-private for unit tests.
     */
    static boolean isTargetDbDamaged(Throwable error) {
        int guard = 0;
        for (Throwable current = error; current != null && guard < MAX_CAUSE_DEPTH; current = current.getCause()) {
            guard++;
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase(Locale.ROOT);
                for (String token : TARGET_DB_DAMAGED_TOKENS) {
                    if (lower.contains(token)) {
                        return true;
                    }
                }
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }

    /** Depth cap for cause-chain walks (self-referencing chains are also guarded explicitly). */
    private static final int MAX_CAUSE_DEPTH = 12;

    /**
     * Builds the {@code TARGET_INFOBASE_DAMAGED} payload: the repair hint, the concrete
     * {@code chdbfl.exe} command for a file infobase, and the raw cause chain.
     */
    private static JsonObject damagedTargetDbPayload(String opId, String projectName, File workspaceRoot,
            String ibPath, Throwable error) {
        JsonObject payload = errorPayload(opId, projectName, workspaceRoot,
                EdtToolErrorCode.TARGET_INFOBASE_DAMAGED, error == null ? null : error.getMessage());
        payload.addProperty("hint", TARGET_DB_DAMAGED_HINT); //$NON-NLS-1$
        if (ibPath != null && !ibPath.isBlank()) {
            payload.addProperty("infobase_path", ibPath); //$NON-NLS-1$
            payload.addProperty("repair_command", repairCommand(ibPath)); //$NON-NLS-1$
        }
        attachCauseChain(payload, error, "TARGET_INFOBASE_DAMAGED"); //$NON-NLS-1$
        return payload;
    }

    /** {@code chdbfl.exe -s "<ib-dir>\1Cv8.1CD"} for a file infobase directory. Package-private for tests. */
    static String repairCommand(String ibPath) {
        String dir = ibPath.trim();
        while (dir.endsWith("\\") || dir.endsWith("/")) { //$NON-NLS-1$ //$NON-NLS-2$
            dir = dir.substring(0, dir.length() - 1);
        }
        return "chdbfl.exe -s \"" + dir + "\\1Cv8.1CD\""; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Echoes the raw cause chain into the payload ({@code raw_error}) and the bundle log. The exact
     * platform wording of a damaged-target-DB failure has never been observed live, so BOTH a successful
     * classification and every unclassified {@code UPDATE_FAILED} carry their raw chain: the first live
     * occurrence then confirms or refutes {@link #TARGET_DB_DAMAGED_TOKENS} without a separate
     * diagnostic build.
     */
    private static void attachCauseChain(JsonObject payload, Throwable error, String context) {
        String chain = causeChain(error, MAX_RAW_ERROR_CHARS);
        if (chain.isEmpty()) {
            return;
        }
        payload.addProperty("raw_error", chain); //$NON-NLS-1$
        LOG.warn("edt_update_infobase %s: raw cause chain: %s", context, chain); //$NON-NLS-1$
    }

    /**
     * Flattens an exception chain into {@code Type: message <- Type: message …}, truncated to
     * {@code maxChars}. Pure; package-private for unit tests.
     */
    static String causeChain(Throwable error, int maxChars) {
        StringBuilder sb = new StringBuilder();
        int guard = 0;
        for (Throwable current = error; current != null && guard < MAX_CAUSE_DEPTH; current = current.getCause()) {
            guard++;
            if (sb.length() > 0) {
                sb.append(" <- "); //$NON-NLS-1$
            }
            sb.append(current.getClass().getSimpleName());
            String message = current.getMessage();
            if (message != null && !message.isBlank()) {
                sb.append(": ").append(message.trim()); //$NON-NLS-1$
            }
            if (current.getCause() == current) {
                break;
            }
        }
        if (maxChars > 0 && sb.length() > maxChars) {
            return sb.substring(0, maxChars) + "…(truncated)"; //$NON-NLS-1$
        }
        return sb.toString();
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
        result.addProperty("schema_applied", false); //$NON-NLS-1$
        result.addProperty("error_code", code.name()); //$NON-NLS-1$
        result.addProperty("message", message == null ? "" : message); //$NON-NLS-1$ //$NON-NLS-2$
        result.add("details", new JsonObject()); //$NON-NLS-1$
        return result;
    }

    /**
     * Error payload from a thrown {@link EdtToolException}, enriched with its detail fields — for
     * {@code EDT_LEASE_HELD} this surfaces the same structured {@code holder} object manage_leases
     * returns, so the caller reads {@code holder.stack_id} instead of parsing the message.
     */
    private static JsonObject errorPayloadFrom(String opId, String projectName, File workspaceRoot,
            EdtToolException e) {
        JsonObject json = errorPayload(opId, projectName, workspaceRoot, e.getCode(), e.getMessage());
        java.util.Map<String, String> details = e.getDetails();
        if (details == null || details.isEmpty()) {
            return json;
        }
        JsonObject holder = new JsonObject();
        if (details.containsKey("holder_stack_id")) { //$NON-NLS-1$
            holder.addProperty("stack_id", details.get("holder_stack_id")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (details.containsKey("holder_workspace")) { //$NON-NLS-1$
            holder.addProperty("workspace", details.get("holder_workspace")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (details.containsKey("holder_host")) { //$NON-NLS-1$
            holder.addProperty("host", details.get("holder_host")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (details.containsKey("holder_pid")) { //$NON-NLS-1$
            try {
                holder.addProperty("pid", Long.valueOf(details.get("holder_pid"))); //$NON-NLS-1$ //$NON-NLS-2$
            } catch (NumberFormatException ignored) {
                holder.addProperty("pid", details.get("holder_pid")); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        if (holder.size() > 0) {
            json.add("holder", holder); //$NON-NLS-1$
        }
        if (details.containsKey("acquired_at")) { //$NON-NLS-1$
            json.addProperty("acquired_at", details.get("acquired_at")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (details.containsKey("branch")) { //$NON-NLS-1$
            json.addProperty("branch", details.get("branch")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (details.containsKey("update_timeout_s")) { //$NON-NLS-1$
            try {
                json.addProperty("timeout_s", Long.valueOf(details.get("update_timeout_s"))); //$NON-NLS-1$ //$NON-NLS-2$
            } catch (NumberFormatException ignored) {
                json.addProperty("timeout_s", details.get("update_timeout_s")); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        if (details.containsKey("designer_pids_still_holding")) { //$NON-NLS-1$
            JsonArray pids = new JsonArray();
            for (String raw : details.get("designer_pids_still_holding").split(",")) { //$NON-NLS-1$ //$NON-NLS-2$
                String pid = raw.trim();
                if (pid.isEmpty()) {
                    continue;
                }
                try {
                    pids.add(Long.valueOf(pid));
                } catch (NumberFormatException ignored) {
                    pids.add(pid);
                }
            }
            if (!pids.isEmpty()) {
                // Surfaced, not killed — see processTimeoutDetails. The name must not imply a kill.
                json.add("designer_pids_still_holding", pids); //$NON-NLS-1$
            }
        }
        // This renderer is an ALLOWLIST: a detail key with no branch here never reaches the payload at
        // all. The empty-scan marker needs its own branch for that reason — dropping the field that
        // exists to disambiguate an absence would have reproduced the very defect it addresses.
        if (details.containsKey("designer_scan")) { //$NON-NLS-1$
            json.addProperty("designer_scan", details.get("designer_scan")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return json;
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
     * Coerces a JSON-decoded value into an int. Accepts {@link Number} and decimal strings (the
     * edt_diagnostics dispatcher may ship numeric params as strings). Falls back to {@code defaultValue}
     * on null/blank/unparseable input.
     */
    static int asInt(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        String s = String.valueOf(value).trim();
        if (s.isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** Clamps a requested update timeout (seconds) into {@code [MIN, MAX]}. Package-private for tests. */
    static int clampTimeoutSeconds(int requested) {
        return Math.max(MIN_UPDATE_TIMEOUT_S, Math.min(MAX_UPDATE_TIMEOUT_S, requested));
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

    /** Extracts the file-infobase path from a resolved reference, or {@code null}. */
    private static String fileIbPath(InfobaseReference infobase) {
        if (infobase == null || infobase.getConnectionString() == null) {
            return null;
        }
        return InfobaseProcessScanner.fileIbPath(infobase.getConnectionString().asConnectionString());
    }

    /**
     * When {@code killAgentMode} is set, terminates phantom {@code /AgentMode} Designer agents
     * bound to {@code ibPath} right before the exclusive lock is taken (the closest we can get to
     * winning EDT's respawn race) and records the outcome on {@code result}. No-op otherwise.
     * Feedback {@code 2026-06-10-bf11104-update-infobase-ib-locked-phantom-respawn.md}.
     */
    private static void killPhantomsIfRequested(JsonObject result, boolean killAgentMode, String ibPath) {
        if (!killAgentMode) {
            return;
        }
        result.addProperty("kill_agent_mode", true); //$NON-NLS-1$
        List<Long> killed = InfobaseProcessScanner.killPhantomDesigners(ibPath);
        JsonArray arr = new JsonArray();
        for (Long pid : killed) {
            arr.add(pid);
        }
        result.add("killed_phantoms", arr); //$NON-NLS-1$
        if (ibPath == null) {
            result.addProperty("kill_agent_mode_note", //$NON-NLS-1$
                    "infobase path unknown (server/standalone IB) — no phantom could be targeted"); //$NON-NLS-1$
        }
    }

    /**
     * Fail-fast pre-flight: refuse the update (instead of hanging) when a web server is running and
     * could hold this FILE infobase. An exclusive (schema) update would block indefinitely on the
     * wsap lock / an EDT modal — the headless caller then just times out. We detect the condition
     * and return a clear, actionable error. Opt out with {@code allow_webserver_running=true} for a
     * dynamic BSL-only update (or when the web server publishes a different IB). Live finding
     * 2026-06-11: a schema update with Apache up hung indefinitely (survived phantom-kill + httpd
     * stop, needed an EDT restart). NB: server/standalone IBs (ibPath null) are not guarded.
     */
    private static void preflightWebserverGuard(boolean allowWebserverRunning, String ibPath) {
        if (allowWebserverRunning || ibPath == null) {
            return;
        }
        if (InfobaseProcessScanner.anyWebserverRunning()) {
            throw new EdtToolException(EdtToolErrorCode.UPDATE_BLOCKED_BY_WEBSERVER,
                    "A web server (Apache wsap/httpd) is running and may hold this file infobase " //$NON-NLS-1$
                            + "exclusively. An exclusive (schema) update would block indefinitely, so it " //$NON-NLS-1$
                            + "was refused up front rather than hung. Stop the web server (web_publication " //$NON-NLS-1$
                            + "action=restart, or stop httpd), then retry. If this is a dynamic BSL-only " //$NON-NLS-1$
                            + "update, or the web server publishes a DIFFERENT infobase, pass " //$NON-NLS-1$
                            + "allow_webserver_running=true to attempt anyway."); //$NON-NLS-1$
        }
    }

    /**
     * Adds a consistency advisory when a web server (Apache wsap/httpd) is running while a FILE
     * infobase was updated. A successful EDT apply does NOT guarantee that live wsap-published
     * sessions reload the changed modules — they may keep serving stale/partial state until the
     * publication is restarted. Per-IB attribution from the process list is unreliable (httpd's
     * command line carries no IB path; the binding lives in the .vrd), so this is an advisory,
     * not a hard refusal. Feedback {@code 2026-06-10-phase6-correct-silence-deploy-tooling.md §1}.
     */
    private static void annotateWebserverConsistency(JsonObject result, String ibPath,
            EdtRuntimeService.UpdateInfobaseStatus status) {
        if (ibPath == null || status == null || !status.updated()) {
            return;
        }
        if (InfobaseProcessScanner.anyWebserverRunning()) {
            result.addProperty("webserver_running", true); //$NON-NLS-1$
            result.addProperty("consistency_warning", //$NON-NLS-1$
                    "A web server (Apache wsap/httpd) is running. If it publishes THIS file infobase, " //$NON-NLS-1$
                            + "live sessions may keep serving stale/partial modules until the publication " //$NON-NLS-1$
                            + "is restarted — updated=true means EDT applied the config, not that published " //$NON-NLS-1$
                            + "sessions reloaded it. Restart the publication (web_publication action=restart) " //$NON-NLS-1$
                            + "and re-verify at runtime."); //$NON-NLS-1$
        }
    }

    /**
     * Builds the IB_LOCKED error payload, attaching a path-filtered, classified list of the
     * processes that may hold the infobase ({@code is_target_ib} flags the ones bound to THIS IB)
     * and an actionable hint — including the {@code kill_agent_mode} suggestion when a phantom
     * {@code /AgentMode} Designer is detected on the target infobase.
     */
    private static JsonObject lockedIbPayload(String opId, String projectName, File workspaceRoot,
            String ibPath) {
        JsonObject payload = errorPayload(opId, projectName, workspaceRoot, EdtToolErrorCode.IB_LOCKED,
                "Infobase is locked by another process (Apache wsap publication, Designer session, or another client holds exclusive access). Stop the blocking process, then retry update_infobase."); //$NON-NLS-1$
        List<InfobaseProcessScanner.LockingProcess> processes = InfobaseProcessScanner.scan(ibPath);
        boolean phantomOnTarget = processes.stream().anyMatch(
                p -> p.kind() == InfobaseProcessScanner.LockKind.DESIGNER_AGENT && p.targetIb());
        if (phantomOnTarget) {
            payload.addProperty("hint", //$NON-NLS-1$
                    "A phantom /AgentMode Designer agent is bound to this infobase and EDT respawns it " //$NON-NLS-1$
                            + "within seconds. Retry with kill_agent_mode=true to terminate it right before " //$NON-NLS-1$
                            + "the lock is taken."); //$NON-NLS-1$
        } else {
            payload.addProperty("hint", //$NON-NLS-1$
                    "Stop all processes holding the infobase open (httpd/wsap, running thin clients, Designer agents), then retry. Use Stop-PhantomDesigner if a Designer agent is stuck."); //$NON-NLS-1$
        }
        JsonArray locking = renderLockingProcesses(processes);
        if (locking.size() > 0) {
            payload.add("locking_processes", locking); //$NON-NLS-1$
        }
        return payload;
    }

    private static JsonArray renderLockingProcesses(List<InfobaseProcessScanner.LockingProcess> processes) {
        JsonArray arr = new JsonArray();
        for (InfobaseProcessScanner.LockingProcess p : processes) {
            JsonObject entry = new JsonObject();
            entry.addProperty("pid", p.pid()); //$NON-NLS-1$
            if (p.command() != null) {
                entry.addProperty("command", p.command()); //$NON-NLS-1$
            }
            if (p.commandLine() != null) {
                entry.addProperty("command_line", p.commandLine()); //$NON-NLS-1$
            }
            entry.addProperty("kind", p.kind().name().toLowerCase(Locale.ROOT)); //$NON-NLS-1$
            entry.addProperty("is_target_ib", p.targetIb()); //$NON-NLS-1$
            arr.add(entry);
        }
        return arr;
    }
}
