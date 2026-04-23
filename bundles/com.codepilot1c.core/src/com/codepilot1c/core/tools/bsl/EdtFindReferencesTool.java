package com.codepilot1c.core.tools.bsl;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.AbstractTool;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.ast.EdtAstException;
import com.codepilot1c.core.edt.ast.EdtAstServices;
import com.codepilot1c.core.edt.ast.FindReferencesRequest;
import com.codepilot1c.core.edt.ast.MarkdownRenderer;
import com.codepilot1c.core.edt.ast.ReferenceSearchResult;

/**
 * Internal EDT AST reference-search tool.
 */
@ToolMeta(name = "edt_find_references", category = "bsl", tags = {"read-only", "workspace", "edt"})
public class EdtFindReferencesTool extends AbstractTool {

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "projectName": {"type": "string", "description": "EDT project name"},
                "objectFqn": {"type": "string", "description": "Metadata object FQN, e.g. Catalog.Products, Document.Invoice, Enum.Status"},
                "limit": {"type": "integer", "description": "Max references (default 20). Large limits (>50) risk MCP timeout on big projects — prefer paging via lower limit if possible."}
              },
              "required": ["projectName", "objectFqn"]
            }
            """; //$NON-NLS-1$

    private final MarkdownRenderer markdownRenderer = new MarkdownRenderer();

    @Override
    public String getDescription() {
        return "Find METADATA references for a metadata object (Catalog.X, Document.Y, " //$NON-NLS-1$
                + "Enum.Z, etc.) and return a markdown report. Scans metadata files only — " //$NON-NLS-1$
                + "Roles, Subsystems, forms, other metadata objects that reference the FQN. " //$NON-NLS-1$
                + "Does NOT search BSL module source code for text usages of the object; " //$NON-NLS-1$
                + "for that use the 'grep' tool with the object name as pattern. Keep " //$NON-NLS-1$
                + "'limit' small (default 20) — higher limits can time out on large projects."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Object> parameters = params.getRaw();
            try {
                FindReferencesRequest request = FindReferencesRequest.fromParameters(parameters);
                ReferenceSearchResult result = EdtAstServices.getInstance().findReferences(request);
                return ToolResult.success(markdownRenderer.renderReferences(result), ToolResult.ToolResultType.SEARCH_RESULTS);
            } catch (EdtAstException e) {
                return ToolResult.failure(e.getCode().name() + ": " + e.getMessage()); //$NON-NLS-1$
            } catch (Exception e) {
                return ToolResult.failure("INTERNAL_ERROR: " + e.getMessage()); //$NON-NLS-1$
            }
        });
    }
}
