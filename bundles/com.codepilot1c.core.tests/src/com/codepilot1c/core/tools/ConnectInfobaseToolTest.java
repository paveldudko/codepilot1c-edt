package com.codepilot1c.core.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAccessManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseManager;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com.codepilot1c.core.edt.runtime.EdtInfobaseConnectService;
import com.codepilot1c.core.edt.runtime.EdtInfobaseConnectService.ConnectRequest;
import com.codepilot1c.core.edt.runtime.EdtInfobaseConnectService.ConnectResult;
import com.codepilot1c.core.edt.runtime.EdtInfobaseConnectService.ConnectionKind;
import com.codepilot1c.core.edt.runtime.EdtRuntimeGateway;
import com.codepilot1c.core.edt.runtime.EdtToolErrorCode;
import com.codepilot1c.core.edt.runtime.EdtToolException;
import com.codepilot1c.core.tools.workspace.ConnectInfobaseTool;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class ConnectInfobaseToolTest {

    private static final String SECRET_PASSWORD = "Str0ng-Secret-Value-XYZ"; //$NON-NLS-1$

    @Test
    public void fileKindReportsSuccessAndMarksPrimary() {
        RecordingConnectService service = new RecordingConnectService();
        ConnectInfobaseTool tool = new ConnectInfobaseTool(service);

        Map<String, Object> params = new HashMap<>();
        params.put("project_name", "Demo"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("database_path", "/tmp/demo-ib"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("kind", "file"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("login", "Admin"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("password", SECRET_PASSWORD); //$NON-NLS-1$

        ToolResult result = tool.execute(params).join();

        assertTrue(result.isSuccess());
        JsonObject json = JsonParser.parseString(result.getContent()).getAsJsonObject();
        assertTrue(json.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("Demo", json.get("project").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(json.get("primary").getAsBoolean()); //$NON-NLS-1$
        JsonObject infobase = json.getAsJsonObject("infobase"); //$NON-NLS-1$
        assertEquals("file", infobase.get("kind").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("/tmp/demo-ib", infobase.get("path").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Admin", infobase.get("login").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(infobase.has("password")); //$NON-NLS-1$
        assertFalse(infobase.has("port")); //$NON-NLS-1$

        ConnectRequest observed = service.lastRequest;
        assertNotNull(observed);
        assertEquals(ConnectionKind.FILE, observed.kind());
        assertEquals("Demo", observed.projectName()); //$NON-NLS-1$
        assertEquals("Admin", observed.login()); //$NON-NLS-1$
        assertEquals(SECRET_PASSWORD, observed.password());
        assertTrue(observed.setPrimary());
    }

    @Test
    public void standaloneKindPropagatesPortAndRuntimeVersion() {
        RecordingConnectService service = new RecordingConnectService();
        service.responseBuilder = req -> new ConnectResult(ConnectionKind.STANDALONE,
                req.databasePath(), "standalone-ib", req.login(), //$NON-NLS-1$
                req.serverPort() == null ? Integer.valueOf(1541) : req.serverPort(), req.setPrimary());
        ConnectInfobaseTool tool = new ConnectInfobaseTool(service);

        Map<String, Object> params = new HashMap<>();
        params.put("project_name", "Demo"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("database_path", "/tmp/sa-ib"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("kind", "standalone"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("server_port", Integer.valueOf(1545)); //$NON-NLS-1$
        params.put("runtime_version", "8.3.24.1656"); //$NON-NLS-1$ //$NON-NLS-2$

        ToolResult result = tool.execute(params).join();

        assertTrue(result.isSuccess());
        JsonObject json = JsonParser.parseString(result.getContent()).getAsJsonObject();
        assertTrue(json.get("success").getAsBoolean()); //$NON-NLS-1$
        JsonObject infobase = json.getAsJsonObject("infobase"); //$NON-NLS-1$
        assertEquals("standalone", infobase.get("kind").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(1545, infobase.get("port").getAsInt()); //$NON-NLS-1$

        ConnectRequest observed = service.lastRequest;
        assertNotNull(observed);
        assertEquals(ConnectionKind.STANDALONE, observed.kind());
        assertEquals(Integer.valueOf(1545), observed.serverPort());
        assertEquals("8.3.24.1656", observed.runtimeVersion()); //$NON-NLS-1$
    }

    @Test
    public void serverKindForwardsSrvrRefAndReportsConnectionString() {
        RecordingConnectService service = new RecordingConnectService();
        service.responseBuilder = req -> new ConnectResult(ConnectionKind.SERVER,
                "Srvr=\"" + req.serverAddress() + "\";Ref=\"" + req.serverRef() + "\";", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                req.serverRef(), req.login(), null, req.setPrimary());
        ConnectInfobaseTool tool = new ConnectInfobaseTool(service);

        Map<String, Object> params = new HashMap<>();
        params.put("project_name", "Demo"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("kind", "server"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("srvr", "term-dev-3.erpdev.team"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("ref", "BF-5946"); //$NON-NLS-1$ //$NON-NLS-2$
        // database_path intentionally omitted — not required for kind=server.

        ToolResult result = tool.execute(params).join();

        assertTrue(result.isSuccess());
        JsonObject json = JsonParser.parseString(result.getContent()).getAsJsonObject();
        JsonObject infobase = json.getAsJsonObject("infobase"); //$NON-NLS-1$
        assertEquals("server", infobase.get("kind").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Srvr=\"term-dev-3.erpdev.team\";Ref=\"BF-5946\";", //$NON-NLS-1$
                infobase.get("path").getAsString()); //$NON-NLS-1$

        ConnectRequest observed = service.lastRequest;
        assertNotNull(observed);
        assertEquals(ConnectionKind.SERVER, observed.kind());
        assertEquals("term-dev-3.erpdev.team", observed.serverAddress()); //$NON-NLS-1$
        assertEquals("BF-5946", observed.serverRef()); //$NON-NLS-1$
    }

    @Test
    public void serverKindWithoutSrvrFails() {
        ConnectInfobaseTool tool = new ConnectInfobaseTool(new RecordingConnectService());

        Map<String, Object> params = new HashMap<>();
        params.put("project_name", "Demo"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("kind", "server"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("ref", "BF-5946"); //$NON-NLS-1$ //$NON-NLS-2$
        // srvr omitted.

        ToolResult result = tool.execute(params).join();
        assertFalse(result.isSuccess());
        JsonObject json = JsonParser.parseString(result.getErrorMessage()).getAsJsonObject();
        assertEquals(EdtToolErrorCode.INVALID_ARGUMENT.name(), json.get("error_code").getAsString()); //$NON-NLS-1$
        assertTrue(json.get("message").getAsString().toLowerCase().contains("srvr")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void connectionKindParsesServer() {
        assertEquals(ConnectionKind.SERVER, ConnectionKind.parse("server")); //$NON-NLS-1$
        assertEquals(ConnectionKind.SERVER, ConnectionKind.parse("  SERVER  ")); //$NON-NLS-1$
        assertEquals(ConnectionKind.FILE, ConnectionKind.parse("file")); //$NON-NLS-1$
        assertNull(ConnectionKind.parse("cluster")); //$NON-NLS-1$
    }

    @Test
    public void serviceValidateRequiresSrvrAndRefForServerKind() {
        EdtInfobaseConnectService service = new EdtInfobaseConnectService();
        // Blank srvr -> INVALID_ARGUMENT before any EDT/workspace access (validate runs first).
        try {
            service.connect(new ConnectRequest("Demo", null, ConnectionKind.SERVER, null, null, //$NON-NLS-1$
                    true, null, null, false, null, "", "BF-5946")); //$NON-NLS-1$ //$NON-NLS-2$
            fail("expected INVALID_ARGUMENT for missing srvr"); //$NON-NLS-1$
        } catch (EdtToolException e) {
            assertEquals(EdtToolErrorCode.INVALID_ARGUMENT, e.getCode());
            assertTrue(e.getMessage().toLowerCase().contains("srvr")); //$NON-NLS-1$
        }
        // srvr present, ref blank -> INVALID_ARGUMENT mentioning ref.
        try {
            service.connect(new ConnectRequest("Demo", null, ConnectionKind.SERVER, null, null, //$NON-NLS-1$
                    true, null, null, false, null, "term-dev-3", "")); //$NON-NLS-1$ //$NON-NLS-2$
            fail("expected INVALID_ARGUMENT for missing ref"); //$NON-NLS-1$
        } catch (EdtToolException e) {
            assertEquals(EdtToolErrorCode.INVALID_ARGUMENT, e.getCode());
            assertTrue(e.getMessage().toLowerCase().contains("ref")); //$NON-NLS-1$
        }
    }

    @Test
    public void missingRequiredFieldFails() {
        ConnectInfobaseTool tool = new ConnectInfobaseTool(new RecordingConnectService());

        Map<String, Object> params = new HashMap<>();
        params.put("project_name", "Demo"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("kind", "file"); //$NON-NLS-1$ //$NON-NLS-2$
        // database_path intentionally omitted.

        ToolResult result = tool.execute(params).join();
        assertFalse(result.isSuccess());
        JsonObject json = JsonParser.parseString(result.getErrorMessage()).getAsJsonObject();
        assertEquals(EdtToolErrorCode.INVALID_ARGUMENT.name(), json.get("error_code").getAsString()); //$NON-NLS-1$
        assertTrue(json.get("message").getAsString().toLowerCase().contains("database_path")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void invalidKindValueFails() {
        ConnectInfobaseTool tool = new ConnectInfobaseTool(new RecordingConnectService());

        Map<String, Object> params = new HashMap<>();
        params.put("project_name", "Demo"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("database_path", "/tmp/demo-ib"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("kind", "cluster"); //$NON-NLS-1$ //$NON-NLS-2$

        ToolResult result = tool.execute(params).join();
        assertFalse(result.isSuccess());
        JsonObject json = JsonParser.parseString(result.getErrorMessage()).getAsJsonObject();
        assertEquals(EdtToolErrorCode.INVALID_ARGUMENT.name(), json.get("error_code").getAsString()); //$NON-NLS-1$
        assertTrue(json.get("message").getAsString().contains("kind")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void passwordIsNeverLeakedToResult() {
        RecordingConnectService service = new RecordingConnectService();
        ConnectInfobaseTool tool = new ConnectInfobaseTool(service);

        Map<String, Object> params = new HashMap<>();
        params.put("project_name", "Demo"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("database_path", "/tmp/demo-ib"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("kind", "file"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("login", "Admin"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("password", SECRET_PASSWORD); //$NON-NLS-1$

        ToolResult result = tool.execute(params).join();
        assertTrue(result.isSuccess());
        String body = result.getContent();
        assertFalse("password must not appear in result content", body.contains(SECRET_PASSWORD)); //$NON-NLS-1$
        // Also verify that even on failure the password is not surfaced in the error payload.
        Map<String, Object> failingParams = new HashMap<>();
        failingParams.put("project_name", "Demo"); //$NON-NLS-1$ //$NON-NLS-2$
        failingParams.put("database_path", "/tmp/demo-ib"); //$NON-NLS-1$ //$NON-NLS-2$
        failingParams.put("kind", "file"); //$NON-NLS-1$ //$NON-NLS-2$
        failingParams.put("password", SECRET_PASSWORD); //$NON-NLS-1$
        RecordingConnectService failing = new RecordingConnectService();
        failing.responseBuilder = req -> { throw new EdtToolException(EdtToolErrorCode.EDT_SERVICE_UNAVAILABLE,
                "service offline"); }; //$NON-NLS-1$
        ToolResult failResult = new ConnectInfobaseTool(failing).execute(failingParams).join();
        assertFalse(failResult.isSuccess());
        assertFalse("password must not appear in error content", //$NON-NLS-1$
                failResult.getErrorMessage().contains(SECRET_PASSWORD));
    }

    @Test
    public void setPrimaryFalseIsHonored() {
        RecordingConnectService service = new RecordingConnectService();
        service.responseBuilder = req -> new ConnectResult(ConnectionKind.FILE, req.databasePath(),
                "ib", req.login(), null, req.setPrimary()); //$NON-NLS-1$
        ConnectInfobaseTool tool = new ConnectInfobaseTool(service);

        Map<String, Object> params = new HashMap<>();
        params.put("project_name", "Demo"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("database_path", "/tmp/demo-ib"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("kind", "file"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("set_primary", Boolean.FALSE); //$NON-NLS-1$

        ToolResult result = tool.execute(params).join();
        assertTrue(result.isSuccess());

        ConnectRequest observed = service.lastRequest;
        assertNotNull(observed);
        assertFalse("tool must forward set_primary=false to service", observed.setPrimary()); //$NON-NLS-1$

        JsonObject json = JsonParser.parseString(result.getContent()).getAsJsonObject();
        assertFalse(json.get("primary").getAsBoolean()); //$NON-NLS-1$
        JsonObject infobase = json.getAsJsonObject("infobase"); //$NON-NLS-1$
        // With no login provided the sanitized login should be omitted from the payload.
        assertNull(infobase.get("login")); //$NON-NLS-1$
    }

    @Test
    public void existingPrimaryWithoutForceReturnsPrimaryExists() {
        RecordingConnectService service = new RecordingConnectService();
        service.responseBuilder = req -> { throw new EdtToolException(EdtToolErrorCode.PRIMARY_EXISTS,
                "primary_exists: current_primary=OldBase, pass force=true to replace"); }; //$NON-NLS-1$
        ConnectInfobaseTool tool = new ConnectInfobaseTool(service);

        Map<String, Object> params = new HashMap<>();
        params.put("project_name", "Demo"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("database_path", "/tmp/demo-ib"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("kind", "file"); //$NON-NLS-1$ //$NON-NLS-2$
        // force intentionally omitted (default false).

        ToolResult result = tool.execute(params).join();

        assertFalse(result.isSuccess());
        JsonObject json = JsonParser.parseString(result.getErrorMessage()).getAsJsonObject();
        assertEquals(EdtToolErrorCode.PRIMARY_EXISTS.name(), json.get("error_code").getAsString()); //$NON-NLS-1$
        assertEquals("primary_exists", json.get("error").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("OldBase", json.get("current_primary").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(json.get("hint").getAsString().contains("force=true")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void existingPrimaryWithForceReportsReplacedPrevious() {
        RecordingConnectService service = new RecordingConnectService();
        service.responseBuilder = req -> new ConnectResult(ConnectionKind.FILE, req.databasePath(),
                "NewBase", req.login(), null, true, "OldBase"); //$NON-NLS-1$ //$NON-NLS-2$
        ConnectInfobaseTool tool = new ConnectInfobaseTool(service);

        Map<String, Object> params = new HashMap<>();
        params.put("project_name", "Demo"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("database_path", "/tmp/demo-ib"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("kind", "file"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("force", Boolean.TRUE); //$NON-NLS-1$

        ToolResult result = tool.execute(params).join();
        assertTrue(result.isSuccess());
        JsonObject json = JsonParser.parseString(result.getContent()).getAsJsonObject();
        assertTrue(json.get("primary").getAsBoolean()); //$NON-NLS-1$
        assertEquals("OldBase", json.get("replaced_previous").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$

        ConnectRequest observed = service.lastRequest;
        assertNotNull(observed);
        assertTrue("tool must forward force=true to service", observed.force()); //$NON-NLS-1$
    }

    @Test
    public void edtNotReadySurfacesFromIllegalStateException() {
        RecordingConnectService service = new RecordingConnectService();
        service.responseBuilder = req -> { throw new IllegalStateException(
                "IInfobaseManager service unavailable \u2014 EDT may not be fully initialized"); }; //$NON-NLS-1$
        ConnectInfobaseTool tool = new ConnectInfobaseTool(service);

        Map<String, Object> params = new HashMap<>();
        params.put("project_name", "Demo"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("database_path", "/tmp/demo-ib"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("kind", "file"); //$NON-NLS-1$ //$NON-NLS-2$

        ToolResult result = tool.execute(params).join();
        assertFalse(result.isSuccess());
        JsonObject json = JsonParser.parseString(result.getErrorMessage()).getAsJsonObject();
        assertEquals(EdtToolErrorCode.EDT_NOT_READY.name(), json.get("error_code").getAsString()); //$NON-NLS-1$
        assertEquals("edt_not_ready", json.get("error").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(json.get("message").getAsString().contains("IInfobaseManager")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void nullMessageExceptionSurfacesClassNameInstead() {
        // When the underlying service throws with a null message, the tool's JSON payload
        // must not surface an empty `message` field — it should fall back to the exception
        // class name so operators have something actionable to grep. See GH issue #31.
        RecordingConnectService service = new RecordingConnectService();
        service.responseBuilder = req -> { throw new RuntimeException((String) null); };
        ConnectInfobaseTool tool = new ConnectInfobaseTool(service);

        Map<String, Object> params = new HashMap<>();
        params.put("project_name", "Demo"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("database_path", "/tmp/demo-ib"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("kind", "file"); //$NON-NLS-1$ //$NON-NLS-2$

        ToolResult result = tool.execute(params).join();
        assertFalse(result.isSuccess());
        JsonObject json = JsonParser.parseString(result.getErrorMessage()).getAsJsonObject();
        assertEquals(EdtToolErrorCode.EDT_SERVICE_UNAVAILABLE.name(),
                json.get("error_code").getAsString()); //$NON-NLS-1$
        String message = json.get("message").getAsString(); //$NON-NLS-1$
        assertFalse("message must not be empty when underlying exception has null getMessage()", //$NON-NLS-1$
                message.isBlank());
        assertTrue("message must include the exception class name, was: " + message, //$NON-NLS-1$
                message.contains("RuntimeException")); //$NON-NLS-1$
    }

    @Test
    public void pathOutsideWorkspaceOrHomeIsRejected() {
        // /etc/passwd is never inside the Eclipse workspace or the user home directory,
        // so the path validator must reject it with INVALID_PATH.
        try {
            EdtInfobaseConnectService.validateAndNormalizePath("/etc/passwd"); //$NON-NLS-1$
            fail("expected EdtToolException for path outside allowed roots"); //$NON-NLS-1$
        } catch (EdtToolException e) {
            assertEquals(EdtToolErrorCode.INVALID_PATH, e.getCode());
            assertTrue(e.getMessage().toLowerCase().contains("invalid_path")); //$NON-NLS-1$
        }
    }

    @Test
    public void pathWithParentSegmentIsRejected() {
        // Even if the normalized path might end up inside the home directory, any raw '..'
        // segment must be rejected as defense-in-depth.
        String home = System.getProperty("user.home"); //$NON-NLS-1$
        String traversal = home + "/../tmp/pwned"; //$NON-NLS-1$
        try {
            EdtInfobaseConnectService.validateAndNormalizePath(traversal);
            fail("expected EdtToolException for path containing '..'"); //$NON-NLS-1$
        } catch (EdtToolException e) {
            assertEquals(EdtToolErrorCode.INVALID_PATH, e.getCode());
            assertTrue(e.getMessage().contains("'..'")); //$NON-NLS-1$
        }
    }

    // ----- GH issue #31: persistReference/storeAccessSettings UUID handling ---------------------

    /**
     * Regression for GH issue #31: when {@code IInfobaseManager.isPersistenceSupported()} is
     * false, {@code persistReference} must still assign a UUID locally so the subsequent
     * {@code storeSettings} call does not fail with an NPE. Before the fix, the reference
     * would come out of {@code persistReference} with {@code getUuid() == null} and EDT's
     * {@code storeSettings} would NPE deep inside.
     */
    @Test
    public void persistReferenceAssignsUuidWhenPersistenceUnsupported() {
        StubInfobaseManager manager = new StubInfobaseManager(false);
        StubGateway gateway = new StubGateway(manager, noopAccessManager());
        TestableConnectService service = new TestableConnectService(gateway);

        InfobaseReference reference = stubReferenceWithState(null, "ib-name"); //$NON-NLS-1$
        assertNull("precondition: reference starts without a uuid", reference.getUuid()); //$NON-NLS-1$

        service.invokePersistReference(reference);

        assertNotNull("persistReference must leave the reference with a non-null UUID " //$NON-NLS-1$
                + "even when persistence is unsupported", reference.getUuid());
        assertTrue("manager.add must NOT be called when persistence is unsupported", //$NON-NLS-1$
                manager.addCalls.isEmpty());
    }

    @Test
    public void persistReferenceThrowsNameCollisionWhenNameTakenByDifferentInfobase() {
        StubInfobaseManager manager = new StubInfobaseManager(true);
        UUID existingUuid = UUID.randomUUID();
        manager.findByNames = List.of(
                stubReferenceWithState(existingUuid, "shared-name", "File=\"/tmp/existing\"")); //$NON-NLS-1$ //$NON-NLS-2$
        StubGateway gateway = new StubGateway(manager, noopAccessManager());
        TestableConnectService service = new TestableConnectService(gateway);

        // Same display name but a different connection identity (e.g. a file-infobase folder named
        // like an existing server infobase). EDT enforces unique names, so manager.add() would
        // throw an opaque "already connected" and could disturb the project's association. The
        // service must instead fail early with NAME_COLLISION and mutate nothing.
        InfobaseReference reference = stubReferenceWithState(null, "shared-name", //$NON-NLS-1$
                "File=\"/tmp/new-target\""); //$NON-NLS-1$

        try {
            service.invokePersistReference(reference);
            fail("expected NAME_COLLISION"); //$NON-NLS-1$
        } catch (EdtToolException e) {
            assertEquals(EdtToolErrorCode.NAME_COLLISION, e.getCode());
        }
        assertTrue("manager.add must NOT be called on a name collision", //$NON-NLS-1$
                manager.addCalls.isEmpty());
    }

    /**
     * Backstop: in live EDT, {@code IInfobaseManager.findInfobasesByNames} /
     * {@code findInfobaseByName} sometimes miss entries (observed for a server infobase whose
     * display name equalled a file-infobase folder). The collision check must also sweep
     * {@code manager.getAll()} so the typed NAME_COLLISION still fires.
     */
    @Test
    public void persistReferenceThrowsNameCollisionWhenOnlyVisibleViaGetAll() {
        StubInfobaseManager manager = new StubInfobaseManager(true);
        // Targeted lookups are empty — the candidate is only reachable via getAll().
        manager.allSections = List.of(
                stubReferenceWithState(UUID.randomUUID(), "shared-name", "Srvr=\"term\";Ref=\"shared-name\"")); //$NON-NLS-1$ //$NON-NLS-2$
        StubGateway gateway = new StubGateway(manager, noopAccessManager());
        TestableConnectService service = new TestableConnectService(gateway);

        InfobaseReference reference = stubReferenceWithState(null, "shared-name", //$NON-NLS-1$
                "File=\"/tmp/new-target\""); //$NON-NLS-1$

        try {
            service.invokePersistReference(reference);
            fail("expected NAME_COLLISION via getAll() sweep"); //$NON-NLS-1$
        } catch (EdtToolException e) {
            assertEquals(EdtToolErrorCode.NAME_COLLISION, e.getCode());
        }
        assertTrue("manager.add must NOT be called when the collision is only visible via getAll()", //$NON-NLS-1$
                manager.addCalls.isEmpty());
    }

    @Test
    public void persistReferenceReusesUuidWhenIdentityMatchesConnectionString() {
        StubInfobaseManager manager = new StubInfobaseManager(true);
        UUID existingUuid = UUID.randomUUID();
        String connection = "File=\"/tmp/existing\""; //$NON-NLS-1$
        manager.findByNames = List.of(stubReferenceWithState(existingUuid, "shared-name", connection)); //$NON-NLS-1$
        StubGateway gateway = new StubGateway(manager, noopAccessManager());
        TestableConnectService service = new TestableConnectService(gateway);

        InfobaseReference reference = stubReferenceWithState(null, "shared-name", connection); //$NON-NLS-1$

        service.invokePersistReference(reference);

        assertTrue("matching infobase identity should reuse the existing entry", //$NON-NLS-1$
                manager.addCalls.isEmpty());
        assertEquals(existingUuid, reference.getUuid());
    }

    /**
     * Regression for GH issue #31: when {@code storeSettings} throws an exception whose
     * {@code getMessage()} returns {@code null} (historically an NPE from inside EDT), the
     * service must wrap it with a message that includes the exception class name so
     * operators no longer see the literal {@code ": null"} tail.
     */
    @Test
    public void storeAccessSettingsSurfacesClassNameWhenMessageNull() {
        StubInfobaseManager manager = new StubInfobaseManager(true);
        ThrowingAccessManager accessManager = new ThrowingAccessManager(
                new NullPointerException((String) null));
        StubGateway gateway = new StubGateway(manager, accessManager);
        TestableConnectService service = new TestableConnectService(gateway);

        InfobaseReference reference = stubReferenceWithState(UUID.randomUUID(), "ib-name"); //$NON-NLS-1$

        try {
            service.invokeStoreAccessSettings(reference, "Admin", "secret"); //$NON-NLS-1$ //$NON-NLS-2$
            fail("expected EdtToolException"); //$NON-NLS-1$
        } catch (EdtToolException e) {
            assertEquals(EdtToolErrorCode.EDT_SERVICE_UNAVAILABLE, e.getCode());
            String message = e.getMessage();
            assertNotNull(message);
            assertFalse("message must not tail with ': null' when cause has null getMessage()", //$NON-NLS-1$
                    message.endsWith(": null")); //$NON-NLS-1$
            assertTrue("message must include the exception class name, was: " + message, //$NON-NLS-1$
                    message.contains("NullPointerException")); //$NON-NLS-1$
        }
    }

    /**
     * Idempotent reconnect: when the project association already binds this exact path under a
     * name that differs from the one the caller derived, the service must adopt the existing
     * name+UUID onto the reference so the downstream setDefaultInfobase finds it inside the
     * association (otherwise EDT throws "Association does not contain ...").
     */
    @Test
    public void adoptExistingAssociationNameReusesExistingNameAndUuid() {
        StubInfobaseManager manager = new StubInfobaseManager(true);
        UUID boundUuid = UUID.randomUUID();
        String sharedConnection = "File=\"c:/db/Branches/BF_5946/BF-12496\""; //$NON-NLS-1$
        InfobaseReference bound = stubReferenceWithState(boundUuid, "File_am_BF-12496", sharedConnection); //$NON-NLS-1$
        StubGateway gateway = new StubGateway(manager, noopAccessManager())
                .withAssociationManager(associationManagerStub(associationStub(List.of(bound), bound)));
        TestableConnectService service = new TestableConnectService(gateway);

        // Reference being connected: same connection-string (path), but the caller-derived name
        // is the folder basename "BF-12496" — different from the bound entry's "File_am_BF-12496".
        InfobaseReference reference = stubReferenceWithState(null, "BF-12496", sharedConnection); //$NON-NLS-1$

        service.invokeAdoptExistingAssociationName(proxyProject(), reference, null);

        assertEquals("must adopt the existing association entry's name", //$NON-NLS-1$
                "File_am_BF-12496", reference.getName()); //$NON-NLS-1$
        assertEquals("must adopt the existing association entry's UUID", boundUuid, reference.getUuid()); //$NON-NLS-1$
    }

    /**
     * When the caller passed an explicit {@code infobase_name} that conflicts with the name the
     * project association already uses for this path, the service must surface a typed
     * PATH_ALREADY_ASSOCIATED_AS error with the existing name so the agent can retry correctly.
     */
    @Test
    public void adoptExistingAssociationNameThrowsWhenExplicitNameConflictsWithBoundName() {
        StubInfobaseManager manager = new StubInfobaseManager(true);
        String sharedConnection = "File=\"c:/db/Branches/BF_5946/BF-12496\""; //$NON-NLS-1$
        InfobaseReference bound = stubReferenceWithState(UUID.randomUUID(), "File_am_BF-12496", sharedConnection); //$NON-NLS-1$
        StubGateway gateway = new StubGateway(manager, noopAccessManager())
                .withAssociationManager(associationManagerStub(associationStub(List.of(bound), bound)));
        TestableConnectService service = new TestableConnectService(gateway);

        InfobaseReference reference = stubReferenceWithState(null, "wanted-name", sharedConnection); //$NON-NLS-1$

        try {
            service.invokeAdoptExistingAssociationName(proxyProject(), reference, "wanted-name"); //$NON-NLS-1$
            fail("expected PATH_ALREADY_ASSOCIATED_AS"); //$NON-NLS-1$
        } catch (EdtToolException e) {
            assertEquals(EdtToolErrorCode.PATH_ALREADY_ASSOCIATED_AS, e.getCode());
            assertTrue("message must include the existing name so the caller can retry, was: " //$NON-NLS-1$
                    + e.getMessage(), e.getMessage().contains("File_am_BF-12496")); //$NON-NLS-1$
        }
    }

    /** Reference stub that actually persists {@code getUuid}/{@code setUuid}/{@code getName}/{@code setName}. */
    private static InfobaseReference stubReferenceWithState(UUID initialUuid, String initialName) {
        return stubReferenceWithState(initialUuid, initialName, null);
    }

    /** Reference stub that also exposes a stable connection string for identity comparison. */
    private static InfobaseReference stubReferenceWithState(UUID initialUuid, String initialName,
            String connectionIdentity) {
        AtomicReference<UUID> uuidSlot = new AtomicReference<>(initialUuid);
        AtomicReference<String> nameSlot = new AtomicReference<>(initialName);
        Object connectionString = connectionStringStub(connectionIdentity);
        return (InfobaseReference) Proxy.newProxyInstance(
                InfobaseReference.class.getClassLoader(),
                new Class<?>[] { InfobaseReference.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUuid" -> uuidSlot.get(); //$NON-NLS-1$
                    case "setUuid" -> { uuidSlot.set((UUID) args[0]); yield null; } //$NON-NLS-1$
                    case "getName" -> nameSlot.get(); //$NON-NLS-1$
                    case "setName" -> { nameSlot.set((String) args[0]); yield null; } //$NON-NLS-1$
                    case "getConnectionString" -> connectionString; //$NON-NLS-1$
                    case "toString" -> "StubReference@" + System.identityHashCode(proxy); //$NON-NLS-1$ //$NON-NLS-2$
                    case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy)); //$NON-NLS-1$
                    case "equals" -> Boolean.valueOf(proxy == args[0]); //$NON-NLS-1$
                    default -> defaultReturnFor(method.getReturnType());
                });
    }

    private static Object connectionStringStub(String value) {
        if (value == null) {
            return null;
        }
        try {
            Method getter = InfobaseReference.class.getMethod("getConnectionString"); //$NON-NLS-1$
            Class<?> returnType = getter.getReturnType();
            if (!returnType.isInterface()) {
                fail("InfobaseReference#getConnectionString() must return an interface in this test"); //$NON-NLS-1$
            }
            return Proxy.newProxyInstance(
                    returnType.getClassLoader(),
                    new Class<?>[] { returnType },
                    (proxy, method, args) -> switch (method.getName()) {
                        case "asConnectionString" -> value; //$NON-NLS-1$
                        case "toString" -> value; //$NON-NLS-1$
                        case "hashCode" -> Integer.valueOf(value.hashCode()); //$NON-NLS-1$
                        case "equals" -> Boolean.valueOf(proxy == args[0]); //$NON-NLS-1$
                        default -> defaultReturnFor(method.getReturnType());
                    });
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to create connection-string stub", e); //$NON-NLS-1$
        }
    }

    private static Object defaultReturnFor(Class<?> returnType) {
        if (returnType == boolean.class) return Boolean.FALSE;
        if (returnType == int.class) return Integer.valueOf(0);
        if (returnType == long.class) return Long.valueOf(0L);
        if (returnType == double.class) return Double.valueOf(0);
        if (returnType == float.class) return Float.valueOf(0);
        if (returnType == short.class) return Short.valueOf((short) 0);
        if (returnType == byte.class) return Byte.valueOf((byte) 0);
        if (returnType == char.class) return Character.valueOf('\0');
        return null;
    }

    private static IInfobaseAccessManager noopAccessManager() {
        return (IInfobaseAccessManager) Proxy.newProxyInstance(
                IInfobaseAccessManager.class.getClassLoader(),
                new Class<?>[] { IInfobaseAccessManager.class },
                (proxy, method, args) -> defaultReturnFor(method.getReturnType()));
    }

    private static com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociation associationStub(
            List<InfobaseReference> bound, InfobaseReference defaultIb) {
        Class<?> iface = com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociation.class;
        return (com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociation) Proxy.newProxyInstance(
                iface.getClassLoader(),
                new Class<?>[] { iface },
                (proxy, method, args) -> switch (method.getName()) {
                    case "getInfobases" -> bound; //$NON-NLS-1$
                    case "getDefaultInfobase" -> defaultIb; //$NON-NLS-1$
                    default -> defaultReturnFor(method.getReturnType());
                });
    }

    private static com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationManager
            associationManagerStub(
                    com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociation assoc) {
        Class<?> iface = com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationManager.class;
        return (com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationManager) Proxy.newProxyInstance(
                iface.getClassLoader(),
                new Class<?>[] { iface },
                (proxy, method, args) -> {
                    if ("getAssociation".equals(method.getName())) { //$NON-NLS-1$
                        return Optional.ofNullable(assoc);
                    }
                    return defaultReturnFor(method.getReturnType());
                });
    }

    private static org.eclipse.core.resources.IProject proxyProject() {
        Class<?> iface = org.eclipse.core.resources.IProject.class;
        return (org.eclipse.core.resources.IProject) Proxy.newProxyInstance(
                iface.getClassLoader(),
                new Class<?>[] { iface },
                (proxy, method, args) -> defaultReturnFor(method.getReturnType()));
    }

    /** Stub gateway that returns the pre-configured manager / access-manager. */
    private static final class StubGateway extends EdtRuntimeGateway {
        private final IInfobaseManager manager;
        private final IInfobaseAccessManager accessManager;
        private com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationManager associationManager;

        StubGateway(IInfobaseManager manager, IInfobaseAccessManager accessManager) {
            this.manager = manager;
            this.accessManager = accessManager;
        }

        StubGateway withAssociationManager(
                com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationManager am) {
            this.associationManager = am;
            return this;
        }

        @Override
        public IInfobaseManager getInfobaseManager() {
            return manager;
        }

        @Override
        public IInfobaseAccessManager getInfobaseAccessManager() {
            return accessManager;
        }

        @Override
        public com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationManager
                getInfobaseAssociationManager() {
            return associationManager;
        }
    }

    /** Subclass exposing protected service methods for unit testing. */
    private static final class TestableConnectService extends EdtInfobaseConnectService {
        TestableConnectService(EdtRuntimeGateway gateway) {
            super(gateway);
        }

        void invokePersistReference(InfobaseReference reference) {
            persistReference(reference);
        }

        void invokeStoreAccessSettings(InfobaseReference reference, String login, String password) {
            storeAccessSettings(reference, login, password);
        }

        void invokeAdoptExistingAssociationName(org.eclipse.core.resources.IProject project,
                InfobaseReference reference, String explicitInfobaseName) {
            adoptExistingAssociationName(project, reference, explicitInfobaseName);
        }
    }

    /** Minimal {@link IInfobaseManager} stub tracking {@code add} calls. */
    private static final class StubInfobaseManager implements IInfobaseManager {
        private final boolean persistenceSupported;
        final java.util.List<Object> addCalls = new java.util.ArrayList<>();
        Optional<InfobaseReference> findByUuid = Optional.empty();
        Optional<InfobaseReference> findByName = Optional.empty();
        java.util.List<InfobaseReference> findByNames = List.of();
        java.util.List<com._1c.g5.v8.dt.platform.services.model.Section> allSections = List.of();

        StubInfobaseManager(boolean persistenceSupported) {
            this.persistenceSupported = persistenceSupported;
        }

        @Override
        public boolean isPersistenceSupported() { return persistenceSupported; }

        @Override
        public java.util.List<InfobaseReference> getRecent() { return List.of(); }

        @Override
        public java.util.List<com._1c.g5.v8.dt.platform.services.model.Section> getAll() { return allSections; }

        @Override
        public org.eclipse.core.runtime.IStatus getLoadStatus() { return null; }

        @Override
        public org.eclipse.emf.ecore.resource.Resource getInfobasesResource() { return null; }

        @Override
        public void save(java.util.List<com._1c.g5.v8.dt.platform.services.model.Section> sections) { }

        @Override
        public void add(java.util.List<com._1c.g5.v8.dt.platform.services.model.Section> added,
                java.util.List<com._1c.g5.v8.dt.platform.services.model.Section> toRemove,
                com._1c.g5.v8.dt.platform.services.model.Section parent) {
            addCalls.add(added);
        }

        @Override
        public void add(com._1c.g5.v8.dt.platform.services.model.Section section, String parentName) {
            addCalls.add(section);
        }

        @Override
        public void delete(com._1c.g5.v8.dt.platform.services.model.Section section) { }

        @Override
        public void update(com._1c.g5.v8.dt.platform.services.model.Section section) { }

        @Override
        public Optional<InfobaseReference> findInfobaseByUuid(UUID uuid) { return findByUuid; }

        @Override
        public Optional<InfobaseReference> findInfobaseByName(String name) { return findByName; }

        @Override
        public java.util.List<InfobaseReference> findInfobasesByNames(
                java.util.Collection<String> names) {
            return findByNames;
        }

        @Override
        public boolean isSortByName() { return false; }

        @Override
        public void setSortByName(boolean value) { }

        @Override
        public boolean isShowRecent() { return false; }

        @Override
        public void setShowRecent(boolean value) { }

        @Override
        public int getRecentInfobasesCount() { return 0; }

        @Override
        public void setRecentInfobasesCount(int value) { }

        @Override
        public void markInfobaseAsRecent(InfobaseReference reference) { }

        @Override
        public java.util.concurrent.locks.Lock getLock(InfobaseReference reference) { return null; }

        @Override
        public java.util.concurrent.locks.Lock getInternalInfobaseLock() { return null; }

        @Override
        public String generateInfobaseName() { return "stub"; } //$NON-NLS-1$

        @Override
        public String generateGroupName() { return "group"; } //$NON-NLS-1$

        @Override
        public Optional<java.nio.file.Path> generateDefaultInfobaseLocation() { return Optional.empty(); }

        @Override
        public void addInfobaseChangeListener(
                com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseChangeListener listener) { }

        @Override
        public void removeInfobaseChangeListener(
                com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseChangeListener listener) { }

        @Override
        public void reload(org.eclipse.core.runtime.IProgressMonitor monitor) { }
    }

    /** Access manager whose {@code updateSettings} always throws the supplied exception. */
    private static final class ThrowingAccessManager implements IInfobaseAccessManager {
        private final RuntimeException toThrow;

        ThrowingAccessManager(RuntimeException toThrow) {
            this.toThrow = toThrow;
        }

        @Override
        public com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAccessSettings createDefaultSettings(
                InfobaseReference reference) { return null; }

        @Override
        public com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAccessSettings resolveSettings(
                InfobaseReference reference) { return null; }

        @Override
        public void updateSettings(InfobaseReference reference,
                com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAccessSettings settings) {
            // EDT 2025.2 routes access-settings persistence through updateSettings (formerly
            // storeSettings); this is the throw path exercised by the GH#31 regression.
            throw toThrow;
        }

        @Override
        public void addInfobaseAccessSettingsChangeListener(
                com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAccessSettingsChangeListener listener) {
        }

        @Override
        public void removeInfobaseAccessSettingsChangeListener(
                com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAccessSettingsChangeListener listener) {
        }

        @Override
        public java.util.Optional<com._1c.g5.v8.dt.platform.services.core.runtimes.environments.IResolvableRuntimeInstallation>
                loadSelectedInstallation(org.eclipse.core.resources.IProject project, InfobaseReference reference) {
            return java.util.Optional.empty();
        }

        @Override
        public void updateSelectedInstallation(org.eclipse.core.resources.IProject project,
                InfobaseReference reference,
                com._1c.g5.v8.dt.platform.services.core.runtimes.environments.IResolvableRuntimeInstallation installation) {
        }
    }

    /** Stub service that records the incoming request and returns a configurable result. */
    private static class RecordingConnectService extends EdtInfobaseConnectService {
        ConnectRequest lastRequest;
        ResponseBuilder responseBuilder = req -> new ConnectResult(req.kind(), req.databasePath(),
                "stub-ib", req.login() == null || req.login().isBlank() ? null : req.login(), //$NON-NLS-1$
                req.serverPort(), req.setPrimary());

        @Override
        public ConnectResult connect(ConnectRequest request) {
            // Reproduce the real service's validation so parameter-error tests still route
            // through the tool's EdtToolException handler.
            if (request == null) {
                throw new EdtToolException(EdtToolErrorCode.INVALID_ARGUMENT, "request is required"); //$NON-NLS-1$
            }
            if (request.projectName() == null || request.projectName().isBlank()) {
                throw new EdtToolException(EdtToolErrorCode.INVALID_ARGUMENT, "project_name is required"); //$NON-NLS-1$
            }
            if (request.kind() == null) {
                throw new EdtToolException(EdtToolErrorCode.INVALID_ARGUMENT,
                        "kind is required and must be 'file', 'standalone' or 'server'"); //$NON-NLS-1$
            }
            if (request.kind() == ConnectionKind.SERVER) {
                if (request.serverAddress() == null || request.serverAddress().isBlank()) {
                    throw new EdtToolException(EdtToolErrorCode.INVALID_ARGUMENT, "srvr is required for kind=server"); //$NON-NLS-1$
                }
                if (request.serverRef() == null || request.serverRef().isBlank()) {
                    throw new EdtToolException(EdtToolErrorCode.INVALID_ARGUMENT, "ref is required for kind=server"); //$NON-NLS-1$
                }
            } else if (request.databasePath() == null || request.databasePath().isBlank()) {
                throw new EdtToolException(EdtToolErrorCode.INVALID_ARGUMENT, "database_path is required"); //$NON-NLS-1$
            }
            this.lastRequest = request;
            return responseBuilder.build(request);
        }
    }

    @FunctionalInterface
    private interface ResponseBuilder {
        ConnectResult build(ConnectRequest request);
    }
}
