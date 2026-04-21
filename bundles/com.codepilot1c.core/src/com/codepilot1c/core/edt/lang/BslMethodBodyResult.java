package com.codepilot1c.core.edt.lang;

/**
 * Result of fetching a BSL method body.
 */
public class BslMethodBodyResult {

    private final String projectName;
    private final String filePath;
    private final String name;
    private final String kind;
    private final int startLine;
    private final int endLine;
    private final String text;
    private final String docComment;

    public BslMethodBodyResult(
            String projectName,
            String filePath,
            String name,
            String kind,
            int startLine,
            int endLine,
            String text,
            String docComment) {
        this.projectName = projectName;
        this.filePath = filePath;
        this.name = name;
        this.kind = kind;
        this.startLine = startLine;
        this.endLine = endLine;
        this.text = text;
        // Preserve null vs empty: null means "no doc block found" and lets Gson drop the field.
        this.docComment = (docComment == null || docComment.isEmpty()) ? null : docComment;
    }

    public String getProjectName() {
        return projectName;
    }

    public String getFilePath() {
        return filePath;
    }

    public String getName() {
        return name;
    }

    public String getKind() {
        return kind;
    }

    public int getStartLine() {
        return startLine;
    }

    public int getEndLine() {
        return endLine;
    }

    public String getText() {
        return text;
    }

    /**
     * Returns the doc comment block that immediately precedes the method
     * declaration (contiguous {@code //} lines, annotations skipped), or
     * {@code null} when the method has no doc block.
     */
    public String getDocComment() {
        return docComment;
    }
}
