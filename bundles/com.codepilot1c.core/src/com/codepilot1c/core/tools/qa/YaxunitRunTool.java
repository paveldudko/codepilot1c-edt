/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.qa;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;

import com.codepilot1c.core.edt.runtime.EdtProjectResolver;
import com.codepilot1c.core.edt.runtime.EdtRuntimeService;
import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.qa.QaJUnitReport;
import com.codepilot1c.core.tools.AbstractTool;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.core.tools.workspace.InfobaseProcessScanner;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Runs YAxUnit unit tests for an EDT project.
 *
 * <p>Spawns the thin client ({@code 1cv8c.exe}) in ENTERPRISE mode with the YAxUnit
 * {@code RunUnitTests=<config>} startup parameter (no TestManager — YAxUnit runs in-process from
 * the extension installed in the infobase). The run config asks YAxUnit to emit a jUnit report and
 * to write a deterministic {@code exitCode} file (0=success, 1=failures); the tool polls for that
 * file (or process exit), then parses the jUnit XML into a structured summary.</p>
 *
 * <p>Reuses {@link EdtRuntimeService} for infobase / thin-client resolution and
 * {@link QaJUnitReport} for report parsing — the heavy lifting Vanessa's {@code qa_run} already
 * carries. There is no TestManager, TestClient feature-mirror or auto-inject layer here.</p>
 */
@ToolMeta(
        name = "yaxunit_run",
        category = "diagnostics",
        surfaceCategory = "qa",
        mutating = true,
        tags = {"workspace", "edt"})
public class YaxunitRunTool extends AbstractTool {

    public interface ProcessStarter {
        Process start(ProcessBuilder processBuilder) throws IOException;
    }

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(YaxunitRunTool.class);

    private static final int DEFAULT_TIMEOUT_SECONDS = 300;
    /** The one report file YAxUnit is told to write; any other {@code *.xml} in the run dir is foreign. */
    private static final String JUNIT_REPORT_NAME = "junit.xml"; //$NON-NLS-1$
    private static final int MAX_FAILURE_DETAILS = 50;
    private static final int LOG_TAIL_LINES = 50;
    private static final long HEARTBEAT_MILLIS = 30_000L;
    private static final long POLL_MILLIS = 1_000L;
    /** Grace window after exitCode.txt appears, letting YAxUnit flush junit.xml and self-close. */
    private static final long CLOSE_GRACE_MILLIS = 10_000L;

    /** Filter keys accepted from tool params, mapped 1:1 onto the YAxUnit config {@code filter}. */
    private static final String[] FILTER_KEYS = {"modules", "tests", "tags", "suites", "extensions", "contexts"};

    /**
     * Substrings (lower-cased) that, when present in the 1C/run logs of a run that produced no
     * report, point at the extension being attached in safe mode / with dangerous-action
     * protection — the failure mode that is otherwise an opaque empty result.
     */
    private static final String[] SAFE_MODE_MARKERS = {
            "безопасн", "защита от опасных", "опасных действий", "safe mode", "safemode",
            "нарушение прав доступа", "недостаточно прав"};

    /**
     * Owner-only remediation for the safe-mode / dangerous-action failure mode. Shared by the
     * no-report diagnosis and by the "zero tests, no filter" verdict — both land on the same fix.
     */
    private static final String SAFE_MODE_REMEDIATION =
            "The YAxUnit extension must run UNPROTECTED: in Designer open Configuration > Extensions, " //$NON-NLS-1$
            + "select the YAxUnit extension and uncheck \"Безопасный режим\" / \"Защита от опасных " //$NON-NLS-1$
            + "действий\", then update the infobase (this is an owner action — the plugin cannot change it)."; //$NON-NLS-1$

    /** Filter hints for a run that executed nothing while the infobase was current. */
    private static final String FILTER_HINT =
            "check the test name spelling (Модуль.Метод), that the test is registered in the module's " //$NON-NLS-1$
            + "YAxUnit ИсполняемыеСценарии handler, and that the 'extensions' filter names the extension " //$NON-NLS-1$
            + "the tests actually live in"; //$NON-NLS-1$

    /** Remediation for an infobase that no longer matches the EDT source. */
    private static final String STALE_HINT =
            "IB differs from EDT source — run update_infobase, then retry"; //$NON-NLS-1$

    private static final String SCHEMA = """
            {
              "type": "object",
              "description": "Запускает YAxUnit unit-тесты проекта EDT (тонкий клиент, RunUnitTests, без TestManager). После правки .bsl сначала вызови update_infobase: устаревшая ИБ выполнит ноль тестов. Пустые onec.log/launch.log при успешном прогоне — норма (детали в knowledge/edt-gotchas.md).",
              "properties": {
                "project_name": {
                  "type": "string",
                  "description": "Имя EDT-проекта, чья primary-инфобаза будет запущена."
                },
                "modules": {
                  "type": "array", "items": {"type": "string"},
                  "description": "Фильтр: имена тестовых общих модулей."
                },
                "tests": {
                  "type": "array", "items": {"type": "string"},
                  "description": "Фильтр: полные пути тестов в формате Модуль.Метод."
                },
                "tags": {
                  "type": "array", "items": {"type": "string"},
                  "description": "Фильтр: теги тестов."
                },
                "suites": {
                  "type": "array", "items": {"type": "string"},
                  "description": "Фильтр: имена наборов (suites)."
                },
                "extensions": {
                  "type": "array", "items": {"type": "string"},
                  "description": "Фильтр: имена расширений, из которых грузятся тесты."
                },
                "contexts": {
                  "type": "array", "items": {"type": "string"},
                  "description": "Фильтр: имена контекстов выполнения."
                },
                "test_client_login": {
                  "type": "string",
                  "description": "Логин сессии ИБ для запуска (вместо настроек EDT). Env-fallback: VANESSA_TEST_CLIENT_LOGIN."
                },
                "test_client_password": {
                  "type": "string",
                  "description": "Пароль к test_client_login (никогда не возвращается в результате). Env-fallback: VANESSA_TEST_CLIENT_PASSWORD."
                },
                "timeout_s": {
                  "type": "integer",
                  "description": "Таймаут прогона в секундах (default 300). По истечении дерево процессов убивается."
                },
                "runtime_version": {
                  "type": "string",
                  "description": "Версия платформы 1С для запуска клиента: линия ('8.3.27' — новейший установленный билд линии) или точный билд ('8.3.27.2074'). Без неё берётся pin из .launch проекта, иначе auto. Синоним: version_mask. Что реально выбрано — в runtime_used (возвращается и при dry_run)."
                },
                "version_mask": {
                  "type": "string",
                  "description": "Синоним runtime_version (историческое имя). При указании обоих побеждает runtime_version."
                },
                "dry_run": {
                  "type": "boolean",
                  "description": "Собрать config.json и команду без запуска процесса. Рантайм при этом всё равно резолвится — это самый дешёвый способ проверить, какой клиент будет запущен."
                }
              },
              "required": ["project_name"]
            }
            """; //$NON-NLS-1$

    private final EdtRuntimeService runtimeService;
    private final ProcessStarter processStarter;
    private final EdtProjectResolver projectResolver;

    public YaxunitRunTool() {
        this(new EdtRuntimeService(), ProcessBuilder::start);
    }

    public YaxunitRunTool(EdtRuntimeService runtimeService, ProcessStarter processStarter) {
        this(runtimeService, processStarter, new EdtProjectResolver());
    }

    public YaxunitRunTool(EdtRuntimeService runtimeService, ProcessStarter processStarter,
            EdtProjectResolver projectResolver) {
        this.runtimeService = runtimeService;
        this.processStarter = processStarter;
        this.projectResolver = projectResolver;
    }

    @Override
    public String getDescription() {
        return "Runs a project's YAxUnit unit tests in EDT via the thin client (RunUnitTests, no TestManager). " //$NON-NLS-1$
                + "Run update_infobase after editing .bsl and before running tests — a stale infobase executes " //$NON-NLS-1$
                + "zero tests."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Object> parameters = params.getRaw();
            String opId = LogSanitizer.newId("yaxunit-run"); //$NON-NLS-1$
            LOG.info("[%s] START yaxunit_run", opId); //$NON-NLS-1$

            String projectName = asString(parameters == null ? null : parameters.get("project_name")); //$NON-NLS-1$
            if (projectName == null || projectName.isBlank()) {
                return ToolResult.failure("project_name is required"); //$NON-NLS-1$
            }
            boolean dryRun = parameters != null && Boolean.TRUE.equals(parameters.get("dry_run")); //$NON-NLS-1$
            int timeoutSeconds = extractTimeoutSeconds(parameters);
            if (timeoutSeconds <= 0) {
                return ToolResult.failure("timeout_s must be a positive integer"); //$NON-NLS-1$
            }
            // runtime_version is the fleet-wide name (launch_app / update_infobase / connect_infobase all
            // use it); version_mask was this tool's own historical spelling and stays a synonym, because
            // the divergence itself was a trap for the calling model.
            String requestedVersion = asOptionalString(parameters == null ? null
                    : parameters.get("runtime_version")); //$NON-NLS-1$
            String legacyVersionMask = asOptionalString(parameters == null ? null
                    : parameters.get("version_mask")); //$NON-NLS-1$
            String ignoredVersionMask = null;
            if (requestedVersion == null) {
                requestedVersion = legacyVersionMask;
            } else if (legacyVersionMask != null && !legacyVersionMask.equals(requestedVersion)) {
                ignoredVersionMask = legacyVersionMask;
                LOG.warn("[%s] yaxunit_run: both runtime_version=%s and version_mask=%s given; using" //$NON-NLS-1$
                        + " runtime_version", opId, requestedVersion, legacyVersionMask); //$NON-NLS-1$
            }

            File workspaceRoot = getWorkspaceRoot();
            // Runtime pin priority: explicit param > the project's .launch pin (USE_AUTO=false) >
            // project+infobase-aware auto-resolution. Same order edt_launch_app applies, so the two
            // tools cannot resolve to different platforms for the same project.
            String pinnedVersion = requestedVersion != null ? null
                    : resolvePinnedRuntimeVersion(opId, projectName, workspaceRoot);
            String versionMask = requestedVersion != null ? requestedVersion : pinnedVersion;
            String runtimeSource = requestedVersion != null ? EdtRuntimeService.RUNTIME_SOURCE_PARAM
                    : (pinnedVersion != null ? EdtRuntimeService.RUNTIME_SOURCE_LAUNCH_CONFIG
                            : EdtRuntimeService.RUNTIME_SOURCE_AUTO);
            File runDir = buildRunDirectory(workspaceRoot, opId);
            File configFile = new File(runDir, "config.json"); //$NON-NLS-1$
            File junitFile = new File(runDir, JUNIT_REPORT_NAME);
            File exitCodeFile = new File(runDir, "exitcode.txt"); //$NON-NLS-1$
            File yaxunitLog = new File(runDir, "yaxunit.log"); //$NON-NLS-1$
            File onecLog = new File(runDir, "onec.log"); //$NON-NLS-1$
            File launchLog = new File(runDir, "launch.log"); //$NON-NLS-1$

            String configJson = buildRunConfigJson(parameters, junitFile, exitCodeFile, yaxunitLog);

            try {
                Files.write(configFile.toPath(), configJson.getBytes(StandardCharsets.UTF_8));

                EdtRuntimeService.AccessSettings access = resolveAccessSettings(opId, parameters);
                // onecLog gets the 1C client's own /Out log (builder.logTo); launchLog gets the OS
                // process stdout. Keeping them separate avoids interleaved double-writes.
                EdtRuntimeService.UnitTestLaunch launch = runtimeService.buildUnitTestLaunch(projectName,
                        configFile, onecLog, versionMask, access);
                EdtRuntimeService.ThinClientResolution runtime = relabel(launch.runtime(), runtimeSource);
                ProcessBuilder processBuilder = launch.processBuilder();
                processBuilder.directory(runDir);
                processBuilder.redirectErrorStream(true);
                processBuilder.redirectOutput(ProcessBuilder.Redirect.appendTo(launchLog));

                JsonObject result = baseResult(opId, projectName, runDir, configFile, junitFile, launchLog);
                result.addProperty("onec_log_path", onecLog.getAbsolutePath()); //$NON-NLS-1$
                addRuntimeUsed(result, runtime, requestedVersion, ignoredVersionMask);
                LOG.info("[%s] yaxunit_run runtime: %s", opId, //$NON-NLS-1$
                        runtime == null ? "unknown" : runtime.describe()); //$NON-NLS-1$
                JsonObject filter = filterJson(parameters);
                result.add("filter", filter); //$NON-NLS-1$
                boolean filterPresent = !filter.entrySet().isEmpty();
                addCommand(result, processBuilder.command());

                if (dryRun) {
                    result.addProperty("status", "dry_run"); //$NON-NLS-1$ //$NON-NLS-2$
                    return ToolResult.success(pretty(result), ToolResult.ToolResultType.CODE, result);
                }

                // Preflight: reap our own leaked thin clients (they hold the named pipe) and read
                // EDT's project-vs-infobase equality state. The equality state is reported even on a
                // green run — it is the only cheap signal that the tests just ran against stale code.
                List<String> preflightWarnings = checkStaledClientProcesses(opId, timeoutSeconds,
                        resolveFileIbPath(opId, projectName));
                String equalityState = readEqualityState(opId, projectName);
                result.addProperty("equality_state", equalityState == null ? "unknown" : equalityState); //$NON-NLS-1$ //$NON-NLS-2$
                if (isStaleState(equalityState)) {
                    String staleWarning = "PREFLIGHT WARNING: equality_state=" + equalityState + " — " //$NON-NLS-1$ //$NON-NLS-2$
                            + STALE_HINT + " (otherwise the run executes stale code, or no tests at all)."; //$NON-NLS-1$
                    preflightWarnings.add(staleWarning);
                    LOG.warn("[%s] %s", opId, staleWarning); //$NON-NLS-1$
                }
                if (!preflightWarnings.isEmpty()) {
                    JsonArray arr = new JsonArray();
                    preflightWarnings.forEach(arr::add);
                    result.add("preflight_warnings", arr); //$NON-NLS-1$
                }

                RunOutcome outcome = runAndAwait(processBuilder, opId, projectName, timeoutSeconds,
                        exitCodeFile, junitFile, launchLog);

                return buildResult(result, opId, runDir, junitFile, exitCodeFile, yaxunitLog, onecLog, launchLog,
                        outcome, filterPresent, equalityState);
            } catch (IOException e) {
                LOG.warn("[%s] yaxunit_run IO failure: %s", opId, e.getMessage()); //$NON-NLS-1$
                return ToolResult.failure("yaxunit_run failed: " + e.getMessage()); //$NON-NLS-1$
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ToolResult.failure("yaxunit_run interrupted"); //$NON-NLS-1$
            } catch (EdtRuntimeService.ThinClientNotResolvedException e) {
                // The one failure the caller could previously only see as a generic sentence. Report the
                // structured audit trail (what was tried, why each candidate lost) instead — this is the
                // whole reason the resolution became a record.
                JsonObject failure = baseResult(opId, projectName, runDir, configFile, junitFile, launchLog);
                failure.addProperty("status", "runtime_not_resolved"); //$NON-NLS-1$ //$NON-NLS-2$
                failure.addProperty("reason", "thin_client_not_resolved"); //$NON-NLS-1$ //$NON-NLS-2$
                failure.addProperty("message", e.getMessage()); //$NON-NLS-1$
                addRuntimeUsed(failure, relabel(e.getResolution(), runtimeSource), requestedVersion,
                        ignoredVersionMask);
                LOG.warn("[%s] yaxunit_run runtime not resolved: %s", opId, e.getMessage()); //$NON-NLS-1$
                return ToolResult.failure(pretty(failure));
            } catch (RuntimeException e) {
                LOG.warn("[%s] yaxunit_run failure: %s", opId, e.getMessage()); //$NON-NLS-1$
                return ToolResult.failure("yaxunit_run failed: " + e.getMessage()); //$NON-NLS-1$
            }
        });
    }

    // ---- run config -------------------------------------------------------------------------

    /**
     * Builds the YAxUnit run config JSON. Package-visible for unit testing.
     */
    static String buildRunConfigJson(Map<String, Object> parameters, File junitFile, File exitCodeFile,
            File yaxunitLog) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("reportPath", junitFile.getAbsolutePath()); //$NON-NLS-1$
        config.put("reportFormat", "jUnit"); //$NON-NLS-1$ //$NON-NLS-2$
        config.put("closeAfterTests", Boolean.TRUE); //$NON-NLS-1$
        config.put("showReport", Boolean.FALSE); //$NON-NLS-1$
        config.put("exitCode", exitCodeFile.getAbsolutePath()); //$NON-NLS-1$

        Map<String, Object> logging = new LinkedHashMap<>();
        logging.put("file", yaxunitLog.getAbsolutePath()); //$NON-NLS-1$
        logging.put("level", "debug"); //$NON-NLS-1$ //$NON-NLS-2$
        config.put("logging", logging); //$NON-NLS-1$

        Map<String, Object> filter = new LinkedHashMap<>();
        for (String key : FILTER_KEYS) {
            List<String> values = asStringList(parameters == null ? null : parameters.get(key));
            if (!values.isEmpty()) {
                filter.put(key, values);
            }
        }
        if (!filter.isEmpty()) {
            config.put("filter", filter); //$NON-NLS-1$
        }
        return new GsonBuilder().setPrettyPrinting().create().toJson(config);
    }

    /**
     * Writes the {@code runtime_used} block: which platform actually got launched, where it came from,
     * and — whether the resolution succeeded or not — the candidates that were probed with the reason
     * each rejected one lost.
     *
     * <p>Present on EVERY outcome including {@code dry_run}, because a dry run is the only cheap way to
     * ask "which client would you start?" without spending a real 300-second run. Package-visible so the
     * payload shape is unit-tested.</p>
     */
    static void addRuntimeUsed(JsonObject result, EdtRuntimeService.ThinClientResolution runtime,
            String requestedVersion, String ignoredVersionMask) {
        JsonObject runtimeUsed = new JsonObject();
        runtimeUsed.addProperty("version", //$NON-NLS-1$
                runtime == null || runtime.versionWithBuild() == null ? "" : runtime.versionWithBuild()); //$NON-NLS-1$
        runtimeUsed.addProperty("location", //$NON-NLS-1$
                runtime == null || runtime.location() == null ? "" : runtime.location()); //$NON-NLS-1$
        runtimeUsed.addProperty("source", //$NON-NLS-1$
                runtime == null || runtime.source() == null ? "" : runtime.source()); //$NON-NLS-1$
        runtimeUsed.addProperty("requested", requestedVersion == null ? "" : requestedVersion); //$NON-NLS-1$ //$NON-NLS-2$
        if (runtime != null && runtime.file() != null) {
            runtimeUsed.addProperty("binary", runtime.file().getAbsolutePath()); //$NON-NLS-1$
        }
        if (ignoredVersionMask != null) {
            runtimeUsed.addProperty("ignored_version_mask", ignoredVersionMask); //$NON-NLS-1$
        }
        if (runtime != null && !runtime.candidatesTried().isEmpty()) {
            runtimeUsed.add("candidates_tried", toJsonArray(runtime.candidatesTried())); //$NON-NLS-1$
        }
        if (runtime != null && !runtime.rejectReasons().isEmpty()) {
            runtimeUsed.add("reject_reasons", toJsonArray(runtime.rejectReasons())); //$NON-NLS-1$
        }
        result.add("runtime_used", runtimeUsed); //$NON-NLS-1$
    }

    private static JsonArray toJsonArray(List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }

    /**
     * Re-labels a service-level resolution with the source THIS tool derived. {@link EdtRuntimeService}
     * can only distinguish an explicit mask from auto-resolution; only the tool knows the mask came from
     * the project's {@code .launch} pin. Keeps the logged {@code describe()} and the envelope in sync.
     */
    private static EdtRuntimeService.ThinClientResolution relabel(
            EdtRuntimeService.ThinClientResolution runtime, String runtimeSource) {
        return runtime == null ? null : runtime.withSource(runtimeSource);
    }

    /**
     * Runtime version pinned in the project's {@code .launch} configuration, or {@code null}. Inherited
     * so an environment owner's explicit pin wins over "newest installed platform" — auto-resolution
     * happily picks a pre-release build, and unlike {@code launch_app} this tool never used to look at
     * the pin at all. Best-effort: never throws, never blocks the run.
     */
    private String resolvePinnedRuntimeVersion(String opId, String projectName, File workspaceRoot) {
        if (projectResolver == null || workspaceRoot == null) {
            return null;
        }
        try {
            return projectResolver.resolvePinnedRuntimeVersion(projectName, workspaceRoot);
        } catch (RuntimeException e) {
            LOG.warn("[%s] yaxunit_run: could not read the .launch runtime pin: %s", opId, e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    private static JsonObject filterJson(Map<String, Object> parameters) {
        JsonObject filter = new JsonObject();
        for (String key : FILTER_KEYS) {
            List<String> values = asStringList(parameters == null ? null : parameters.get(key));
            if (!values.isEmpty()) {
                JsonArray arr = new JsonArray();
                values.forEach(arr::add);
                filter.add(key, arr);
            }
        }
        return filter;
    }

    private EdtRuntimeService.AccessSettings resolveAccessSettings(String opId, Map<String, Object> parameters) {
        String login = resolveCred(parameters, "test_client_login", "VANESSA_TEST_CLIENT_LOGIN"); //$NON-NLS-1$ //$NON-NLS-2$
        String password = resolveCred(parameters, "test_client_password", "VANESSA_TEST_CLIENT_PASSWORD"); //$NON-NLS-1$ //$NON-NLS-2$
        boolean hasLogin = login != null && !login.isBlank();
        boolean hasPassword = password != null && !password.isBlank();
        if (hasLogin && hasPassword) {
            return EdtRuntimeService.AccessSettings.infobaseAuthentication(login, password, null);
        }
        if (hasLogin || hasPassword) {
            LOG.warn("[%s] yaxunit_run creds incomplete (login=%s, password=%s); using EDT access settings", //$NON-NLS-1$
                    opId, Boolean.valueOf(hasLogin), Boolean.valueOf(hasPassword));
            return null;
        }
        // Neither login nor password provided. For a FILE infobase an empty TestClient login makes the
        // thin client log in as the .1CD's cached last-user (inherited via robocopy from the live
        // File_am — often a real employee) who cannot run tests → silent no_report (BF-12562). Default
        // to the documented file-IB test account instead of falling through to that cached user. SERVER
        // IBs keep the current behaviour (return null → EDT-stored owner creds), which is intended.
        String projectName = asString(parameters == null ? null : parameters.get("project_name")); //$NON-NLS-1$
        if (runtimeService.isFileInfobase(projectName)) {
            LOG.warn("[%s] yaxunit_run: no test_client creds for a FILE infobase — defaulting to Admin/1 per test-runners.md (pass test_client_login/password to override).", //$NON-NLS-1$
                    opId);
            return EdtRuntimeService.AccessSettings.infobaseAuthentication("Admin", "1", null); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return null;
    }

    private static String resolveCred(Map<String, Object> parameters, String paramKey, String envVar) {
        if (parameters != null && parameters.get(paramKey) instanceof String text && !text.isBlank()) {
            return text;
        }
        String env = System.getenv(envVar);
        return (env != null && !env.isBlank()) ? env : null;
    }

    // ---- process execution ------------------------------------------------------------------

    /** Package-visible so the pure {@link #classify} verdict logic can be unit-tested. */
    record RunOutcome(boolean finished, int processExitCode, Integer yaxunitExitCode, boolean exitCodeSeen,
            boolean timedOut) {
    }

    private RunOutcome runAndAwait(ProcessBuilder processBuilder, String opId, String projectName,
            int timeoutSeconds, File exitCodeFile, File junitFile, File launchLog)
            throws IOException, InterruptedException {
        Process process = processStarter.start(processBuilder);
        long pid = process.pid();
        LOG.info("[%s] yaxunit_run spawned (pid=%d, project=%s, timeout=%ds, log=%s)", opId, //$NON-NLS-1$
                Long.valueOf(pid), projectName, Integer.valueOf(timeoutSeconds), launchLog.getAbsolutePath());

        long startMillis = System.currentTimeMillis();
        long deadlineMillis = startMillis + (long) timeoutSeconds * 1000L;
        long lastHeartbeat = startMillis;
        long exitCodeSeenAt = -1L;
        boolean finished = false;

        while (true) {
            finished = process.waitFor(POLL_MILLIS, TimeUnit.MILLISECONDS);
            long now = System.currentTimeMillis();
            if (finished) {
                break;
            }
            // Deterministic completion signal: YAxUnit writes exitCode.txt, then self-closes
            // (closeAfterTests). Once we see it, give the client a short grace to flush junit.xml
            // and exit on its own; if it lingers past the grace, terminate the tree.
            if (exitCodeFile.exists()) {
                if (exitCodeSeenAt < 0) {
                    exitCodeSeenAt = now;
                    LOG.info("[%s] yaxunit_run exitcode.txt observed; awaiting client close", opId); //$NON-NLS-1$
                } else if (now - exitCodeSeenAt > CLOSE_GRACE_MILLIS) {
                    LOG.info("[%s] yaxunit_run client lingering after exitcode.txt — terminating tree", opId); //$NON-NLS-1$
                    terminateProcessTree(process);
                    break;
                }
            }
            if (now > deadlineMillis) {
                LOG.warn("[%s] yaxunit_run TIMEOUT after %ds — terminating tree (pid=%d)", opId, //$NON-NLS-1$
                        Integer.valueOf(timeoutSeconds), Long.valueOf(pid));
                terminateProcessTree(process);
                Integer code = readExitCode(exitCodeFile);
                return new RunOutcome(false, -1, code, exitCodeFile.exists(), true);
            }
            if (now - lastHeartbeat >= HEARTBEAT_MILLIS) {
                lastHeartbeat = now;
                long descendants;
                try {
                    descendants = process.descendants().count();
                } catch (RuntimeException ignored) {
                    descendants = -1L;
                }
                LOG.info("[%s] yaxunit_run heartbeat: elapsed=%ds/%ds, pid=%d, descendants=%d, " //$NON-NLS-1$
                        + "exitcode=%b, junit=%db", opId, Long.valueOf((now - startMillis) / 1000L), //$NON-NLS-1$
                        Integer.valueOf(timeoutSeconds), Long.valueOf(pid), Long.valueOf(descendants),
                        Boolean.valueOf(exitCodeFile.exists()), Long.valueOf(safeSize(junitFile)));
            }
        }

        int processExitCode = finished ? process.exitValue() : -1;
        Integer yaxunitExit = readExitCode(exitCodeFile);
        LOG.info("[%s] yaxunit_run finished: process_exit=%d, yaxunit_exit=%s, elapsed=%dms", opId, //$NON-NLS-1$
                Integer.valueOf(processExitCode), String.valueOf(yaxunitExit),
                Long.valueOf(System.currentTimeMillis() - startMillis));
        return new RunOutcome(finished, processExitCode, yaxunitExit, exitCodeFile.exists(), false);
    }

    private static void terminateProcessTree(Process process) throws InterruptedException {
        if (process == null) {
            return;
        }
        List<ProcessHandle> descendants = process.descendants().collect(Collectors.toList());
        descendants.forEach(ProcessHandle::destroy);
        process.destroy();
        process.waitFor(5, TimeUnit.SECONDS);
        if (process.isAlive()) {
            descendants.forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
    }

    // ---- result assembly --------------------------------------------------------------------

    /**
     * The classified outcome of a run: the envelope {@code status}, a machine-readable {@code reason}
     * (empty when the status already says everything), the human-readable {@code message} (empty when
     * none is needed) and the MCP channel — {@code ok=true} → success, {@code ok=false} → error.
     */
    record Verdict(String status, String reason, String message, boolean ok) {
    }

    /**
     * Maps a finished run onto its verdict. Pure and package-visible so the matrix is unit-tested.
     *
     * <p>Channel contract: the MCP <b>error</b> channel means "there is no verdict — draw no
     * conclusions", the <b>success</b> channel means "there is a verdict — read the report". Two
     * consequences, both deliberate:</p>
     * <ul>
     * <li>A completed run with RED tests is a <b>success</b> ({@code tests_failed}): the verdict
     * exists and the next action is reading {@code failures}, not re-diagnosing the environment
     * (feedback {@code 2026-07-03-yaxunit-run-red-tests-as-mcp-error.md}).</li>
     * <li>A run that executed ZERO tests is an <b>error</b> even though nothing failed: zero resolved
     * work carries no verdict. Previously {@code total == 0} satisfied the green predicate
     * identically, so an empty report read as "all tests passed".</li>
     * </ul>
     *
     * @param filterPresent whether the caller passed any filter (modules/tests/tags/...) — splits
     *                      "the filter matched nothing" from "the infobase holds no tests at all"
     * @param equalityState EDT's project-vs-infobase equality state ({@code EQUAL}, {@code NOT_EQUAL},
     *                      {@code LOADING}), or {@code null} when it could not be read (cold EDT) —
     *                      splits "the infobase is stale" from "the filter matched nothing"
     */
    static Verdict classify(QaJUnitReport report, RunOutcome outcome, boolean filterPresent, String equalityState) {
        if (outcome != null && outcome.timedOut()) {
            return new Verdict("timeout", "", "Run timed out; partial report parsed", false); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        if (report == null || report.tests <= 0) {
            return classifyZeroTests(filterPresent, equalityState);
        }
        Integer exitCode = outcome == null ? null : outcome.yaxunitExitCode();
        boolean reportGreen = report.failures == 0 && report.errors == 0;
        boolean exitGreen = exitCode == null || exitCode.intValue() == 0;
        if (reportGreen && exitGreen) {
            return new Verdict("passed", "", "", true); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        if (reportGreen) {
            // The runner says "failed" while the report shows nothing red: the report is partial, or
            // something blew up outside a testcase. No coherent verdict → error channel.
            return new Verdict("report_exit_mismatch", "exit_code_nonzero_report_green", //$NON-NLS-1$ //$NON-NLS-2$
                    "YAxUnit exited with code " + exitCode + " but the parsed report contains no failed or " //$NON-NLS-1$ //$NON-NLS-2$
                    + "errored test — the report is likely partial, or a failure happened outside a test " //$NON-NLS-1$
                    + "case. Treat the run as inconclusive and inspect junit.xml / yaxunit.log.", false); //$NON-NLS-1$
        }
        return new Verdict("tests_failed", "", "", true); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * Verdict for a run that produced a report with zero executed tests. Always the error channel —
     * nothing ran, so there is nothing to conclude — but the reason narrows the cause down to an
     * action: a stale infobase, a filter that matched nothing, or no tests in the infobase at all.
     */
    private static Verdict classifyZeroTests(boolean filterPresent, String equalityState) {
        String state = equalityState == null ? null : equalityState.trim().toUpperCase(Locale.ROOT);
        if (!filterPresent) {
            return new Verdict("no_tests_found", "no_tests_in_infobase", //$NON-NLS-1$ //$NON-NLS-2$
                    "YAxUnit executed 0 tests and no filter was passed, so nothing at all was discovered: " //$NON-NLS-1$
                    + "the YAxUnit extension is most likely not installed / not attached to this infobase, " //$NON-NLS-1$
                    + "or it is attached in safe mode. " + SAFE_MODE_REMEDIATION, false); //$NON-NLS-1$
        }
        if (isStaleState(state)) {
            return new Verdict("no_tests_matched", "infobase_stale", //$NON-NLS-1$ //$NON-NLS-2$
                    "YAxUnit executed 0 tests and equality_state=" + state + ": " + STALE_HINT + ".", false); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        if ("EQUAL".equals(state)) { //$NON-NLS-1$
            return new Verdict("no_tests_matched", "filter_matched_nothing", //$NON-NLS-1$ //$NON-NLS-2$
                    "YAxUnit executed 0 tests while the infobase matches the EDT source, so the filter " //$NON-NLS-1$
                    + "matched nothing: " + FILTER_HINT + ".", false); //$NON-NLS-1$ //$NON-NLS-2$
        }
        // Cold EDT: readInfobaseEqualityState is best-effort and returns null. Both causes stay open,
        // stale first because it is the cheaper thing to rule out.
        return new Verdict("no_tests_matched", "no_match_unverified", //$NON-NLS-1$ //$NON-NLS-2$
                "YAxUnit executed 0 tests and the infobase equality state could not be read, so both causes " //$NON-NLS-1$
                + "stay open. Most likely: " + STALE_HINT + ". If the infobase is already current, the filter " //$NON-NLS-1$ //$NON-NLS-2$
                + "matched nothing: " + FILTER_HINT + ".", false); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** {@code true} for the equality states that mean the infobase no longer matches the EDT source. */
    private static boolean isStaleState(String equalityState) {
        String state = equalityState == null ? "" : equalityState.trim().toUpperCase(Locale.ROOT); //$NON-NLS-1$
        return "NOT_EQUAL".equals(state) || "LOADING".equals(state); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private ToolResult buildResult(JsonObject result, String opId, File runDir, File junitFile, File exitCodeFile,
            File yaxunitLog, File onecLog, File launchLog, RunOutcome outcome, boolean filterPresent,
            String equalityState) {
        result.addProperty("yaxunit_exit_code", //$NON-NLS-1$
                outcome.yaxunitExitCode() == null ? "" : String.valueOf(outcome.yaxunitExitCode())); //$NON-NLS-1$
        result.addProperty("process_exit_code", outcome.processExitCode()); //$NON-NLS-1$

        QaJUnitReport report = null;
        try {
            // The run directory doubles as the client's working directory, so parse the one file
            // YAxUnit was told to write instead of every *.xml that happens to land there.
            report = QaJUnitReport.parseDirectory(runDir, MAX_FAILURE_DETAILS, JUNIT_REPORT_NAME);
        } catch (IOException e) {
            LOG.warn("[%s] yaxunit_run report parse failed: %s", opId, e.getMessage()); //$NON-NLS-1$
        }
        if (report != null) {
            result.addProperty("report_source", //$NON-NLS-1$
                    report.fallbackScan ? "fallback_xml_scan" : JUNIT_REPORT_NAME); //$NON-NLS-1$
            if (report.fallbackScan) {
                result.addProperty("report_source_note", //$NON-NLS-1$
                        "junit.xml was not produced in the run directory; " + report.files.size() //$NON-NLS-1$
                        + " unrelated *.xml file(s) found there were parsed instead — their counts are not " //$NON-NLS-1$
                        + "proven to come from this YAxUnit run."); //$NON-NLS-1$
            }
        }

        // No usable report: either nothing was parsed at all, or only foreign *.xml with zero tests
        // was found — in both cases YAxUnit produced nothing, and the richer no-report diagnosis
        // (safe mode / extension not attached) is what the caller needs.
        if (report == null || (report.fallbackScan && report.tests <= 0)) {
            String tail = tail(onecLog, LOG_TAIL_LINES) + "\n" + tail(yaxunitLog, LOG_TAIL_LINES) //$NON-NLS-1$
                    + "\n" + tail(launchLog, LOG_TAIL_LINES); //$NON-NLS-1$
            String hint = classifyNoReport(tail, outcome);
            result.addProperty("status", "no_report"); //$NON-NLS-1$ //$NON-NLS-2$
            result.addProperty("message", hint); //$NON-NLS-1$
            result.addProperty("tail_log", tail.strip()); //$NON-NLS-1$
            return ToolResult.failure(pretty(result));
        }

        int passed = report.passed();
        result.addProperty("total", report.tests); //$NON-NLS-1$
        result.addProperty("passed", passed); //$NON-NLS-1$
        result.addProperty("failed", report.failures); //$NON-NLS-1$
        result.addProperty("errors", report.errors); //$NON-NLS-1$
        result.addProperty("skipped", report.skipped); //$NON-NLS-1$
        result.addProperty("duration_s", report.timeSeconds); //$NON-NLS-1$
        result.add("suites", suitesJson(report)); //$NON-NLS-1$
        result.add("failures", failuresJson(report)); //$NON-NLS-1$

        Verdict verdict = classify(report, outcome, filterPresent, equalityState);
        result.addProperty("status", verdict.status()); //$NON-NLS-1$
        if (!verdict.reason().isEmpty()) {
            result.addProperty("reason", verdict.reason()); //$NON-NLS-1$
        }
        if (!verdict.message().isEmpty()) {
            result.addProperty("message", verdict.message()); //$NON-NLS-1$
        }
        LOG.info("[%s] yaxunit_run verdict: status=%s, reason=%s, total=%d, failed=%d, errors=%d, equality=%s", //$NON-NLS-1$
                opId, verdict.status(), verdict.reason(), Integer.valueOf(report.tests),
                Integer.valueOf(report.failures), Integer.valueOf(report.errors), String.valueOf(equalityState));
        return verdict.ok()
                ? ToolResult.success(pretty(result), ToolResult.ToolResultType.CODE, result)
                : ToolResult.failure(pretty(result));
    }

    /** Package-visible for unit tests: the diagnosis for a run that produced no jUnit report. */
    static String classifyNoReport(String tail, RunOutcome outcome) {
        String lower = tail == null ? "" : tail.toLowerCase(Locale.ROOT); //$NON-NLS-1$
        for (String marker : SAFE_MODE_MARKERS) {
            if (lower.contains(marker)) {
                return "YAxUnit produced no report and the log mentions safe mode / dangerous-action " //$NON-NLS-1$
                        + "protection. " + SAFE_MODE_REMEDIATION; //$NON-NLS-1$
            }
        }
        if (outcome == null || !outcome.exitCodeSeen()) {
            return "YAxUnit produced neither a jUnit report nor an exitCode file. Verify the YAxUnit " //$NON-NLS-1$
                    + "extension is installed and active in the infobase, and that the RunUnitTests " //$NON-NLS-1$
                    + "startup handler is registered. See tail_log for the client output."; //$NON-NLS-1$
        }
        return "YAxUnit wrote an exitCode file but no jUnit report — the filter may have matched zero " //$NON-NLS-1$
                + "tests, or the report path was not writable. See tail_log."; //$NON-NLS-1$
    }

    private static JsonArray suitesJson(QaJUnitReport report) {
        JsonArray arr = new JsonArray();
        for (QaJUnitReport.Suite s : report.suites) {
            JsonObject o = new JsonObject();
            o.addProperty("name", s.name); //$NON-NLS-1$
            o.addProperty("tests", s.tests); //$NON-NLS-1$
            o.addProperty("failures", s.failures); //$NON-NLS-1$
            o.addProperty("errors", s.errors); //$NON-NLS-1$
            o.addProperty("skipped", s.skipped); //$NON-NLS-1$
            arr.add(o);
        }
        return arr;
    }

    private static JsonArray failuresJson(QaJUnitReport report) {
        JsonArray arr = new JsonArray();
        for (QaJUnitReport.FailureDetail d : report.failureDetails) {
            JsonObject o = new JsonObject();
            o.addProperty("name", d.name); //$NON-NLS-1$
            o.addProperty("classname", d.className); //$NON-NLS-1$
            o.addProperty("type", d.type); //$NON-NLS-1$
            o.addProperty("message", d.message); //$NON-NLS-1$
            arr.add(o);
        }
        return arr;
    }

    private static JsonObject baseResult(String opId, String projectName, File runDir, File configFile,
            File junitFile, File launchLog) {
        JsonObject result = new JsonObject();
        result.addProperty("op_id", opId); //$NON-NLS-1$
        result.addProperty("project_name", projectName); //$NON-NLS-1$
        result.addProperty("run_dir", runDir.getAbsolutePath()); //$NON-NLS-1$
        result.addProperty("config_path", configFile.getAbsolutePath()); //$NON-NLS-1$
        result.addProperty("junit_path", junitFile.getAbsolutePath()); //$NON-NLS-1$
        result.addProperty("log_path", launchLog.getAbsolutePath()); //$NON-NLS-1$
        return result;
    }

    private static void addCommand(JsonObject result, List<String> command) {
        JsonArray arr = new JsonArray();
        command.forEach(arr::add);
        result.add("command", arr); //$NON-NLS-1$
    }

    // ---- small helpers ----------------------------------------------------------------------

    /**
     * Reaps the thin clients this tool leaked on {@code ibPath} and warns about the rest.
     *
     * <p>A stuck {@code 1cv8c} holding the infobase's named pipe is the most common silent cause of a
     * 300-second timeout (BF-12678 Phase 4). {@link #terminateProcessTree} never cleaned it up
     * because the real client is not a descendant of the process we spawn — the launcher reparents it,
     * and the heartbeat logs {@code descendants=0} throughout — so the orphan has to be matched by
     * command line and killed by PID.</p>
     *
     * <p>The previous version only warned, and it warned about EVERY {@code 1cv8c} on the machine:
     * on a multi-stand box that fires on every run and cannot tell our leaked client from a
     * neighbour's legitimate one. Now only clients that reference THIS infobase and carry our own
     * {@code RunUnitTests=} parameter are terminated; anything unattributable (server infobase,
     * unresolved association, unreadable command line because WMI is unavailable) is reported loudly
     * and left running — killing on a guess would take down another stand.</p>
     */
    private static List<String> checkStaledClientProcesses(String opId, int timeoutSeconds, String ibPath) {
        List<String> warnings = new ArrayList<>();
        try {
            InfobaseProcessScanner.TestClientCleanup cleanup =
                    InfobaseProcessScanner.killLeakedTestClients(ibPath);
            if (!cleanup.killed().isEmpty()) {
                String msg = "PREFLIGHT: terminated " + cleanup.killed().size() + " leaked YAxUnit thin-client " //$NON-NLS-1$ //$NON-NLS-2$
                        + "process(es) of this infobase (PIDs: " + joinPids(cleanup.killed()) + "). They hold the " //$NON-NLS-1$ //$NON-NLS-2$
                        + "named pipe and would have made this run time out after " + timeoutSeconds + "s."; //$NON-NLS-1$ //$NON-NLS-2$
                warnings.add(msg);
                LOG.warn("[%s] %s", opId, msg); //$NON-NLS-1$
            }
            if (!cleanup.spared().isEmpty()) {
                String msg = "PREFLIGHT WARNING: " + cleanup.spared().size() + " other 1cv8c process(es) are " //$NON-NLS-1$ //$NON-NLS-2$
                        + "running (PIDs: " + joinPids(cleanup.spared()) + ") that could NOT be attributed to " //$NON-NLS-1$ //$NON-NLS-2$
                        + "this run" //$NON-NLS-1$
                        + (ibPath == null
                                ? " (this project's infobase path did not resolve — server infobase or no " //$NON-NLS-1$
                                        + "association, so no client can be attributed at all)" //$NON-NLS-1$
                                : " (another infobase, an interactive session, or an unreadable command line)") //$NON-NLS-1$
                        + (cleanup.unreadable().isEmpty() ? "" //$NON-NLS-1$
                                : "; " + cleanup.unreadable().size() + " of them expose no command line at all " //$NON-NLS-1$ //$NON-NLS-2$
                                        + "(WMI unavailable) — identification is impossible for those") //$NON-NLS-1$
                        + ". They were left running: without an infobase match a kill could take down another " //$NON-NLS-1$
                        + "stand's client. If this run times out after " + timeoutSeconds + "s, check them by hand."; //$NON-NLS-1$ //$NON-NLS-2$
                warnings.add(msg);
                LOG.warn("[%s] %s", opId, msg); //$NON-NLS-1$
            }
        } catch (RuntimeException e) {
            LOG.warn("[%s] yaxunit_run preflight process scan failed: %s", opId, e.getMessage()); //$NON-NLS-1$
        }
        return warnings;
    }

    private static String joinPids(List<Long> pids) {
        return pids.stream().map(String::valueOf).collect(Collectors.joining(", ")); //$NON-NLS-1$
    }

    /**
     * Best-effort file-infobase path of the project's default infobase — the binding key that makes
     * the orphan kill safe. {@code null} for a server infobase or when the association does not
     * resolve; the caller must then warn instead of terminating anything. Never throws.
     */
    private String resolveFileIbPath(String opId, String projectName) {
        try {
            var infobase = runtimeService.resolveDefaultInfobase(projectName);
            if (infobase == null || infobase.getConnectionString() == null) {
                return null;
            }
            return InfobaseProcessScanner.fileIbPath(infobase.getConnectionString().asConnectionString());
        } catch (RuntimeException e) {
            LOG.warn("[%s] yaxunit_run: infobase path unresolved, orphan clients cannot be attributed: %s", //$NON-NLS-1$
                    opId, e.getMessage());
            return null;
        }
    }

    /**
     * Reads EDT's project-vs-infobase equality state. Best-effort by contract: {@code null} on a cold
     * EDT or an older API, which the verdict must degrade into {@code no_match_unverified} rather than
     * fail on.
     */
    private String readEqualityState(String opId, String projectName) {
        try {
            String state = runtimeService.readInfobaseEqualityState(projectName);
            LOG.info("[%s] yaxunit_run equality_state=%s", opId, String.valueOf(state)); //$NON-NLS-1$
            return state;
        } catch (RuntimeException e) {
            LOG.warn("[%s] yaxunit_run: equality state unavailable: %s", opId, e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    static Integer readExitCode(File exitCodeFile) {
        if (exitCodeFile == null || !exitCodeFile.isFile()) {
            return null;
        }
        try {
            // YAxUnit writes the file with a UTF-8 BOM (EF BB BF) ahead of the 0/1. Files.readString
            // keeps it as a leading U+FEFF, which strip() does NOT treat as whitespace — so without
            // removing it, parse fails and yaxunit_exit_code surfaces empty. Drop any BOM first.
            String raw = Files.readString(exitCodeFile.toPath(), StandardCharsets.UTF_8)
                    .replace("﻿", "").strip(); //$NON-NLS-1$ //$NON-NLS-2$
            if (raw.isEmpty()) {
                return null;
            }
            // YAxUnit writes a single digit (0/1); be lenient about trailing content.
            return Integer.parseInt(raw.split("\\s")[0]); //$NON-NLS-1$
        } catch (IOException | NumberFormatException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    static List<String> asStringList(Object value) {
        List<String> out = new ArrayList<>();
        if (value == null) {
            return out;
        }
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    String s = String.valueOf(item).trim();
                    if (!s.isEmpty()) {
                        out.add(s);
                    }
                }
            }
        } else if (value instanceof String s) {
            // Tolerate a comma-separated string for callers that don't pass a JSON array.
            for (String part : s.split(",")) { //$NON-NLS-1$
                String t = part.trim();
                if (!t.isEmpty()) {
                    out.add(t);
                }
            }
        }
        return out;
    }

    private static int extractTimeoutSeconds(Map<String, Object> parameters) {
        Object value = parameters == null ? null : parameters.get("timeout_s"); //$NON-NLS-1$
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return DEFAULT_TIMEOUT_SECONDS;
            }
        }
        return DEFAULT_TIMEOUT_SECONDS;
    }

    protected File getWorkspaceRoot() {
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace() == null ? null
                : ResourcesPlugin.getWorkspace().getRoot();
        return root == null || root.getLocation() == null ? null : root.getLocation().toFile();
    }

    protected File buildRunDirectory(File workspaceRoot, String opId) {
        File root = workspaceRoot == null
                ? new File(System.getProperty("java.io.tmpdir"), "codepilot1c-yaxunit-run/" + opId) //$NON-NLS-1$ //$NON-NLS-2$
                : new File(workspaceRoot, ".codepilot/runs/yaxunit_run/" + opId); //$NON-NLS-1$
        if (!root.exists()) {
            root.mkdirs();
        }
        return root;
    }

    private static long safeSize(File file) {
        return file != null && file.exists() ? file.length() : 0L;
    }

    private static String tail(File file, int maxLines) {
        if (file == null || !file.isFile()) {
            return ""; //$NON-NLS-1$
        }
        try {
            Deque<String> lines = new ArrayDeque<>();
            for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                lines.addLast(line);
                while (lines.size() > maxLines) {
                    lines.removeFirst();
                }
            }
            return String.join("\n", lines); //$NON-NLS-1$
        } catch (IOException e) {
            return ""; //$NON-NLS-1$
        }
    }

    private static String pretty(JsonObject object) {
        return new GsonBuilder().setPrettyPrinting().create().toJson(object);
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private static String asOptionalString(Object value) {
        if (value == null) {
            return null;
        }
        String raw = String.valueOf(value).trim();
        return raw.isEmpty() ? null : raw;
    }
}
