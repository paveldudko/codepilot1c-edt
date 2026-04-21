package com.codepilot1c.core.tools;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.Map;

import org.junit.Ignore;
import org.junit.Test;

import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com.codepilot1c.core.edt.runtime.EdtProjectResolver;
import com.codepilot1c.core.edt.runtime.EdtRuntimeService;
import com.codepilot1c.core.edt.runtime.EdtToolErrorCode;
import com.codepilot1c.core.edt.runtime.EdtToolException;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

// Depends on InfobaseReference / IProgressMonitor from the EDT target
// platform, which are not on the Maven classpath of com.codepilot1c.core.tests
// (a plain Maven jar, not a PDE test bundle). Run this class from Eclipse
// (Run as > JUnit Plug-in Test); CI Maven skips it to keep the green path
// simple.
@Ignore("requires EDT target platform — run as Eclipse JUnit Plug-in Test") //$NON-NLS-1$
public class EdtUpdateInfobaseToolTest {

    @Test
    public void returnsDryRunPayloadWithoutCallingUpdate() throws Exception {
        File workspaceRoot = Files.createTempDirectory("edt-update-tool").toFile(); //$NON-NLS-1$
        StubRuntimeService runtimeService = new StubRuntimeService();
        EdtUpdateInfobaseTool tool = new TestEdtUpdateInfobaseTool(
                new StubProjectResolver(),
                runtimeService,
                workspaceRoot);

        ToolResult result = tool.execute(Map.of(
                "project_name", "Demo", //$NON-NLS-1$ //$NON-NLS-2$
                "dry_run", Boolean.TRUE //$NON-NLS-1$
        )).join();

        assertTrue(result.isSuccess());
        JsonObject json = JsonParser.parseString(result.getContent()).getAsJsonObject();
        assertTrue(json.get("dry_run").getAsBoolean()); //$NON-NLS-1$
        assertFalse(json.get("updated").getAsBoolean()); //$NON-NLS-1$
        assertFalse(runtimeService.updateCalled);
    }

    @Test
    public void returnsStructuredErrorPayload() throws Exception {
        File workspaceRoot = Files.createTempDirectory("edt-update-tool-error").toFile(); //$NON-NLS-1$
        EdtUpdateInfobaseTool tool = new TestEdtUpdateInfobaseTool(
                new StubProjectResolver() {
                    @Override
                    public InfobaseReference resolveInfobase(String projectName, File ignoredWorkspaceRoot) {
                        throw new EdtToolException(EdtToolErrorCode.PROJECT_NOT_FOUND,
                                "EDT project not found: " + projectName); //$NON-NLS-1$
                    }
                },
                new StubRuntimeService(),
                workspaceRoot);

        ToolResult result = tool.execute(Map.of("project_name", "Missing")).join(); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse(result.isSuccess());
        JsonObject json = JsonParser.parseString(result.getErrorMessage()).getAsJsonObject();
        assertTrue("PROJECT_NOT_FOUND".equals(json.get("error_code").getAsString())); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static class TestEdtUpdateInfobaseTool extends EdtUpdateInfobaseTool {
        private final File workspaceRoot;

        TestEdtUpdateInfobaseTool(EdtProjectResolver projectResolver, EdtRuntimeService runtimeService,
                File workspaceRoot) {
            super(projectResolver, runtimeService);
            this.workspaceRoot = workspaceRoot;
        }

        @Override
        protected File getWorkspaceRoot() {
            return workspaceRoot;
        }
    }

    private static class StubProjectResolver extends EdtProjectResolver {
        @Override
        public InfobaseReference resolveInfobase(String projectName, File workspaceRoot) {
            return null;
        }
    }

    private static class StubRuntimeService extends EdtRuntimeService {
        private boolean updateCalled;

        @Override
        public boolean updateInfobase(String projectName, boolean keepConnected,
                org.eclipse.core.runtime.IProgressMonitor monitor) {
            updateCalled = true;
            return true;
        }
    }
}
