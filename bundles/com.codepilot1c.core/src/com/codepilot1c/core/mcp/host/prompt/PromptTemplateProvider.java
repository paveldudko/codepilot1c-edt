package com.codepilot1c.core.mcp.host.prompt;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.codepilot1c.core.mcp.model.McpContent;
import com.codepilot1c.core.mcp.model.McpPrompt;
import com.codepilot1c.core.mcp.model.McpPromptResult;

/**
 * Serves a small, self-contained set of MCP prompt templates for CodePilot1C's
 * EDT tool surface.
 *
 * <p>The MCP bridge only exposes tools; the reasoning is done by the connecting
 * MCP client (the agent). These templates are therefore static scaffolds that
 * point the client at the CodePilot1C tools — they carry no dependency on any
 * in-plugin agent/LLM engine.
 */
public class PromptTemplateProvider implements IMcpPromptProvider {

    private static final String BUILD_PROMPT =
        "You are working inside a 1C:EDT workspace through the CodePilot1C MCP tools. " //$NON-NLS-1$
        + "Implement the requested change end to end: inspect the relevant BSL modules and metadata, " //$NON-NLS-1$
        + "apply edits with the editing tools, and validate the result with the diagnostics tools before finishing."; //$NON-NLS-1$

    private static final String PLAN_PROMPT =
        "You are working inside a 1C:EDT workspace through the CodePilot1C MCP tools. " //$NON-NLS-1$
        + "Do not modify anything yet. Explore the workspace with the read-only tools, then produce a concise, " //$NON-NLS-1$
        + "ordered implementation plan naming the concrete modules, metadata objects and steps required."; //$NON-NLS-1$

    private static final String EXPLORE_PROMPT =
        "You are working inside a 1C:EDT workspace through the CodePilot1C MCP tools. " //$NON-NLS-1$
        + "Answer the question by searching and reading the workspace with the read-only tools only. " //$NON-NLS-1$
        + "Report findings with concrete file paths and symbols; do not change any files."; //$NON-NLS-1$

    @Override
    public List<McpPrompt> listPrompts() {
        return List.of(
            new McpPrompt("build", "Build-mode system prompt for CodePilot1C EDT tools"), //$NON-NLS-1$ //$NON-NLS-2$
            new McpPrompt("plan", "Plan-mode system prompt for CodePilot1C EDT tools"), //$NON-NLS-1$ //$NON-NLS-2$
            new McpPrompt("explore", "Explore-mode system prompt for CodePilot1C EDT tools"), //$NON-NLS-1$ //$NON-NLS-2$
            new McpPrompt("subagent", "Subagent system prompt for a scoped CodePilot1C task") //$NON-NLS-1$ //$NON-NLS-2$
        );
    }

    @Override
    public Optional<McpPromptResult> getPrompt(String name, Map<String, Object> arguments) {
        String text;
        switch (name) {
            case "build": //$NON-NLS-1$
                text = BUILD_PROMPT;
                break;
            case "plan": //$NON-NLS-1$
                text = PLAN_PROMPT;
                break;
            case "explore": //$NON-NLS-1$
                text = EXPLORE_PROMPT;
                break;
            case "subagent": //$NON-NLS-1$
                String description = stringArg(arguments, "description", "the requested task"); //$NON-NLS-1$ //$NON-NLS-2$
                boolean readOnly = Boolean.parseBoolean(stringArg(arguments, "readOnly", "true")); //$NON-NLS-1$ //$NON-NLS-2$
                text = "You are a focused subagent working inside a 1C:EDT workspace through the CodePilot1C MCP tools. " //$NON-NLS-1$
                    + "Complete only this task: " + description + ". " //$NON-NLS-1$ //$NON-NLS-2$
                    + (readOnly
                        ? "Use the read-only tools only and report your findings." //$NON-NLS-1$
                        : "Apply the necessary changes and validate them with the diagnostics tools."); //$NON-NLS-1$
                break;
            default:
                return Optional.empty();
        }

        McpPromptResult result = new McpPromptResult();
        result.setDescription("CodePilot prompt template: " + name); //$NON-NLS-1$
        McpPromptResult.PromptMessage message = new McpPromptResult.PromptMessage();
        message.setRole("system"); //$NON-NLS-1$
        message.setContent(McpContent.text(text));
        result.setMessages(List.of(message));
        return Optional.of(result);
    }

    private String stringArg(Map<String, Object> args, String key, String defaultValue) {
        if (args == null) {
            return defaultValue;
        }
        Object value = args.get(key);
        return value != null ? String.valueOf(value) : defaultValue;
    }
}
