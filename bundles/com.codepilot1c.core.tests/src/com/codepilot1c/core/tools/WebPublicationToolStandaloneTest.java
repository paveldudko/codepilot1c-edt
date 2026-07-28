package com.codepilot1c.core.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.Test;

import com._1c.g5.v8.dt.platform.services.model.InfobasePublication;
import com._1c.g5.v8.dt.platform.services.model.Publication;
import com._1c.g5.v8.dt.platform.services.model.WebServer;
import com.codepilot1c.core.edt.publication.EdtWebPublicationService;
import com.codepilot1c.core.edt.runtime.EdtRuntimeService;
import com.codepilot1c.core.edt.runtime.EdtToolErrorCode;
import com.codepilot1c.core.edt.runtime.EdtToolException;
import com.codepilot1c.core.tools.workspace.WebPublicationTool;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Standalone (plain-JUnit, no OSGi) tests for the {@code web_publication} tool: parameter
 * validation, the explicit-connection publish path, restart/probe result rendering, and the
 * pre-gateway validation in {@link EdtWebPublicationService#registerServer}.
 */
public class WebPublicationToolStandaloneTest {

    @Test
    public void unknownActionReturnsInvalidArgument() {
        WebPublicationTool tool = new WebPublicationTool(new StubPublicationService(), new EdtRuntimeService());

        ToolResult result = tool.execute(Map.of("action", "frobnicate")).join(); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse("Unknown action must fail", result.isSuccess()); //$NON-NLS-1$
        JsonObject json = JsonParser.parseString(result.getErrorMessage()).getAsJsonObject();
        assertEquals(EdtToolErrorCode.INVALID_ARGUMENT.name(), json.get("error_code").getAsString()); //$NON-NLS-1$
    }

    @Test
    public void publishRequiresConnectionOrProject() {
        StubPublicationService service = new StubPublicationService();
        WebPublicationTool tool = new WebPublicationTool(service, new EdtRuntimeService());

        ToolResult result = tool.execute(Map.of(
                "action", "publish", //$NON-NLS-1$ //$NON-NLS-2$
                "server", "apache-local", //$NON-NLS-1$ //$NON-NLS-2$
                "name", "demo")).join(); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse("publish without infobase_connection/project_name must fail", result.isSuccess()); //$NON-NLS-1$
        JsonObject json = JsonParser.parseString(result.getErrorMessage()).getAsJsonObject();
        assertEquals(EdtToolErrorCode.INVALID_ARGUMENT.name(), json.get("error_code").getAsString()); //$NON-NLS-1$
        assertFalse("Service publish must not be reached on validation failure", service.publishCalled); //$NON-NLS-1$
    }

    @Test
    public void publishWithExplicitConnectionRendersResultAndRestartHint() {
        StubPublicationService service = new StubPublicationService();
        WebPublicationTool tool = new WebPublicationTool(service, new EdtRuntimeService());

        ToolResult result = tool.execute(Map.of(
                "action", "publish", //$NON-NLS-1$ //$NON-NLS-2$
                "server", "apache-local", //$NON-NLS-1$ //$NON-NLS-2$
                "name", "agent-current", //$NON-NLS-1$ //$NON-NLS-2$
                "infobase_connection", "File=\"C:\\db\\sandbox\";")).join(); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("publish with explicit connection must succeed: " + result.getContent(), //$NON-NLS-1$
                result.isSuccess());
        JsonObject json = JsonParser.parseString(result.getContent()).getAsJsonObject();
        assertEquals("agent-current", json.get("name").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("File=\"C:\\db\\sandbox\";", json.get("infobase_connection").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("conf_or_auto", json.get("wsap_source").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("Without restart=true the result must carry a restart hint", //$NON-NLS-1$
                json.has("restart_hint")); //$NON-NLS-1$
        assertFalse("restart must not fire without restart=true", service.restartCalled); //$NON-NLS-1$
        assertEquals("File=\"C:\\db\\sandbox\";", service.lastConnection); //$NON-NLS-1$
        assertEquals("apache-local", service.lastServerName); //$NON-NLS-1$
    }

    @Test
    public void publishWithRestartReportsKillStartOutcome() {
        StubPublicationService service = new StubPublicationService();
        WebPublicationTool tool = new WebPublicationTool(service, new EdtRuntimeService());

        ToolResult result = tool.execute(Map.of(
                "action", "publish", //$NON-NLS-1$ //$NON-NLS-2$
                "server", "apache-local", //$NON-NLS-1$ //$NON-NLS-2$
                "name", "agent-current", //$NON-NLS-1$ //$NON-NLS-2$
                "infobase_connection", "File=\"C:\\db\\sandbox\";", //$NON-NLS-1$ //$NON-NLS-2$
                "restart", Boolean.TRUE)).join(); //$NON-NLS-1$

        assertTrue("publish+restart must succeed: " + result.getContent(), result.isSuccess()); //$NON-NLS-1$
        JsonObject json = JsonParser.parseString(result.getContent()).getAsJsonObject();
        assertTrue(json.get("restarted").getAsBoolean()); //$NON-NLS-1$
        assertEquals("kill_start", json.get("restart_method").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(456L, json.get("started_pid").getAsLong()); //$NON-NLS-1$
        assertTrue(service.restartCalled);
    }

    @Test
    public void publishCarriesCustomHttpServicesToServiceAndEchoesThem() {
        StubPublicationService service = new StubPublicationService();
        WebPublicationTool tool = new WebPublicationTool(service, new EdtRuntimeService());

        ToolResult result = tool.execute(Map.of(
                "action", "publish", //$NON-NLS-1$ //$NON-NLS-2$
                "server", "apache-local", //$NON-NLS-1$ //$NON-NLS-2$
                "name", "agent-current", //$NON-NLS-1$ //$NON-NLS-2$
                "infobase_connection", "File=\"C:\\db\\sandbox\";", //$NON-NLS-1$ //$NON-NLS-2$
                "publish_http_by_default", Boolean.TRUE, //$NON-NLS-1$
                "http_services", List.of(Map.of( //$NON-NLS-1$
                        "name", "BSLAnalyzerService", //$NON-NLS-1$ //$NON-NLS-2$
                        "root_url", "bsl-analyzer", //$NON-NLS-1$ //$NON-NLS-2$
                        "enable", Boolean.TRUE)))).join(); //$NON-NLS-1$

        assertTrue("publish with http_services must succeed: " + result.getContent(), result.isSuccess()); //$NON-NLS-1$
        // extras reached the service layer
        assertNotNull("extras must be passed to the publication service", service.lastExtras); //$NON-NLS-1$
        assertEquals(1, service.lastExtras.httpServices().size());
        assertEquals("bsl-analyzer", service.lastExtras.httpServices().get(0).rootUrl()); //$NON-NLS-1$
        assertEquals(Boolean.TRUE, service.lastExtras.publishHttpByDefault());
        // and are echoed back in the result payload
        JsonObject json = JsonParser.parseString(result.getContent()).getAsJsonObject();
        assertTrue("result must echo extras_applied", json.has("extras_applied")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("bsl-analyzer", json.getAsJsonObject("extras_applied") //$NON-NLS-1$ //$NON-NLS-2$
                .getAsJsonArray("http_services").get(0).getAsJsonObject().get("root_url").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void publishWiresPublishExtensionsByDefaultAndEchoesIt() {
        StubPublicationService service = new StubPublicationService();
        WebPublicationTool tool = new WebPublicationTool(service, new EdtRuntimeService());

        ToolResult result = tool.execute(Map.of(
                "action", "publish", //$NON-NLS-1$ //$NON-NLS-2$
                "server", "apache-local", //$NON-NLS-1$ //$NON-NLS-2$
                "name", "agent-current", //$NON-NLS-1$ //$NON-NLS-2$
                "infobase_connection", "File=\"C:\\db\\sandbox\";", //$NON-NLS-1$ //$NON-NLS-2$
                "http_services", List.of(Map.of( //$NON-NLS-1$
                        "name", "BSLAnalyzerService", //$NON-NLS-1$ //$NON-NLS-2$
                        "root_url", "bsl-analyzer", //$NON-NLS-1$ //$NON-NLS-2$
                        "enable", Boolean.TRUE)), //$NON-NLS-1$
                "publish_extensions_by_default", Boolean.TRUE)).join(); //$NON-NLS-1$

        assertTrue("publish must succeed: " + result.getContent(), result.isSuccess()); //$NON-NLS-1$
        assertNotNull("extras must reach the service", service.lastExtras); //$NON-NLS-1$
        assertEquals(Boolean.TRUE, service.lastExtras.publishExtensionsByDefault());
        JsonObject json = JsonParser.parseString(result.getContent()).getAsJsonObject();
        assertTrue("result must echo publish_extensions_by_default", //$NON-NLS-1$
                json.getAsJsonObject("extras_applied").has("publish_extensions_by_default")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(json.getAsJsonObject("extras_applied") //$NON-NLS-1$
                .get("publish_extensions_by_default").getAsBoolean()); //$NON-NLS-1$
    }

    @Test
    public void probeFailureSurfacesProbeFailedCode() {
        StubPublicationService service = new StubPublicationService();
        service.probeStatus = 503;
        WebPublicationTool tool = new WebPublicationTool(service, new EdtRuntimeService());

        ToolResult result = tool.execute(Map.of(
                "action", "probe", //$NON-NLS-1$ //$NON-NLS-2$
                "probe_url", "http://localhost:8090/agent-current/hs/bsl-analyzer/version")).join(); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse("HTTP 503 probe must fail the tool call", result.isSuccess()); //$NON-NLS-1$
        JsonObject json = JsonParser.parseString(result.getErrorMessage()).getAsJsonObject();
        assertEquals(EdtToolErrorCode.PROBE_FAILED.name(), json.get("error_code").getAsString()); //$NON-NLS-1$
    }

    @Test
    public void probePassesCredentialsToService() {
        StubPublicationService service = new StubPublicationService();
        service.probeStatus = 200;
        WebPublicationTool tool = new WebPublicationTool(service, new EdtRuntimeService());

        ToolResult result = tool.execute(Map.of(
                "action", "probe", //$NON-NLS-1$ //$NON-NLS-2$
                "probe_url", "http://localhost:8090/agent-current/hs/bsl-analyzer/version", //$NON-NLS-1$ //$NON-NLS-2$
                "probe_user", "agent", //$NON-NLS-1$ //$NON-NLS-2$
                "probe_password", "secret")).join(); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("authenticated 200 probe must succeed", result.isSuccess()); //$NON-NLS-1$
        assertEquals("agent", service.lastProbeUser); //$NON-NLS-1$
        JsonObject json = JsonParser.parseString(result.getContent()).getAsJsonObject();
        assertTrue(json.get("probe_authenticated").getAsBoolean()); //$NON-NLS-1$
    }

    /**
     * Regression pin for the inline probe of {@code action=publish}: the credentials the caller
     * passes alongside the publish must reach the probe, otherwise the self-verification goes
     * unauthenticated and an auth-protected 1C service answers 401 — a false "publication broken".
     */
    @Test
    public void publishInlineProbeCarriesCredentials() {
        StubPublicationService service = new StubPublicationService();
        service.probeStatus = 200;
        WebPublicationTool tool = new WebPublicationTool(service, new EdtRuntimeService());

        ToolResult result = tool.execute(Map.of(
                "action", "publish", //$NON-NLS-1$ //$NON-NLS-2$
                "server", "apache-local", //$NON-NLS-1$ //$NON-NLS-2$
                "name", "agent-current", //$NON-NLS-1$ //$NON-NLS-2$
                "infobase_connection", "File=\"C:\\db\\sandbox\";", //$NON-NLS-1$ //$NON-NLS-2$
                "probe_url", "http://localhost:8090/agent-current/hs/bsl-analyzer/version", //$NON-NLS-1$ //$NON-NLS-2$
                "probe_user", "agent", //$NON-NLS-1$ //$NON-NLS-2$
                "probe_password", "secret")).join(); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("publish with an authenticated inline probe must succeed: " + result.getContent(), //$NON-NLS-1$
                result.isSuccess());
        assertEquals("inline probe must receive probe_user", "agent", service.lastProbeUser); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("inline probe must receive probe_password", "secret", service.lastProbePassword); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject json = JsonParser.parseString(result.getContent()).getAsJsonObject();
        assertTrue(json.get("probe_authenticated").getAsBoolean()); //$NON-NLS-1$
        assertEquals(200, json.get("probe_status").getAsInt()); //$NON-NLS-1$
    }

    /** Same pin for {@code action=restart}, the other caller of the shared inline-probe path. */
    @Test
    public void restartInlineProbeCarriesCredentials() {
        StubPublicationService service = new StubPublicationService();
        service.probeStatus = 200;
        WebPublicationTool tool = new WebPublicationTool(service, new EdtRuntimeService());

        ToolResult result = tool.execute(Map.of(
                "action", "restart", //$NON-NLS-1$ //$NON-NLS-2$
                "server", "apache-local", //$NON-NLS-1$ //$NON-NLS-2$
                "probe_url", "http://localhost:8090/agent-current/hs/bsl-analyzer/version", //$NON-NLS-1$ //$NON-NLS-2$
                "probe_user", "agent", //$NON-NLS-1$ //$NON-NLS-2$
                "probe_password", "secret")).join(); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("restart with an authenticated inline probe must succeed: " + result.getContent(), //$NON-NLS-1$
                result.isSuccess());
        assertEquals("agent", service.lastProbeUser); //$NON-NLS-1$
        assertEquals("secret", service.lastProbePassword); //$NON-NLS-1$
    }

    @Test
    public void probe401WithoutCredentialsHintsAuth() {
        StubPublicationService service = new StubPublicationService();
        service.probeStatus = 401;
        WebPublicationTool tool = new WebPublicationTool(service, new EdtRuntimeService());

        ToolResult result = tool.execute(Map.of(
                "action", "probe", //$NON-NLS-1$ //$NON-NLS-2$
                "probe_url", "http://localhost:8090/agent-current/hs/bsl-analyzer/version")).join(); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse("401 probe must fail", result.isSuccess()); //$NON-NLS-1$
        JsonObject json = JsonParser.parseString(result.getErrorMessage()).getAsJsonObject();
        assertEquals(EdtToolErrorCode.PROBE_FAILED.name(), json.get("error_code").getAsString()); //$NON-NLS-1$
        assertTrue("401-without-creds message must hint probe_user/probe_password", //$NON-NLS-1$
                json.get("message").getAsString().contains("probe_user")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A raw {@code RuntimeException} from the EDT layer (in production: {@code SWTException:
     * Invalid thread access} from an unguarded web-server-editor listener) must not leak as the
     * non-JSON {@code "Exception: <message>"} string {@code ToolExecutionService} renders for an
     * uncaught async failure — the tool contract is structured JSON.
     */
    @Test
    public void registerServerRuntimeFailureReturnsStructuredError() {
        StubPublicationService service = new StubPublicationService();
        service.registerFailure = new IllegalStateException("Invalid thread access"); //$NON-NLS-1$
        WebPublicationTool tool = new WebPublicationTool(service, new EdtRuntimeService());

        ToolResult result = tool.execute(Map.of(
                "action", "register_server", //$NON-NLS-1$ //$NON-NLS-2$
                "server", "apache-local", //$NON-NLS-1$ //$NON-NLS-2$
                "install_location", "C:\\Apache24", //$NON-NLS-1$ //$NON-NLS-2$
                "config_location", "C:\\Apache24\\conf\\httpd.conf")).join(); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse("an unregistered server after a runtime failure must fail the call", //$NON-NLS-1$
                result.isSuccess());
        String message = result.getErrorMessage();
        assertFalse("error must be JSON, not a raw 'Exception: ...' string: " + message, //$NON-NLS-1$
                message.startsWith("Exception")); //$NON-NLS-1$
        JsonObject json = JsonParser.parseString(message).getAsJsonObject();
        assertEquals(EdtToolErrorCode.WEB_SERVER_ACCESS_FAILED.name(), json.get("error_code").getAsString()); //$NON-NLS-1$
        assertTrue("message must name the underlying EDT failure: " + json, //$NON-NLS-1$
                json.get("message").getAsString().contains("Invalid thread access")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * {@code IWebServerManager.add} saves the registry BEFORE it fires its change event, so a
     * listener blowing up on the worker thread leaves a genuinely registered server behind. The
     * tool must re-read the registry and report success with an advisory instead of a failure the
     * caller cannot act on.
     */
    @Test
    public void registerServerSurvivesUiListenerFailureWithAdvisory() {
        StubPublicationService service = new StubPublicationService();
        service.registerFailure = new IllegalStateException("Invalid thread access"); //$NON-NLS-1$
        service.registeredServer = newWebServerProxy("apache-local"); //$NON-NLS-1$
        WebPublicationTool tool = new WebPublicationTool(service, new EdtRuntimeService());

        ToolResult result = tool.execute(Map.of(
                "action", "register_server", //$NON-NLS-1$ //$NON-NLS-2$
                "server", "apache-local", //$NON-NLS-1$ //$NON-NLS-2$
                "install_location", "C:\\Apache24", //$NON-NLS-1$ //$NON-NLS-2$
                "config_location", "C:\\Apache24\\conf\\httpd.conf")).join(); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("a persisted registration must be reported as success: " + result.getContent(), //$NON-NLS-1$
                result.isSuccess());
        JsonObject json = JsonParser.parseString(result.getContent()).getAsJsonObject();
        assertTrue(json.get("registered_with_ui_warning").getAsBoolean()); //$NON-NLS-1$
        assertEquals("ok_with_warning", json.get("status").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("apache-local", json.getAsJsonObject("server").get("name").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue("advisory must explain the UI-listener failure: " + json, //$NON-NLS-1$
                json.get("ui_warning").getAsString().contains("Invalid thread access")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** The same belt for every other action: no raw runtime failure escapes as a non-JSON string. */
    @Test
    public void unexpectedRuntimeFailureIsStructuredJson() {
        StubPublicationService service = new StubPublicationService();
        service.listServersFailure = new IllegalStateException("Invalid thread access"); //$NON-NLS-1$
        WebPublicationTool tool = new WebPublicationTool(service, new EdtRuntimeService());

        ToolResult result = tool.execute(Map.of("action", "list_servers")).join(); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse(result.isSuccess());
        JsonObject json = JsonParser.parseString(result.getErrorMessage()).getAsJsonObject();
        assertEquals(EdtToolErrorCode.WEB_SERVER_ACCESS_FAILED.name(), json.get("error_code").getAsString()); //$NON-NLS-1$
        assertEquals("error", json.get("status").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("list_servers", json.get("action").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void registerServerValidatesLocationsBeforeTouchingEdt() {
        EdtWebPublicationService service = new EdtWebPublicationService();
        try {
            service.registerServer("apache-local", Paths.get("Z:\\no\\such\\dir"), //$NON-NLS-1$ //$NON-NLS-2$
                    Paths.get("Z:\\no\\such\\dir\\conf\\httpd.conf"), null, null); //$NON-NLS-1$
            fail("Expected INVALID_PATH for a missing install directory"); //$NON-NLS-1$
        } catch (EdtToolException e) {
            assertEquals(EdtToolErrorCode.INVALID_PATH, e.getCode());
        }
    }

    @Test
    public void registerServerRejectsBlankName() {
        EdtWebPublicationService service = new EdtWebPublicationService();
        try {
            service.registerServer(" ", Paths.get("C:\\"), Paths.get("C:\\httpd.conf"), null, null); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            fail("Expected INVALID_ARGUMENT for a blank server name"); //$NON-NLS-1$
        } catch (EdtToolException e) {
            assertEquals(EdtToolErrorCode.INVALID_ARGUMENT, e.getCode());
        }
    }

    // -- stubs ------------------------------------------------------------------------------

    /**
     * Publication-service stub: records calls and returns proxy model objects so the tests run
     * without EMF/OSGi. {@code restartServer} reports a canned kill+start outcome.
     */
    private static final class StubPublicationService extends EdtWebPublicationService {

        boolean publishCalled;
        boolean restartCalled;
        String lastServerName;
        String lastConnection;
        PublicationExtras lastExtras;
        int probeStatus = 200;
        /** Raw EDT-side failure {@code registerServer} should throw (SWTException stand-in). */
        RuntimeException registerFailure;
        /** What {@code findServer} finds afterwards — non-null means the registration persisted. */
        WebServer registeredServer;
        /** Raw EDT-side failure {@code listServers} should throw. */
        RuntimeException listServersFailure;

        @Override
        public List<WebServer> listServers() {
            if (listServersFailure != null) {
                throw listServersFailure;
            }
            return List.of();
        }

        @Override
        public WebServer registerServer(String name, Path installLocation, Path configLocation,
                String apacheVersion, String arch) {
            if (registerFailure != null) {
                throw registerFailure;
            }
            return newWebServerProxy(name);
        }

        @Override
        public Optional<WebServer> findServer(String name) {
            return Optional.ofNullable(registeredServer);
        }

        @Override
        public List<Publication> listPublications(String serverName) {
            return List.of();
        }

        @Override
        public InfobasePublication publish(String serverName, String name, Path location,
                String infobaseConnection, String wsapVersion, PublicationExtras extras) {
            publishCalled = true;
            lastServerName = serverName;
            lastConnection = infobaseConnection;
            lastExtras = extras;
            return newPublicationProxy(name, location == null ? "" : location.toString(), infobaseConnection); //$NON-NLS-1$
        }

        @Override
        public Optional<java.net.URL> getPublicationUrl(String serverName, String name) {
            return Optional.empty();
        }

        @Override
        public RestartOutcome restartServer(String serverName) {
            restartCalled = true;
            return new RestartOutcome("kill_start", List.of(123L), 456L, "httpd -d ... -f ..."); //$NON-NLS-1$ //$NON-NLS-2$
        }

        String lastProbeUser;
        String lastProbePassword;

        @Override
        public ProbeOutcome probe(String url, int timeoutMs, String user, String password) {
            lastProbeUser = user;
            lastProbePassword = password;
            return new ProbeOutcome(probeStatus, 5L);
        }
    }

    private static WebServer newWebServerProxy(String name) {
        InvocationHandler handler = (Object proxy, Method method, Object[] args) -> {
            switch (method.getName()) {
            case "getName": //$NON-NLS-1$
                return name;
            case "getTypeId": //$NON-NLS-1$
                return "com._1c.g5.v8.dt.platform.services.core.webServerType.Apache2_4"; //$NON-NLS-1$
            case "getInstallLocation": //$NON-NLS-1$
                return Paths.get("C:\\Apache24"); //$NON-NLS-1$
            case "getConfigLocation": //$NON-NLS-1$
                return Paths.get("C:\\Apache24\\conf\\httpd.conf"); //$NON-NLS-1$
            case "toString": //$NON-NLS-1$
                return "WebServerProxy[" + name + "]"; //$NON-NLS-1$ //$NON-NLS-2$
            case "hashCode": //$NON-NLS-1$
                return System.identityHashCode(proxy);
            case "equals": //$NON-NLS-1$
                return proxy == args[0];
            default:
                Class<?> returnType = method.getReturnType();
                if (returnType == boolean.class) {
                    return Boolean.FALSE;
                }
                return null;
            }
        };
        return (WebServer) Proxy.newProxyInstance(
                WebPublicationToolStandaloneTest.class.getClassLoader(),
                new Class<?>[] {WebServer.class}, handler);
    }

    private static InfobasePublication newPublicationProxy(String name, String location, String connection) {
        InvocationHandler handler = (Object proxy, Method method, Object[] args) -> {
            switch (method.getName()) {
            case "getName": //$NON-NLS-1$
                return name;
            case "getLocation": //$NON-NLS-1$
                return location;
            case "getInfobaseConnection": //$NON-NLS-1$
                return connection;
            case "isEnable": //$NON-NLS-1$
                return Boolean.TRUE;
            case "getPublicationType": //$NON-NLS-1$
                return null;
            case "toString": //$NON-NLS-1$
                return "InfobasePublicationProxy[" + name + "]"; //$NON-NLS-1$ //$NON-NLS-2$
            case "hashCode": //$NON-NLS-1$
                return System.identityHashCode(proxy);
            case "equals": //$NON-NLS-1$
                return proxy == args[0];
            default:
                Class<?> returnType = method.getReturnType();
                if (returnType == boolean.class) {
                    return Boolean.FALSE;
                }
                return null;
            }
        };
        return (InfobasePublication) Proxy.newProxyInstance(
                WebPublicationToolStandaloneTest.class.getClassLoader(),
                new Class<?>[] {InfobasePublication.class}, handler);
    }
}
