package com.codepilot1c.core.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
        int probeStatus = 200;

        @Override
        public List<WebServer> listServers() {
            return List.of();
        }

        @Override
        public List<Publication> listPublications(String serverName) {
            return List.of();
        }

        @Override
        public InfobasePublication publish(String serverName, String name, Path location,
                String infobaseConnection, String wsapVersion) {
            publishCalled = true;
            lastServerName = serverName;
            lastConnection = infobaseConnection;
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

        @Override
        public ProbeOutcome probe(String url, int timeoutMs, String user, String password) {
            lastProbeUser = user;
            return new ProbeOutcome(probeStatus, 5L);
        }
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
