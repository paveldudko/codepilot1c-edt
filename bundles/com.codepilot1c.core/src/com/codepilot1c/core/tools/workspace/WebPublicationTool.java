package com.codepilot1c.core.tools.workspace;

import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import com._1c.g5.v8.dt.platform.services.model.InfobasePublication;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.services.model.Publication;
import com._1c.g5.v8.dt.platform.services.model.WebServer;
import com.codepilot1c.core.edt.publication.EdtWebPublicationService;
import com.codepilot1c.core.edt.runtime.EdtRuntimeService;
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

/**
 * Manages 1C web-server publications through the EDT publication API
 * ({@code IWebServerManager} / {@code IPublicationManager} / {@code ApachePublishDelegateWin32}).
 */
@ToolMeta(
        name = "web_publication",
        category = "diagnostics",
        surfaceCategory = "smoke_runtime_recovery",
        mutating = true,
        tags = {"workspace", "edt"})
public class WebPublicationTool extends AbstractTool {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(WebPublicationTool.class);
    private static final int DEFAULT_PROBE_TIMEOUT_S = 10;

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "action": {
                  "type": "string",
                  "enum": ["list_servers", "register_server", "list", "get", "publish", "remove", "restart", "probe"],
                  "description": "Операция. list_servers/list/get/probe — только чтение."
                },
                "server": {
                  "type": "string",
                  "description": "Имя веб-сервера в реестре EDT (см. list_servers). Обязателен для всех action кроме list_servers и probe."
                },
                "name": {
                  "type": "string",
                  "description": "Имя публикации (alias без слешей, напр. 'agent-current'). Для get/publish/remove."
                },
                "install_location": {
                  "type": "string",
                  "description": "register_server: корень установки Apache (где bin/httpd.exe). Портативный Apache авто-поиском EDT не находится — регистрируйте явно."
                },
                "config_location": {
                  "type": "string",
                  "description": "register_server: путь к httpd conf, который EDT будет править (LoadModule/Alias/Directory пишутся именно сюда). Дубликатов директив не возникает — делегат переписывает свои блоки идемпотентно."
                },
                "apache_version": {
                  "type": "string",
                  "description": "register_server: 2.0|2.2|2.4 (по умолчанию 2.4)."
                },
                "project_name": {
                  "type": "string",
                  "description": "publish: EDT-проект — строка соединения берётся из его инфобазы. Альтернатива infobase_connection."
                },
                "infobase_connection": {
                  "type": "string",
                  "description": "publish: явная строка соединения ИБ (File=\\"C:\\\\db\\";  или Srvr=...;Ref=...;). Приоритетнее project_name."
                },
                "location": {
                  "type": "string",
                  "description": "publish: каталог публикации для default.vrd (по умолчанию <default_root>/<name>)."
                },
                "wsap_version": {
                  "type": "string",
                  "description": "publish: пин версии платформы для wsap-модуля ('8.3.27' или точный билд). Без него: существующий LoadModule в conf сохраняется; в свежем conf EDT возьмёт новейшую установленную платформу, включая пре-релизы."
                },
                "restart": {
                  "type": "boolean",
                  "description": "publish/remove: рестартовать веб-сервер после изменения conf (kill+start для foreground Apache — EDT-делегат на Windows рестартовать не умеет)."
                },
                "probe_url": {
                  "type": "string",
                  "description": "publish/restart/probe: URL для HTTP GET проверки после операции (ожидается 200)."
                },
                "timeout_s": {
                  "type": "integer",
                  "description": "Таймаут probe в секундах (по умолчанию 10)."
                }
              },
              "required": ["action"]
            }
            """; //$NON-NLS-1$

    private final EdtWebPublicationService publicationService;
    private final EdtRuntimeService runtimeService;

    public WebPublicationTool() {
        this(new EdtWebPublicationService(), new EdtRuntimeService());
    }

    public WebPublicationTool(EdtWebPublicationService publicationService, EdtRuntimeService runtimeService) {
        this.publicationService = publicationService;
        this.runtimeService = runtimeService;
    }

    @Override
    public String getDescription() {
        return "Управляет публикациями инфобаз на веб-сервере через EDT API (Apache, портативный включительно). " //$NON-NLS-1$
                + "Перед первым publish зарегистрируйте сервер (register_server). " //$NON-NLS-1$
                + "publish идемпотентен (re-point alias на другую ИБ = повторный publish); " //$NON-NLS-1$
                + "restart=true обязателен, чтобы изменения conf вступили в силу."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Object> parameters = params.getRaw();
            String opId = LogSanitizer.newId("web-pub"); //$NON-NLS-1$
            String action = asString(get(parameters, "action")); //$NON-NLS-1$
            LOG.info("[%s] START web_publication action=%s", opId, action); //$NON-NLS-1$
            try {
                if (action == null || action.isBlank()) {
                    throw new EdtToolException(EdtToolErrorCode.INVALID_ARGUMENT, "action is required"); //$NON-NLS-1$
                }
                JsonObject result = new JsonObject();
                result.addProperty("op_id", opId); //$NON-NLS-1$
                result.addProperty("action", action); //$NON-NLS-1$
                switch (action) {
                case "list_servers": //$NON-NLS-1$
                    doListServers(result);
                    break;
                case "register_server": //$NON-NLS-1$
                    doRegisterServer(parameters, result);
                    break;
                case "list": //$NON-NLS-1$
                    doList(parameters, result);
                    break;
                case "get": //$NON-NLS-1$
                    doGet(parameters, result);
                    break;
                case "publish": //$NON-NLS-1$
                    doPublish(parameters, result);
                    break;
                case "remove": //$NON-NLS-1$
                    doRemove(parameters, result);
                    break;
                case "restart": //$NON-NLS-1$
                    doRestart(parameters, result);
                    break;
                case "probe": //$NON-NLS-1$
                    doProbe(parameters, result);
                    break;
                default:
                    throw new EdtToolException(EdtToolErrorCode.INVALID_ARGUMENT,
                            "Unknown action: " + action); //$NON-NLS-1$
                }
                result.addProperty("status", result.has("status") ? result.get("status").getAsString() : "ok"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                return ToolResult.success(pretty(result), ToolResult.ToolResultType.CODE);
            } catch (EdtToolException e) {
                JsonObject error = new JsonObject();
                error.addProperty("op_id", opId); //$NON-NLS-1$
                error.addProperty("action", action == null ? "" : action); //$NON-NLS-1$ //$NON-NLS-2$
                error.addProperty("status", "error"); //$NON-NLS-1$ //$NON-NLS-2$
                error.addProperty("error_code", e.getCode().name()); //$NON-NLS-1$
                error.addProperty("message", e.getMessage() == null ? "" : e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
                return ToolResult.failure(pretty(error));
            }
        });
    }

    private void doListServers(JsonObject result) {
        JsonArray servers = new JsonArray();
        for (WebServer server : publicationService.listServers()) {
            servers.add(serverJson(server));
        }
        result.add("servers", servers); //$NON-NLS-1$
    }

    private void doRegisterServer(Map<String, Object> parameters, JsonObject result) {
        String name = requireString(parameters, "server"); //$NON-NLS-1$
        Path install = Paths.get(requireString(parameters, "install_location")); //$NON-NLS-1$
        Path config = Paths.get(requireString(parameters, "config_location")); //$NON-NLS-1$
        String apacheVersion = asString(get(parameters, "apache_version")); //$NON-NLS-1$
        WebServer server = publicationService.registerServer(name, install, config, apacheVersion, null);
        result.add("server", serverJson(server)); //$NON-NLS-1$
    }

    private void doList(Map<String, Object> parameters, JsonObject result) {
        String serverName = requireString(parameters, "server"); //$NON-NLS-1$
        JsonArray publications = new JsonArray();
        for (Publication publication : publicationService.listPublications(serverName)) {
            publications.add(publicationJson(serverName, publication));
        }
        result.add("publications", publications); //$NON-NLS-1$
    }

    private void doGet(Map<String, Object> parameters, JsonObject result) {
        String serverName = requireString(parameters, "server"); //$NON-NLS-1$
        String name = requireString(parameters, "name"); //$NON-NLS-1$
        Publication publication = publicationService.getPublication(serverName, name);
        if (publication == null) {
            throw new EdtToolException(EdtToolErrorCode.PUBLICATION_NOT_FOUND,
                    "Publication '" + name + "' not found on web server '" + serverName + "'"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        result.add("publication", publicationJson(serverName, publication)); //$NON-NLS-1$
    }

    private void doPublish(Map<String, Object> parameters, JsonObject result) {
        String serverName = requireString(parameters, "server"); //$NON-NLS-1$
        String name = requireString(parameters, "name"); //$NON-NLS-1$
        String locationRaw = asString(get(parameters, "location")); //$NON-NLS-1$
        Path location = locationRaw == null ? null : Paths.get(locationRaw);
        String wsapVersion = asString(get(parameters, "wsap_version")); //$NON-NLS-1$
        String connection = resolveInfobaseConnection(parameters);
        InfobasePublication publication = publicationService.publish(serverName, name, location, connection,
                wsapVersion);
        result.addProperty("name", publication.getName()); //$NON-NLS-1$
        result.addProperty("location", publication.getLocation()); //$NON-NLS-1$
        result.addProperty("infobase_connection", publication.getInfobaseConnection()); //$NON-NLS-1$
        result.addProperty("wsap_source", wsapVersion == null ? "conf_or_auto" : "pinned"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        publicationService.getPublicationUrl(serverName, name)
                .ifPresent(url -> result.addProperty("url", url.toString())); //$NON-NLS-1$
        maybeRestart(parameters, serverName, result);
        maybeProbe(parameters, result);
    }

    private void doRemove(Map<String, Object> parameters, JsonObject result) {
        String serverName = requireString(parameters, "server"); //$NON-NLS-1$
        String name = requireString(parameters, "name"); //$NON-NLS-1$
        boolean removed = publicationService.removePublication(serverName, name);
        result.addProperty("removed", removed); //$NON-NLS-1$
        maybeRestart(parameters, serverName, result);
    }

    private void doRestart(Map<String, Object> parameters, JsonObject result) {
        String serverName = requireString(parameters, "server"); //$NON-NLS-1$
        appendRestart(publicationService.restartServer(serverName), result);
        maybeProbe(parameters, result);
    }

    private void doProbe(Map<String, Object> parameters, JsonObject result) {
        String url = requireString(parameters, "probe_url"); //$NON-NLS-1$
        int timeoutS = asInt(get(parameters, "timeout_s"), DEFAULT_PROBE_TIMEOUT_S); //$NON-NLS-1$
        EdtWebPublicationService.ProbeOutcome outcome = publicationService.probe(url, timeoutS * 1000);
        result.addProperty("probe_url", url); //$NON-NLS-1$
        result.addProperty("probe_status", outcome.statusCode()); //$NON-NLS-1$
        result.addProperty("probe_elapsed_ms", outcome.elapsedMs()); //$NON-NLS-1$
        if (outcome.statusCode() >= 400) {
            throw new EdtToolException(EdtToolErrorCode.PROBE_FAILED,
                    "Probe of " + url + " returned HTTP " + outcome.statusCode()); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private void maybeRestart(Map<String, Object> parameters, String serverName, JsonObject result) {
        if (asBool(get(parameters, "restart"))) { //$NON-NLS-1$
            appendRestart(publicationService.restartServer(serverName), result);
        } else {
            result.addProperty("restart_hint", //$NON-NLS-1$
                    "conf updated; restart the web server (action=restart or restart=true) to apply"); //$NON-NLS-1$
        }
    }

    private void maybeProbe(Map<String, Object> parameters, JsonObject result) {
        String probeUrl = asString(get(parameters, "probe_url")); //$NON-NLS-1$
        if (probeUrl == null) {
            return;
        }
        int timeoutS = asInt(get(parameters, "timeout_s"), DEFAULT_PROBE_TIMEOUT_S); //$NON-NLS-1$
        EdtWebPublicationService.ProbeOutcome outcome = publicationService.probe(probeUrl, timeoutS * 1000);
        result.addProperty("probe_url", probeUrl); //$NON-NLS-1$
        result.addProperty("probe_status", outcome.statusCode()); //$NON-NLS-1$
        result.addProperty("probe_elapsed_ms", outcome.elapsedMs()); //$NON-NLS-1$
        if (outcome.statusCode() >= 400) {
            throw new EdtToolException(EdtToolErrorCode.PROBE_FAILED,
                    "Probe of " + probeUrl + " returned HTTP " + outcome.statusCode()); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static void appendRestart(EdtWebPublicationService.RestartOutcome outcome, JsonObject result) {
        result.addProperty("restarted", true); //$NON-NLS-1$
        result.addProperty("restart_method", outcome.method()); //$NON-NLS-1$
        if (!outcome.stoppedPids().isEmpty()) {
            JsonArray pids = new JsonArray();
            outcome.stoppedPids().forEach(pids::add);
            result.add("stopped_pids", pids); //$NON-NLS-1$
        }
        if (outcome.startedPid() > 0) {
            result.addProperty("started_pid", outcome.startedPid()); //$NON-NLS-1$
        }
    }

    /**
     * Resolution priority for the publication's infobase connection: explicit
     * {@code infobase_connection} wins; otherwise {@code project_name} resolves through the
     * project's default infobase association (same path launch_app/update_infobase use).
     */
    private String resolveInfobaseConnection(Map<String, Object> parameters) {
        String explicit = asString(get(parameters, "infobase_connection")); //$NON-NLS-1$
        if (explicit != null) {
            return explicit;
        }
        String projectName = asString(get(parameters, "project_name")); //$NON-NLS-1$
        if (projectName == null) {
            throw new EdtToolException(EdtToolErrorCode.INVALID_ARGUMENT,
                    "publish requires either infobase_connection or project_name"); //$NON-NLS-1$
        }
        InfobaseReference infobase = runtimeService.resolveDefaultInfobase(projectName);
        if (infobase == null || infobase.getConnectionString() == null) {
            throw new EdtToolException(EdtToolErrorCode.INFOBASE_NOT_FOUND,
                    "Project '" + projectName + "' has no associated infobase with a connection string"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return infobase.getConnectionString().asConnectionString();
    }

    private JsonObject serverJson(WebServer server) {
        JsonObject json = new JsonObject();
        json.addProperty("name", server.getName()); //$NON-NLS-1$
        json.addProperty("type_id", server.getTypeId()); //$NON-NLS-1$
        json.addProperty("install_location", //$NON-NLS-1$
                server.getInstallLocation() == null ? "" : server.getInstallLocation().toString()); //$NON-NLS-1$
        json.addProperty("config_location", //$NON-NLS-1$
                server.getConfigLocation() == null ? "" : server.getConfigLocation().toString()); //$NON-NLS-1$
        json.addProperty("arch", server.getArch() == null ? "" : server.getArch().getLiteral()); //$NON-NLS-1$ //$NON-NLS-2$
        return json;
    }

    private JsonObject publicationJson(String serverName, Publication publication) {
        JsonObject json = new JsonObject();
        json.addProperty("name", publication.getName()); //$NON-NLS-1$
        json.addProperty("location", publication.getLocation() == null ? "" : publication.getLocation()); //$NON-NLS-1$ //$NON-NLS-2$
        json.addProperty("kind", EdtWebPublicationService.publicationKind(publication)); //$NON-NLS-1$
        if (publication instanceof InfobasePublication infobasePublication) {
            json.addProperty("infobase_connection", //$NON-NLS-1$
                    infobasePublication.getInfobaseConnection() == null ? "" //$NON-NLS-1$
                            : infobasePublication.getInfobaseConnection());
            json.addProperty("enabled", infobasePublication.isEnable()); //$NON-NLS-1$
        }
        Optional<URL> url = publicationService.getPublicationUrl(serverName, publication.getName());
        url.ifPresent(value -> json.addProperty("url", value.toString())); //$NON-NLS-1$
        return json;
    }

    private static Object get(Map<String, Object> parameters, String key) {
        return parameters == null ? null : parameters.get(key);
    }

    private String requireString(Map<String, Object> parameters, String key) {
        String value = asString(get(parameters, key));
        if (value == null) {
            throw new EdtToolException(EdtToolErrorCode.INVALID_ARGUMENT, key + " is required"); //$NON-NLS-1$
        }
        return value;
    }

    private static String asString(Object value) {
        if (value == null) {
            return null;
        }
        String raw = String.valueOf(value).trim();
        return raw.isEmpty() ? null : raw;
    }

    private static boolean asBool(Object value) {
        if (value instanceof Boolean b) {
            return b.booleanValue();
        }
        if (value instanceof String s) {
            return "true".equalsIgnoreCase(s.trim()); //$NON-NLS-1$
        }
        return false;
    }

    private static int asInt(Object value, int defaultValue) {
        if (value instanceof Number number) {
            int parsed = number.intValue();
            return parsed > 0 ? parsed : defaultValue;
        }
        if (value instanceof String s) {
            try {
                int parsed = Integer.parseInt(s.trim());
                return parsed > 0 ? parsed : defaultValue;
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private static String pretty(JsonObject object) {
        return new GsonBuilder().setPrettyPrinting().create().toJson(object);
    }
}
