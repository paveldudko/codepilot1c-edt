package com.codepilot1c.core.tools.workspace;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.runtime.EdtInfobaseConnectService;
import com.codepilot1c.core.edt.runtime.EdtInfobaseConnectService.ConnectRequest;
import com.codepilot1c.core.edt.runtime.EdtInfobaseConnectService.ConnectResult;
import com.codepilot1c.core.edt.runtime.EdtInfobaseConnectService.ConnectionKind;
import com.codepilot1c.core.edt.runtime.EdtToolErrorCode;
import com.codepilot1c.core.edt.runtime.EdtToolException;
import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.tools.AbstractTool;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolResult;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Binds an infobase (file- or standalone-server-based) to an EDT project without user interaction.
 */
@ToolMeta(
        name = "connect_infobase",
        category = "workspace",
        mutating = true,
        tags = {"workspace", "edt"})
public class ConnectInfobaseTool extends AbstractTool {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(ConnectInfobaseTool.class);

    /**
     * Cap on a single bind so an EDT interactive credential modal can't hang the caller. Legit
     * file/standalone/server binds complete in seconds; exceeding this almost always means EDT is
     * waiting on a credential dialog (no creds passed, none stored, IB needs auth) or an unreachable
     * server. Kept under the connect_infobase MCP HTTP timeout (~60s) so the sync path returns a
     * clean EDT_AUTH_REQUIRED rather than a raw transport timeout.
     */
    private static final long CONNECT_TIMEOUT_SECONDS = 50L;

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "project_name": {
                  "type": "string",
                  "description": "EDT project name to bind the infobase to"
                },
                "database_path": {
                  "type": "string",
                  "description": "REQUIRED for kind=file/standalone (ignored for kind=server). kind=file: path to an EXISTING file-infobase folder — may live anywhere (e.g. a per-branch sandbox), only associated, not written to. kind=standalone: infobase data path that MUST be inside the workspace or home directory (the standalone server writes a .codepilot-standalone/ subtree there)."
                },
                "kind": {
                  "type": "string",
                  "enum": ["file", "standalone", "server"],
                  "description": "Connection mode: 'file' for a file-based infobase, 'standalone' for the local standalone server, 'server' for a client/server (cluster) infobase identified by srvr+ref."
                },
                "srvr": {
                  "type": "string",
                  "description": "kind=server: cluster server address (the Srvr key), e.g. 'host' or 'host:port'."
                },
                "ref": {
                  "type": "string",
                  "description": "kind=server: infobase name on the cluster (the Ref key)."
                },
                "login": {
                  "type": "string",
                  "description": "Optional infobase login for EDT binding — must have sufficient rights for update_infobase (admin-level). These credentials are PERSISTED by EDT as the project's IB access credentials. Do NOT pass test-only credentials here (e.g. AutotestDataInput) — test credentials belong in yaxunit_run's login/password parameters. Empty string means OS authentication."
                },
                "password": {
                  "type": "string",
                  "description": "Optional infobase password for EDT binding (see login). Never echoed back in the result."
                },
                "set_primary": {
                  "type": "boolean",
                  "description": "Make the bound infobase the project's primary infobase (default: true)"
                },
                "force": {
                  "type": "boolean",
                  "description": "Replace an existing primary infobase when set_primary=true (default: false)"
                },
                "server_port": {
                  "type": "integer",
                  "description": "Cluster port for 'standalone' kind (default: 1541)"
                },
                "runtime_version": {
                  "type": "string",
                  "description": "Optional 1C runtime version for 'standalone' kind (e.g. 8.3.24.1656)"
                },
                "infobase_name": {
                  "type": "string",
                  "description": "Optional display name for the infobase in EDT's registry. Defaults to the folder name. Pass a distinct name when the folder name collides with an existing infobase (e.g. a same-named server infobase) — otherwise the call fails with NAME_COLLISION."
                },
                "async": {
                  "type": "boolean",
                  "description": "Run the bind in the background and return a job_id immediately. Use when the bind may take longer than the MCP HTTP timeout (~60s). Poll the result with connect_infobase_status(job_id=...)."
                },
                "auto_stop_phantom": {
                  "type": "boolean",
                  "description": "После бинда убить phantom-Designer'ы (1cv8 DESIGNER /AgentMode), привязанные к этой ИБ (kind=file/standalone). EDT авто-респавнит такой агент на primary-ИБ; флаг чистит уже висящий. Best-effort — свежий агент может появиться после возврата; для надёжного апдейта используйте update_infobase(kill_agent_mode=true) (default: false)."
                }
              },
              "required": ["project_name", "kind"]
            }
            """; //$NON-NLS-1$

    private final EdtInfobaseConnectService connectService;

    public ConnectInfobaseTool() {
        this(new EdtInfobaseConnectService());
    }

    public ConnectInfobaseTool(EdtInfobaseConnectService connectService) {
        this.connectService = connectService;
    }

    @Override
    public String getDescription() {
        return "Подключает файловую или standalone-инфобазу к EDT проекту и, по желанию, делает её основной. " //$NON-NLS-1$
                + "ВАЖНО: login/password — это admin-credentials для EDT (сохраняются EDT как credentials проекта); " //$NON-NLS-1$
                + "test-credentials (например AutotestDataInput) передавайте отдельно в yaxunit_run, а не сюда."; //$NON-NLS-1$
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
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Object> parameters = params.getRaw();
            String opId = LogSanitizer.newId("connect-ib"); //$NON-NLS-1$
            String projectName = asString(parameters == null ? null : parameters.get("project_name")); //$NON-NLS-1$
            String rawKind = asString(parameters == null ? null : parameters.get("kind")); //$NON-NLS-1$

            LOG.info("[%s] START connect_infobase project=%s kind=%s", opId, projectName, rawKind); //$NON-NLS-1$

            try {
                ConnectionKind kind = ConnectionKind.parse(rawKind);
                if (kind == null) {
                    throw new EdtToolException(EdtToolErrorCode.INVALID_ARGUMENT,
                            "kind must be 'file', 'standalone' or 'server', got: " + rawKind); //$NON-NLS-1$
                }
                String databasePath = asString(parameters.get("database_path")); //$NON-NLS-1$
                String login = asString(parameters.get("login")); //$NON-NLS-1$
                String password = asRawString(parameters.get("password")); //$NON-NLS-1$
                boolean setPrimary = !Boolean.FALSE.equals(parameters.get("set_primary")); //$NON-NLS-1$
                boolean force = Boolean.TRUE.equals(parameters.get("force")); //$NON-NLS-1$
                Integer serverPort = asInteger(parameters.get("server_port")); //$NON-NLS-1$
                String runtimeVersion = asString(parameters.get("runtime_version")); //$NON-NLS-1$
                String infobaseName = asString(parameters.get("infobase_name")); //$NON-NLS-1$
                String srvr = asString(parameters.get("srvr")); //$NON-NLS-1$
                String ref = asString(parameters.get("ref")); //$NON-NLS-1$
                boolean autoStopPhantom = Boolean.TRUE.equals(parameters.get("auto_stop_phantom")) //$NON-NLS-1$
                        || "true".equalsIgnoreCase(asString(parameters.get("auto_stop_phantom"))); //$NON-NLS-1$

                boolean isAsync = Boolean.TRUE.equals(parameters.get("async")) //$NON-NLS-1$
                        || "true".equalsIgnoreCase(asString(parameters.get("async"))); //$NON-NLS-1$

                ConnectRequest request = new ConnectRequest(projectName, databasePath, kind, login,
                        password, setPrimary, serverPort, runtimeVersion, force, infobaseName, srvr, ref);

                if (isAsync) {
                    Callable<String> work = () -> {
                        try {
                            ConnectResult asyncResult = connectWithTimeout(request);
                            JsonObject asyncSuccess = successPayload(opId, projectName, asyncResult);
                            applyAutoStopPhantom(asyncSuccess, autoStopPhantom, asyncResult);
                            return pretty(asyncSuccess);
                        } catch (EdtToolException e) {
                            return pretty(errorPayload(opId, projectName, e.getCode(), e.getMessage()));
                        } catch (IllegalStateException e) {
                            return pretty(errorPayload(opId, projectName,
                                    EdtToolErrorCode.EDT_NOT_READY, detailFor(e)));
                        } catch (Exception e) {
                            return pretty(errorPayload(opId, projectName,
                                    EdtToolErrorCode.EDT_SERVICE_UNAVAILABLE, detailFor(e)));
                        }
                    };
                    String jobId = BackgroundJobRegistry.getInstance().startJob("connect_infobase", work); //$NON-NLS-1$
                    LOG.info("[%s] connect_infobase async started, job_id=%s", opId, jobId); //$NON-NLS-1$
                    JsonObject asyncPayload = new JsonObject();
                    asyncPayload.addProperty("op_id", opId); //$NON-NLS-1$
                    asyncPayload.addProperty("project", projectName); //$NON-NLS-1$
                    asyncPayload.addProperty("async", true); //$NON-NLS-1$
                    asyncPayload.addProperty("state", "RUNNING"); //$NON-NLS-1$ //$NON-NLS-2$
                    asyncPayload.addProperty("job_id", jobId); //$NON-NLS-1$
                    asyncPayload.addProperty("hint", "Poll with connect_infobase_status(job_id=...) to get the final result."); //$NON-NLS-1$
                    return ToolResult.success(pretty(asyncPayload), ToolResult.ToolResultType.CODE);
                }

                ConnectResult result = connectWithTimeout(request);
                JsonObject payload = successPayload(opId, projectName, result);
                applyAutoStopPhantom(payload, autoStopPhantom, result);
                return ToolResult.success(pretty(payload), ToolResult.ToolResultType.CODE);
            } catch (EdtToolException e) {
                LOG.warn(String.format("[%s] connect_infobase project=%s failed with %s: %s", //$NON-NLS-1$
                        opId, projectName, e.getCode() == null ? "<unknown>" : e.getCode().name(), //$NON-NLS-1$
                        e.getMessage() == null ? "" : e.getMessage()), e); //$NON-NLS-1$
                return ToolResult.failure(pretty(errorPayload(opId, projectName, e.getCode(), e.getMessage())));
            } catch (IllegalStateException e) {
                LOG.error(String.format("[%s] connect_infobase project=%s EDT_NOT_READY", //$NON-NLS-1$
                        opId, projectName), e);
                return ToolResult.failure(pretty(errorPayload(opId, projectName,
                        EdtToolErrorCode.EDT_NOT_READY, detailFor(e))));
            } catch (Exception e) {
                LOG.error(String.format("[%s] connect_infobase project=%s EDT_SERVICE_UNAVAILABLE", //$NON-NLS-1$
                        opId, projectName), e);
                return ToolResult.failure(pretty(errorPayload(opId, projectName,
                        EdtToolErrorCode.EDT_SERVICE_UNAVAILABLE, detailFor(e))));
            }
        });
    }

    /**
     * Runs the bind with a hard timeout so an EDT interactive credential modal (or an unreachable
     * server) can never hang the caller indefinitely. On timeout returns EDT_AUTH_REQUIRED with an
     * actionable message. The underlying EDT call may keep running on its worker (a native modal
     * cannot be interrupted), but the caller is freed. Live finding 2026-06-11.
     */
    private ConnectResult connectWithTimeout(ConnectRequest request) {
        CompletableFuture<ConnectResult> future =
                CompletableFuture.supplyAsync(() -> connectService.connect(request));
        try {
            return future.get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new EdtToolException(EdtToolErrorCode.EDT_AUTH_REQUIRED,
                    "Bind did not complete within " + CONNECT_TIMEOUT_SECONDS + "s and was aborted. " //$NON-NLS-1$ //$NON-NLS-2$
                            + "EDT is most likely waiting on an interactive credential prompt for this " //$NON-NLS-1$
                            + "infobase — pass login/password (a headless bind cannot answer the dialog). " //$NON-NLS-1$
                            + "For a client/server IB also verify srvr/ref are reachable."); //$NON-NLS-1$
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new EdtToolException(EdtToolErrorCode.EDT_SERVICE_UNAVAILABLE, "Bind interrupted"); //$NON-NLS-1$
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof EdtToolException ete) {
                throw ete;
            }
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            if (cause instanceof Error err) {
                throw err;
            }
            throw new EdtToolException(EdtToolErrorCode.EDT_SERVICE_UNAVAILABLE, detailFor(cause));
        }
    }

    /**
     * Builds a caller-facing detail string that is never empty. Falls back to the exception
     * class name plus the first stack-trace frame when {@link Throwable#getMessage()} is
     * {@code null} or blank, so standalone failures stop surfacing as empty messages.
     */
    private static String detailFor(Throwable t) {
        if (t == null) {
            return ""; //$NON-NLS-1$
        }
        String message = t.getMessage();
        if (message != null && !message.isBlank()) {
            return message;
        }
        return t.getClass().getSimpleName() + " at " + topStackFrame(t); //$NON-NLS-1$
    }

    /** Extracts the first stack-trace frame as {@code Class.method:line}, or "<unknown>" if absent. */
    private static String topStackFrame(Throwable t) {
        if (t == null) {
            return "<unknown>"; //$NON-NLS-1$
        }
        StackTraceElement[] stack = t.getStackTrace();
        if (stack == null || stack.length == 0) {
            return "<unknown>"; //$NON-NLS-1$
        }
        StackTraceElement frame = stack[0];
        return frame.getClassName() + "." + frame.getMethodName() + ":" + frame.getLineNumber(); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Best-effort: when {@code auto_stop_phantom} is set, terminate phantom {@code /AgentMode}
     * Designer agents EDT respawns on the just-bound infobase (kind=file/standalone) and record
     * the PIDs killed. A freshly respawned agent may reappear after this returns; for a reliable
     * update use {@code update_infobase(kill_agent_mode=true)}. Feedback
     * {@code 2026-06-09-phase6-infra-tooling.md §1}.
     */
    private static void applyAutoStopPhantom(JsonObject payload, boolean autoStopPhantom, ConnectResult result) {
        if (!autoStopPhantom || result == null) {
            return;
        }
        payload.addProperty("auto_stop_phantom", true); //$NON-NLS-1$
        List<Long> killed = InfobaseProcessScanner.killPhantomDesigners(result.resolvedPath());
        JsonArray arr = new JsonArray();
        for (Long pid : killed) {
            arr.add(pid);
        }
        payload.add("killed_phantoms", arr); //$NON-NLS-1$
    }

    private static JsonObject successPayload(String opId, String projectName, ConnectResult result) {
        JsonObject json = new JsonObject();
        json.addProperty("op_id", opId); //$NON-NLS-1$
        json.addProperty("success", true); //$NON-NLS-1$
        json.addProperty("project", projectName); //$NON-NLS-1$
        json.addProperty("primary", result.primary()); //$NON-NLS-1$
        if (result.idempotent()) {
            json.addProperty("idempotent", true); //$NON-NLS-1$
        }
        if (result.replacedPrevious() != null && !result.replacedPrevious().isBlank()) {
            json.addProperty("replaced_previous", result.replacedPrevious()); //$NON-NLS-1$
        }
        JsonObject infobase = new JsonObject();
        infobase.addProperty("kind", result.kind().name().toLowerCase()); //$NON-NLS-1$
        infobase.addProperty("path", result.resolvedPath()); //$NON-NLS-1$
        if (result.infobaseName() != null && !result.infobaseName().isBlank()) {
            infobase.addProperty("name", result.infobaseName()); //$NON-NLS-1$
        }
        if (result.login() != null) {
            infobase.addProperty("login", result.login()); //$NON-NLS-1$
        }
        if (result.serverPort() != null) {
            infobase.addProperty("port", result.serverPort().intValue()); //$NON-NLS-1$
        }
        json.add("infobase", infobase); //$NON-NLS-1$
        return json;
    }

    private static JsonObject errorPayload(String opId, String projectName, EdtToolErrorCode code, String message) {
        JsonObject json = new JsonObject();
        json.addProperty("op_id", opId); //$NON-NLS-1$
        json.addProperty("success", false); //$NON-NLS-1$
        json.addProperty("project", projectName == null ? "" : projectName); //$NON-NLS-1$ //$NON-NLS-2$
        EdtToolErrorCode effectiveCode = code == null ? EdtToolErrorCode.EDT_SERVICE_UNAVAILABLE : code;
        json.addProperty("error_code", effectiveCode.name()); //$NON-NLS-1$
        String safeMessage = message == null ? "" : message; //$NON-NLS-1$
        json.addProperty("message", safeMessage); //$NON-NLS-1$
        switch (effectiveCode) {
            case PRIMARY_EXISTS -> {
                json.addProperty("error", "primary_exists"); //$NON-NLS-1$ //$NON-NLS-2$
                String currentPrimary = extractCurrentPrimary(safeMessage);
                if (currentPrimary != null) {
                    json.addProperty("current_primary", currentPrimary); //$NON-NLS-1$
                }
                json.addProperty("hint", "pass force=true to replace"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            case INVALID_PATH -> {
                json.addProperty("error", "invalid_path"); //$NON-NLS-1$ //$NON-NLS-2$
                json.addProperty("hint", //$NON-NLS-1$
                        "kind=file: point at an existing infobase folder (any location); " //$NON-NLS-1$
                        + "kind=standalone: path must be inside the workspace or home directory"); //$NON-NLS-1$
            }
            case EDT_NOT_READY -> json.addProperty("error", "edt_not_ready"); //$NON-NLS-1$ //$NON-NLS-2$
            case NAME_COLLISION -> {
                json.addProperty("error", "name_collision"); //$NON-NLS-1$ //$NON-NLS-2$
                json.addProperty("hint", //$NON-NLS-1$
                        "an infobase with this name already exists with a different connection. " //$NON-NLS-1$
                        + "For a new branch/context, use a distinct infobase_name. " //$NON-NLS-1$
                        + "For a re-bind to the same path (e.g. after a git branch-switch), " //$NON-NLS-1$
                        + "pass force=true to adopt the existing entry."); //$NON-NLS-1$
            }
            case PATH_ALREADY_ASSOCIATED_AS -> {
                json.addProperty("error", "path_already_associated_as"); //$NON-NLS-1$ //$NON-NLS-2$
                String suggested = extractSuggestedName(safeMessage);
                if (suggested != null) {
                    json.addProperty("existing_name", suggested); //$NON-NLS-1$
                    json.addProperty("hint", //$NON-NLS-1$
                            "this path is already bound under '" + suggested //$NON-NLS-1$
                                    + "'; retry with infobase_name=\"" + suggested //$NON-NLS-1$
                                    + "\" (or omit infobase_name to reuse it silently)"); //$NON-NLS-1$
                } else {
                    json.addProperty("hint", //$NON-NLS-1$
                            "this path is already bound under another name; omit infobase_name or retry with the existing one"); //$NON-NLS-1$
                }
            }
            default -> { /* no extra shape */ }
        }
        return json;
    }

    /** Best-effort parse of the suggested infobase-name embedded in a PATH_ALREADY_ASSOCIATED_AS message. */
    private static String extractSuggestedName(String message) {
        if (message == null) {
            return null;
        }
        String marker = "under name '"; //$NON-NLS-1$
        int idx = message.indexOf(marker);
        if (idx < 0) {
            return null;
        }
        int start = idx + marker.length();
        int end = message.indexOf('\'', start);
        if (end < 0) {
            return null;
        }
        String value = message.substring(start, end).trim();
        return value.isEmpty() ? null : value;
    }

    /** Best-effort parse of the primary-exists marker embedded in the service message. */
    private static String extractCurrentPrimary(String message) {
        if (message == null) {
            return null;
        }
        String marker = "current_primary="; //$NON-NLS-1$
        int idx = message.indexOf(marker);
        if (idx < 0) {
            return null;
        }
        int start = idx + marker.length();
        int end = message.indexOf(',', start);
        String value = (end < 0 ? message.substring(start) : message.substring(start, end)).trim();
        return value.isEmpty() ? null : value;
    }

    private static String pretty(JsonObject object) {
        return new GsonBuilder().setPrettyPrinting().create().toJson(object);
    }

    private static String asString(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }

    /** Returns the raw string value (NOT trimmed, NOT null-to-blank-collapsed), for passwords. */
    private static String asRawString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Integer asInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return Integer.valueOf(n.intValue());
        }
        String s = String.valueOf(value).trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(Integer.parseInt(s));
        } catch (NumberFormatException e) {
            throw new EdtToolException(EdtToolErrorCode.INVALID_ARGUMENT,
                    "Expected integer value, got: " + s); //$NON-NLS-1$
        }
    }
}
