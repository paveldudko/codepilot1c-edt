/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.file;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.AbstractTool;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;

import com.codepilot1c.core.edit.BslMethodParser;

/**
 * Tool for searching text patterns in files.
 */
@ToolMeta(
    name = "grep",
    category = "file",
    tags = {"read-only", "workspace"}
)
public class GrepTool extends AbstractTool {

    private static final String SCHEMA = """
            {
                "type": "object",
                "properties": {
                    "pattern": {
                        "type": "string",
                        "description": "Plain-text or regex pattern for raw text search across files."
                    },
                    "path": {
                        "type": "string",
                        "description": "Optional workspace directory scope. Use this to narrow text search, not semantic object scope."
                    },
                    "file_pattern": {
                        "type": "string",
                        "description": "Optional file-name glob such as '*.bsl' or '*.xml'."
                    },
                    "regex": {
                        "type": "boolean",
                        "description": "Treat pattern as regex (default: false)"
                    },
                    "case_sensitive": {
                        "type": "boolean",
                        "description": "Case-sensitive search (default: false)"
                    },
                    "context_lines": {
                        "type": "integer",
                        "description": "Lines of context around matches (default: 0)"
                    },
                    "output_mode": {
                        "type": "string",
                        "enum": ["full", "compact"],
                        "description": "Output format. 'full' (default) returns matches with surrounding context blocks; 'compact' emits one line per match in 'file:line [EnclosingMethod] >>> snippet' format — token-efficient for high-count searches."
                    },
                    "match_kind": {
                        "type": "string",
                        "enum": ["any", "call", "definition"],
                        "description": "BSL-aware filter applied to .bsl files. 'any' (default) keeps every regex hit. 'definition' keeps only Procedure/Function declaration lines. 'call' drops lines that are themselves Procedure/Function declarations or BSL line-comments (starting with //) — leaves call sites and other code references. For non-.bsl files the filter is ignored."
                    }
                },
                "required": ["pattern"]
            }
            """; //$NON-NLS-1$

    private static final int MAX_RESULTS = 50;

    @Override
    public String getDescription() {
        return "Searches workspace files by text or regex. Use to locate strings, errors, handlers, and literals."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        return CompletableFuture.supplyAsync(() -> {
            String patternStr = params.requireString("pattern"); //$NON-NLS-1$

            String path = params.optString("path", null); //$NON-NLS-1$
            String filePattern = params.optString("file_pattern", null); //$NON-NLS-1$
            boolean useRegex = params.optBoolean("regex", false); //$NON-NLS-1$
            boolean caseSensitive = params.optBoolean("case_sensitive", false); //$NON-NLS-1$
            int contextLines = params.optInt("context_lines", 0); //$NON-NLS-1$
            String outputMode = params.optString("output_mode", "full"); //$NON-NLS-1$ //$NON-NLS-2$
            String matchKind = params.optString("match_kind", "any"); //$NON-NLS-1$ //$NON-NLS-2$

            Pattern searchPattern;
            try {
                int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE;
                if (useRegex) {
                    searchPattern = Pattern.compile(patternStr, flags);
                } else {
                    searchPattern = Pattern.compile(Pattern.quote(patternStr), flags);
                }
            } catch (PatternSyntaxException e) {
                return ToolResult.failure("Invalid regex pattern: " + e.getMessage()); //$NON-NLS-1$
            }

            try {
                IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
                IContainer searchRoot;

                if (path != null && !path.isEmpty()) {
                    // Normalize path for cross-platform compatibility
                    String normalizedPath = normalizePath(path);
                    IResource resource = findWorkspaceResource(normalizedPath);
                    if (resource instanceof IContainer) {
                        searchRoot = (IContainer) resource;
                    } else {
                        return ToolResult.failure("Path not found or not a directory: " + path); //$NON-NLS-1$
                    }
                } else {
                    searchRoot = root;
                }

                List<SearchMatch> matches = new ArrayList<>();
                searchInContainer(searchRoot, searchPattern, filePattern, contextLines, matchKind, matches);

                return formatResults(patternStr, matches, outputMode);
            } catch (CoreException e) {
                return ToolResult.failure("Error searching: " + e.getMessage()); //$NON-NLS-1$
            }
        });
    }

    /**
     * Normalizes path separators for cross-platform compatibility.
     */
    private String normalizePath(String path) {
        if (path == null) {
            return null;
        }
        String normalized = path;
        if (normalized.startsWith("/") && !normalized.startsWith("//")) { //$NON-NLS-1$ //$NON-NLS-2$
            normalized = normalized.substring(1);
        }
        return normalized.replace('/', File.separatorChar).replace('\\', File.separatorChar);
    }

    /**
     * Finds a resource in the workspace by path.
     */
    private IResource findWorkspaceResource(String path) {
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();

        // Try direct lookup
        IResource resource = root.findMember(path);
        if (resource != null && resource.exists()) {
            return resource;
        }

        // Try with forward slashes
        String forwardSlashPath = path.replace('\\', '/');
        resource = root.findMember(forwardSlashPath);
        if (resource != null && resource.exists()) {
            return resource;
        }

        return null;
    }

    private void searchInContainer(IContainer container, Pattern pattern,
                                   String filePattern, int contextLines,
                                   String matchKind,
                                   List<SearchMatch> matches) throws CoreException {
        if (matches.size() >= MAX_RESULTS) {
            return;
        }

        IResource[] members;
        if (container instanceof IWorkspaceRoot) {
            IProject[] projects = ((IWorkspaceRoot) container).getProjects();
            for (IProject project : projects) {
                if (project.isOpen()) {
                    searchInContainer(project, pattern, filePattern, contextLines, matchKind, matches);
                }
            }
            return;
        }

        members = container.members();
        for (IResource member : members) {
            if (matches.size() >= MAX_RESULTS) {
                break;
            }

            if (member instanceof IContainer) {
                searchInContainer((IContainer) member, pattern, filePattern, contextLines, matchKind, matches);
            } else if (member instanceof IFile) {
                IFile file = (IFile) member;
                if (matchesFilePattern(file.getName(), filePattern)) {
                    searchInFile(file, pattern, contextLines, matchKind, matches);
                }
            }
        }
    }

    private boolean matchesFilePattern(String name, String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            // Default to common code files
            return name.endsWith(".bsl") || name.endsWith(".os") ||  //$NON-NLS-1$ //$NON-NLS-2$
                   name.endsWith(".java") || name.endsWith(".xml"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        String regex = pattern
                .replace(".", "\\.") //$NON-NLS-1$ //$NON-NLS-2$
                .replace("*", ".*") //$NON-NLS-1$ //$NON-NLS-2$
                .replace("?", "."); //$NON-NLS-1$ //$NON-NLS-2$
        return name.matches(regex);
    }

    private void searchInFile(IFile file, Pattern pattern, int contextLines,
                              String matchKind,
                              List<SearchMatch> matches) throws CoreException {
        if (matches.size() >= MAX_RESULTS) {
            return;
        }

        Charset charset = getFileCharset(file);
        boolean isBsl = file.getName().toLowerCase().endsWith(".bsl"); //$NON-NLS-1$

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getContents(), charset))) {

            List<String> lines = new ArrayList<>();
            String line;
            boolean firstLine = true;
            while ((line = reader.readLine()) != null) {
                if (firstLine && line.startsWith("\uFEFF")) { //$NON-NLS-1$
                    line = line.substring(1);
                }
                firstLine = false;
                lines.add(line);
            }

            for (int i = 0; i < lines.size() && matches.size() < MAX_RESULTS; i++) {
                String raw = lines.get(i);
                Matcher matcher = pattern.matcher(raw);
                if (!matcher.find()) {
                    continue;
                }
                if (isBsl && !BslMethodParser.passesMatchKindFilter(raw, matchKind)) {
                    continue;
                }
                int startContext = Math.max(0, i - contextLines);
                int endContext = Math.min(lines.size() - 1, i + contextLines);
                StringBuilder contextBuilder = new StringBuilder();
                for (int j = startContext; j <= endContext; j++) {
                    String prefix = (j == i) ? ">" : " "; //$NON-NLS-1$ //$NON-NLS-2$
                    contextBuilder.append(String.format("%s%4d | %s%n", prefix, j + 1, lines.get(j))); //$NON-NLS-1$
                }
                String enclosing = isBsl ? BslMethodParser.findEnclosingMethodName(lines, i) : null;
                matches.add(new SearchMatch(
                        file.getFullPath().toString(),
                        i + 1,
                        raw.trim(),
                        contextBuilder.toString().trim(),
                        enclosing
                ));
            }
        } catch (java.io.IOException e) {
            // Skip files that can't be read
        }
    }


    /**
     * Gets the charset for a file, defaulting to UTF-8.
     */
    private Charset getFileCharset(IFile file) {
        try {
            String charsetName = file.getCharset();
            if (charsetName != null) {
                return Charset.forName(charsetName);
            }
        } catch (CoreException | IllegalArgumentException e) {
            // Use default
        }
        return StandardCharsets.UTF_8;
    }

    private ToolResult formatResults(String pattern, List<SearchMatch> matches, String outputMode) {
        StringBuilder sb = new StringBuilder();
        if ("compact".equals(outputMode)) { //$NON-NLS-1$
            for (SearchMatch match : matches) {
                sb.append(match.filePath).append(':').append(match.lineNumber);
                if (match.enclosingSymbol != null) {
                    sb.append(" [").append(match.enclosingSymbol).append(']'); //$NON-NLS-1$
                }
                sb.append(" >>> ").append(match.matchLine).append('\n'); //$NON-NLS-1$
            }
            if (matches.isEmpty()) {
                sb.append("(no matches for `").append(pattern).append("`)\n"); //$NON-NLS-1$ //$NON-NLS-2$
            } else if (matches.size() == MAX_RESULTS) {
                sb.append("...truncated at ").append(MAX_RESULTS).append(" matches\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            return ToolResult.success(sb.toString(), ToolResult.ToolResultType.SEARCH_RESULTS);
        }

        sb.append("**Search results for:** `").append(pattern).append("`\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("**Found:** ").append(matches.size()); //$NON-NLS-1$
        if (matches.size() == MAX_RESULTS) {
            sb.append("+ (limited)"); //$NON-NLS-1$
        }
        sb.append(" matches\n\n"); //$NON-NLS-1$

        for (SearchMatch match : matches) {
            sb.append("**").append(match.filePath).append(":").append(match.lineNumber).append("**"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            if (match.enclosingSymbol != null) {
                sb.append(" [").append(match.enclosingSymbol).append(']'); //$NON-NLS-1$
            }
            sb.append('\n');
            sb.append("```\n").append(match.context).append("\n```\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }

        return ToolResult.success(sb.toString(), ToolResult.ToolResultType.SEARCH_RESULTS);
    }

    private static class SearchMatch {
        final String filePath;
        final int lineNumber;
        final String matchLine;
        final String context;
        final String enclosingSymbol;

        SearchMatch(String filePath, int lineNumber, String matchLine, String context, String enclosingSymbol) {
            this.filePath = filePath;
            this.lineNumber = lineNumber;
            this.matchLine = matchLine;
            this.context = context;
            this.enclosingSymbol = enclosingSymbol;
        }
    }
}
