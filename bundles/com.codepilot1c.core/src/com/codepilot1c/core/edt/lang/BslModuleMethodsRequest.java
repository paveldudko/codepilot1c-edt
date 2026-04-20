package com.codepilot1c.core.edt.lang;

import java.util.Locale;
import java.util.Map;

import com.codepilot1c.core.edt.ast.EdtAstErrorCode;
import com.codepilot1c.core.edt.ast.EdtAstException;

/**
 * Request to list methods in a BSL module.
 */
public class BslModuleMethodsRequest {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 500;

    private final String projectName;
    private final String filePath;
    private final String nameContains;
    private final String kind;
    private final int limit;
    private final int offset;
    private final boolean compact;

    public BslModuleMethodsRequest(
            String projectName,
            String filePath,
            String nameContains,
            String kind,
            int limit,
            int offset,
            boolean compact) {
        this.projectName = projectName;
        this.filePath = filePath;
        this.nameContains = nameContains;
        this.kind = kind;
        this.limit = limit;
        this.offset = offset;
        this.compact = compact;
    }

    public static BslModuleMethodsRequest fromParameters(Map<String, Object> parameters) {
        String projectName = asString(parameters.get("projectName")); //$NON-NLS-1$
        String filePath = asString(parameters.get("filePath")); //$NON-NLS-1$
        String nameContains = asString(parameters.get("name_contains")); //$NON-NLS-1$
        String kind = asString(parameters.get("kind")); //$NON-NLS-1$
        int limit = asInt(parameters.get("limit"), DEFAULT_LIMIT); //$NON-NLS-1$
        int offset = asInt(parameters.get("offset"), 0); //$NON-NLS-1$
        boolean compact = asBoolean(parameters.get("compact"), false); //$NON-NLS-1$
        return new BslModuleMethodsRequest(projectName, filePath, nameContains, kind, limit, offset, compact);
    }

    public void validate() {
        if (projectName == null || projectName.isBlank()) {
            throw new EdtAstException(EdtAstErrorCode.INVALID_ARGUMENT,
                    "projectName is required", false); //$NON-NLS-1$
        }
        if (filePath == null || filePath.isBlank()) {
            throw new EdtAstException(EdtAstErrorCode.INVALID_ARGUMENT,
                    "filePath is required", false); //$NON-NLS-1$
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new EdtAstException(EdtAstErrorCode.INVALID_ARGUMENT,
                    "limit must be between 1 and " + MAX_LIMIT, false); //$NON-NLS-1$
        }
        if (offset < 0) {
            throw new EdtAstException(EdtAstErrorCode.INVALID_ARGUMENT,
                    "offset must be >= 0", false); //$NON-NLS-1$
        }
        String normalizedKind = normalizedKind();
        if (!"any".equals(normalizedKind) //$NON-NLS-1$
                && !"procedure".equals(normalizedKind) //$NON-NLS-1$
                && !"function".equals(normalizedKind)) { //$NON-NLS-1$
            throw new EdtAstException(EdtAstErrorCode.INVALID_ARGUMENT,
                    "kind must be one of: any, procedure, function", false); //$NON-NLS-1$
        }
    }

    public String getProjectName() {
        return projectName;
    }

    public String getFilePath() {
        return filePath;
    }

    public String getNameContains() {
        return nameContains;
    }

    public String getKind() {
        return kind;
    }

    public int getLimit() {
        return limit;
    }

    public int getOffset() {
        return offset;
    }

    public boolean isCompact() {
        return compact;
    }

    public String normalizedKind() {
        if (kind == null || kind.isBlank()) {
            return "any"; //$NON-NLS-1$
        }
        return kind.trim().toLowerCase(Locale.ROOT);
    }

    public String normalizedNameContains() {
        if (nameContains == null || nameContains.isBlank()) {
            return ""; //$NON-NLS-1$
        }
        return nameContains.trim().toLowerCase(Locale.ROOT);
    }

    private static String asString(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static int asInt(Object value, int defaultValue) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return defaultValue;
        }
        try {
            return (int) Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static boolean asBoolean(Object value, boolean defaultValue) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value == null) {
            return defaultValue;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(text);
    }
}
