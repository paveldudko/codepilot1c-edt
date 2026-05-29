package com.codepilot1c.core.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import com.codepilot1c.core.tools.workspace.BackgroundJobRegistry;
import com.codepilot1c.core.tools.workspace.BackgroundJobRegistry.JobState;
import com.codepilot1c.core.tools.workspace.BackgroundJobRegistry.JobStatus;
import com.codepilot1c.core.tools.workspace.EdtUpdateInfobaseTool;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com.codepilot1c.core.edt.runtime.EdtProjectResolver;
import com.codepilot1c.core.edt.runtime.EdtRuntimeService;
import com.codepilot1c.core.edt.runtime.EdtToolErrorCode;
import com.codepilot1c.core.edt.runtime.EdtToolException;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

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
        // Plain dry-run (no async) must not include the async_ignored flag.
        assertFalse(json.has("async_ignored")); //$NON-NLS-1$
    }

    @Test
    public void dryRunWithAsync_returnsSyncResultWithAsyncIgnored() throws Exception {
        File workspaceRoot = Files.createTempDirectory("edt-update-tool-async-dry").toFile(); //$NON-NLS-1$
        StubRuntimeService runtimeService = new StubRuntimeService();
        EdtUpdateInfobaseTool tool = new TestEdtUpdateInfobaseTool(
                new StubProjectResolver(),
                runtimeService,
                workspaceRoot);

        ToolResult result = tool.execute(Map.of(
                "project_name", "Demo", //$NON-NLS-1$ //$NON-NLS-2$
                "dry_run", Boolean.TRUE, //$NON-NLS-1$
                "async", Boolean.TRUE //$NON-NLS-1$
        )).join();

        assertTrue(result.isSuccess());
        JsonObject json = JsonParser.parseString(result.getContent()).getAsJsonObject();
        assertTrue(json.get("dry_run").getAsBoolean()); //$NON-NLS-1$
        assertFalse(json.get("updated").getAsBoolean()); //$NON-NLS-1$
        assertFalse(runtimeService.updateCalled);
        assertTrue(json.has("async_ignored")); //$NON-NLS-1$
        assertTrue(json.get("async_ignored").getAsBoolean()); //$NON-NLS-1$
        assertTrue(json.has("async_ignored_reason")); //$NON-NLS-1$
        // No job id should be issued for dry-run; the response is a plain sync payload.
        assertFalse(json.has("job_id")); //$NON-NLS-1$
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

    @Test
    public void async_true_returnsJobIdImmediately() throws Exception {
        File workspaceRoot = Files.createTempDirectory("edt-update-tool-async").toFile(); //$NON-NLS-1$
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        BlockingRuntimeService runtimeService = new BlockingRuntimeService(entered, release);
        EdtUpdateInfobaseTool tool = new TestEdtUpdateInfobaseTool(
                new StubProjectResolver(),
                runtimeService,
                workspaceRoot);

        long t0 = System.currentTimeMillis();
        ToolResult result = tool.execute(Map.of(
                "project_name", "Demo", //$NON-NLS-1$ //$NON-NLS-2$
                "async", Boolean.TRUE //$NON-NLS-1$
        )).join();
        long elapsed = System.currentTimeMillis() - t0;

        assertTrue(result.isSuccess());
        JsonObject json = JsonParser.parseString(result.getContent()).getAsJsonObject();
        assertTrue(json.get("async").getAsBoolean()); //$NON-NLS-1$
        assertEquals("RUNNING", json.get("state").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        String jobId = json.get("job_id").getAsString(); //$NON-NLS-1$
        assertNotNull(jobId);
        assertFalse(jobId.isEmpty());
        // Tool must not have waited for the blocking update to complete.
        assertTrue("Async tool returned in " + elapsed + " ms", elapsed < 2000); //$NON-NLS-1$ //$NON-NLS-2$

        // Status should show the job is running while the update is blocked.
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        BackgroundJobRegistry registry = BackgroundJobRegistry.getInstance();
        JobStatus running = registry.getStatus(jobId).orElseThrow();
        assertTrue(running.getState() == JobState.PENDING || running.getState() == JobState.RUNNING);

        // Release the background work and poll for terminal state.
        release.countDown();
        JobStatus finished = pollForTerminal(registry, jobId);
        assertEquals(JobState.DONE, finished.getState());
        assertTrue(runtimeService.updateCalled);
        assertNotNull(finished.getResult());
        JsonObject resultJson = JsonParser.parseString(finished.getResult()).getAsJsonObject();
        assertTrue(resultJson.get("updated").getAsBoolean()); //$NON-NLS-1$
    }

    @Test
    public void async_stringTrue_returnsJobIdImmediately() throws Exception {
        // Regression: "async":"true" (JSON string) must take the async path,
        // not be silently coerced to false and block the MCP transport.
        File workspaceRoot = Files.createTempDirectory("edt-update-tool-async-str").toFile(); //$NON-NLS-1$
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        BlockingRuntimeService runtimeService = new BlockingRuntimeService(entered, release);
        EdtUpdateInfobaseTool tool = new TestEdtUpdateInfobaseTool(
                new StubProjectResolver(),
                runtimeService,
                workspaceRoot);

        long t0 = System.currentTimeMillis();
        ToolResult result = tool.execute(Map.of(
                "project_name", "Demo", //$NON-NLS-1$ //$NON-NLS-2$
                "async", "true" //$NON-NLS-1$ //$NON-NLS-2$
        )).join();
        long elapsed = System.currentTimeMillis() - t0;

        assertTrue(result.isSuccess());
        JsonObject json = JsonParser.parseString(result.getContent()).getAsJsonObject();
        assertTrue(json.get("async").getAsBoolean()); //$NON-NLS-1$
        assertEquals("RUNNING", json.get("state").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        String jobId = json.get("job_id").getAsString(); //$NON-NLS-1$
        assertNotNull(jobId);
        assertTrue("Async tool returned in " + elapsed + " ms", elapsed < 2000); //$NON-NLS-1$ //$NON-NLS-2$

        // Drain the blocked background job so it doesn't leak into other tests.
        release.countDown();
        entered.await(5, TimeUnit.SECONDS);
        pollForTerminal(BackgroundJobRegistry.getInstance(), jobId);
    }

    @Test
    public void runsUpdateInsideNamedEclipseJobWithWorkbenchMonitor() throws Exception {
        // Feedback 2026-05-29-update-infobase-progress-visibility: the (non-dry) update must run
        // inside an Eclipse Job named "Updating infobase: <project>…" so the workbench can attach a
        // progress monitor and surface EDT's sub-task progress. Headless there is no progress UI,
        // so we install a marker ProgressProvider (standing in for the workbench's) and assert the
        // monitor the update receives is the one the provider supplied for our Job — proving the
        // GUI hook fires when a workbench is present, regardless of this harness having none.
        File workspaceRoot = Files.createTempDirectory("edt-update-tool-jobprogress").toFile(); //$NON-NLS-1$
        ProbingRuntimeService runtimeService = new ProbingRuntimeService();
        EdtUpdateInfobaseTool tool = new TestEdtUpdateInfobaseTool(
                new StubProjectResolver(),
                runtimeService,
                workspaceRoot);

        org.eclipse.core.runtime.jobs.IJobManager jobManager =
                org.eclipse.core.runtime.jobs.Job.getJobManager();
        jobManager.setProgressProvider(new org.eclipse.core.runtime.jobs.ProgressProvider() {
            @Override
            public org.eclipse.core.runtime.IProgressMonitor createMonitor(
                    org.eclipse.core.runtime.jobs.Job job) {
                return new MarkerMonitor();
            }
        });
        try {
            ToolResult result = tool.execute(Map.of("project_name", "Demo")).join(); //$NON-NLS-1$ //$NON-NLS-2$

            assertTrue(result.isSuccess());
            assertTrue(runtimeService.updateCalled);
            assertEquals("REGRESSION: the update must run with the workbench-provided monitor so " //$NON-NLS-1$
                    + "progress surfaces in the GUI — not a bare NullProgressMonitor", //$NON-NLS-1$
                    MarkerMonitor.class.getName(), runtimeService.monitorClass);
            assertEquals("update must run inside the named progress Job", //$NON-NLS-1$
                    "Updating infobase: Demo…", runtimeService.currentJobName); //$NON-NLS-1$
        } finally {
            jobManager.setProgressProvider(null);
        }
    }

    /** Sentinel monitor identifying the Job-framework progress provider's contribution. */
    private static final class MarkerMonitor extends org.eclipse.core.runtime.NullProgressMonitor {
    }

    private static class ProbingRuntimeService extends EdtRuntimeService {
        volatile boolean updateCalled;
        volatile String monitorClass;
        volatile String currentJobName;

        @Override
        public boolean updateInfobase(String projectName, boolean keepConnected,
                org.eclipse.core.runtime.IProgressMonitor monitor) {
            updateCalled = true;
            // The Job framework wraps the provider's monitor; unwrap to the delegate so the
            // assertion sees MarkerMonitor rather than the framework's wrapper.
            org.eclipse.core.runtime.IProgressMonitor effective = unwrap(monitor);
            monitorClass = effective == null ? null : effective.getClass().getName();
            org.eclipse.core.runtime.jobs.Job current = org.eclipse.core.runtime.jobs.Job.getJobManager().currentJob();
            currentJobName = current == null ? null : current.getName();
            return true;
        }

        private static org.eclipse.core.runtime.IProgressMonitor unwrap(
                org.eclipse.core.runtime.IProgressMonitor monitor) {
            if (monitor instanceof org.eclipse.core.runtime.ProgressMonitorWrapper wrapper) {
                return unwrap(wrapper.getWrappedProgressMonitor());
            }
            return monitor;
        }

        @Override
        public UpdateInfobaseStatus updateInfobaseWithStatus(String projectName, boolean keepConnected,
                org.eclipse.core.runtime.IProgressMonitor monitor) {
            boolean updated = updateInfobase(projectName, keepConnected, monitor);
            return new UpdateInfobaseStatus(updated, false);
        }
    }

    private static JobStatus pollForTerminal(BackgroundJobRegistry registry, String jobId)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            JobStatus status = registry.getStatus(jobId).orElse(null);
            if (status != null
                    && (status.getState() == JobState.DONE || status.getState() == JobState.FAILED)) {
                return status;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Job " + jobId + " never reached terminal state"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static class BlockingRuntimeService extends EdtRuntimeService {
        private final CountDownLatch entered;
        private final CountDownLatch release;
        volatile boolean updateCalled;

        BlockingRuntimeService(CountDownLatch entered, CountDownLatch release) {
            this.entered = entered;
            this.release = release;
        }

        @Override
        public boolean updateInfobase(String projectName, boolean keepConnected,
                org.eclipse.core.runtime.IProgressMonitor monitor) {
            updateCalled = true;
            entered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("release latch timed out"); //$NON-NLS-1$
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            return true;
        }

        @Override
        public UpdateInfobaseStatus updateInfobaseWithStatus(String projectName, boolean keepConnected,
                org.eclipse.core.runtime.IProgressMonitor monitor) {
            boolean updated = updateInfobase(projectName, keepConnected, monitor);
            return new UpdateInfobaseStatus(updated, false);
        }
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

        @Override
        public UpdateInfobaseStatus updateInfobaseWithStatus(String projectName, boolean keepConnected,
                org.eclipse.core.runtime.IProgressMonitor monitor) {
            boolean updated = updateInfobase(projectName, keepConnected, monitor);
            return new UpdateInfobaseStatus(updated, false);
        }
    }
}
