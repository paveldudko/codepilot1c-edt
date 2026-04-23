package com.codepilot1c.core.tools.bsl;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.AbstractTool;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.ast.ContentAssistRequest;
import com.codepilot1c.core.edt.ast.ContentAssistResult;
import com.codepilot1c.core.edt.ast.EdtAstException;
import com.codepilot1c.core.edt.ast.EdtAstServices;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * Internal EDT AST content-assist tool.
 */
@ToolMeta(name = "edt_content_assist", category = "bsl", tags = {"read-only", "workspace", "edt"})
public class EdtContentAssistTool extends AbstractTool {

    private static final Gson GSON = new Gson();

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "projectName": {"type": "string", "description": "EDT project name"},
                "filePath": {"type": "string", "description": "Path relative to src/, for example CommonModules/MyModule/Module.bsl"},
                "line": {"type": "integer", "description": "1-based line of the CURSOR position (the caret — not the identifier start). Empty result usually means the cursor is not in a position where the parser expects proposals (e.g. inside a comment, string literal, or at column 1 of a blank line)."},
                "column": {"type": "integer", "description": "1-based column of the cursor. For member access on an object 'Obj.', set column to the character AFTER the dot. For identifier completion, set column to the position inside or right after the partial identifier. For empty in-body completion, place cursor inside a method body with valid indentation."},
                "limit": {"type": "integer", "description": "Max proposals returned (default 20)"},
                "offset": {"type": "integer", "description": "Pagination offset into the filtered proposals list"},
                "contains": {"type": "string", "description": "Comma-separated substring filters on proposal display (case-insensitive). Narrow noisy results by keyword before paginating."},
                "extendedDocumentation": {"type": "boolean", "description": "Include full proposal documentation (can be large). Default: false."}
              },
              "required": ["projectName", "filePath", "line", "column"]
            }
            """; //$NON-NLS-1$

    @Override
    public String getDescription() {
        return "Get AST-aware content assist (proposals) for a cursor position in a BSL " //$NON-NLS-1$
                + "module. The line/column pair is the CURSOR, not the start of the partial " //$NON-NLS-1$
                + "identifier. Empty result ≠ error: it means no proposals exist at that " //$NON-NLS-1$
                + "position (common causes: inside a comment or string literal, column=1 of " //$NON-NLS-1$
                + "a blank line, or in syntactically broken code). Typical productive " //$NON-NLS-1$
                + "positions: right after a '.' on an object (member access), inside or " //$NON-NLS-1$
                + "right after a partial identifier, or in a method body at a statement " //$NON-NLS-1$
                + "boundary. Use 'contains' to narrow + 'limit'/'offset' to paginate."; //$NON-NLS-1$
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
                ContentAssistRequest request = ContentAssistRequest.fromParameters(parameters);
                ContentAssistResult result = EdtAstServices.getInstance().getContentAssist(request);
                return ToolResult.success(GSON.toJson(result));
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
