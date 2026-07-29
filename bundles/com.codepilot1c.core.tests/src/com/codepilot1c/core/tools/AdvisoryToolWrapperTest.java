package com.codepilot1c.core.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.junit.Test;

/**
 * Behavioural tests for the unknown-parameter advisory on a tool that does NOT extend
 * {@link AbstractTool}.
 *
 * <p>The advisory used to be reachable only by inheritance, so the two tools implementing
 * {@link ITool} directly — {@code get_diagnostics} and {@code get_diagnostics_details} — never got
 * it. Live 2026-07-29 on build {@code 0.1.7.20260729-0811}: {@code get_diagnostics} was called with
 * {@code project=TestConfiguration, scope=project}; the accepted spelling is {@code project_name},
 * so the key was dropped, {@code resolveDefaultProjectName()} chose another project, and the caller
 * received 6402 errors belonging to {@code /Accounting management} with no note that the project it
 * asked about was not the project answered. Every other tool in the same session did emit the note.
 * These tests assert the wrapper closes that hole through the registration choke point, and — just
 * as important — that it delegates the {@link ITool} defaults instead of answering them itself.</p>
 */
public class AdvisoryToolWrapperTest {

    @Test
    public void aDroppedKeyIsNamedForAToolThatDoesNotExtendAbstractTool() {
        ITool wrapped = AdvisoryToolWrapper.wrapIfNeeded(new PlainScopeTool());

        ToolResult result = wrapped.execute(Map.of(
                "project", "TestConfiguration", //$NON-NLS-1$ //$NON-NLS-2$
                "scope", "project")).join(); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("the call itself must still succeed", result.isSuccess()); //$NON-NLS-1$
        assertTrue(result.getContent(), result.getContent().startsWith("{\"ok\":true}")); //$NON-NLS-1$
        assertTrue(result.getContent(), result.getContent().contains("ignored an unknown parameter")); //$NON-NLS-1$
        assertTrue(result.getContent(), result.getContent().contains("'project'")); //$NON-NLS-1$
        assertTrue("the accepted spelling must be offered", //$NON-NLS-1$
                result.getContent().contains("project_name")); //$NON-NLS-1$
    }

    @Test
    public void anAcceptedParameterSetLeavesTheResultByteIdentical() {
        // The regression guard for every existing caller of the two diagnostics tools.
        ITool wrapped = AdvisoryToolWrapper.wrapIfNeeded(new PlainScopeTool());

        ToolResult result = wrapped.execute(Map.of(
                "project_name", "TestConfiguration", //$NON-NLS-1$ //$NON-NLS-2$
                "scope", "project")).join(); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("{\"ok\":true}", result.getContent()); //$NON-NLS-1$
    }

    @Test
    public void aFailingCallKeepsFailingAndGetsTheAdvisoryToo() {
        ITool wrapped = AdvisoryToolWrapper.wrapIfNeeded(new PlainFailingTool());

        ToolResult result = wrapped.execute(Map.of("nonsense", "1")).join(); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage(), result.getErrorMessage().startsWith("boom")); //$NON-NLS-1$
        assertTrue(result.getErrorMessage(), result.getErrorMessage().contains("'nonsense'")); //$NON-NLS-1$
    }

    @Test
    public void aToolThatThrowsInsteadOfFailingItsFutureStillKeepsTheAdvisory() {
        ITool wrapped = AdvisoryToolWrapper.wrapIfNeeded(new PlainThrowingTool());

        ToolResult result = wrapped.execute(Map.of("nonsense", "1")).join(); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage(), result.getErrorMessage().contains("'nonsense'")); //$NON-NLS-1$
    }

    @Test
    public void theInterfaceDefaultsAreDelegatedAndNotAnsweredByTheWrapper() {
        // A wrapper that answered the ITool defaults would silently turn a destructive, mutating,
        // token-requiring tool into a harmless one — the confirmation prompt would disappear.
        ITool wrapped = AdvisoryToolWrapper.wrapIfNeeded(new PlainOpinionatedTool());

        assertTrue("destructive must come from the delegate", wrapped.isDestructive()); //$NON-NLS-1$
        assertTrue("confirmation must come from the delegate", wrapped.requiresConfirmation()); //$NON-NLS-1$
        assertTrue("mutating must come from the delegate", wrapped.isMutating()); //$NON-NLS-1$
        assertTrue("token requirement must come from the delegate", //$NON-NLS-1$
                wrapped.requiresValidationToken());
        assertEquals("diagnostics", wrapped.getCategory()); //$NON-NLS-1$
        assertEquals("read", wrapped.getSurfaceCategory()); //$NON-NLS-1$
        assertEquals(Set.of("edt"), wrapped.getTags()); //$NON-NLS-1$
        assertEquals("plain_opinionated", wrapped.getName()); //$NON-NLS-1$
        assertEquals("probe", wrapped.getDescription()); //$NON-NLS-1$
    }

    @Test
    public void anAbstractToolIsNotWrappedBecauseItAlreadyCarriesTheAdvisory() {
        // Double coverage would append the same note twice.
        AbstractTool tool = new InheritedScopeTool();
        assertSame(tool, AdvisoryToolWrapper.wrapIfNeeded(tool));
    }

    @Test
    public void wrappingIsIdempotent() {
        ITool once = AdvisoryToolWrapper.wrapIfNeeded(new PlainScopeTool());
        assertSame(once, AdvisoryToolWrapper.wrapIfNeeded(once));

        ToolResult result = AdvisoryToolWrapper.wrapIfNeeded(once)
                .execute(Map.of("project", "X")).join(); //$NON-NLS-1$ //$NON-NLS-2$
        int first = result.getContent().indexOf("ignored an unknown parameter"); //$NON-NLS-1$
        int last = result.getContent().lastIndexOf("ignored an unknown parameter"); //$NON-NLS-1$
        assertEquals("the note must appear exactly once", first, last); //$NON-NLS-1$
    }

    @Test
    public void anExemptToolStaysSilentEvenWhenWrapped() {
        // Dispatch-only delegates receive keys the dispatcher injected, so a note would blame the
        // caller for the dispatcher's routing.
        ITool wrapped = AdvisoryToolWrapper.wrapIfNeeded(new PlainExemptTool());

        ToolResult result = wrapped.execute(Map.of("project", "X")).join(); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("{\"ok\":true}", result.getContent()); //$NON-NLS-1$
    }

    /** Mirrors {@code get_diagnostics}: implements {@link ITool} directly, accepts {@code project_name}. */
    private static class PlainScopeTool implements ITool {
        @Override
        public String getName() {
            return "plain_scope"; //$NON-NLS-1$
        }

        @Override
        public String getDescription() {
            return "probe"; //$NON-NLS-1$
        }

        @Override
        public String getParameterSchema() {
            return "{\"type\":\"object\",\"properties\":{\"scope\":{\"type\":\"string\"}," //$NON-NLS-1$
                    + "\"project_name\":{\"type\":\"string\"}}}"; //$NON-NLS-1$
        }

        @Override
        public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
            return CompletableFuture.completedFuture(ToolResult.success("{\"ok\":true}")); //$NON-NLS-1$
        }
    }

    private static final class PlainFailingTool extends PlainScopeTool {
        @Override
        public String getName() {
            return "plain_failing"; //$NON-NLS-1$
        }

        @Override
        public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
            return CompletableFuture.completedFuture(ToolResult.failure("boom")); //$NON-NLS-1$
        }
    }

    private static final class PlainThrowingTool extends PlainScopeTool {
        @Override
        public String getName() {
            return "plain_throwing"; //$NON-NLS-1$
        }

        @Override
        public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
            throw new IllegalStateException("boom"); //$NON-NLS-1$
        }
    }

    private static final class PlainExemptTool extends PlainScopeTool {
        @Override
        public String getName() {
            // On the dispatch-only exempt list.
            return "edt_metadata_smoke"; //$NON-NLS-1$
        }
    }

    private static final class PlainOpinionatedTool extends PlainScopeTool {
        @Override
        public String getName() {
            return "plain_opinionated"; //$NON-NLS-1$
        }

        @Override
        public boolean isDestructive() {
            return true;
        }

        @Override
        public boolean requiresConfirmation() {
            return true;
        }

        @Override
        public boolean isMutating() {
            return true;
        }

        @Override
        public boolean requiresValidationToken() {
            return true;
        }

        @Override
        public String getCategory() {
            return "diagnostics"; //$NON-NLS-1$
        }

        @Override
        public String getSurfaceCategory() {
            return "read"; //$NON-NLS-1$
        }

        @Override
        public Set<String> getTags() {
            return Set.of("edt"); //$NON-NLS-1$
        }
    }

    @ToolMeta(name = "inherited_scope", category = "test")
    private static final class InheritedScopeTool extends AbstractTool {
        @Override
        public String getDescription() {
            return "probe"; //$NON-NLS-1$
        }

        @Override
        public String getParameterSchema() {
            return "{\"type\":\"object\",\"properties\":{\"scope\":{\"type\":\"string\"}}}"; //$NON-NLS-1$
        }

        @Override
        protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
            return CompletableFuture.completedFuture(ToolResult.success("{\"ok\":true}")); //$NON-NLS-1$
        }
    }
}
