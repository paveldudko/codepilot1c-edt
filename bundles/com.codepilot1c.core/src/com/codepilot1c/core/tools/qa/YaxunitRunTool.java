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

import com.codepilot1c.core.edt.runtime.EdtRuntimeService;
import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.qa.QaJUnitReport;
import com.codepilot1c.core.tools.AbstractTool;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolResult;
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

    private static final String SCHEMA = """
            {
              "type": "object",
              "description": "Запускает YAxUnit unit-тесты проекта EDT (тонкий клиент, RunUnitTests, без TestManager). Парсит jUnit-отчёт в структурированный результат.",
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
                "version_mask": {
                  "type": "string",
                  "description": "Маска версии платформы для резолва клиента (необязательно)."
                },
                "dry_run": {
                  "type": "boolean",
                  "description": "Собрать config.json и команду без запуска процесса."
                }
              },
              "required": ["project_name"]
            }
            """; //$NON-NLS-1$

    private final EdtRuntimeService runtimeService;
    private final ProcessStarter processStarter;

    public YaxunitRunTool() {
        this(new EdtRuntimeService(), ProcessBuilder::start);
    }

    public YaxunitRunTool(EdtRuntimeService runtimeService, ProcessStarter processStarter) {
        this.runtimeService = runtimeService;
        this.processStarter = processStarter;
    }

    @Override
    public String getDescription() {
        return "Запускает YAxUnit unit-тесты проекта EDT через тонкий клиент (RunUnitTests, без TestManager) " //$NON-NLS-1$
                + "и возвращает структурированный jUnit-результат."; //$NON-NLS-1$
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
            String versionMask = asOptionalString(parameters == null ? null : parameters.get("version_mask")); //$NON-NLS-1$

            File workspaceRoot = getWorkspaceRoot();
            File runDir = buildRunDirectory(workspaceRoot, opId);
            File configFile = new File(runDir, "config.json"); //$NON-NLS-1$
            File junitFile = new File(runDir, "junit.xml"); //$NON-NLS-1$
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
                var builder = runtimeService.buildUnitTestCommand(projectName, configFile, onecLog, versionMask,
                        access);
                ProcessBuilder processBuilder = builder.toProcessBuilder();
                processBuilder.directory(runDir);
                processBuilder.redirectErrorStream(true);
                processBuilder.redirectOutput(ProcessBuilder.Redirect.appendTo(launchLog));

                JsonObject result = baseResult(opId, projectName, runDir, configFile, junitFile, launchLog);
                result.addProperty("onec_log_path", onecLog.getAbsolutePath()); //$NON-NLS-1$
                result.add("filter", filterJson(parameters)); //$NON-NLS-1$
                addCommand(result, processBuilder.command());

                if (dryRun) {
                    result.addProperty("status", "dry_run"); //$NON-NLS-1$ //$NON-NLS-2$
                    return ToolResult.success(pretty(result), ToolResult.ToolResultType.CODE, result);
                }

                RunOutcome outcome = runAndAwait(processBuilder, opId, projectName, timeoutSeconds,
                        exitCodeFile, junitFile, launchLog);

                return buildResult(result, opId, runDir, junitFile, exitCodeFile, yaxunitLog, onecLog, launchLog,
                        outcome);
            } catch (IOException e) {
                LOG.warn("[%s] yaxunit_run IO failure: %s", opId, e.getMessage()); //$NON-NLS-1$
                return ToolResult.failure("yaxunit_run failed: " + e.getMessage()); //$NON-NLS-1$
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ToolResult.failure("yaxunit_run interrupted"); //$NON-NLS-1$
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

    private record RunOutcome(boolean finished, int processExitCode, Integer yaxunitExitCode, boolean exitCodeSeen,
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

    private ToolResult buildResult(JsonObject result, String opId, File runDir, File junitFile, File exitCodeFile,
            File yaxunitLog, File onecLog, File launchLog, RunOutcome outcome) {
        result.addProperty("yaxunit_exit_code", //$NON-NLS-1$
                outcome.yaxunitExitCode() == null ? "" : String.valueOf(outcome.yaxunitExitCode())); //$NON-NLS-1$
        result.addProperty("process_exit_code", outcome.processExitCode()); //$NON-NLS-1$

        QaJUnitReport report = null;
        try {
            report = QaJUnitReport.parseDirectory(runDir, MAX_FAILURE_DETAILS);
        } catch (IOException e) {
            LOG.warn("[%s] yaxunit_run report parse failed: %s", opId, e.getMessage()); //$NON-NLS-1$
        }

        if (report == null) {
            // No jUnit XML was produced — the most common cause is the YAxUnit extension being
            // attached in safe mode / not installed, which fails on the BSL side before reporting.
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

        boolean green = report.failures == 0 && report.errors == 0
                && (outcome.yaxunitExitCode() == null || outcome.yaxunitExitCode() == 0);
        if (outcome.timedOut()) {
            result.addProperty("status", "timeout"); //$NON-NLS-1$ //$NON-NLS-2$
            result.addProperty("message", "Run timed out; partial report parsed"); //$NON-NLS-1$ //$NON-NLS-2$
            return ToolResult.failure(pretty(result));
        }
        result.addProperty("status", green ? "passed" : "failed"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        return green
                ? ToolResult.success(pretty(result), ToolResult.ToolResultType.CODE, result)
                : ToolResult.failure(pretty(result));
    }

    private static String classifyNoReport(String tail, RunOutcome outcome) {
        String lower = tail == null ? "" : tail.toLowerCase(Locale.ROOT); //$NON-NLS-1$
        for (String marker : SAFE_MODE_MARKERS) {
            if (lower.contains(marker)) {
                return "YAxUnit produced no report and the log mentions safe mode / dangerous-action " //$NON-NLS-1$
                        + "protection. The YAxUnit extension must run UNPROTECTED: in Designer open " //$NON-NLS-1$
                        + "Configuration > Extensions, select the YAxUnit extension and uncheck " //$NON-NLS-1$
                        + "\"Безопасный режим\" / \"Защита от опасных действий\", then update the infobase " //$NON-NLS-1$
                        + "(this is an owner action — the plugin cannot change it)."; //$NON-NLS-1$
            }
        }
        if (!outcome.exitCodeSeen()) {
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

    private static Integer readExitCode(File exitCodeFile) {
        if (exitCodeFile == null || !exitCodeFile.isFile()) {
            return null;
        }
        try {
            String raw = Files.readString(exitCodeFile.toPath(), StandardCharsets.UTF_8).strip();
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
