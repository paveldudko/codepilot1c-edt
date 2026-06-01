package com.codepilot1c.core.edt.metadata.eol;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Pure, side-effect-free helpers for detecting and re-applying line-ending
 * conventions, plus resolving the project default from {@code .gitattributes}
 * and {@code .editorconfig}.
 *
 * <p>All methods are static and operate on strings — no filesystem or workspace
 * access — so the logic is unit-testable in a plain JVM. The {@code IFile}/
 * {@code IProject} orchestration lives in {@code EdtMetadataService}.</p>
 */
public final class EolNormalizer {

    private EolNormalizer() {
    }

    /**
     * Detect the dominant EOL style of {@code content}. Returns empty when the
     * text has no line terminators at all (a single-line or empty file), in
     * which case there is nothing to preserve. Mixed content resolves to
     * whichever style occurs more often; {@link EolStyle#LF} wins a tie.
     */
    public static Optional<EolStyle> detect(String content) {
        if (content == null || content.isEmpty()) {
            return Optional.empty();
        }
        int crlf = 0;
        int loneLf = 0;
        int loneCr = 0;
        int len = content.length();
        for (int i = 0; i < len; i++) {
            char c = content.charAt(i);
            if (c == '\r') {
                if (i + 1 < len && content.charAt(i + 1) == '\n') {
                    crlf++;
                    i++;
                } else {
                    loneCr++;
                }
            } else if (c == '\n') {
                loneLf++;
            }
        }
        int lf = loneLf + loneCr; // treat a stray CR as an LF-ish single-char terminator
        if (crlf == 0 && lf == 0) {
            return Optional.empty();
        }
        return Optional.of(crlf > lf ? EolStyle.CRLF : EolStyle.LF);
    }

    /**
     * Re-terminate every line in {@code content} with {@code target}. Idempotent:
     * applying the same target twice yields identical output. A trailing newline
     * (or its absence) is preserved; only the terminator characters change.
     */
    public static String normalizeTo(String content, EolStyle target) {
        if (content == null || content.isEmpty()) {
            return content;
        }
        // Collapse every CRLF / lone CR / lone LF to a single LF first.
        String lf = content.replace("\r\n", "\n").replace('\r', '\n'); //$NON-NLS-1$ //$NON-NLS-2$
        if (target == EolStyle.LF) {
            return lf;
        }
        return lf.replace("\n", "\r\n"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Resolve the EOL a file named {@code fileName} should use according to the
     * given {@code .gitattributes} content. Honors an explicit {@code eol=lf} /
     * {@code eol=crlf}; the last matching pattern wins (git semantics). Patterns
     * without an explicit {@code eol} (plain {@code text}, {@code -text}, or
     * unmatched) contribute nothing.
     */
    public static Optional<EolStyle> fromGitattributes(String gitattributesContent, String fileName) {
        if (gitattributesContent == null || fileName == null) {
            return Optional.empty();
        }
        String baseName = baseName(fileName);
        Optional<EolStyle> result = Optional.empty();
        for (String rawLine : gitattributesContent.split("\\R")) { //$NON-NLS-1$
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) { //$NON-NLS-1$
                continue;
            }
            String[] tokens = line.split("\\s+"); //$NON-NLS-1$
            if (tokens.length < 2) {
                continue;
            }
            if (!globMatches(tokens[0], baseName, fileName)) {
                continue;
            }
            for (int i = 1; i < tokens.length; i++) {
                String attr = tokens[i].toLowerCase(java.util.Locale.ROOT);
                if (attr.equals("eol=lf")) { //$NON-NLS-1$
                    result = Optional.of(EolStyle.LF);
                } else if (attr.equals("eol=crlf")) { //$NON-NLS-1$
                    result = Optional.of(EolStyle.CRLF);
                }
            }
        }
        return result;
    }

    /**
     * Resolve the EOL a file named {@code fileName} should use according to the
     * given {@code .editorconfig} content. Honors {@code end_of_line = lf|crlf}
     * in the last matching section. Section globs support {@code *}, {@code ?},
     * and simple {@code {a,b}} brace lists.
     */
    public static Optional<EolStyle> fromEditorconfig(String editorconfigContent, String fileName) {
        if (editorconfigContent == null || fileName == null) {
            return Optional.empty();
        }
        String baseName = baseName(fileName);
        Optional<EolStyle> result = Optional.empty();
        boolean sectionMatches = false;
        for (String rawLine : editorconfigContent.split("\\R")) { //$NON-NLS-1$
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) { //$NON-NLS-1$ //$NON-NLS-2$
                continue;
            }
            if (line.startsWith("[") && line.endsWith("]")) { //$NON-NLS-1$ //$NON-NLS-2$
                String pattern = line.substring(1, line.length() - 1).trim();
                sectionMatches = editorconfigSectionMatches(pattern, baseName, fileName);
                continue;
            }
            if (!sectionMatches) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = line.substring(0, eq).trim().toLowerCase(java.util.Locale.ROOT);
            String value = line.substring(eq + 1).trim().toLowerCase(java.util.Locale.ROOT);
            if (key.equals("end_of_line")) { //$NON-NLS-1$
                if (value.equals("lf")) { //$NON-NLS-1$
                    result = Optional.of(EolStyle.LF);
                } else if (value.equals("crlf")) { //$NON-NLS-1$
                    result = Optional.of(EolStyle.CRLF);
                }
            }
        }
        return result;
    }

    private static boolean editorconfigSectionMatches(String pattern, String baseName, String fileName) {
        // Expand a single {a,b,c} brace list (the common .editorconfig form).
        int open = pattern.indexOf('{');
        int close = pattern.indexOf('}', open + 1);
        if (open >= 0 && close > open) {
            String prefix = pattern.substring(0, open);
            String suffix = pattern.substring(close + 1);
            for (String alt : pattern.substring(open + 1, close).split(",")) { //$NON-NLS-1$
                if (globMatches(prefix + alt.trim() + suffix, baseName, fileName)) {
                    return true;
                }
            }
            return false;
        }
        return globMatches(pattern, baseName, fileName);
    }

    /**
     * Match a git/editorconfig-style glob. A pattern without a slash matches the
     * base name anywhere in the tree (git behavior); a pattern with a slash
     * matches the full relative path.
     */
    private static boolean globMatches(String glob, String baseName, String fileName) {
        if (glob == null || glob.isEmpty()) {
            return false;
        }
        boolean anchored = glob.contains("/"); //$NON-NLS-1$
        String target = anchored ? fileName.replace('\\', '/') : baseName;
        String normalizedGlob = anchored && glob.startsWith("/") ? glob.substring(1) : glob; //$NON-NLS-1$
        return Pattern.compile(globToRegex(normalizedGlob)).matcher(target).matches();
    }

    private static String globToRegex(String glob) {
        StringBuilder sb = new StringBuilder("(?s)"); //$NON-NLS-1$
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> {
                    if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                        sb.append(".*"); //$NON-NLS-1$
                        i++;
                    } else {
                        sb.append("[^/]*"); //$NON-NLS-1$
                    }
                }
                case '?' -> sb.append("[^/]"); //$NON-NLS-1$
                case '.', '(', ')', '+', '|', '^', '$', '@', '%', '{', '}', '[', ']', '\\' ->
                        sb.append('\\').append(c);
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String baseName(String fileName) {
        String normalized = fileName.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }
}
