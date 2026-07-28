package com.codepilot1c.core.edt.lang;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the {@code См.} / {@code See} link out of a BSL documentation comment and walks the link
 * chain inside a single module.
 *
 * <p>Deliberately pure text logic — no EDT/EMF dependency — so the whole contract below is covered
 * by plain JUnit instead of a live EDT run.</p>
 *
 * <h2>What EDT actually does</h2>
 * <p>Mirrors {@code BslDocumentationComment} of {@code com._1c.g5.v8.dt.bsl.comment} (verified
 * against EDT 2025.2.3). Contrary to the common assumption, EDT does <b>not</b> stop after one hop:
 * {@code computeReturnTypes} / {@code computeParameterTypes} recurse into the linked comment and
 * guard against loops with an {@code alreadyProcessingMethods} set. What makes it <em>look</em> like
 * a single hop is the condition guarding the recursion — the chain is only followed while every
 * comment on it is a <b>bare link</b>:</p>
 * <ul>
 * <li>the link must be the <b>last part of the description</b> (any text after it ends the chain),
 * and</li>
 * <li>the comment must <b>not declare its own</b> {@code Параметры:} / {@code Parameters:} or
 * {@code Возвращаемое значение:} / {@code Returns:} section.</li>
 * </ul>
 * <p>The first intermediate comment that declares one of those sections wins outright: EDT takes the
 * types from it and never looks at its own {@code См.} link. So type documentation belongs on the
 * <em>root</em> of a link chain, and callers should link to that root rather than to a middleman.</p>
 *
 * <h2>Deliberate deviations from EDT</h2>
 * <ul>
 * <li>The keyword must start at a word boundary. EDT matches {@code see} / {@code см.} as a raw
 * case-insensitive substring, so it also finds them glued to a preceding letter; requiring a
 * boundary only removes false positives.</li>
 * <li>A comment whose whole body sits under a section header (for example a leading
 * {@code Устарела.}) is reported as link-free, because the link is then not part of the main
 * description.</li>
 * </ul>
 *
 * <p>Everything else is faithful, including the quirks worth knowing about: the link text swallows a
 * trailing {@code .} (so {@code См. МойМетод.} links to {@code "МойМетод."}, which EDT then fails to
 * resolve), {@code См. также Модуль.Метод} links to {@code "также"} with the rest becoming trailing
 * text, and {@code См. Модуль.Метод()} stops at {@code (} leaving {@code ()} as trailing text — in
 * the last two cases EDT does not chain at all.</p>
 */
public final class BslDocSeeChain {

    /** Default cap on the number of hops the chain walk reports. */
    public static final int DEFAULT_MAX_DEPTH = 8;

    /**
     * The chaining contract in one sentence, shared by every tool that reports it so the wording
     * cannot drift between tool descriptions.
     */
    public static final String CONTRACT_HINT =
            "Doc-comment See/См. links are reported per method: EDT follows them transitively, but only" //$NON-NLS-1$
            + " while every hop is a bare trailing link — the first comment that declares its own" //$NON-NLS-1$
            + " Parameters:/Returns: (Параметры:/Возвращаемое значение:) section ends the chain there," //$NON-NLS-1$
            + " so put type documentation on the chain root and link to that root. Details in" //$NON-NLS-1$
            + " knowledge/edt-gotchas.md."; //$NON-NLS-1$

    /**
     * Locates a documentation link: the {@code см.} / {@code see} keyword at a word boundary,
     * followed by the link text. The link-text character set repeats EDT's terminator rule — an
     * identifier character or one of {@code . : /} continues the link, anything else ends it.
     */
    private static final Pattern LINK_PATTERN = Pattern.compile(
            "(?<![\\p{L}\\p{N}_$])(см\\.|see)\\h*([\\p{L}\\p{N}_$.:/]*)", //$NON-NLS-1$
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Section headers that make EDT take types from this comment and drop its link. */
    private static final List<String> TYPE_SECTIONS = List.of(
            "parameters:", //$NON-NLS-1$
            "параметры:", //$NON-NLS-1$
            "returns:", //$NON-NLS-1$
            "return value:", //$NON-NLS-1$
            "возвращаемое значение:"); //$NON-NLS-1$

    /** Section headers that end the description but leave the link chain intact. */
    private static final List<String> OTHER_SECTIONS = List.of(
            "example:", //$NON-NLS-1$
            "пример:", //$NON-NLS-1$
            "call options:", //$NON-NLS-1$
            "варианты вызова:", //$NON-NLS-1$
            "deprecated.", //$NON-NLS-1$
            "устарела."); //$NON-NLS-1$

    private BslDocSeeChain() {
        // utility class
    }

    /**
     * The documentation link of one comment.
     *
     * @param target link text exactly as written; EDT keeps a trailing {@code .} inside it, so it is
     *            kept here too
     * @param trailing the link is the last part of the description — EDT only chains through
     *            trailing links
     * @param typeSectionPresent the comment declares its own {@code Параметры:} /
     *            {@code Возвращаемое значение:} section, which ends the chain at this comment
     */
    public record SeeLink(String target, boolean trailing, boolean typeSectionPresent) {

        /** Whether EDT would follow this link when inferring types. */
        public boolean followed() {
            return trailing && !typeSectionPresent;
        }
    }

    /**
     * Outcome of the in-module chain walk.
     *
     * @param chain link targets starting at the first hop, in order, as written in the comments; a
     *            name repeated at the end means the chain is cyclic and stopped there, exactly where
     *            EDT's own loop guard stops
     * @param truncated the chain is longer than the depth limit and was cut
     * @param crossModule the walk left this module — the last target is either qualified
     *            ({@code Модуль.Метод}) or a name this module does not declare, so nothing beyond it
     *            is reported
     */
    public record ChainResult(List<String> chain, boolean truncated, boolean crossModule) {

        public ChainResult {
            chain = chain != null ? List.copyOf(chain) : List.of();
        }

        static ChainResult none() {
            return new ChainResult(List.of(), false, false);
        }

        public boolean isEmpty() {
            return chain.isEmpty();
        }
    }

    /**
     * Case-insensitive index of the documentation comments of one module, built once per module.
     *
     * <p>Membership and documentation are separate questions: a method declared without any comment
     * still belongs to the module, and mixing the two would report it as an outside target.</p>
     */
    public static final class ModuleDocs {

        private static final ModuleDocs EMPTY = new ModuleDocs(Map.of());

        private final Map<String, String> byName;

        private ModuleDocs(Map<String, String> byName) {
            this.byName = byName;
        }

        public static ModuleDocs empty() {
            return EMPTY;
        }

        /**
         * @param documentationByMethodName method name to its raw documentation comment; a
         *            {@code null} comment means "declared here, undocumented" and is kept as an
         *            empty string
         */
        public static ModuleDocs of(Map<String, String> documentationByMethodName) {
            if (documentationByMethodName == null || documentationByMethodName.isEmpty()) {
                return EMPTY;
            }
            Map<String, String> index = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : documentationByMethodName.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                String key = normalizeName(entry.getKey());
                if (!index.containsKey(key)) {
                    index.put(key, entry.getValue() != null ? entry.getValue() : ""); //$NON-NLS-1$
                }
            }
            return new ModuleDocs(index);
        }

        boolean declares(String methodName) {
            return methodName != null && byName.containsKey(normalizeName(methodName));
        }

        String documentationOf(String methodName) {
            return methodName != null ? byName.get(normalizeName(methodName)) : null;
        }
    }

    /**
     * Parses the chain-relevant link of a single documentation comment: the last link of the main
     * description, together with the two facts that decide whether EDT follows it.
     *
     * @param documentation raw comment text, {@code //} markers optional, one line per line
     * @return the link, or empty when the description carries none
     */
    public static Optional<SeeLink> parse(String documentation) {
        if (documentation == null || documentation.isBlank()) {
            return Optional.empty();
        }
        String[] lines = documentation.split("\r\n|\r|\n", -1); //$NON-NLS-1$
        for (int i = 0; i < lines.length; i++) {
            lines[i] = normalizeLine(lines[i]);
        }

        int descriptionEnd = -1;
        boolean typeSectionPresent = false;
        for (int i = 0; i < lines.length; i++) {
            SectionKind kind = sectionKindOf(lines[i]);
            if (kind == SectionKind.NONE) {
                continue;
            }
            if (descriptionEnd < 0) {
                descriptionEnd = i;
            }
            if (kind == SectionKind.TYPES) {
                typeSectionPresent = true;
            }
        }
        if (descriptionEnd < 0) {
            descriptionEnd = lines.length;
        }

        int linkLine = -1;
        LinkMatch link = null;
        for (int i = 0; i < descriptionEnd; i++) {
            LinkMatch found = lastLinkInLine(lines[i]);
            if (found != null) {
                linkLine = i;
                link = found;
            }
        }
        if (link == null) {
            return Optional.empty();
        }

        boolean trailing = isBlank(lines[linkLine].substring(link.end()));
        for (int i = linkLine + 1; trailing && i < descriptionEnd; i++) {
            trailing = isBlank(lines[i]);
        }
        return Optional.of(new SeeLink(link.target(), trailing, typeSectionPresent));
    }

    /**
     * The link target EDT would consider for this comment, that is the trailing one. A link buried
     * in prose is deliberately not reported: EDT does not chain through it either.
     */
    public static Optional<String> effectiveTarget(String documentation) {
        return parse(documentation).filter(SeeLink::trailing).map(SeeLink::target);
    }

    /** @see #resolveChain(String, String, ModuleDocs, int) */
    public static ChainResult resolveChain(String methodName, String documentation, ModuleDocs moduleDocs) {
        return resolveChain(methodName, documentation, moduleDocs, DEFAULT_MAX_DEPTH);
    }

    /**
     * Walks the link chain the way EDT would, but iteratively and only as far as this module
     * reaches. The walk stops on the first comment that declares its own {@code Параметры:} /
     * {@code Возвращаемое значение:} section, on a target outside the module, on a loop, and on the
     * depth limit.
     *
     * @param methodName the method the chain starts from; seeds the loop guard, mirroring EDT's
     *            {@code alreadyProcessingMethods.add(methodURI)}
     * @param documentation that method's raw documentation comment
     * @param moduleDocs the module's other comments, or {@code null} for none
     * @param maxDepth hop cap; anything not positive falls back to {@link #DEFAULT_MAX_DEPTH}
     * @return an empty chain when EDT would not follow the link at all
     */
    public static ChainResult resolveChain(String methodName, String documentation, ModuleDocs moduleDocs,
            int maxDepth) {
        Optional<SeeLink> start = parse(documentation);
        if (start.isEmpty() || !start.get().followed()) {
            return ChainResult.none();
        }
        ModuleDocs docs = moduleDocs != null ? moduleDocs : ModuleDocs.empty();
        int limit = maxDepth > 0 ? maxDepth : DEFAULT_MAX_DEPTH;

        List<String> chain = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        if (methodName != null) {
            visited.add(normalizeName(methodName));
        }

        String target = start.get().target();
        boolean truncated = false;
        boolean crossModule = false;
        while (true) {
            if (chain.size() >= limit) {
                truncated = true;
                break;
            }
            chain.add(target);
            if (!visited.add(normalizeName(target))) {
                break;
            }
            if (!docs.declares(target)) {
                crossModule = true;
                break;
            }
            Optional<SeeLink> next = parse(docs.documentationOf(target));
            if (next.isEmpty() || !next.get().followed()) {
                break;
            }
            target = next.get().target();
        }
        return new ChainResult(chain, truncated, crossModule);
    }

    private static LinkMatch lastLinkInLine(String line) {
        if (line == null || line.isEmpty() || line.trim().startsWith("@")) { //$NON-NLS-1$
            // EDT turns a tag line into a single TagPart and never scans it for links.
            return null;
        }
        LinkMatch last = null;
        Matcher matcher = LINK_PATTERN.matcher(line);
        int from = 0;
        while (from <= line.length() && matcher.find(from)) {
            LinkMatch candidate = toMatch(line, matcher);
            if (candidate != null) {
                last = candidate;
            }
            int resume = candidate != null ? candidate.end() : matcher.end();
            from = Math.max(matcher.start(1) + 1, resume);
        }
        return last;
    }

    private static LinkMatch toMatch(String line, Matcher matcher) {
        int keywordIndex = matcher.start(1);
        int afterKeyword = matcher.end(1);
        String target;
        int end;
        if (findOpenParenBefore(line, keywordIndex) >= 0) {
            // "(См. Цель)" — the whole bracketed span is the link.
            int close = line.indexOf(')', afterKeyword);
            if (close >= 0) {
                target = line.substring(afterKeyword, close).trim();
                end = close + 1;
            } else {
                target = line.substring(afterKeyword).trim();
                end = line.length();
            }
        } else {
            target = matcher.group(2).trim();
            end = matcher.end(2);
        }
        return target.isEmpty() ? null : new LinkMatch(target, end);
    }

    private static int findOpenParenBefore(String line, int keywordIndex) {
        for (int i = keywordIndex - 1; i >= 0; i--) {
            char ch = line.charAt(i);
            if (ch == '(') {
                return i;
            }
            if (!Character.isWhitespace(ch)) {
                return -1;
            }
        }
        return -1;
    }

    /**
     * Brings a raw comment line to the shape EDT's parser works on: no {@code //} marker, trimmed.
     * {@link BslSemanticService} already hands over stripped lines; accepting both shapes keeps this
     * utility usable on raw comment text as well.
     */
    private static String normalizeLine(String line) {
        if (line == null) {
            return ""; //$NON-NLS-1$
        }
        String trimmed = line.trim();
        while (trimmed.startsWith("//")) { //$NON-NLS-1$
            trimmed = trimmed.substring(2).trim();
        }
        return trimmed;
    }

    private static SectionKind sectionKindOf(String line) {
        String normalized = line != null ? line.toLowerCase(Locale.ROOT) : ""; //$NON-NLS-1$
        if (normalized.isEmpty()) {
            return SectionKind.NONE;
        }
        for (String keyword : TYPE_SECTIONS) {
            if (normalized.startsWith(keyword)) {
                return SectionKind.TYPES;
            }
        }
        for (String keyword : OTHER_SECTIONS) {
            if (normalized.startsWith(keyword)) {
                return SectionKind.OTHER;
            }
        }
        return SectionKind.NONE;
    }

    private static String normalizeName(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT); //$NON-NLS-1$
    }

    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    private enum SectionKind {
        NONE, TYPES, OTHER
    }

    private record LinkMatch(String target, int end) {
    }
}
