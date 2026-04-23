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

import com.codepilot1c.core.tools.grep.GrepFormatters;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;

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
                    "object_name": {
                        "type": "string",
                        "description": "Narrow results to files whose workspace path contains this substring (case-insensitive). Useful for scoping to a single metadata object, e.g. 'BankStatementsLoader_v2' matches only that data processor."
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
                    "limit": {
                        "type": "integer",
                        "description": "Max matches to return (default 50, max 500). Response indicates when the cap is hit."
                    },
                    "compact": {
                        "type": "boolean",
                        "description": "When true, emit plain grep-like lines (path:line:text) with no markdown or code fences. ~40-60% smaller than default markdown output — prefer for large scans."
                    }
                },
                "required": ["pattern"]
            }
            """; //$NON-NLS-1$

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 500;

    @Override
    public String getDescription() {
        return "Search for text patterns in workspace files. Supports plain text / regex, " //$NON-NLS-1$
                + "file-pattern filter, case-sensitivity, context lines, and a configurable " //$NON-NLS-1$
                + "limit (default 50, max 500). Pass object_name to narrow to files whose " //$NON-NLS-1$
                + "path contains a metadata-object name (case-insensitive). Pass compact=true " //$NON-NLS-1$
                + "for terse grep-style output (path:line:text, no markdown) — typically " //$NON-NLS-1$
                + "40-60% smaller than the default markdown output."; //$NON-NLS-1$
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
            String objectName = params.optString("object_name", null); //$NON-NLS-1$
            if (objectName != null) {
                objectName = objectName.trim();
                if (objectName.isEmpty()) {
                    objectName = null;
                }
            }
            boolean useRegex = params.optBoolean("regex", false); //$NON-NLS-1$
            boolean caseSensitive = params.optBoolean("case_sensitive", false); //$NON-NLS-1$
            boolean compact = params.optBoolean("compact", false); //$NON-NLS-1$
            int contextLines = params.optInt("context_lines", 0); //$NON-NLS-1$

            int limit = params.optInt("limit", DEFAULT_LIMIT); //$NON-NLS-1$
            if (limit < 1) {
                limit = 1;
            } else if (limit > MAX_LIMIT) {
                limit = MAX_LIMIT;
            }

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
                String objectNameLower = objectName == null ? null : objectName.toLowerCase(java.util.Locale.ROOT);
                searchInContainer(searchRoot, searchPattern, filePattern, objectNameLower, contextLines, limit, matches);

                return compact
                        ? formatResultsCompact(patternStr, matches, limit, contextLines)
                        : formatResults(patternStr, matches, limit);
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
                                   String filePattern, String objectNameLower,
                                   int contextLines, int limit,
                                   List<SearchMatch> matches) throws CoreException {
        if (matches.size() >= limit) {
            return;
        }

        IResource[] members;
        if (container instanceof IWorkspaceRoot) {
            IProject[] projects = ((IWorkspaceRoot) container).getProjects();
            for (IProject project : projects) {
                if (project.isOpen()) {
                    searchInContainer(project, pattern, filePattern, objectNameLower, contextLines, limit, matches);
                }
            }
            return;
        }

        members = container.members();
        for (IResource member : members) {
            if (matches.size() >= limit) {
                break;
            }

            if (member instanceof IContainer) {
                searchInContainer((IContainer) member, pattern, filePattern, objectNameLower, contextLines, limit, matches);
            } else if (member instanceof IFile) {
                IFile file = (IFile) member;
                if (!matchesFilePattern(file.getName(), filePattern)) {
                    continue;
                }
                if (objectNameLower != null && !file.getFullPath().toString()
                        .toLowerCase(java.util.Locale.ROOT).contains(objectNameLower)) {
                    continue;
                }
                searchInFile(file, pattern, contextLines, limit, matches);
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
                              int limit, List<SearchMatch> matches) throws CoreException {
        if (matches.size() >= limit) {
            return;
        }

        // Get file charset
        Charset charset = getFileCharset(file);

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getContents(), charset))) {

            List<String> lines = new ArrayList<>();
            String line;
            boolean firstLine = true;
            while ((line = reader.readLine()) != null) {
                // Skip BOM on first line
                if (firstLine && line.startsWith("\uFEFF")) { //$NON-NLS-1$
                    line = line.substring(1);
                }
                firstLine = false;
                lines.add(line);
            }

            for (int i = 0; i < lines.size() && matches.size() < limit; i++) {
                Matcher matcher = pattern.matcher(lines.get(i));
                if (matcher.find()) {
                    int startContext = Math.max(0, i - contextLines);
                    int endContext = Math.min(lines.size() - 1, i + contextLines);
                    List<String> span = new ArrayList<>(endContext - startContext + 1);
                    for (int j = startContext; j <= endContext; j++) {
                        span.add(lines.get(j));
                    }
                    matches.add(new SearchMatch(
                            file.getFullPath().toString(),
                            i + 1,
                            lines.get(i).trim(),
                            startContext + 1,
                            span));
                }
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

    private ToolResult formatResults(String pattern, List<SearchMatch> matches, int limit) {
        return ToolResult.success(
                GrepFormatters.markdown(pattern, toHits(matches), limit),
                ToolResult.ToolResultType.SEARCH_RESULTS);
    }

    private ToolResult formatResultsCompact(String pattern, List<SearchMatch> matches, int limit, int contextLines) {
        return ToolResult.success(
                GrepFormatters.compact(pattern, toHits(matches), limit, contextLines),
                ToolResult.ToolResultType.SEARCH_RESULTS);
    }

    private static List<GrepFormatters.Hit> toHits(List<SearchMatch> matches) {
        List<GrepFormatters.Hit> hits = new ArrayList<>(matches.size());
        for (SearchMatch m : matches) {
            hits.add(new GrepFormatters.Hit(m.filePath, m.lineNumber, m.contextStartLine, m.contextLines));
        }
        return hits;
    }

    private static class SearchMatch {
        final String filePath;
        final int lineNumber;
        final String matchLine;
        final int contextStartLine;
        final List<String> contextLines;

        SearchMatch(String filePath, int lineNumber, String matchLine,
                int contextStartLine, List<String> contextLines) {
            this.filePath = filePath;
            this.lineNumber = lineNumber;
            this.matchLine = matchLine;
            this.contextStartLine = contextStartLine;
            this.contextLines = contextLines;
        }
    }
}
