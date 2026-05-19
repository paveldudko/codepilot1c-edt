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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;

import com.codepilot1c.core.edit.BslMethodParser;
import com.codepilot1c.core.edit.BslMethodParser.MethodInfo;
import com.codepilot1c.core.edit.DiffComputer;
import com.codepilot1c.core.edit.DiffComputer.UnifiedDiff;
import com.codepilot1c.core.edit.EditBlock;
import com.codepilot1c.core.edit.FileEditApplier;
import com.codepilot1c.core.edit.FuzzyMatcher;
import com.codepilot1c.core.edit.MatchResult;
import com.codepilot1c.core.edit.SearchReplaceFormat;
import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.logging.VibeLogger;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Tool for editing file contents.
 *
 * <p>Supports modifying existing files only.</p>
 */
@ToolMeta(
    name = "edit_file",
    category = "file",
    mutating = true,
    tags = {"workspace"}
)
public class EditFileTool extends AbstractTool {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(EditFileTool.class);

    private static final String SCHEMA = """
            {
                "type": "object",
                "properties": {
                    "path": {
                        "type": "string",
                        "description": "Path to an existing workspace file that should be edited"
                    },
                    "content": {
                        "type": "string",
                        "description": "Full replacement content for the file; prefer write_file if you intentionally overwrite the whole file"
                    },
                    "old_text": {
                        "type": "string",
                        "description": "Existing text to search for in partial-edit mode; supports fuzzy matching"
                    },
                    "new_text": {
                        "type": "string",
                        "description": "Replacement text used together with old_text, or as the replacement payload in mode=replaceLines / mode=replaceMethod"
                    },
                    "edits": {
                        "type": "string",
                        "description": "SEARCH/REPLACE blocks for targeted multi-edit patches inside an existing file"
                    },
                    "mode": {
                        "type": "string",
                        "enum": ["replaceLines", "replaceMethod"],
                        "description": "Optional atomic edit mode. replaceLines: replaces 1-based inclusive line range [line_from..line_to] with new_text. replaceMethod: replaces the BSL Procedure/Function named method_name (together with its directive and tightly-coupled doc-comment) with new_text."
                    },
                    "line_from": {
                        "type": "integer",
                        "description": "1-based inclusive start line for mode=replaceLines"
                    },
                    "line_to": {
                        "type": "integer",
                        "description": "1-based inclusive end line for mode=replaceLines"
                    },
                    "method_name": {
                        "type": "string",
                        "description": "Name of the Procedure/Function to replace in mode=replaceMethod (case-insensitive)"
                    },
                    "dry_run": {
                        "type": "boolean",
                        "description": "Compute the would-be edit without writing. Returns the unified diff and (for old_text/new_text) an ambiguity report listing every literal match line, so the caller can detect when old_text matches in more than one place before committing."
                    },
                    "return_diff": {
                        "type": "boolean",
                        "description": "Include the unified diff of applied changes in the response (apply mode only). Defaults to false to keep the common path cheap."
                    },
                    "create": {
                        "type": "boolean",
                        "description": "Deprecated. Creating new files is not allowed."
                    },
                    "allow_metadata_descriptor_edit": {
                        "type": "boolean",
                        "description": "Аварийный override: разрешить редактирование .mdo (не рекомендуется, используйте только когда BM API не покрывает кейс)."
                    }
                },
                "required": ["path"]
            }
            """; //$NON-NLS-1$

    private final FuzzyMatcher fuzzyMatcher = new FuzzyMatcher();
    private final SearchReplaceFormat searchReplaceFormat = new SearchReplaceFormat();
    private final FileEditApplier fileEditApplier = new FileEditApplier(fuzzyMatcher, searchReplaceFormat);
    private final DiffComputer diffComputer = new DiffComputer();
    private final BslMethodParser methodParser = new BslMethodParser();

    @Override
    public String getDescription() {
        return "Редактирует существующий файл workspace через replace, SEARCH/REPLACE или fuzzy-патч."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    public boolean requiresConfirmation() {
        return false;  // Агент может редактировать файлы без подтверждения
    }

    @Override
    public boolean isDestructive() {
        return false;  // Не требует специальной обработки
    }

    @Override
    protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        return CompletableFuture.supplyAsync(() -> {
            long startTime = System.currentTimeMillis();

            String pathStr = params.requireString("path"); //$NON-NLS-1$

            String content = params.optString("content", null); //$NON-NLS-1$
            String oldText = params.optString("old_text", null); //$NON-NLS-1$
            String newText = params.optString("new_text", null); //$NON-NLS-1$
            String edits = params.optString("edits", null); //$NON-NLS-1$
            String mode = params.optString("mode", null); //$NON-NLS-1$
            int lineFrom = params.optInt("line_from", -1); //$NON-NLS-1$
            int lineTo = params.optInt("line_to", -1); //$NON-NLS-1$
            String methodName = params.optString("method_name", null); //$NON-NLS-1$
            boolean dryRun = params.optBoolean("dry_run", false); //$NON-NLS-1$
            boolean returnDiff = params.optBoolean("return_diff", false); //$NON-NLS-1$
            boolean create = params.optBoolean("create", false); //$NON-NLS-1$
            boolean allowMetadataDescriptorEdit = params.optBoolean("allow_metadata_descriptor_edit", false); //$NON-NLS-1$

            LOG.debug("edit_file: path=%s, hasContent=%b, hasOldText=%b, hasEdits=%b, create=%b", //$NON-NLS-1$
                    LogSanitizer.truncatePath(pathStr), content != null, oldText != null, edits != null, create);

            try {
                // Normalize path for cross-platform compatibility
                String normalizedPath = normalizePath(pathStr);
                if (isMetadataDescriptorPath(normalizedPath)) {
                    if (!allowMetadataDescriptorEdit) {
                        LOG.warn("edit_file: заблокирована попытка редактирования metadata descriptor без override: %s", normalizedPath); //$NON-NLS-1$
                        return ToolResult.failure(
                                "❌ Редактирование .mdo файлов по умолчанию заблокировано.\n" + //$NON-NLS-1$
                                "Сначала используйте create_metadata/create_form/add_metadata_child/update_metadata.\n" + //$NON-NLS-1$
                                "Для аварийного обхода передайте allow_metadata_descriptor_edit=true."); //$NON-NLS-1$
                    }
                    LOG.warn("edit_file: аварийный override .mdo включен для %s", normalizedPath); //$NON-NLS-1$
                }

                // QWEN-308: Прямое редактирование .form/.form.xml файлов запрещено — ломает XML-структуру формы.
                if (isFormFilePath(normalizedPath)) {
                    LOG.warn("edit_file: заблокирована попытка редактирования .form файла: %s", normalizedPath); //$NON-NLS-1$
                    return ToolResult.failure(
                            "Writing .form files is blocked. Use create_form/apply_form_recipe/mutate_form_model to manage forms.\n" + //$NON-NLS-1$
                            "Прямое редактирование .form/.form.xml ломает XML-структуру формы EDT.\n" + //$NON-NLS-1$
                            "Используйте штатные инструменты управления формами."); //$NON-NLS-1$
                }

                // Find or create file in workspace
                IFile file = findWorkspaceFile(normalizedPath);

                if (file == null || !file.exists()) {
                    LOG.warn("edit_file: файл не найден: %s", pathStr); //$NON-NLS-1$
                    return ToolResult.failure(
                            "File not found: " + pathStr + ". " + //$NON-NLS-1$ //$NON-NLS-2$
                            "Creating new files via edit_file is not allowed. " + //$NON-NLS-1$
                            "Use ensure_module_artifact to prepare Module.bsl/ObjectModule.bsl/ManagerModule.bsl first, " + //$NON-NLS-1$
                            "then edit existing module files only."); //$NON-NLS-1$
                }

                if (create) {
                    LOG.warn("edit_file: параметр create=true игнорируется и запрещен"); //$NON-NLS-1$
                }

                ToolResult result;
                if ("replaceLines".equals(mode)) { //$NON-NLS-1$
                    LOG.info("edit_file: mode=replaceLines в %s, строки %d..%d", //$NON-NLS-1$
                            file.getFullPath(), lineFrom, lineTo);
                    result = replaceLineRangeMode(file, lineFrom, lineTo, newText, dryRun, returnDiff);
                } else if ("replaceMethod".equals(mode)) { //$NON-NLS-1$
                    LOG.info("edit_file: mode=replaceMethod в %s, имя=%s", //$NON-NLS-1$
                            file.getFullPath(), methodName);
                    result = replaceMethodMode(file, methodName, newText, dryRun, returnDiff);
                } else if (mode != null && !mode.isEmpty()) {
                    return ToolResult.failure(
                            "Unknown mode: " + mode + ". Supported: replaceLines, replaceMethod."); //$NON-NLS-1$ //$NON-NLS-2$
                } else if (content != null) {
                    LOG.info("edit_file: замена содержимого файла %s (%d символов)", //$NON-NLS-1$
                            file.getFullPath(), content.length());
                    result = replaceContent(file, content, dryRun, returnDiff);
                } else if (edits != null && !edits.isEmpty()) {
                    LOG.info("edit_file: SEARCH/REPLACE редактирование %s", //$NON-NLS-1$
                            file.getFullPath());
                    result = applySearchReplaceEdits(file, edits, dryRun, returnDiff);
                } else if (oldText != null && newText != null) {
                    LOG.info("edit_file: fuzzy search-replace в %s (oldText=%d символов)", //$NON-NLS-1$
                            file.getFullPath(), oldText.length());
                    result = fuzzySearchAndReplace(file, oldText, newText, dryRun, returnDiff);
                } else {
                    LOG.warn("edit_file: недостаточно параметров для редактирования"); //$NON-NLS-1$
                    return ToolResult.failure(
                            "Either 'content', 'edits', 'mode' (replaceLines/replaceMethod), or both 'old_text' and 'new_text' are required"); //$NON-NLS-1$
                }

                LOG.debug("edit_file: завершено за %s, success=%b", //$NON-NLS-1$
                        LogSanitizer.formatDuration(System.currentTimeMillis() - startTime),
                        result.isSuccess());
                return result;

            } catch (CoreException e) {
                LOG.error("edit_file: ошибка редактирования %s: %s", pathStr, e.getMessage()); //$NON-NLS-1$
                return ToolResult.failure("Error editing file: " + e.getMessage()); //$NON-NLS-1$
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
        // Remove leading slash if present (workspace paths don't start with /)
        String normalized = path;
        if (normalized.startsWith("/") && !normalized.startsWith("//")) { //$NON-NLS-1$ //$NON-NLS-2$
            normalized = normalized.substring(1);
        }
        // Convert to platform-specific separators
        return normalized.replace('/', File.separatorChar).replace('\\', File.separatorChar);
    }

    /**
     * Finds a file in the workspace by path.
     */
    private IFile findWorkspaceFile(String path) {
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
        LOG.debug("findWorkspaceFile: ищем файл по пути '%s'", path); //$NON-NLS-1$
        LOG.debug("findWorkspaceFile: workspace root = %s", root.getLocation()); //$NON-NLS-1$

        // Strategy 1: Try as workspace-relative path
        try {
            IResource resource = root.findMember(path);
            if (resource instanceof IFile && resource.exists()) {
                LOG.debug("findWorkspaceFile: найден через findMember: %s -> %s", //$NON-NLS-1$
                        resource.getFullPath(), resource.getLocation());
                return (IFile) resource;
            }
        } catch (Exception e) {
            LOG.debug("findWorkspaceFile: findMember failed: %s", e.getMessage()); //$NON-NLS-1$
        }

        // Strategy 2: Try using Path.fromOSString
        try {
            IFile file = root.getFile(Path.fromOSString(path));
            if (file.exists()) {
                LOG.debug("findWorkspaceFile: найден через fromOSString: %s -> %s", //$NON-NLS-1$
                        file.getFullPath(), file.getLocation());
                return file;
            }
        } catch (Exception e) {
            LOG.debug("findWorkspaceFile: fromOSString failed: %s", e.getMessage()); //$NON-NLS-1$
        }

        // Strategy 3: Try with forward slashes
        try {
            String forwardSlashPath = path.replace('\\', '/');
            IResource resource = root.findMember(forwardSlashPath);
            if (resource instanceof IFile && resource.exists()) {
                LOG.debug("findWorkspaceFile: найден через forward slashes: %s -> %s", //$NON-NLS-1$
                        resource.getFullPath(), resource.getLocation());
                return (IFile) resource;
            }
        } catch (Exception e) {
            LOG.debug("findWorkspaceFile: forward slashes failed: %s", e.getMessage()); //$NON-NLS-1$
        }

        LOG.warn("findWorkspaceFile: файл не найден: %s", path); //$NON-NLS-1$
        return null;
    }

    private boolean isMetadataDescriptorPath(String normalizedPath) {
        if (normalizedPath == null) {
            return false;
        }
        String lower = normalizedPath.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".mdo"); //$NON-NLS-1$
    }

    private boolean isFormFilePath(String normalizedPath) {
        if (normalizedPath == null) {
            return false;
        }
        String lower = normalizedPath.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".form") || lower.endsWith(".form.xml"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private ToolResult replaceContent(IFile file, String content, boolean dryRun, boolean returnDiff) throws CoreException {
        String currentContent = readFileContent(file);
        if (currentContent == null) {
            return ToolResult.failure("Error reading file content"); //$NON-NLS-1$
        }
        String lineSeparator = detectLineSeparator(currentContent);
        String normalizedContent = normalizeLineEndings(content, lineSeparator);

        if (dryRun) {
            return buildDryRunResult(file, currentContent, normalizedContent, null);
        }

        Charset charset = getFileCharset(file);
        ByteArrayInputStream stream = new ByteArrayInputStream(
                normalizedContent.getBytes(charset));
        file.setContents(stream, IResource.FORCE | IResource.KEEP_HISTORY, new NullProgressMonitor());
        file.refreshLocal(IResource.DEPTH_ZERO, new NullProgressMonitor());

        LOG.info("edit_file: содержимое записано в %s (%d байт)", //$NON-NLS-1$
                file.getFullPath(), normalizedContent.length());

        String summary = "Updated file: " + file.getFullPath().toString() //$NON-NLS-1$
                + " (location: " + file.getLocation() + ")"; //$NON-NLS-1$ //$NON-NLS-2$
        if (returnDiff) {
            return buildApplyWithDiffResult(summary, currentContent, normalizedContent);
        }
        return ToolResult.success(summary, ToolResult.ToolResultType.CONFIRMATION);
    }

    /**
     * Applies SEARCH/REPLACE blocks to a file using the FileEditApplier.
     */
    private ToolResult applySearchReplaceEdits(IFile file, String edits, boolean dryRun, boolean returnDiff) throws CoreException {
        String currentContent = readFileContent(file);
        if (currentContent == null) {
            return ToolResult.failure("Error reading file content"); //$NON-NLS-1$
        }
        String lineSeparator = detectLineSeparator(currentContent);

        List<EditBlock> blocks = searchReplaceFormat.parse(edits);
        if (blocks.isEmpty()) {
            return ToolResult.failure("No valid SEARCH/REPLACE blocks found in 'edits' parameter. " + //$NON-NLS-1$
                    "Use format: <<<<<<< SEARCH\\nold code\\n=======\\nnew code\\n>>>>>>> REPLACE"); //$NON-NLS-1$
        }

        List<String> errors = searchReplaceFormat.validate(blocks);
        if (!errors.isEmpty()) {
            return ToolResult.failure("Invalid edit blocks: " + String.join("; ", errors)); //$NON-NLS-1$ //$NON-NLS-2$
        }

        FileEditApplier.ApplyResult applyResult = fileEditApplier.apply(currentContent, blocks);

        if (!applyResult.allSuccessful()) {
            String feedback = applyResult.getFailureFeedback();
            LOG.warn("edit_file: не все блоки применены: %s", applyResult.getSummary()); //$NON-NLS-1$
            return ToolResult.failure(feedback);
        }

        String normalizedContent = normalizeLineEndings(applyResult.afterContent(), lineSeparator);

        if (dryRun) {
            return buildDryRunResult(file, currentContent, normalizedContent, null);
        }

        Charset charset = getFileCharset(file);
        ByteArrayInputStream stream = new ByteArrayInputStream(
                normalizedContent.getBytes(charset));
        file.setContents(stream, true, true, new NullProgressMonitor());

        String summary = applyResult.getSummary() + " в: " + file.getFullPath().toString(); //$NON-NLS-1$
        if (returnDiff) {
            return buildApplyWithDiffResult(summary, currentContent, normalizedContent);
        }
        return ToolResult.success(summary, ToolResult.ToolResultType.CONFIRMATION);
    }

    /**
     * Search and replace with fuzzy matching support.
     */
    private ToolResult fuzzySearchAndReplace(IFile file, String oldText, String newText, boolean dryRun, boolean returnDiff) throws CoreException {
        String currentContent = readFileContent(file);
        if (currentContent == null) {
            return ToolResult.failure("Error reading file content"); //$NON-NLS-1$
        }
        String lineSeparator = detectLineSeparator(currentContent);

        List<int[]> literalMatches = findLiteralMatches(currentContent, oldText);

        MatchResult matchResult = fuzzyMatcher.findMatch(oldText, currentContent);
        if (!matchResult.isSuccess()) {
            String feedback = matchResult.generateFeedback();
            LOG.warn("edit_file: fuzzy match не найден"); //$NON-NLS-1$
            return ToolResult.failure(feedback);
        }
        var location = matchResult.getLocation().orElseThrow();

        String beforeStr = currentContent.substring(0, location.getStartOffset());
        String afterStr = currentContent.substring(location.getEndOffset());
        String normalizedNewText = normalizeLineEndings(newText, lineSeparator);
        String newContent = beforeStr + normalizedNewText + afterStr;

        if (dryRun) {
            JsonArray ambiguity = buildAmbiguityArray(literalMatches);
            return buildDryRunResult(file, currentContent, newContent, ambiguity);
        }

        Charset charset = getFileCharset(file);
        ByteArrayInputStream stream = new ByteArrayInputStream(
                newContent.getBytes(charset));
        file.setContents(stream, true, true, new NullProgressMonitor());

        String strategyInfo = matchResult.getStrategy() != null
                ? " (стратегия: " + matchResult.getStrategy().getDisplayName() + ")" //$NON-NLS-1$ //$NON-NLS-2$
                : ""; //$NON-NLS-1$
        String summary = "Заменено в строках " + location.getStartLine() + "-" + location.getEndLine() //$NON-NLS-1$ //$NON-NLS-2$
                + strategyInfo + " в: " + file.getFullPath().toString(); //$NON-NLS-1$
        if (returnDiff) {
            return buildApplyWithDiffResult(summary, currentContent, newContent);
        }
        return ToolResult.success(summary, ToolResult.ToolResultType.CONFIRMATION);
    }

    private ToolResult replaceLineRangeMode(IFile file, int lineFrom, int lineTo, String newText, boolean dryRun, boolean returnDiff) throws CoreException {
        if (lineFrom < 1 || lineTo < 1 || lineTo < lineFrom) {
            return ToolResult.failure(
                    "mode=replaceLines requires line_from >= 1 and line_to >= line_from (got line_from=" //$NON-NLS-1$
                            + lineFrom + ", line_to=" + lineTo + ")"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (newText == null) {
            return ToolResult.failure("mode=replaceLines requires new_text"); //$NON-NLS-1$
        }
        String currentContent = readFileContent(file);
        if (currentContent == null) {
            return ToolResult.failure("Error reading file content"); //$NON-NLS-1$
        }
        String lineSeparator = detectLineSeparator(currentContent);
        String[] lines = currentContent.split("\\r\\n|\\r|\\n", -1); //$NON-NLS-1$
        if (lineTo > lines.length) {
            return ToolResult.failure(
                    "mode=replaceLines: line_to=" + lineTo + " is past end of file (" + lines.length + " lines)"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lineFrom - 1; i++) {
            out.append(lines[i]);
            if (i < lines.length - 1) {
                out.append(lineSeparator);
            }
        }
        // Insert new_text — normalize its endings to match the file.
        String normalizedNewText = normalizeLineEndings(newText, lineSeparator);
        out.append(normalizedNewText);
        boolean newTextEndsWithSep = normalizedNewText.endsWith(lineSeparator);
        if (lineTo < lines.length && !newTextEndsWithSep) {
            out.append(lineSeparator);
        }
        for (int i = lineTo; i < lines.length; i++) {
            out.append(lines[i]);
            if (i < lines.length - 1) {
                out.append(lineSeparator);
            }
        }
        String newContent = out.toString();

        if (dryRun) {
            return buildDryRunResult(file, currentContent, newContent, null);
        }

        Charset charset = getFileCharset(file);
        ByteArrayInputStream stream = new ByteArrayInputStream(
                newContent.getBytes(charset));
        file.setContents(stream, true, true, new NullProgressMonitor());

        String summary = "Заменены строки " + lineFrom + "-" + lineTo //$NON-NLS-1$ //$NON-NLS-2$
                + " в: " + file.getFullPath().toString(); //$NON-NLS-1$
        if (returnDiff) {
            return buildApplyWithDiffResult(summary, currentContent, newContent);
        }
        return ToolResult.success(summary, ToolResult.ToolResultType.CONFIRMATION);
    }

    private ToolResult replaceMethodMode(IFile file, String methodName, String newText, boolean dryRun, boolean returnDiff) throws CoreException {
        if (methodName == null || methodName.isEmpty()) {
            return ToolResult.failure("mode=replaceMethod requires method_name"); //$NON-NLS-1$
        }
        if (newText == null) {
            return ToolResult.failure("mode=replaceMethod requires new_text"); //$NON-NLS-1$
        }
        String currentContent = readFileContent(file);
        if (currentContent == null) {
            return ToolResult.failure("Error reading file content"); //$NON-NLS-1$
        }
        var maybeMethod = methodParser.findByName(currentContent, methodName);
        if (maybeMethod.isEmpty()) {
            return ToolResult.failure(
                    "mode=replaceMethod: метод '" + methodName + "' не найден в " + file.getFullPath()); //$NON-NLS-1$ //$NON-NLS-2$
        }
        MethodInfo m = maybeMethod.get();
        return replaceLineRangeMode(file, m.replaceFromLine(), m.replaceToLine(), newText, dryRun, returnDiff);
    }

    /**
     * Returns starting offsets and 1-based line numbers of every literal
     * occurrence of {@code needle} in {@code haystack}.
     */
    private List<int[]> findLiteralMatches(String haystack, String needle) {
        List<int[]> hits = new java.util.ArrayList<>();
        if (needle == null || needle.isEmpty()) {
            return hits;
        }
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            int line = 1;
            for (int k = 0; k < idx; k++) {
                if (haystack.charAt(k) == '\n') {
                    line++;
                }
            }
            hits.add(new int[] { idx, line });
            idx += Math.max(1, needle.length());
        }
        return hits;
    }

    private JsonArray buildAmbiguityArray(List<int[]> matches) {
        JsonArray array = new JsonArray();
        for (int[] hit : matches) {
            array.add(hit[1]);
        }
        return array;
    }

    private ToolResult buildDryRunResult(IFile file, String before, String after, JsonArray literalMatchLines) {
        UnifiedDiff ud = diffComputer.unifiedDiff(before, after, 3);
        JsonObject payload = new JsonObject();
        payload.addProperty("dry_run", true); //$NON-NLS-1$
        payload.addProperty("path", file.getFullPath().toString()); //$NON-NLS-1$
        payload.addProperty("would_apply", ud.summary().hasChanges()); //$NON-NLS-1$
        payload.addProperty("added", ud.summary().added()); //$NON-NLS-1$
        payload.addProperty("removed", ud.summary().removed()); //$NON-NLS-1$
        JsonArray hunks = new JsonArray();
        for (DiffComputer.Hunk h : ud.hunks()) {
            JsonObject hunk = new JsonObject();
            if (h.previousRange() != null) hunk.addProperty("previous_range", h.previousRange()); //$NON-NLS-1$
            if (h.currentRange() != null) hunk.addProperty("current_range", h.currentRange()); //$NON-NLS-1$
            hunk.addProperty("text", h.text()); //$NON-NLS-1$
            hunks.add(hunk);
        }
        payload.add("hunks", hunks); //$NON-NLS-1$
        if (literalMatchLines != null && literalMatchLines.size() > 1) {
            payload.add("ambiguity", literalMatchLines); //$NON-NLS-1$
        }
        String summary = "dry_run: " + file.getFullPath() + " (+" + ud.summary().added() //$NON-NLS-1$ //$NON-NLS-2$
                + "/-" + ud.summary().removed() + ", " + ud.hunks().size() + " hunks)"; //$NON-NLS-1$ //$NON-NLS-2$
        if (literalMatchLines != null && literalMatchLines.size() > 1) {
            summary += " — ВНИМАНИЕ: old_text встречается на " + literalMatchLines.size() //$NON-NLS-1$
                    + " позициях, fuzzy выберет первую"; //$NON-NLS-1$
        }
        return ToolResult.success(summary, payload);
    }

    private ToolResult buildApplyWithDiffResult(String summary, String before, String after) {
        UnifiedDiff ud = diffComputer.unifiedDiff(before, after, 3);
        JsonObject payload = new JsonObject();
        payload.addProperty("added", ud.summary().added()); //$NON-NLS-1$
        payload.addProperty("removed", ud.summary().removed()); //$NON-NLS-1$
        JsonArray hunks = new JsonArray();
        for (DiffComputer.Hunk h : ud.hunks()) {
            JsonObject hunk = new JsonObject();
            if (h.previousRange() != null) hunk.addProperty("previous_range", h.previousRange()); //$NON-NLS-1$
            if (h.currentRange() != null) hunk.addProperty("current_range", h.currentRange()); //$NON-NLS-1$
            hunk.addProperty("text", h.text()); //$NON-NLS-1$
            hunks.add(hunk);
        }
        payload.add("hunks", hunks); //$NON-NLS-1$
        return ToolResult.success(summary + " (+" + ud.summary().added() //$NON-NLS-1$
                + "/-" + ud.summary().removed() + ")", payload); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Reads file content with proper encoding handling.
     */
    private String readFileContent(IFile file) {
        Charset charset = getFileCharset(file);
        try (InputStream stream = file.getContents()) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] data = new byte[4096];
            int read;
            while ((read = stream.read(data)) != -1) {
                buffer.write(data, 0, read);
            }
            String content = new String(buffer.toByteArray(), charset);
            if (content.startsWith("\uFEFF")) { //$NON-NLS-1$
                content = content.substring(1);
            }
            return content;
        } catch (IOException | CoreException e) {
            LOG.error("Error reading file %s: %s", file.getFullPath(), e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    private String detectLineSeparator(String content) {
        if (content == null || content.isEmpty()) {
            return System.lineSeparator();
        }
        int lfIndex = content.indexOf('\n');
        if (lfIndex > 0 && content.charAt(lfIndex - 1) == '\r') {
            return "\r\n"; //$NON-NLS-1$
        }
        if (content.indexOf('\r') >= 0) {
            return "\r"; //$NON-NLS-1$
        }
        if (lfIndex >= 0) {
            return "\n"; //$NON-NLS-1$
        }
        return System.lineSeparator();
    }

    private String normalizeLineEndings(String text, String lineSeparator) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        if (lineSeparator == null || lineSeparator.isEmpty() || "\n".equals(lineSeparator)) { //$NON-NLS-1$
            return text.replace("\r\n", "\n").replace("\r", "\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        }
        String normalized = text.replace("\r\n", "\n").replace("\r", "\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        return normalized.replace("\n", lineSeparator); //$NON-NLS-1$
    }

    /**
     * Legacy search and replace (exact match only).
     * @deprecated Use fuzzySearchAndReplace instead
     */
    @Deprecated
    private ToolResult searchAndReplace(IFile file, String oldText, String newText) throws CoreException {
        String currentContent = readFileContent(file);
        if (currentContent == null) {
            return ToolResult.failure("Error reading file content"); //$NON-NLS-1$
        }
        String lineSeparator = detectLineSeparator(currentContent);

        // Check if old_text exists
        if (!currentContent.contains(oldText)) {
            return ToolResult.failure("Text not found in file: " + oldText); //$NON-NLS-1$
        }

        // Count occurrences
        int count = 0;
        int index = 0;
        while ((index = currentContent.indexOf(oldText, index)) != -1) {
            count++;
            index += oldText.length();
        }

        // Replace
        String normalizedNewText = normalizeLineEndings(newText, lineSeparator);
        String newContent = currentContent.replace(oldText, normalizedNewText);

        // Write with same charset
        Charset charset = getFileCharset(file);
        ByteArrayInputStream stream = new ByteArrayInputStream(
                newContent.getBytes(charset));
        file.setContents(stream, true, true, new NullProgressMonitor());

        return ToolResult.success(
                "Replaced " + count + " occurrence(s) in: " + file.getFullPath().toString(), //$NON-NLS-1$ //$NON-NLS-2$
                ToolResult.ToolResultType.CONFIRMATION);
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
}
