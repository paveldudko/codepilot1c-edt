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
        return "Connects a file or standalone infobase to an EDT project and, optionally, makes it the default. " //$NON-NLS-1$
                + "IMPORTANT: login/password are admin credentials for EDT (stored by EDT as project credentials); " //$NON-NLS-1$
                + "pass test credentials (e.g. AutotestDataInput) separately to yaxunit_run, not here."; //$NON-NLS-1$
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
                    boolean autoStopPhantomFinal = autoStopPhantom;
                    Callable<String> work = () -> {
                        try {
                            ConnectResult asyncResult = connectWithTimeout(request, autoStopPhantomFinal);
                            JsonObject asyncSuccess = successPayload(opId, projectName, asyncResult);
                            applyAutoStopPhantom(asyncSuccess, autoStopPhantom, asyncResult);
                            return pretty(asyncSuccess);
                        } catch (EdtToolException e) {
                            return pretty(errorPayloadFrom(opId, projectName, e));
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

                ConnectResult result = connectWithTimeout(request, autoStopPhantom);
                JsonObject payload = successPayload(opId, projectName, result);
                applyAutoStopPhantom(payload, autoStopPhantom, result);
                return ToolResult.success(pretty(payload), ToolResult.ToolResultType.CODE);
            } catch (EdtToolException e) {
                LOG.warn(String.format("[%s] connect_infobase project=%s failed with %s: %s", //$NON-NLS-1$
                        opId, projectName, e.getCode() == null ? "<unknown>" : e.getCode().name(), //$NON-NLS-1$
                        e.getMessage() == null ? "" : e.getMessage()), e); //$NON-NLS-1$
                return ToolResult.failure(pretty(errorPayloadFrom(opId, projectName, e)));
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
    private ConnectResult connectWithTimeout(ConnectRequest request, boolean autoStopPhantom) {
        return connectWithTimeout(request, autoStopPhantom, true);
    }

    private ConnectResult connectWithTimeout(ConnectRequest request, boolean autoStopPhantom, boolean allowKillRetry) {
        CompletableFuture<ConnectResult> future =
                CompletableFuture.supplyAsync(() -> connectService.connect(request));
        try {
            return future.get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            // A 50s timeout has two very different causes that must NOT be conflated:
            //   (1) an interactive EDT credential dialog a headless bind can't answer, or
            //   (2) a Designer agent (often a phantom /AgentMode) holding the infobase lock, which
            //       makes the bind's own Designer agent disconnect ("Cannot lock … open in Designer").
            // Feedback 2026-06-24: collapsing (2) into EDT_AUTH_REQUIRED sent the caller down a
            // fruitless credential path. Probe the OS for a lock holder to tell them apart and, when
            // auto_stop_phantom is set, clear a phantom and retry the bind once.
            String lockHolder = describeInfobaseLockHolder(request);
            if (lockHolder != null) {
                if (autoStopPhantom && allowKillRetry) {
                    List<Long> killed = InfobaseProcessScanner.killPhantomDesigners(request.databasePath());
                    if (!killed.isEmpty()) {
                        LOG.warn("connect_infobase: killed phantom Designer(s) %s holding the lock, retrying bind once", //$NON-NLS-1$
                                killed);
                        return connectWithTimeout(request, autoStopPhantom, false);
                    }
                }
                throw new EdtToolException(EdtToolErrorCode.EDT_INFOBASE_LOCKED,
                        "Bind did not complete within " + CONNECT_TIMEOUT_SECONDS + "s: the infobase is locked — " //$NON-NLS-1$ //$NON-NLS-2$
                                + lockHolder + ". This is a LOCK conflict, NOT a credential prompt. Stop the process " //$NON-NLS-1$
                                + "holding the lock (a phantom /AgentMode Designer: pass auto_stop_phantom=true here, " //$NON-NLS-1$
                                + "or run update_infobase(kill_agent_mode=true)) and retry."); //$NON-NLS-1$
            }
            throw new EdtToolException(EdtToolErrorCode.EDT_AUTH_REQUIRED,
                    "Bind did not complete within " + CONNECT_TIMEOUT_SECONDS + "s and was aborted. " //$NON-NLS-1$ //$NON-NLS-2$
                            + "This is one of: (a) EDT waiting on an interactive credential prompt — pass " //$NON-NLS-1$
                            + "login/password (a headless bind cannot answer the dialog); (b) an SSH auth " //$NON-NLS-1$
                            + "failure to EDT's own Designer agent (login/password will NOT help — a fresh " //$NON-NLS-1$
                            + "connect_infobase(force=true) usually clears it); or (c) an unreachable " //$NON-NLS-1$
                            + "client/server IB (verify srvr/ref). NB: if login/password were ALREADY passed, " //$NON-NLS-1$
                            + "(a) is ruled out — suspect (b). EDT's worker may still finish the bind after this " //$NON-NLS-1$
                            + "cap, so check get_workspace_state before re-binding to avoid misreading a " //$NON-NLS-1$
                            + "completed bind as broken."); //$NON-NLS-1$
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new EdtToolException(EdtToolErrorCode.EDT_SERVICE_UNAVAILABLE, "Bind interrupted"); //$NON-NLS-1$
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof EdtToolException ete) {
                throw ete;
            }
            // The Designer-agent SSH session can disconnect with "Cannot lock the infobase because it
            // is open in Designer" (SSH_MSG_DISCONNECT -33554432). Surface that as a distinct
            // EDT_INFOBASE_LOCKED instead of a generic service error. Feedback 2026-06-24.
            String lockMessage = findInfobaseLockMessage(cause);
            if (lockMessage != null) {
                throw new EdtToolException(EdtToolErrorCode.EDT_INFOBASE_LOCKED,
                        "The infobase is locked: " + lockMessage + ". This is a LOCK conflict, NOT a credential " //$NON-NLS-1$ //$NON-NLS-2$
                                + "prompt. Stop the Designer/agent holding the lock (auto_stop_phantom=true here, or " //$NON-NLS-1$
                                + "update_infobase(kill_agent_mode=true)) and retry."); //$NON-NLS-1$
            }
            // EDT's SSH session to its OWN Designer agent can be rejected ("Auth fail") on a fresh
            // association — a distinct cause from a lock or a credential prompt. Surface it as its own
            // code so the caller doesn't waste a round on login/password. Feedback 2026-07-14 (BF-12839).
            String authFailure = findDesignerAuthFailureMessage(cause);
            if (authFailure != null) {
                throw new EdtToolException(EdtToolErrorCode.EDT_DESIGNER_AGENT_AUTH_FAILED,
                        "EDT could not open an SSH session to its own Designer agent for this infobase (" //$NON-NLS-1$
                                + authFailure + "). This is NOT an infobase credential prompt — login/password " //$NON-NLS-1$
                                + "will not fix it. A stale/OS-auth-only association is the usual cause; a fresh " //$NON-NLS-1$
                                + "connect_infobase(force=true) normally rewrites it and clears the failure. " //$NON-NLS-1$
                                + "Verify with get_workspace_state."); //$NON-NLS-1$
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
     * Best-effort: returns a human-readable description of a Designer process currently holding the
     * lock on the request's (file/standalone) infobase path, or {@code null} when none is found, the
     * path is unknown, or this is a server bind. Used to tell a Designer-lock timeout apart from a
     * credential-prompt timeout. Never throws. Feedback 2026-06-24.
     */
    private static String describeInfobaseLockHolder(ConnectRequest request) {
        if (request == null || request.kind() == ConnectionKind.SERVER) {
            return null;
        }
        String path = request.databasePath();
        if (path == null || path.isBlank()) {
            return null;
        }
        try {
            for (InfobaseProcessScanner.LockingProcess p : InfobaseProcessScanner.scan(path)) {
                if (!p.targetIb()) {
                    continue;
                }
                if (p.kind() == InfobaseProcessScanner.LockKind.DESIGNER_AGENT) {
                    return "held by a phantom /AgentMode Designer (PID " + p.pid() + ")"; //$NON-NLS-1$ //$NON-NLS-2$
                }
                if (p.kind() == InfobaseProcessScanner.LockKind.DESIGNER) {
                    return "held by an interactive Designer (PID " + p.pid() + ")"; //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
        } catch (RuntimeException ignore) {
            // best-effort lock probe; never mask the original timeout
        }
        return null;
    }

    /**
     * Walks the cause chain for the EDT/SSH "infobase is open in Designer" lock-disconnect signature
     * and returns the first matching message, or {@code null}. Bounded against cause-chain cycles.
     */
    static String findInfobaseLockMessage(Throwable t) {
        int guard = 0;
        for (Throwable c = t; c != null && guard < 25; c = c.getCause(), guard++) {
            if (isInfobaseLockMessage(c.getMessage())) {
                return c.getMessage().trim();
            }
        }
        return null;
    }

    /** True when an exception message is the EDT/SSH "infobase is open in Designer" lock signature. */
    static boolean isInfobaseLockMessage(String message) {
        if (message == null) {
            return false;
        }
        String m = message.toLowerCase(java.util.Locale.ROOT);
        return m.contains("cannot lock the infobase") //$NON-NLS-1$
                || m.contains("open in designer") //$NON-NLS-1$
                || m.contains("-33554432"); //$NON-NLS-1$
    }

    /**
     * Walks the cause chain for the JSch "Auth fail" signature EDT throws when its OWN
     * locally-spawned Designer agent rejects the SSH session (distinct from an infobase-lock
     * disconnect and from an interactive credential prompt). Returns the first matching message, or
     * {@code null}. Bounded against cause-chain cycles. Feedback 2026-07-14 (BF-12839): this failure
     * used to collapse into {@code EDT_AUTH_REQUIRED}, sending the caller down a fruitless
     * login/password path. NB: it only fires when EDT throws the failure SYNCHRONOUSLY; the same
     * failure often manifests instead as a 50s bind timeout (handled in the TimeoutException branch),
     * where no exception reaches this tool.
     */
    static String findDesignerAuthFailureMessage(Throwable t) {
        int guard = 0;
        for (Throwable c = t; c != null && guard < 25; c = c.getCause(), guard++) {
            if (isDesignerAuthFailureMessage(c.getMessage())) {
                return c.getMessage().trim();
            }
        }
        return null;
    }

    /** True when an exception message is the JSch/SSH Designer-agent auth-rejection signature. */
    static boolean isDesignerAuthFailureMessage(String message) {
        if (message == null) {
            return false;
        }
        String m = message.toLowerCase(java.util.Locale.ROOT);
        if (m.contains("com.jcraft.jsch") || m.contains("jschexception")) { //$NON-NLS-1$ //$NON-NLS-2$
            return true;
        }
        if (m.contains("auth fail")) { //$NON-NLS-1$
            return true;
        }
        boolean authWord = m.contains("authentication") || m.contains("auth "); //$NON-NLS-1$ //$NON-NLS-2$
        boolean designerSsh = m.contains("designer agent") //$NON-NLS-1$
                || (m.contains("ssh") && m.contains("session")); //$NON-NLS-1$ //$NON-NLS-2$
        return authWord && designerSsh;
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
        // Credential persistence is decoupled from the primary commit (BF-13140): the bind can succeed
        // (primary pointer committed) while EDT could not flush the credentials — surface it so callers
        // don't assume creds landed. False means: binding is good, re-run connect_infobase (or clear the
        // multi-instance secure-storage conflict / use a per-instance -eclipse.keyring) to persist creds.
        json.addProperty("credentials_persisted", result.credentialsPersisted()); //$NON-NLS-1$
        if (!result.credentialsPersisted()) {
            if (result.credentialsErrorCode() != null) {
                json.addProperty("credentials_error_code", result.credentialsErrorCode()); //$NON-NLS-1$
            }
            if (result.credentialsWarning() != null && !result.credentialsWarning().isBlank()) {
                json.addProperty("credentials_warning", result.credentialsWarning()); //$NON-NLS-1$
            }
        }
        return json;
    }

    /**
     * Error payload from a thrown {@link EdtToolException}, enriched with its machine-readable
     * detail fields (e.g. the lease holder for {@code EDT_LEASE_HELD}) so callers read
     * {@code holder.stack_id} instead of parsing the message.
     */
    private static JsonObject errorPayloadFrom(String opId, String projectName, EdtToolException e) {
        JsonObject json = errorPayload(opId, projectName, e.getCode(), e.getMessage());
        attachHolder(json, e.getDetails());
        return json;
    }

    /** Renders the flat {@code holder_*} detail keys as the nested {@code holder} object manage_leases uses. */
    private static void attachHolder(JsonObject json, java.util.Map<String, String> details) {
        if (details == null || details.isEmpty()) {
            return;
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
                // Machine-readable retry: merge these fields into the ORIGINAL arguments and
                // re-issue the call — no hint parsing (consumer feedback 2026-07-03, A1/C1).
                JsonObject retryWith = new JsonObject();
                retryWith.addProperty("force", true); //$NON-NLS-1$
                json.add("retry_with", retryWith); //$NON-NLS-1$
            }
            case INVALID_PATH -> {
                json.addProperty("error", "invalid_path"); //$NON-NLS-1$ //$NON-NLS-2$
                json.addProperty("hint", //$NON-NLS-1$
                        "kind=file: point at an existing infobase folder (any location); " //$NON-NLS-1$
                        + "kind=standalone: path must be inside the workspace or home directory"); //$NON-NLS-1$
            }
            case EDT_NOT_READY -> json.addProperty("error", "edt_not_ready"); //$NON-NLS-1$ //$NON-NLS-2$
            case EDT_INFOBASE_LOCKED -> {
                json.addProperty("error", "infobase_locked"); //$NON-NLS-1$ //$NON-NLS-2$
                json.addProperty("hint", //$NON-NLS-1$
                        "a Designer agent holds the infobase lock (not a credential prompt). " //$NON-NLS-1$
                        + "Retry with auto_stop_phantom=true, or run update_infobase(kill_agent_mode=true), " //$NON-NLS-1$
                        + "then re-issue connect_infobase."); //$NON-NLS-1$
            }
            case EDT_AUTH_REQUIRED -> {
                json.addProperty("error", "auth_required"); //$NON-NLS-1$ //$NON-NLS-2$
                json.addProperty("hint", //$NON-NLS-1$
                        "the bind timed out at the 50s cap. If login/password were NOT passed, pass them " //$NON-NLS-1$
                        + "(admin-level). If they WERE already passed, a credential prompt is ruled out — " //$NON-NLS-1$
                        + "suspect an SSH auth failure to EDT's Designer agent (EDT_DESIGNER_AGENT_AUTH_FAILED); " //$NON-NLS-1$
                        + "try connect_infobase(force=true). If the path is also open in Designer you get " //$NON-NLS-1$
                        + "EDT_INFOBASE_LOCKED instead. EDT may complete the bind on its worker after the cap — " //$NON-NLS-1$
                        + "verify with get_workspace_state before assuming the bind failed."); //$NON-NLS-1$
            }
            case EDT_DESIGNER_AGENT_AUTH_FAILED -> {
                json.addProperty("error", "designer_agent_auth_failed"); //$NON-NLS-1$ //$NON-NLS-2$
                json.addProperty("hint", //$NON-NLS-1$
                        "EDT's SSH session to its own Designer agent was rejected (JSch Auth fail) — this is " //$NON-NLS-1$
                        + "NOT an infobase credential prompt, so login/password will not help. A stale or " //$NON-NLS-1$
                        + "OS-auth-only persisted association is the usual cause; a fresh " //$NON-NLS-1$
                        + "connect_infobase(force=true) normally rewrites it and clears the failure. " //$NON-NLS-1$
                        + "Verify with get_workspace_state."); //$NON-NLS-1$
                JsonObject retryWith = new JsonObject();
                retryWith.addProperty("force", true); //$NON-NLS-1$
                json.add("retry_with", retryWith); //$NON-NLS-1$
            }
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
                    // Machine-readable retry: merge into the original arguments and re-issue
                    // (consumer feedback 2026-07-03, A1/C1).
                    JsonObject retryWith = new JsonObject();
                    retryWith.addProperty("infobase_name", suggested); //$NON-NLS-1$
                    json.add("retry_with", retryWith); //$NON-NLS-1$
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
