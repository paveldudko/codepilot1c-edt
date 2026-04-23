package com.codepilot1c.core.tools.bsl;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.AbstractTool;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.ast.EdtAstException;
import com.codepilot1c.core.edt.lang.BslModuleMethodsRequest;
import com.codepilot1c.core.edt.lang.BslModuleMethodsResult;
import com.codepilot1c.core.edt.lang.BslSemanticService;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * Lists procedures/functions in a BSL module.
 */
@ToolMeta(name = "bsl_list_methods", category = "bsl", tags = {"read-only", "workspace", "edt"})
public class BslListMethodsTool extends AbstractTool {

    private static final Gson GSON = new Gson();

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "projectName": {"type": "string", "description": "EDT project name"},
                "filePath": {"type": "string", "description": "Path relative to src/, for example CommonModules/MyModule/Module.bsl"},
                "name_contains": {"type": "string", "description": "Substring filter (case-insensitive) on method name. Use this before pagination to narrow the result set."},
                "kind": {"type": "string", "enum": ["any", "procedure", "function"], "description": "Filter by method kind (default any)"},
                "limit": {"type": "integer", "description": "Max items per page (default 100, max 500). Response returns total + hasMore for pagination."},
                "offset": {"type": "integer", "description": "Pagination offset (0-based). Use with limit when total > limit."},
                "compact": {"type": "boolean", "description": "When true, omit the 'params' array per method (name/kind/lines/flags only). Cuts ~60-80% of output size on parameter-heavy modules — prefer this for first-pass overviews."}
              },
              "required": ["projectName", "filePath"]
            }
            """; //$NON-NLS-1$

    private final BslSemanticService service;

    public BslListMethodsTool() {
        this(new BslSemanticService());
    }

    public BslListMethodsTool(BslSemanticService service) {
        this.service = service;
    }

    @Override
    public String getDescription() {
        return "List procedures/functions in a BSL module with line ranges, flags and parameters. " //$NON-NLS-1$
                + "Supports pagination (limit/offset, total + hasMore in response) and substring " //$NON-NLS-1$
                + "filter (name_contains). Large modules can produce very verbose output — " //$NON-NLS-1$
                + "pass compact=true to omit the params array (keeps name/kind/lines/flags only). " //$NON-NLS-1$
                + "For a 160-method module, compact+limit can cut output from ~175KB to ~5-10KB."; //$NON-NLS-1$
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
                BslModuleMethodsRequest request = BslModuleMethodsRequest.fromParameters(parameters);
                BslModuleMethodsResult result = service.listMethods(request);
                JsonObject structured = GSON.toJsonTree(result).getAsJsonObject();
                return ToolResult.success(GSON.toJson(result), ToolResult.ToolResultType.SEARCH_RESULTS, structured);
            } catch (EdtAstException e) {
                return ToolResult.failure(toErrorJson(e));
            } catch (Exception e) {
                return ToolResult.failure("{\"error\":\"INTERNAL_ERROR\",\"message\":\"" //$NON-NLS-1$
                        + escapeJson(e.getMessage()) + "\"}"); //$NON-NLS-1$
            }
        });
    }

    private String toErrorJson(EdtAstException e) {
        JsonObject obj = new JsonObject();
        obj.addProperty("error", e.getCode().name()); //$NON-NLS-1$
        obj.addProperty("message", e.getMessage()); //$NON-NLS-1$
        obj.addProperty("recoverable", e.isRecoverable()); //$NON-NLS-1$
        return GSON.toJson(obj);
    }

    private String escapeJson(String text) {
        if (text == null) {
            return "unknown"; //$NON-NLS-1$
        }
        return text.replace("\\", "\\\\").replace("\"", "\\\""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }
}
