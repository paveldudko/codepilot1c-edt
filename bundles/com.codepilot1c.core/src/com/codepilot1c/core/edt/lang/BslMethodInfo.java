package com.codepilot1c.core.edt.lang;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * BSL method outline entry.
 */
public class BslMethodInfo {

    private final String name;
    private final String kind;
    private final int startLine;
    private final int endLine;
    private final boolean export;
    private final boolean async;
    private final boolean event;
    private final boolean used;
    private final List<BslMethodParamInfo> params;
    private final List<String> pragmas;
    private final String documentation;
    /** Target of the doc-comment {@code См.} / {@code See} link EDT would consider, if any. */
    private final String seeTarget;
    /**
     * Link chain EDT would walk from here, first hop first. Empty (and therefore omitted from the
     * payload) when EDT ignores the link — which happens exactly when this comment declares its own
     * {@code Параметры:} / {@code Возвращаемое значение:} section. See {@link BslDocSeeChain}.
     */
    private final List<String> seeChain;
    /** Only present when {@code true}, to keep the per-method payload small. */
    private final Boolean seeChainTruncated;
    /** Only present when {@code true}, to keep the per-method payload small. */
    private final Boolean seeChainCrossModule;

    public BslMethodInfo(
            String name,
            String kind,
            int startLine,
            int endLine,
            boolean export,
            boolean async,
            boolean event,
            boolean used,
            List<BslMethodParamInfo> params,
            List<String> pragmas,
            String documentation) {
        this(name, kind, startLine, endLine, export, async, event, used, params, pragmas, documentation,
                null, null, false, false);
    }

    public BslMethodInfo(
            String name,
            String kind,
            int startLine,
            int endLine,
            boolean export,
            boolean async,
            boolean event,
            boolean used,
            List<BslMethodParamInfo> params,
            List<String> pragmas,
            String documentation,
            String seeTarget,
            List<String> seeChain,
            boolean seeChainTruncated,
            boolean seeChainCrossModule) {
        this.name = name;
        this.kind = kind;
        this.startLine = startLine;
        this.endLine = endLine;
        this.export = export;
        this.async = async;
        this.event = event;
        this.used = used;
        this.params = new ArrayList<>(params != null ? params : List.of());
        this.pragmas = new ArrayList<>(pragmas != null ? pragmas : List.of());
        this.documentation = documentation;
        this.seeTarget = seeTarget;
        this.seeChain = seeChain == null || seeChain.isEmpty() ? null : new ArrayList<>(seeChain);
        this.seeChainTruncated = seeChainTruncated ? Boolean.TRUE : null;
        this.seeChainCrossModule = seeChainCrossModule ? Boolean.TRUE : null;
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

    public boolean isExport() {
        return export;
    }

    public boolean isAsync() {
        return async;
    }

    public boolean isEvent() {
        return event;
    }

    public boolean isUsed() {
        return used;
    }

    public List<BslMethodParamInfo> getParams() {
        return Collections.unmodifiableList(params);
    }

    public List<String> getPragmas() {
        return Collections.unmodifiableList(pragmas);
    }

    public String getDocumentation() {
        return documentation;
    }

    public String getSeeTarget() {
        return seeTarget;
    }

    public List<String> getSeeChain() {
        return seeChain == null ? List.of() : Collections.unmodifiableList(seeChain);
    }

    public boolean isSeeChainTruncated() {
        return Boolean.TRUE.equals(seeChainTruncated);
    }

    public boolean isSeeChainCrossModule() {
        return Boolean.TRUE.equals(seeChainCrossModule);
    }
}
