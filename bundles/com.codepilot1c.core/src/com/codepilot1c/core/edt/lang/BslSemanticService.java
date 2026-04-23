package com.codepilot1c.core.edt.lang;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.xtext.nodemodel.INode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.resource.EObjectAtOffsetHelper;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.resource.XtextResourceSet;

import com._1c.g5.v8.dt.bsl.model.DynamicFeatureAccess;
import com._1c.g5.v8.dt.bsl.model.FeatureAccess;
import com._1c.g5.v8.dt.bsl.model.FeatureEntry;
import com._1c.g5.v8.dt.bsl.model.FormalParam;
import com._1c.g5.v8.dt.bsl.model.Invocation;
import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.bsl.model.ModuleType;
import com._1c.g5.v8.dt.bsl.model.Pragma;
import com._1c.g5.v8.dt.bsl.model.Procedure;
import com._1c.g5.v8.dt.bsl.model.StaticFeatureAccess;
import com._1c.g5.v8.dt.bsl.model.Variable;
import com._1c.g5.v8.dt.bsl.resource.TypesComputer;
import com._1c.g5.v8.dt.mcore.Environmental;
import com._1c.g5.v8.dt.mcore.NamedElement;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.mcore.util.Environments;
import com.codepilot1c.core.edt.ast.ContentAssistRequest;
import com.codepilot1c.core.edt.ast.ContentAssistResult;
import com.codepilot1c.core.edt.ast.EdtAstErrorCode;
import com.codepilot1c.core.edt.ast.EdtAstException;
import com.codepilot1c.core.edt.ast.EdtContentAssistService;
import com.codepilot1c.core.edt.ast.EdtServiceGateway;
import com.codepilot1c.core.edt.ast.ProjectReadinessChecker;
import com.codepilot1c.core.edt.platformdoc.EdtPlatformDocumentationService;
import com.codepilot1c.core.edt.platformdoc.PlatformDocumentationException;
import com.codepilot1c.core.edt.platformdoc.PlatformDocumentationRequest;
import com.codepilot1c.core.edt.platformdoc.PlatformDocumentationResult;
import com.codepilot1c.core.edt.platformdoc.PlatformMemberFilter;
import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.util.TimeBoundedCall;

/**
 * Semantic BSL model service for symbol/type/scope extraction at source position.
 */
public class BslSemanticService {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(BslSemanticService.class);

    private static final int RESOURCE_SET_RETRY_ATTEMPTS = 3;
    private static final long RESOURCE_SET_RETRY_BASE_DELAY_MS = 200L;
    private static final double RESOURCE_SET_RETRY_MULTIPLIER = 3.0;
    private static final long CONTENT_ASSIST_FALLBACK_TIMEOUT_MS = 5_000L;
    private static final long PLATFORM_DOC_MEMBERS_TIMEOUT_MS = 5_000L;

    private final EdtServiceGateway gateway;
    private final ProjectReadinessChecker readinessChecker;
    private final EdtPlatformDocumentationService platformDocService;
    private final EdtContentAssistService contentAssistService;

    /**
     * Per-project ResourceSet cache. {@code BmAwareResourceSetProvider.get(project)}
     * blocks ~30 s in EDT 2025.1.4+ (observed on AM): apparently waits for a
     * BmEditingContext readiness latch with a default 30 s timeout and returns
     * a fresh ResourceSet each call (unique identityHash). Without caching,
     * every MCP tool call paid the 30 s penalty even on warm workspaces.
     *
     * <p>Static so the cache is shared across every {@code BslSemanticService}
     * instance — each MCP tool (list_methods, get_method_body,
     * type_at_position, scope_members, …) constructs its own service, and a
     * per-instance cache made the first call of each tool re-pay the 30 s
     * timeout. Static shares the warm ResourceSet across all tools.</p>
     *
     * <p>Caching the first successful ResourceSet per project brings repeated
     * calls down to milliseconds. Invalidation on resource changes is not
     * yet wired — Xtext keeps its own per-ResourceSet state fresh via the
     * platform listeners attached to it at construction.</p>
     */
    private static final Map<IProject, ResourceSet> resourceSetCache = new ConcurrentHashMap<>();

    public BslSemanticService() {
        this(new EdtServiceGateway());
    }

    public BslSemanticService(EdtServiceGateway gateway) {
        this.gateway = gateway;
        this.readinessChecker = new ProjectReadinessChecker(gateway);
        this.platformDocService = new EdtPlatformDocumentationService();
        this.contentAssistService = new EdtContentAssistService(gateway, readinessChecker);
    }

    public BslSymbolResult getSymbolAtPosition(BslPositionRequest request) {
        request.validate();
        return executeRead(request.getProjectName(), () -> doGetSymbolAtPosition(request));
    }

    BslSymbolResult doGetSymbolAtPosition(BslPositionRequest request) {
        PositionContext context = resolveContext(request);
        EObject resolvedElement = resolveSemanticTarget(context.element(), resolveCurrentEnvironments(context.element()));
        EObject symbolElement = resolvedElement != null ? resolvedElement : context.element();

        String symbolName = extractName(symbolElement);

        INode node = NodeModelUtils.findActualNodeFor(context.element());
        String symbolText = node != null ? safeTrim(NodeModelUtils.getTokenText(node)) : null;
        if (symbolName == null) {
            symbolName = symbolText;
        }

        int[] start = lineColFromOffset(context.text(), node != null ? node.getOffset() : context.offset());
        int[] end = lineColFromOffset(context.text(), node != null ? node.getEndOffset() : context.offset());
        EObject container = symbolElement.eContainer();

        return new BslSymbolResult(
                request.getProjectName(),
                request.getFilePath(),
                request.getLine(),
                request.getColumn(),
                context.offset(),
                symbolKind(symbolElement),
                symbolName,
                symbolText,
                symbolElement.eClass().getName(),
                EcoreUtil.getURI(symbolElement).toString(),
                container != null ? container.eClass().getName() : null,
                container != null ? extractName(container) : null,
                start[0],
                start[1],
                end[0],
                end[1]);
    }

    public BslTypeResult getTypeAtPosition(BslPositionRequest request) {
        request.validate();
        return executeRead(request.getProjectName(), () -> doGetTypeAtPosition(request));
    }

    BslTypeResult doGetTypeAtPosition(BslPositionRequest request) {
        try {
            PositionContext context = resolveContext(request);
            List<BslTypeResult.TypeInfo> types = computeTypes(context);
            return new BslTypeResult(
                    request.getProjectName(),
                    request.getFilePath(),
                    request.getLine(),
                    request.getColumn(),
                    context.offset(),
                    context.element().eClass().getName(),
                    types);
        } catch (EdtAstException e) {
            if (!canFallbackToContentAssist(e)) {
                throw e;
            }
            return new BslTypeResult(
                    request.getProjectName(),
                    request.getFilePath(),
                    request.getLine(),
                    request.getColumn(),
                    -1,
                    "Unavailable", //$NON-NLS-1$
                    List.of());
        }
    }

    public BslScopeMembersResult getScopeMembers(BslScopeMembersRequest request) {
        request.validate();
        return executeRead(request.getProjectName(), () -> doGetScopeMembers(request));
    }

    BslScopeMembersResult doGetScopeMembers(BslScopeMembersRequest request) {
        long tStart = System.nanoTime();
        LOG.debug("getScopeMembers: project=%s file=%s pos=%d:%d limit=%d", //$NON-NLS-1$
                request.getProjectName(), request.getFilePath(),
                request.getLine(), request.getColumn(), request.getLimit());

        List<BslTypeResult.TypeInfo> types = List.of();
        long tCtx = System.nanoTime();
        try {
            PositionContext context = resolveContext(request.toPositionRequest());
            LOG.debug("getScopeMembers: resolveContext took %d ms", //$NON-NLS-1$
                    (System.nanoTime() - tCtx) / 1_000_000);
            long tTypes = System.nanoTime();
            types = computeTypes(context);
            LOG.debug("getScopeMembers: computeTypes took %d ms, types.size=%d", //$NON-NLS-1$
                    (System.nanoTime() - tTypes) / 1_000_000, types.size());
        } catch (EdtAstException e) {
            LOG.debug("getScopeMembers: resolveContext/computeTypes threw %s: %s", //$NON-NLS-1$
                    e.getCode(), e.getMessage());
            if (!canFallbackToContentAssist(e)) {
                throw e;
            }
        }

        List<String> resolvedTypes = new ArrayList<>();
        for (BslTypeResult.TypeInfo type : types) {
            String display = displayTypeName(type, request.getLanguageNormalized());
            if (display != null && !display.isBlank() && !resolvedTypes.contains(display)) {
                resolvedTypes.add(display);
            }
        }

        long tDoc = System.nanoTime();
        List<BslScopeMembersResult.MemberItem> all = new ArrayList<>();
        // Budget the platform-doc lookup: on AM-scale projects it can hit
        // the same ~30s BM-readiness wait that plagued resolveResourceSet
        // (observed on the Alerts register's RecordManager resolved type).
        // If we miss the budget, fall through to the content-assist
        // fallback below — pragmatically better than a 30s stall.
        List<BslTypeResult.TypeInfo> typesForDoc = types;
        List<BslScopeMembersResult.MemberItem> docMembers = TimeBoundedCall.callWithin(
                () -> collectMembersFromPlatformDoc(request, typesForDoc),
                PLATFORM_DOC_MEMBERS_TIMEOUT_MS,
                List.of(),
                "platformDoc", //$NON-NLS-1$
                LOG::debug);
        all.addAll(docMembers);
        long docMs = (System.nanoTime() - tDoc) / 1_000_000;
        LOG.debug("getScopeMembers: collectMembersFromPlatformDoc took %d ms, n=%d (budget=%d ms)", //$NON-NLS-1$
                docMs, all.size(), PLATFORM_DOC_MEMBERS_TIMEOUT_MS);
        if (all.isEmpty()) {
            long tCa = System.nanoTime();
            all.addAll(collectMembersFromContentAssist(request));
            LOG.debug("getScopeMembers: content-assist fallback took %d ms, n=%d", //$NON-NLS-1$
                    (System.nanoTime() - tCa) / 1_000_000, all.size());
        }
        LOG.debug("getScopeMembers: total elapsed %d ms", //$NON-NLS-1$
                (System.nanoTime() - tStart) / 1_000_000);

        int total = all.size();
        int from = Math.min(request.getOffset(), total);
        int to = Math.min(from + request.getLimit(), total);
        List<BslScopeMembersResult.MemberItem> page = all.subList(from, to);

        return new BslScopeMembersResult(
                request.getProjectName(),
                request.getFilePath(),
                request.getLine(),
                request.getColumn(),
                resolvedTypes,
                total,
                to < total,
                page);
    }

    public BslModuleMethodsResult listMethods(BslModuleMethodsRequest request) {
        request.validate();
        return executeRead(request.getProjectName(), () -> doListMethods(request));
    }

    BslModuleMethodsResult doListMethods(BslModuleMethodsRequest request) {
        ModuleContext context = resolveModuleContext(request.getProjectName(), request.getFilePath());
        LineIndex lineIndex = new LineIndex(context.text());
        List<ResolvedMethod> methods = collectMethods(context.module(), context.text(), lineIndex);

        String kindFilter = request.normalizedKind();
        String nameFilter = request.normalizedNameContains();
        List<ResolvedMethod> filtered = new ArrayList<>();
        for (ResolvedMethod method : methods) {
            if (!"any".equals(kindFilter) && !kindFilter.equals(method.kind())) { //$NON-NLS-1$
                continue;
            }
            if (!nameFilter.isEmpty()) {
                String normalizedName = normalize(method.name());
                if (!normalizedName.contains(nameFilter)) {
                    continue;
                }
            }
            filtered.add(method);
        }

        int total = filtered.size();
        int from = Math.min(request.getOffset(), total);
        int to = Math.min(from + request.getLimit(), total);
        boolean compact = request.isCompact();
        List<BslMethodInfo> page = new ArrayList<>();
        for (int i = from; i < to; i++) {
            page.add(filtered.get(i).toInfo(compact));
        }

        return new BslModuleMethodsResult(
                request.getProjectName(),
                request.getFilePath(),
                total,
                to < total,
                page);
    }

    public BslMethodBodyResult getMethodBody(BslMethodBodyRequest request) {
        request.validate();
        return executeRead(request.getProjectName(), () -> doGetMethodBody(request));
    }

    BslMethodBodyResult doGetMethodBody(BslMethodBodyRequest request) {
        ModuleContext context = resolveModuleContext(request.getProjectName(), request.getFilePath());
        LineIndex lineIndex = new LineIndex(context.text());
        List<ResolvedMethod> methods = collectMethods(context.module(), context.text(), lineIndex);
        ResolvedMethod method = resolveSingleMethod(
                findMatchingMethods(methods, request.normalizedName(), request.normalizedKind(), request.getStartLine()),
                request.getName());
        // Extract the doc block from the method's declared start line, before any
        // context_lines expansion — context_lines are a display convenience and
        // should not change the "what is this method's doc comment" answer.
        String docComment = BslDocCommentExtractor.extract(context.text(), method.startLine());

        int startLine = method.startLine();
        int endLine = method.endLine();
        int startOffset = method.startOffset();
        int endOffset = method.endOffset();

        int contextLines = request.getContextLines();
        if (contextLines > 0) {
            int fromLine = Math.max(1, startLine - contextLines);
            int toLine = Math.min(lineIndex.totalLines(), endLine + contextLines);
            startOffset = lineIndex.startOffset(fromLine);
            endOffset = lineIndex.startOffset(toLine + 1);
            startLine = fromLine;
            endLine = toLine;
        }

        String text = sliceText(context.text(), startOffset, endOffset);
        return new BslMethodBodyResult(
                request.getProjectName(),
                request.getFilePath(),
                method.name(),
                method.kind(),
                startLine,
                endLine,
                text,
                docComment);
    }

    public BslMethodAnalysisResult analyzeMethod(BslMethodAnalysisRequest request) {
        request.validate();
        return executeRead(request.getProjectName(), () -> doAnalyzeMethod(request));
    }

    BslMethodAnalysisResult doAnalyzeMethod(BslMethodAnalysisRequest request) {
        ModuleContext context = resolveModuleContext(request.getProjectName(), request.getFilePath());
        LineIndex lineIndex = new LineIndex(context.text());
        List<ResolvedMethod> methods = collectMethods(context.module(), context.text(), lineIndex);
        ResolvedMethod method = resolveSingleMethod(
                findMatchingMethods(methods, request.normalizedMethodName(), request.normalizedKind(), request.getStartLine()),
                request.getMethodName());

        BslMethodAnalyzer.AnalysisSnapshot analysis =
                new BslMethodAnalyzer().analyze(method.model(), method.startLine(), method.endLine());
        return new BslMethodAnalysisResult(
                request.getProjectName(),
                request.getFilePath(),
                method.name(),
                method.kind(),
                method.startLine(),
                method.endLine(),
                analysis.loc(),
                analysis.cyclomatic(),
                analysis.branches(),
                analysis.loops(),
                analysis.tryExcepts(),
                analysis.unusedParams(),
                analysis.serverCallsInLoops(),
                analysis.callees(),
                analysis.callers(),
                analysis.warnings());
    }

    public BslModuleContextResult getModuleContext(BslModuleRequest request) {
        request.validate();
        return executeRead(request.getProjectName(), () -> doGetModuleContext(request));
    }

    BslModuleContextResult doGetModuleContext(BslModuleRequest request) {
        ModuleContext context = resolveModuleContext(request.getProjectName(), request.getFilePath());
        List<ResolvedMethod> methods = collectMethods(context.module(), context.text(), new LineIndex(context.text()));

        int exportedMethods = 0;
        int asyncMethods = 0;
        int eventMethods = 0;
        for (ResolvedMethod method : methods) {
            if (method.exportFlag()) {
                exportedMethods++;
            }
            if (method.asyncFlag()) {
                asyncMethods++;
            }
            if (method.eventFlag()) {
                eventMethods++;
            }
        }

        EObject owner = context.module().getOwner();
        return new BslModuleContextResult(
                request.getProjectName(),
                request.getFilePath(),
                normalizeModuleType(context.module().getModuleType()),
                owner != null ? owner.eClass().getName() : null,
                owner != null ? extractName(owner) : null,
                owner != null ? EcoreUtil.getURI(owner).toString() : null,
                collectPragmaTexts(context.module().getDefaultPragmas()),
                methods.size(),
                exportedMethods,
                asyncMethods,
                eventMethods);
    }

    public BslModuleExportsResult getModuleExports(BslModuleMethodsRequest request) {
        request.validate();
        return executeRead(request.getProjectName(), () -> doGetModuleExports(request));
    }

    BslModuleExportsResult doGetModuleExports(BslModuleMethodsRequest request) {
        ModuleContext context = resolveModuleContext(request.getProjectName(), request.getFilePath());
        List<ResolvedMethod> methods = collectMethods(context.module(), context.text(), new LineIndex(context.text()));

        String nameFilter = request.normalizedNameContains();
        List<BslMethodInfo> filtered = new ArrayList<>();
        for (ResolvedMethod method : methods) {
            if (!method.exportFlag()) {
                continue;
            }
            if (!nameFilter.isEmpty() && !normalize(method.name()).contains(nameFilter)) {
                continue;
            }
            filtered.add(method.toInfo());
        }

        int total = filtered.size();
        int from = Math.min(request.getOffset(), total);
        int to = Math.min(from + request.getLimit(), total);
        return new BslModuleExportsResult(
                request.getProjectName(),
                request.getFilePath(),
                total,
                to < total,
                filtered.subList(from, to));
    }

    private <T> T executeRead(String projectName, ReadOnlyTask<T> task) {
        IProject project = gateway.resolveProject(projectName);
        if (project == null) {
            return task.execute();
        }
        try {
            return gateway.getBmModelManager().executeReadOnlyTask(project, tx -> task.execute());
        } catch (EdtAstException e) {
            if (e.getCode() == EdtAstErrorCode.EDT_SERVICE_UNAVAILABLE) {
                return task.execute();
            }
            throw e;
        } catch (RuntimeException e) {
            throw new EdtAstException(
                    EdtAstErrorCode.EDT_SERVICE_UNAVAILABLE,
                    "Failed to execute BSL read transaction: " + e.getMessage(), true, e); //$NON-NLS-1$
        }
    }

    private PositionContext resolveContext(BslPositionRequest request) {
        long tProj = System.nanoTime();
        IProject project = gateway.resolveProject(request.getProjectName());
        long projMs = (System.nanoTime() - tProj) / 1_000_000;

        long tReady = System.nanoTime();
        readinessChecker.ensureReady(project);
        long readyMs = (System.nanoTime() - tReady) / 1_000_000;

        long tFile = System.nanoTime();
        IFile file = gateway.resolveSourceFile(project, request.getFilePath());
        long fileMs = (System.nanoTime() - tFile) / 1_000_000;
        if (file == null || !file.exists()) {
            throw new EdtAstException(EdtAstErrorCode.FILE_NOT_FOUND,
                    "File not found: " + request.getFilePath(), false); //$NON-NLS-1$
        }

        long tLoad = System.nanoTime();
        XtextResource resource = loadResource(project, file);
        long loadMs = (System.nanoTime() - tLoad) / 1_000_000;

        long tText = System.nanoTime();
        String text = readResourceText(resource, file);
        long textMs = (System.nanoTime() - tText) / 1_000_000;

        int offset = calculateOffset(text, request.getLine(), request.getColumn());

        long tRsp = System.nanoTime();
        IResourceServiceProvider rsp = resourceServiceProvider(file);
        EObjectAtOffsetHelper helper = rsp != null ? rsp.get(EObjectAtOffsetHelper.class) : null;
        if (helper == null) {
            helper = new EObjectAtOffsetHelper();
        }
        long rspMs = (System.nanoTime() - tRsp) / 1_000_000;

        long tResolve = System.nanoTime();
        EObject element = helper.resolveElementAt(resource, offset);
        if (element == null) {
            element = helper.resolveContainedElementAt(resource, offset);
        }
        long resolveMs = (System.nanoTime() - tResolve) / 1_000_000;
        if (element == null) {
            throw new EdtAstException(EdtAstErrorCode.INVALID_POSITION,
                    "Cannot resolve BSL element at line/column", false); //$NON-NLS-1$
        }

        LOG.debug("resolveContext phases ms: project=%d readiness=%d file=%d loadResource=%d" //$NON-NLS-1$
                + " readText=%d rsp=%d resolveElementAt=%d", //$NON-NLS-1$
                projMs, readyMs, fileMs, loadMs, textMs, rspMs, resolveMs);

        return new PositionContext(project, file, resource, rsp, element, offset, text);
    }

    private ModuleContext resolveModuleContext(String projectName, String filePath) {
        long tProj = System.nanoTime();
        IProject project = gateway.resolveProject(projectName);
        long projMs = (System.nanoTime() - tProj) / 1_000_000;

        long tReady = System.nanoTime();
        readinessChecker.ensureReady(project);
        long readyMs = (System.nanoTime() - tReady) / 1_000_000;

        long tFile = System.nanoTime();
        IFile file = gateway.resolveSourceFile(project, filePath);
        long fileMs = (System.nanoTime() - tFile) / 1_000_000;
        if (file == null || !file.exists()) {
            throw new EdtAstException(EdtAstErrorCode.FILE_NOT_FOUND,
                    "File not found: " + filePath, false); //$NON-NLS-1$
        }

        long tLoad = System.nanoTime();
        XtextResource resource = loadResource(project, file);
        long loadMs = (System.nanoTime() - tLoad) / 1_000_000;
        long tText = System.nanoTime();
        String text = readResourceText(resource, file);
        long textMs = (System.nanoTime() - tText) / 1_000_000;
        long tParse = System.nanoTime();
        EObject root = resource.getParseResult() != null
                ? resource.getParseResult().getRootASTElement()
                : null;
        long parseMs = (System.nanoTime() - tParse) / 1_000_000;
        LOG.debug("resolveModuleContext phases ms: project=%d readiness=%d file=%d loadResource=%d" //$NON-NLS-1$
                + " readText=%d getRoot=%d", //$NON-NLS-1$
                projMs, readyMs, fileMs, loadMs, textMs, parseMs);
        if (!(root instanceof Module module)) {
            throw new EdtAstException(EdtAstErrorCode.MODULE_PARSE_ERROR,
                    "Root AST element is not a BSL Module for: " + filePath, true); //$NON-NLS-1$
        }

        return new ModuleContext(project, file, resource, module, text);
    }

    private XtextResource loadResource(IProject project, IFile file) {
        long tRs = System.nanoTime();
        ResourceSet resourceSet = resolveResourceSet(project);
        long rsMs = (System.nanoTime() - tRs) / 1_000_000;
        URI uri = URI.createPlatformResourceURI(file.getFullPath().toString(), true);

        // Fast path: resource may already be in the ResourceSet (cached from a
        // prior call). getResource(uri, false) does NOT trigger load — returns
        // null if missing. Skipping load when already present avoids the BM
        // wait-for-ready blocking inside Xtext linker.
        long tCached = System.nanoTime();
        Resource cached = null;
        try {
            cached = resourceSet.getResource(uri, false);
        } catch (RuntimeException e) {
            LOG.debug("loadResource: getResource(false) threw %s: %s", //$NON-NLS-1$
                    e.getClass().getSimpleName(), e.getMessage());
        }
        long cachedMs = (System.nanoTime() - tCached) / 1_000_000;
        boolean wasCached = cached != null && cached.isLoaded();
        int rsId = System.identityHashCode(resourceSet);

        Resource resource = cached;
        long tLoad = 0;
        long loadMs = 0;
        if (!wasCached) {
            tLoad = System.nanoTime();
            resource = tryLoadResource(resourceSet, uri);
            loadMs = (System.nanoTime() - tLoad) / 1_000_000;
        }
        long tFallback = System.nanoTime();
        long fallbackMs = 0;
        if (resource == null) {
            resource = tryLoadResource(createStandaloneResourceSet(), uri);
            fallbackMs = (System.nanoTime() - tFallback) / 1_000_000;
        }

        LOG.debug("loadResource phases ms: resolveRs=%d cachedLookup=%d (wasCached=%s) load=%d" //$NON-NLS-1$
                + " fallback=%d rsId=%d", //$NON-NLS-1$
                rsMs, cachedMs, wasCached, loadMs, fallbackMs, rsId);

        if (resource == null) {
            throw new EdtAstException(EdtAstErrorCode.EDT_SERVICE_UNAVAILABLE,
                    "Failed to load BSL resource from EDT resource sets", true); //$NON-NLS-1$
        }
        if (!(resource instanceof XtextResource xtextResource)) {
            throw new EdtAstException(EdtAstErrorCode.EDT_SERVICE_UNAVAILABLE,
                    "Resource is not XtextResource: " + uri, false); //$NON-NLS-1$
        }
        return xtextResource;
    }

    private ResourceSet resolveResourceSet(IProject project) {
        // Fast path: reuse the ResourceSet obtained on a prior call. The BM
        // provider caches nothing and blocks ~30s in every .get() call on
        // AM-scale workspaces, so without this cache every tool invocation
        // paid the full timeout.
        ResourceSet cached = project != null ? resourceSetCache.get(project) : null;
        if (cached != null) {
            LOG.debug("resolveResourceSet: cache HIT, rsId=%d", System.identityHashCode(cached)); //$NON-NLS-1$
            return cached;
        }

        EdtAstException providerUnavailable = null;
        RuntimeException providerException = null;
        int attemptsTaken = 0;
        try {
            for (int attempt = 1; attempt <= RESOURCE_SET_RETRY_ATTEMPTS; attempt++) {
                attemptsTaken = attempt;
                ResourceSet resourceSet;
                try {
                    resourceSet = gateway.getResourceSetProvider().get(project);
                } catch (RuntimeException e) {
                    providerException = e;
                    break;
                }
                if (resourceSet != null) {
                    if (project != null) {
                        ResourceSet previous = resourceSetCache.putIfAbsent(project, resourceSet);
                        if (previous != null) {
                            resourceSet = previous;
                        }
                    }
                    LOG.debug("resolveResourceSet: cache MISS path=provider, rsId=%d after %d attempt(s)", //$NON-NLS-1$
                            System.identityHashCode(resourceSet), attempt);
                    return resourceSet;
                }
                if (attempt == RESOURCE_SET_RETRY_ATTEMPTS) {
                    break;
                }
                try {
                    long delay = (long) (RESOURCE_SET_RETRY_BASE_DELAY_MS
                            * Math.pow(RESOURCE_SET_RETRY_MULTIPLIER, attempt - 1));
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new EdtAstException(EdtAstErrorCode.EDT_SERVICE_UNAVAILABLE,
                            "Interrupted while waiting for EDT resource set initialization", true, e); //$NON-NLS-1$
                }
            }
        } catch (EdtAstException e) {
            if (e.getCode() != EdtAstErrorCode.EDT_SERVICE_UNAVAILABLE) {
                throw e;
            }
            providerUnavailable = e;
        }

        // Provider returned null or threw — fall back to a standalone
        // XtextResourceSet. Cache it too: the BM provider path is evidently
        // not working in this EDT instance, so calling it again just burns
        // another 30 seconds for the same empty result.
        String reason = providerException != null
                ? providerException.getClass().getSimpleName() + ": " + providerException.getMessage() //$NON-NLS-1$
                : providerUnavailable != null
                        ? "EdtAstException: " + providerUnavailable.getMessage() //$NON-NLS-1$
                        : "provider returned null after " + attemptsTaken + " attempt(s)"; //$NON-NLS-1$ //$NON-NLS-2$
        ResourceSet fallback = createStandaloneResourceSet();
        if (fallback != null) {
            if (project != null) {
                ResourceSet previous = resourceSetCache.putIfAbsent(project, fallback);
                if (previous != null) {
                    fallback = previous;
                }
            }
            LOG.debug("resolveResourceSet: cache MISS path=fallback, rsId=%d reason=%s", //$NON-NLS-1$
                    System.identityHashCode(fallback), reason);
            return fallback;
        }
        LOG.debug("resolveResourceSet: fallback FAILED, reason=%s", reason); //$NON-NLS-1$
        if (providerUnavailable != null) {
            throw providerUnavailable;
        }
        if (providerException != null) {
            throw providerException;
        }
        return new ResourceSetImpl();
    }

    /**
     * Drops the cached ResourceSet for the given project, forcing the next
     * call to re-obtain it from the provider. Intended for workspace-change
     * listeners once they are wired up — not currently invoked, kept for
     * later completeness.
     */
    public static void invalidateResourceSet(IProject project) {
        if (project != null) {
            resourceSetCache.remove(project);
        }
    }

    private Resource tryLoadResource(ResourceSet resourceSet, URI uri) {
        if (resourceSet == null || uri == null) {
            return null;
        }
        try {
            return resourceSet.getResource(uri, true);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private ResourceSet createStandaloneResourceSet() {
        try {
            return new XtextResourceSet();
        } catch (RuntimeException e) {
            return new ResourceSetImpl();
        }
    }

    private boolean canFallbackToContentAssist(EdtAstException e) {
        return e != null
                && e.getCode() == EdtAstErrorCode.EDT_SERVICE_UNAVAILABLE
                && e.isRecoverable();
    }

    private IResourceServiceProvider resourceServiceProvider(IFile file) {
        URI uri = URI.createPlatformResourceURI(file.getFullPath().toString(), true);
        return IResourceServiceProvider.Registry.INSTANCE.getResourceServiceProvider(uri);
    }

    private List<BslTypeResult.TypeInfo> computeTypes(PositionContext context) {
        IResourceServiceProvider rsp = context.resourceServiceProvider();
        if (rsp == null) {
            throw new EdtAstException(EdtAstErrorCode.EDT_SERVICE_UNAVAILABLE,
                    "IResourceServiceProvider is unavailable for BSL resource", false); //$NON-NLS-1$
        }
        TypesComputer typesComputer = rsp.get(TypesComputer.class);
        if (typesComputer == null) {
            throw new EdtAstException(EdtAstErrorCode.EDT_SERVICE_UNAVAILABLE,
                    "TypesComputer is unavailable for BSL resource", false); //$NON-NLS-1$
        }

        // Xtext lazy-resolves cross-references on demand. TypesComputer walks
        // StaticFeatureAccess.feature / Invocation resolutions to build the type
        // graph — if those xrefs are still proxies, it silently returns no
        // types. Force resolution of the module's xrefs before inference.
        long t0 = System.nanoTime();
        try {
            EcoreUtil.resolveAll(context.resource());
        } catch (RuntimeException e) {
            LOG.debug("computeTypes: resolveAll threw %s: %s", //$NON-NLS-1$
                    e.getClass().getSimpleName(), e.getMessage());
            // If resolution itself fails (e.g. missing platform index), fall
            // through — TypesComputer will return its own empty result.
        }
        LOG.debug("computeTypes: resolveAll took %d ms on element eClass=%s", //$NON-NLS-1$
                (System.nanoTime() - t0) / 1_000_000,
                context.element().eClass().getName());

        Environments envs = Environments.ALL;
        try {
            Environmental environmental = findContainerOfType(context.element(), Environmental.class);
            if (environmental != null) {
                envs = environmental.environments();
            }
        } catch (RuntimeException ignored) {
            // fallback to ALL
        }

        List<TypeItem> typeItems;
        try {
            typeItems = typesComputer.computeTypes(context.element(), envs);
        } catch (Exception e) {
            throw new EdtAstException(EdtAstErrorCode.INTERNAL_ERROR,
                    "Failed to compute BSL types: " + e.getMessage(), true, e); //$NON-NLS-1$
        }
        if (typeItems == null || typeItems.isEmpty()) {
            // TypesComputer has no flow analysis for BSL implicit variable
            // declarations (e.g. LHS of `X = SomeCall()`), nor for reads of
            // such variables later in the method. Fall back to a local AST
            // walk: compute types of the RHS of the enclosing/first
            // assignment for this symbol.
            typeItems = astWalkTypeFallback(context.element(), typesComputer);
            if (typeItems != null && !typeItems.isEmpty()) {
                LOG.debug("computeTypes: AST-walk fallback produced %d type(s) for %s", //$NON-NLS-1$
                        typeItems.size(), context.element().eClass().getName());
            } else {
                // Last-resort fallback for FormalParam: parse the type(s)
                // declared in the method's doc comment. Returns TypeInfo
                // directly (bypasses TypeItem) because we do not need to
                // re-resolve through EDT scope.
                List<BslTypeResult.TypeInfo> docTypes = docCommentParamTypeFallback(context);
                if (!docTypes.isEmpty()) {
                    LOG.debug("computeTypes: doc-comment fallback produced %d type(s) for %s", //$NON-NLS-1$
                            docTypes.size(), context.element().eClass().getName());
                    return docTypes;
                }
                return List.of();
            }
        }

        List<BslTypeResult.TypeInfo> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (TypeItem type : typeItems) {
            if (type == null) {
                continue;
            }
            String name = safeTrim(type.getName());
            String nameRu = safeTrim(type.getNameRu());
            String compositeId = type.getCompositeId() != null ? String.valueOf(type.getCompositeId()) : null;
            String key = normalize(firstNonBlank(name, nameRu, compositeId));
            if (!seen.add(key)) {
                continue;
            }
            result.add(new BslTypeResult.TypeInfo(name, nameRu, compositeId));
        }
        return result;
    }

    /**
     * Flow-sensitive BSL type fallback.
     *
     * <p>EDT's TypesComputer is declaration-driven: it resolves types from
     * the symbol table (explicit var declarations, formal params with doc
     * types, etc.) but does not infer the type of an implicit local from
     * its initialising assignment. In BSL almost every local variable is
     * implicit, so in practice the computer returns an empty list for:
     * <ul>
     *   <li>{@code NewRecord} on the LHS of {@code NewRecord = CreateRecordManager()};</li>
     *   <li>{@code NewRecord} on a subsequent read like {@code NewRecord.Date};</li>
     *   <li>the method access part of an invocation chain.</li>
     * </ul>
     *
     * <p>This fallback handles two common shapes using only the AST shape
     * (no direct BSL-model imports — we navigate via eClass name + eGet):
     * <ol>
     *   <li>If the element is the LHS of a SimpleStatement (assignment),
     *       compute types for the RHS.</li>
     *   <li>If the element is a StaticFeatureAccess whose resolved feature
     *       is a Variable, walk the enclosing method for the first
     *       SimpleStatement that assigns to the same Variable, then
     *       compute types for its RHS.</li>
     * </ol>
     */
    private List<TypeItem> astWalkTypeFallback(EObject element, TypesComputer tc) {
        if (element == null || tc == null) {
            return List.of();
        }

        // Case 1: element is left side of an assignment (SimpleStatement).
        EObject parent = element.eContainer();
        if (parent != null && "SimpleStatement".equals(parent.eClass().getName())) { //$NON-NLS-1$
            EObject left = eGetChild(parent, "left"); //$NON-NLS-1$
            if (left == element) {
                EObject right = eGetChild(parent, "right"); //$NON-NLS-1$
                if (right != null) {
                    List<TypeItem> types = safeComputeTypes(tc, right);
                    if (!types.isEmpty()) {
                        return types;
                    }
                }
            }
        }

        // Case 2: element is a StaticFeatureAccess → find feature → find the
        // first assignment to it inside the enclosing method (or module).
        String eClass = element.eClass().getName();
        if ("StaticFeatureAccess".equals(eClass) || "DynamicFeatureAccess".equals(eClass)) { //$NON-NLS-1$ //$NON-NLS-2$
            EObject feature = eGetChild(element, "feature"); //$NON-NLS-1$
            if (feature != null && !feature.eIsProxy()) {
                EObject right = findFirstAssignedRight(element, feature);
                if (right != null) {
                    return safeComputeTypes(tc, right);
                }
            }
        }

        // Case 3: element IS the Variable (ImplicitVariable / ExplicitVariable).
        // Happens when Xtext resolves a FeatureAccess xref down to the
        // declaration node — e.g. bsl_type_at_position on `NewRecord` in
        // `NewRecord.Date = Date` returns the ImplicitVariable directly.
        // Similarly, scope_members on the `.` after `NewRecord.` resolves
        // the `source` of the DynamicFeatureAccess to the ImplicitVariable.
        // Look for the first assignment to this variable.
        if ("ImplicitVariable".equals(eClass) || "ExplicitVariable".equals(eClass) //$NON-NLS-1$ //$NON-NLS-2$
                || "Variable".equals(eClass)) { //$NON-NLS-1$
            EObject right = findFirstAssignedRight(element, element);
            if (right != null) {
                return safeComputeTypes(tc, right);
            }
        }

        return List.of();
    }

    private List<TypeItem> safeComputeTypes(TypesComputer tc, EObject target) {
        try {
            List<TypeItem> t = tc.computeTypes(target, Environments.ALL);
            return t != null ? t : List.of();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /**
     * Walks up from {@code usage} to the enclosing method (falls back to
     * resource root), then iterates descendants looking for the first
     * {@code SimpleStatement} that assigns to {@code variable}. Match is
     * done by variable name, not EMF identity — proxies and resolver
     * quirks make {@code ==} unreliable across Xtext loads. Returns the
     * {@code right} side of that assignment, or {@code null}.
     */
    private EObject findFirstAssignedRight(EObject usage, EObject variable) {
        String targetName = variableName(variable);
        if (targetName == null || targetName.isEmpty()) {
            return null;
        }
        EObject scope = usage;
        while (scope != null) {
            String name = scope.eClass().getName();
            if (name != null) {
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.contains("procedure") || lower.contains("function") || lower.equals("method")) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    break;
                }
            }
            scope = scope.eContainer();
        }
        if (scope == null) {
            scope = EcoreUtil.getRootContainer(usage);
        }
        if (scope == null) {
            return null;
        }
        java.util.Iterator<EObject> it = scope.eAllContents();
        while (it.hasNext()) {
            EObject node = it.next();
            if (node == null || node == usage) {
                continue;
            }
            if (!"SimpleStatement".equals(node.eClass().getName())) { //$NON-NLS-1$
                continue;
            }
            EObject left = eGetChild(node, "left"); //$NON-NLS-1$
            if (left == null) {
                continue;
            }
            // Name-based match covers all shapes we've observed:
            //   - left IS the Variable (first-assignment implicit decl).
            //   - left is a StaticFeatureAccess whose name/feature.name
            //     equals the variable name (subsequent reads).
            //   - left is a DynamicFeatureAccess (rare on LHS — e.g.
            //     array element assignment with a different source).
            String leftName = assignmentLeftName(left);
            if (leftName != null && leftName.equalsIgnoreCase(targetName)) {
                return eGetChild(node, "right"); //$NON-NLS-1$
            }
        }
        return null;
    }

    /** Extracts the name of a Variable (ImplicitVariable / ExplicitVariable / FormalParam). */
    private String variableName(EObject variable) {
        if (variable == null) {
            return null;
        }
        String name = getStringFeature(variable, "name"); //$NON-NLS-1$
        if (name == null || name.isEmpty()) {
            name = getStringFeature(variable, "nameRu"); //$NON-NLS-1$
        }
        return name;
    }

    /**
     * Extracts the textual name targeted by the LHS of an assignment.
     * Tries, in order: a StaticFeatureAccess's direct "name" feature;
     * the name of the resolved feature reference; the LHS node's own
     * "name" (handles the implicit-declaration case where left IS
     * the Variable).
     */
    private String assignmentLeftName(EObject left) {
        String name = getStringFeature(left, "name"); //$NON-NLS-1$
        if (name != null && !name.isEmpty()) {
            return name;
        }
        EObject feature = eGetChild(left, "feature"); //$NON-NLS-1$
        if (feature != null) {
            String fName = getStringFeature(feature, "name"); //$NON-NLS-1$
            if (fName == null || fName.isEmpty()) {
                fName = getStringFeature(feature, "nameRu"); //$NON-NLS-1$
            }
            if (fName != null && !fName.isEmpty()) {
                return fName;
            }
        }
        return getStringFeature(left, "nameRu"); //$NON-NLS-1$
    }

    /**
     * Doc-comment-driven type fallback for {@code FormalParam}.
     *
     * <p>When EDT's TypesComputer returns no types for a parameter
     * reference and the AST walk finds no assignment either (typical for
     * read-only method parameters), parse the owning method's doc
     * comment: the 1C convention declares parameter types in a
     * {@code Parameters:} / {@code Параметры:} block like
     * {@code //   ParamName - Type - description}. Returns
     * {@link BslTypeResult.TypeInfo} directly — we cannot synthesise a
     * {@link TypeItem} without access to EDT's type registry from this
     * path, so the caller must handle the short-circuit.</p>
     */
    private List<BslTypeResult.TypeInfo> docCommentParamTypeFallback(PositionContext context) {
        EObject element = context.element();
        if (element == null || !"FormalParam".equals(element.eClass().getName())) { //$NON-NLS-1$
            return List.of();
        }
        String paramName = firstNonBlank(
                getStringFeature(element, "name"), //$NON-NLS-1$
                getStringFeature(element, "nameRu")); //$NON-NLS-1$
        if (paramName == null || paramName.isBlank()) {
            return List.of();
        }
        EObject method = element.eContainer();
        while (method != null) {
            String name = method.eClass().getName();
            if (name != null) {
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.contains("procedure") || lower.contains("function") || lower.equals("method")) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    break;
                }
            }
            method = method.eContainer();
        }
        if (method == null) {
            return List.of();
        }
        INode methodNode = NodeModelUtils.getNode(method);
        if (methodNode == null) {
            return List.of();
        }
        int methodStartLine = methodNode.getStartLine();
        List<String> typeNames = BslDocParamExtractor.findParamTypes(context.text(), methodStartLine, paramName);
        if (typeNames.isEmpty()) {
            return List.of();
        }
        List<BslTypeResult.TypeInfo> result = new ArrayList<>();
        for (String name : typeNames) {
            // Cannot supply nameRu / compositeId from a free-form doc
            // comment — the caller only needs `name` to display a type hint
            // and the scope-members path will re-resolve via platform doc.
            result.add(new BslTypeResult.TypeInfo(name, null, null));
        }
        return result;
    }

    private EObject eGetChild(EObject obj, String featureName) {
        if (obj == null || featureName == null) {
            return null;
        }
        EStructuralFeature feature = obj.eClass().getEStructuralFeature(featureName);
        if (feature == null) {
            return null;
        }
        Object value = obj.eGet(feature);
        return value instanceof EObject eo ? eo : null;
    }

    private String getStringFeature(EObject object, String featureName) {
        if (object == null || featureName == null) {
            return null;
        }
        EStructuralFeature feature = object.eClass().getEStructuralFeature(featureName);
        if (feature == null) {
            return null;
        }
        Object value = object.eGet(feature);
        if (!(value instanceof String text)) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private List<EObject> getEObjectList(EObject object, String featureName) {
        if (object == null || featureName == null) {
            return List.of();
        }
        EStructuralFeature feature = object.eClass().getEStructuralFeature(featureName);
        if (feature == null) {
            return List.of();
        }
        Object value = object.eGet(feature);
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<EObject> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof EObject eObject) {
                result.add(eObject);
            }
        }
        return result;
    }

    private List<BslScopeMembersResult.MemberItem> collectMembersFromPlatformDoc(
            BslScopeMembersRequest request,
            List<BslTypeResult.TypeInfo> types) {
        if (types.isEmpty()) {
            return List.of();
        }

        Map<String, BslScopeMembersResult.MemberItem> dedup = new LinkedHashMap<>();

        for (BslTypeResult.TypeInfo type : types) {
            String queryType = firstNonBlank(type.getName(), type.getNameRu());
            if (queryType == null || queryType.isBlank()) {
                continue;
            }

            PlatformDocumentationResult doc;
            try {
                doc = platformDocService.getDocumentation(new PlatformDocumentationRequest(
                        request.getProjectName(),
                        queryType,
                        request.getLanguageNormalized(),
                        PlatformMemberFilter.ALL,
                        request.getContains(),
                        200,
                        0));
            } catch (PlatformDocumentationException e) {
                continue;
            }

            String ownerType = firstNonBlank(doc.resolvedTypeName(), doc.resolvedTypeNameRu(), queryType);
            for (PlatformDocumentationResult.MethodDoc method : doc.methods()) {
                String signature = buildMethodSignature(method);
                List<String> returnTypes = method.returnTypes() != null ? method.returnTypes() : List.of();
                BslScopeMembersResult.MemberItem item = new BslScopeMembersResult.MemberItem(
                        "method", //$NON-NLS-1$
                        method.name(),
                        method.nameRu(),
                        ownerType,
                        signature,
                        returnTypes,
                        "platform_doc"); //$NON-NLS-1$
                dedup.putIfAbsent(memberKey(item), item);
            }
            for (PlatformDocumentationResult.PropertyDoc property : doc.properties()) {
                List<String> returnTypes = property.types() != null ? property.types() : List.of();
                BslScopeMembersResult.MemberItem item = new BslScopeMembersResult.MemberItem(
                        "property", //$NON-NLS-1$
                        property.name(),
                        property.nameRu(),
                        ownerType,
                        property.name(),
                        returnTypes,
                        "platform_doc"); //$NON-NLS-1$
                dedup.putIfAbsent(memberKey(item), item);
            }
        }
        return new ArrayList<>(dedup.values());
    }

    private List<BslScopeMembersResult.MemberItem> collectMembersFromContentAssist(BslScopeMembersRequest request) {
        ContentAssistRequest assistRequest = new ContentAssistRequest(
                request.getProjectName(),
                request.getFilePath(),
                request.getLine(),
                request.getColumn(),
                200,
                0,
                request.getContains(),
                false);
        ContentAssistResult result = TimeBoundedCall.callWithin(
                () -> contentAssistService.getContentAssist(assistRequest),
                CONTENT_ASSIST_FALLBACK_TIMEOUT_MS,
                null,
                "contentAssist", //$NON-NLS-1$
                LOG::debug);
        if (result == null) {
            return List.of();
        }
        List<BslScopeMembersResult.MemberItem> items = new ArrayList<>();
        for (ContentAssistResult.Item item : result.getItems()) {
            items.add(new BslScopeMembersResult.MemberItem(
                    "proposal", //$NON-NLS-1$
                    item.getLabel(),
                    null,
                    null,
                    item.getLabel(),
                    List.of(),
                    "content_assist")); //$NON-NLS-1$
        }
        return items;
    }

    private String buildMethodSignature(PlatformDocumentationResult.MethodDoc method) {
        if (method == null) {
            return null;
        }
        String name = firstNonBlank(method.name(), method.nameRu(), "method"); //$NON-NLS-1$
        if (method.paramSets() == null || method.paramSets().isEmpty()) {
            return name + "()"; //$NON-NLS-1$
        }
        PlatformDocumentationResult.ParamSetDoc first = method.paramSets().get(0);
        List<String> params = new ArrayList<>();
        for (PlatformDocumentationResult.ParameterDoc parameter : first.params()) {
            params.add(firstNonBlank(parameter.name(), parameter.nameRu(), "arg")); //$NON-NLS-1$
        }
        return name + "(" + String.join(", ", params) + ")"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private String memberKey(BslScopeMembersResult.MemberItem item) {
        return normalize(item.getKind()) + "|" + normalize(item.getOwnerType()) + "|" + normalize(item.getName()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private String displayTypeName(BslTypeResult.TypeInfo type, String language) {
        if ("ru".equalsIgnoreCase(language)) { //$NON-NLS-1$
            return firstNonBlank(type.getNameRu(), type.getName(), type.getCompositeId());
        }
        return firstNonBlank(type.getName(), type.getNameRu(), type.getCompositeId());
    }

    private int calculateOffset(String text, int line, int column) {
        if (text == null) {
            throw new EdtAstException(EdtAstErrorCode.INVALID_POSITION,
                    "Cannot compute position without text content", false); //$NON-NLS-1$
        }

        int currentLine = 1;
        int index = 0;
        while (index < text.length() && currentLine < line) {
            char ch = text.charAt(index++);
            if (ch == '\n') {
                currentLine++;
            } else if (ch == '\r') {
                if (index < text.length() && text.charAt(index) == '\n') {
                    index++;
                }
                currentLine++;
            }
        }
        if (currentLine != line) {
            throw new EdtAstException(EdtAstErrorCode.INVALID_POSITION,
                    "Line is outside document bounds", false); //$NON-NLS-1$
        }

        int lineEnd = index;
        while (lineEnd < text.length()) {
            char ch = text.charAt(lineEnd);
            if (ch == '\n' || ch == '\r') {
                break;
            }
            lineEnd++;
        }
        int lineLength = lineEnd - index;
        int columnIndex = column - 1;
        if (columnIndex < 0 || columnIndex > lineLength) {
            throw new EdtAstException(EdtAstErrorCode.INVALID_POSITION,
                    "Column is outside document bounds", false); //$NON-NLS-1$
        }
        return index + columnIndex;
    }

    private int[] lineColFromOffset(String text, int offset) {
        if (text == null) {
            return new int[] {1, 1};
        }
        LineIndex index = new LineIndex(text);
        int bounded = Math.max(0, Math.min(offset, text.length()));
        int line = index.lineOfOffset(bounded);
        int lineStart = index.startOffset(line);
        return new int[] {line, bounded - lineStart + 1};
    }

    private String extractName(EObject element) {
        if (element instanceof Method m) {
            return m.getName();
        }
        if (element instanceof Variable v) {
            return v.getName();
        }
        if (element instanceof NamedElement named) {
            return named.getName();
        }
        if (element instanceof FeatureAccess fa) {
            return fa.getName();
        }
        if (element instanceof Module) {
            return "Module"; //$NON-NLS-1$
        }
        // fallback for platform types that are not BSL model elements
        INode node = NodeModelUtils.findActualNodeFor(element);
        return node != null ? safeTrim(NodeModelUtils.getTokenText(node)) : null;
    }

    private String symbolKind(EObject element) {
        if (element instanceof Variable) {
            return "variable"; //$NON-NLS-1$
        }
        if (element instanceof Invocation) {
            return "invocation"; //$NON-NLS-1$
        }
        if (element instanceof Method) {
            return "method"; //$NON-NLS-1$
        }
        if (element instanceof Module) {
            return "module"; //$NON-NLS-1$
        }
        if (element instanceof DynamicFeatureAccess) {
            return "property"; //$NON-NLS-1$
        }
        if (element instanceof StaticFeatureAccess) {
            return "reference"; //$NON-NLS-1$
        }
        if (element instanceof FeatureAccess) {
            return "reference"; //$NON-NLS-1$
        }
        return element.eClass().getName().toLowerCase(Locale.ROOT);
    }

    static EObject resolveSemanticTarget(EObject element, Environments currentEnvironments) {
        if (element == null) {
            return null;
        }
        if (element instanceof Invocation invocation) {
            EObject resolved = resolveSemanticTarget(invocation.getMethodAccess(), currentEnvironments);
            return resolved != null ? resolved : invocation;
        }
        if (element instanceof StaticFeatureAccess staticAccess) {
            EObject resolved = resolveFeatureEntries(staticAccess.getFeatureEntries(), currentEnvironments);
            return resolved != null ? resolveSemanticTarget(resolved, currentEnvironments) : staticAccess;
        }
        if (element instanceof DynamicFeatureAccess dynamicAccess) {
            EObject resolved = resolveFeatureEntries(dynamicAccess.getFeatureEntries(), currentEnvironments);
            return resolved != null ? resolveSemanticTarget(resolved, currentEnvironments) : dynamicAccess;
        }
        if (element instanceof FeatureEntry featureEntry) {
            EObject resolved = featureEntry.getFeature();
            return resolved != null ? resolveSemanticTarget(resolved, currentEnvironments) : featureEntry;
        }
        return element;
    }

    static EObject resolveFeatureEntries(List<FeatureEntry> entries, Environments currentEnvironments) {
        if (entries == null || entries.isEmpty()) {
            return null;
        }

        EObject fallback = null;
        for (FeatureEntry entry : entries) {
            if (entry == null || entry.getFeature() == null) {
                continue;
            }
            if (fallback == null) {
                fallback = entry.getFeature();
            }
            Environments entryEnvironments = entry.getEnvironments();
            if (currentEnvironments == null || currentEnvironments.isEmpty()
                    || entryEnvironments == null || entryEnvironments.isEmpty()
                    || currentEnvironments.containsAny(entryEnvironments)
                    || entryEnvironments.containsAny(currentEnvironments)) {
                return entry.getFeature();
            }
        }
        return fallback;
    }

    private Environments resolveCurrentEnvironments(EObject element) {
        Environmental environmental = findContainerOfType(element, Environmental.class);
        if (environmental == null) {
            return Environments.ALL;
        }
        try {
            Environments environments = environmental.environments();
            return environments != null ? environments : Environments.ALL;
        } catch (RuntimeException e) {
            return Environments.ALL;
        }
    }

    private String readResourceText(XtextResource resource, IFile file) {
        if (resource != null
                && resource.getParseResult() != null
                && resource.getParseResult().getRootNode() != null) {
            String text = resource.getParseResult().getRootNode().getText();
            if (text != null) {
                return text;
            }
        }
        return readFileText(file);
    }

    private String readFileText(IFile file) {
        if (file == null || !file.exists()) {
            return ""; //$NON-NLS-1$
        }
        try (InputStream input = file.getContents()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (CoreException | IOException e) {
            throw new EdtAstException(EdtAstErrorCode.INTERNAL_ERROR,
                    "Failed to read BSL file content: " + e.getMessage(), true, e); //$NON-NLS-1$
        }
    }

    private List<ResolvedMethod> collectMethods(Module module, String text, LineIndex lineIndex) {
        List<ResolvedMethod> result = new ArrayList<>();
        for (Method method : module.allMethods()) {
            String name = method.getName();
            if (name == null || name.isBlank()) {
                continue;
            }

            INode node = NodeModelUtils.getNode(method);
            if (node == null) {
                throw new EdtAstException(EdtAstErrorCode.MODULE_PARSE_ERROR,
                        "Failed to resolve AST node for method: " + name, true); //$NON-NLS-1$
            }

            int startOffset = node.getOffset();
            int endOffset = startOffset + node.getLength();
            int startLine = lineIndex.lineOfOffset(startOffset);
            int endLine = lineIndex.lineOfOffset(endOffset);

            String kind = method instanceof Procedure ? "procedure" : "function"; //$NON-NLS-1$ //$NON-NLS-2$
            List<BslMethodParamInfo> params = collectParams(method);
            List<String> pragmas = collectPragmas(method);
            String doc = extractDocumentation(text, startOffset);

            result.add(new ResolvedMethod(
                    method,
                    name,
                    kind,
                    startLine,
                    endLine,
                    startOffset,
                    endOffset,
                    method.isExport(),
                    method.isAsync(),
                    method.isEvent(),
                    method.isUsed(),
                    params,
                    pragmas,
                    doc));
        }
        return result;
    }

    // TODO MERGE: HEAD added collectMethodElements()/filterMethodChildren()/skipSubtree()/childEClassesSummary() for lazy-xref #If-preprocessor safety. Helpers are now unreachable after adopting main's typed Module.allMethods() path; evaluate whether to wire them back as a fallback when allMethods() returns empty.
    /**
     * Collects Method/Procedure/Function children of the Module AST root.
     * Tries named features {@code methods} / {@code allMethods}, then
     * eContents() filter, then EcoreUtil.resolveAll and retry — recovers on
     * lazy-xref regressions and locates methods under #If preprocessor wrappers.
     */
    @SuppressWarnings("unused")
    private List<EObject> collectMethodElementsLegacy(EObject module) {
        if (module == null) {
            LOG.debug("collectMethodElements: module=null, returning empty"); //$NON-NLS-1$
            return List.of();
        }
        String moduleClass = module.eClass().getName();
        int totalChildren = module.eContents().size();
        LOG.debug("collectMethodElements: module eClass=%s, eContents.size=%d", //$NON-NLS-1$
                moduleClass, totalChildren);

        List<EObject> methods = getEObjectList(module, "methods"); //$NON-NLS-1$
        if (!methods.isEmpty()) {
            LOG.debug("collectMethodElements: via feature 'methods' n=%d", methods.size()); //$NON-NLS-1$
            return methods;
        }
        methods = getEObjectList(module, "allMethods"); //$NON-NLS-1$
        if (!methods.isEmpty()) {
            LOG.debug("collectMethodElements: via feature 'allMethods' n=%d", methods.size()); //$NON-NLS-1$
            return methods;
        }
        List<EObject> fromChildren = filterMethodChildrenLegacy(module);
        if (!fromChildren.isEmpty()) {
            LOG.debug("collectMethodElements: via eContents() filter n=%d", fromChildren.size()); //$NON-NLS-1$
            return fromChildren;
        }
        LOG.debug("collectMethodElements: all fast paths empty — invoking EcoreUtil.resolveAll"); //$NON-NLS-1$
        Resource resource = module.eResource();
        if (resource != null) {
            long t0 = System.nanoTime();
            try {
                EcoreUtil.resolveAll(resource);
            } catch (RuntimeException e) {
                LOG.debug("collectMethodElements: resolveAll threw %s: %s", //$NON-NLS-1$
                        e.getClass().getSimpleName(), e.getMessage());
                return List.of();
            }
            LOG.debug("collectMethodElements: resolveAll took %d ms", //$NON-NLS-1$
                    (System.nanoTime() - t0) / 1_000_000);
        }
        methods = getEObjectList(module, "methods"); //$NON-NLS-1$
        if (!methods.isEmpty()) {
            LOG.debug("collectMethodElements: after resolveAll, 'methods' n=%d", methods.size()); //$NON-NLS-1$
            return methods;
        }
        methods = getEObjectList(module, "allMethods"); //$NON-NLS-1$
        if (!methods.isEmpty()) {
            LOG.debug("collectMethodElements: after resolveAll, 'allMethods' n=%d", methods.size()); //$NON-NLS-1$
            return methods;
        }
        List<EObject> finalChildren = filterMethodChildrenLegacy(module);
        LOG.debug("collectMethodElements: after resolveAll, eContents() filter n=%d (dumping child eClass names): %s", //$NON-NLS-1$
                finalChildren.size(), childEClassesSummaryLegacy(module));
        return finalChildren;
    }

    @SuppressWarnings("unused")
    private String childEClassesSummaryLegacy(EObject module) {
        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (EObject child : module.eContents()) {
            if (child == null) {
                continue;
            }
            String name = child.eClass().getName();
            counts.merge(name, 1, Integer::sum);
        }
        return counts.toString();
    }

    @SuppressWarnings("unused")
    private List<EObject> filterMethodChildrenLegacy(EObject module) {
        // Walk the full subtree: in AM-style modules, methods are nested under
        // IfPreprocessorDeclareStatement (the #If Server Or ThickClient...
        // Then / #EndIf wrapper that's mandatory for manager modules). A
        // shallow eContents() scan misses them entirely. eAllContents() is
        // depth-first and cheap — it doesn't trigger lazy xref resolution.
        // We stop descending into a method once we've found it, to avoid
        // counting nested declarations inside a method body as separate items.
        List<EObject> result = new ArrayList<>();
        java.util.Iterator<EObject> it = module.eAllContents();
        while (it.hasNext()) {
            EObject child = it.next();
            if (child == null) {
                continue;
            }
            String eClassName = child.eClass().getName();
            if (eClassName == null) {
                continue;
            }
            String lower = eClassName.toLowerCase(Locale.ROOT);
            if (lower.contains("procedure") //$NON-NLS-1$
                    || lower.contains("function") //$NON-NLS-1$
                    || lower.equals("method")) { //$NON-NLS-1$
                result.add(child);
                // Skip descent into the method's own body — any nested "method"-
                // like constructs (e.g. lambdas, if EDT ever introduces them)
                // should not be treated as top-level module methods.
                it = skipSubtreeLegacy(it, child);
            }
        }
        return result;
    }

    /**
     * Returns an iterator positioned past the subtree of {@code current}.
     * With {@code eAllContents()} we cannot directly prune — but we can
     * advance the iterator by re-filtering using the root, since the cost is
     * negligible compared to method-body contents. Kept as a thin seam so
     * callers read naturally.
     */
    @SuppressWarnings("unused")
    private java.util.Iterator<EObject> skipSubtreeLegacy(java.util.Iterator<EObject> it, EObject current) {
        if (it instanceof org.eclipse.emf.common.util.TreeIterator<EObject> tree) {
            tree.prune();
            return tree;
        }
        return it;
    }

    private List<BslMethodParamInfo> collectParams(Method method) {
        List<FormalParam> formalParams = method.getFormalParams();
        if (formalParams.isEmpty()) {
            return List.of();
        }
        List<BslMethodParamInfo> result = new ArrayList<>();
        for (FormalParam param : formalParams) {
            String name = param.getName();
            boolean byValue = param.isByValue();
            String defaultText = param.getDefaultValue() != null
                    ? extractLiteralText(param.getDefaultValue()) : null;
            result.add(new BslMethodParamInfo(name, byValue, defaultText));
        }
        return result;
    }

    private List<String> collectPragmas(Method method) {
        return collectPragmaTexts(method.getPragmas());
    }

    private List<String> collectPragmaTexts(List<Pragma> pragmaList) {
        if (pragmaList == null || pragmaList.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Pragma pragma : pragmaList) {
            String text = pragmaToText(pragma);
            if (text != null) {
                result.add(text);
            }
        }
        return result;
    }

    private String pragmaToText(Pragma pragma) {
        if (pragma == null) {
            return null;
        }
        String symbol = safeTrim(pragma.getSymbol());
        String value = safeTrim(pragma.getValue());
        if (symbol == null) {
            return value;
        }
        if (value == null) {
            return symbol;
        }
        return symbol + "=" + value; //$NON-NLS-1$
    }

    private String normalizeModuleType(ModuleType moduleType) {
        if (moduleType == null) {
            return null;
        }
        String literal = safeTrim(moduleType.getLiteral());
        if (literal != null) {
            return literal;
        }
        return safeTrim(moduleType.getName());
    }


    private String extractDocumentation(String text, int methodStartOffset) {
        if (text == null || methodStartOffset <= 0) {
            return null;
        }
        // Walk backwards from method start to find contiguous // comment block
        int pos = methodStartOffset - 1;
        // Skip whitespace/newlines before method keyword
        while (pos >= 0 && (text.charAt(pos) == ' ' || text.charAt(pos) == '\t'
                || text.charAt(pos) == '\r' || text.charAt(pos) == '\n')) {
            pos--;
        }
        if (pos < 1) {
            return null;
        }
        // Now pos points to last non-whitespace char before method. Collect comment lines upward.
        List<String> lines = new ArrayList<>();
        while (pos >= 0) {
            // Find the start of the current line
            int lineEnd = pos;
            int lineStart = pos;
            while (lineStart > 0 && text.charAt(lineStart - 1) != '\n') {
                lineStart--;
            }
            String line = text.substring(lineStart, lineEnd + 1).trim();
            if (line.startsWith("//")) { //$NON-NLS-1$
                lines.add(line.substring(2).trim());
                // Move to previous line
                pos = lineStart - 1;
                // Skip newline chars
                while (pos >= 0 && (text.charAt(pos) == '\r' || text.charAt(pos) == '\n')) {
                    pos--;
                }
            } else {
                break;
            }
        }
        if (lines.isEmpty()) {
            return null;
        }
        // Reverse since we collected bottom-up
        Collections.reverse(lines);
        String doc = String.join("\n", lines); //$NON-NLS-1$
        return doc.isBlank() ? null : doc;
    }

    private String extractLiteralText(EObject literal) {
        if (literal == null) {
            return null;
        }
        INode node = NodeModelUtils.findActualNodeFor(literal);
        String text = node != null ? safeTrim(NodeModelUtils.getTokenText(node)) : null;
        return text;
    }

    private String sliceText(String text, int startOffset, int endOffset) {
        if (text == null || text.isEmpty()) {
            return ""; //$NON-NLS-1$
        }
        int safeStart = Math.max(0, Math.min(startOffset, text.length()));
        int safeEnd = Math.max(safeStart, Math.min(endOffset, text.length()));
        return text.substring(safeStart, safeEnd);
    }


    private <T> T findContainerOfType(EObject element, Class<T> type) {
        EObject current = element;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.eContainer();
        }
        return null;
    }

    private String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT); //$NON-NLS-1$
    }

    private String safeTrim(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String firstNonBlank(String... candidates) {
        if (candidates == null) {
            return null;
        }
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }

    private ResolvedMethod selectPreferredMethod(List<ResolvedMethod> matches) {
        ResolvedMethod exported = selectUnique(matches, ResolvedMethod::exportFlag);
        if (exported != null) {
            return exported;
        }
        return null;
    }

    private ResolvedMethod selectUnique(List<ResolvedMethod> matches, Predicate<ResolvedMethod> predicate) {
        ResolvedMethod candidate = null;
        for (ResolvedMethod method : matches) {
            if (!predicate.test(method)) {
                continue;
            }
            if (candidate != null) {
                return null;
            }
            candidate = method;
        }
        return candidate;
    }

    private List<ResolvedMethod> findMatchingMethods(
            List<ResolvedMethod> methods,
            String normalizedName,
            String normalizedKind,
            Integer startLine) {
        List<ResolvedMethod> matches = new ArrayList<>();
        for (ResolvedMethod method : methods) {
            if (!"any".equals(normalizedKind) && !normalizedKind.equals(method.kind())) { //$NON-NLS-1$
                continue;
            }
            if (!normalize(method.name()).equals(normalizedName)) {
                continue;
            }
            if (startLine != null && method.startLine() != startLine.intValue()) {
                continue;
            }
            matches.add(method);
        }
        return matches;
    }

    private ResolvedMethod resolveSingleMethod(List<ResolvedMethod> matches, String requestedName) {
        if (matches.isEmpty()) {
            throw new EdtAstException(EdtAstErrorCode.METHOD_NOT_FOUND,
                    "Method not found: " + requestedName, false); //$NON-NLS-1$
        }
        if (matches.size() == 1) {
            return matches.get(0);
        }
        ResolvedMethod preferred = selectPreferredMethod(matches);
        if (preferred != null) {
            return preferred;
        }
        List<BslMethodCandidate> candidates = new ArrayList<>();
        for (ResolvedMethod method : matches) {
            candidates.add(new BslMethodCandidate(
                    method.name(),
                    method.kind(),
                    method.startLine(),
                    method.endLine()));
        }
        throw new BslMethodLookupException(
                EdtAstErrorCode.AMBIGUOUS_METHOD,
                "Ambiguous method name: " + requestedName, //$NON-NLS-1$
                true,
                candidates);
    }

    private record ResolvedMethod(
            Method model,
            String name,
            String kind,
            int startLine,
            int endLine,
            int startOffset,
            int endOffset,
            boolean exportFlag,
            boolean asyncFlag,
            boolean eventFlag,
            boolean usedFlag,
            List<BslMethodParamInfo> params,
            List<String> pragmas,
            String documentation) {
        BslMethodInfo toInfo() {
            return toInfo(false);
        }

        BslMethodInfo toInfo(boolean compact) {
            return new BslMethodInfo(
                    name,
                    kind,
                    startLine,
                    endLine,
                    exportFlag,
                    asyncFlag,
                    eventFlag,
                    usedFlag,
                    compact ? null : params,
                    pragmas,
                    documentation);
        }
    }

    private static final class LineIndex {
        private final int[] lineStarts;
        private final int textLength;

        LineIndex(String text) {
            if (text == null || text.isEmpty()) {
                this.textLength = 0;
                this.lineStarts = new int[] {0};
                return;
            }
            this.textLength = text.length();
            List<Integer> starts = new ArrayList<>();
            starts.add(0);
            int i = 0;
            while (i < text.length()) {
                char ch = text.charAt(i++);
                if (ch == '\n') {
                    starts.add(i);
                } else if (ch == '\r') {
                    if (i < text.length() && text.charAt(i) == '\n') {
                        i++;
                    }
                    starts.add(i);
                }
            }
            this.lineStarts = new int[starts.size()];
            for (int idx = 0; idx < starts.size(); idx++) {
                this.lineStarts[idx] = starts.get(idx);
            }
        }

        int totalLines() {
            return lineStarts.length;
        }

        int startOffset(int line) {
            if (line <= 1) {
                return 0;
            }
            if (line > lineStarts.length) {
                return textLength;
            }
            return lineStarts[line - 1];
        }

        int lineOfOffset(int offset) {
            int bounded = Math.max(0, Math.min(offset, textLength));
            int low = 0;
            int high = lineStarts.length - 1;
            int result = 0;
            while (low <= high) {
                int mid = (low + high) >>> 1;
                int start = lineStarts[mid];
                if (start <= bounded) {
                    result = mid;
                    low = mid + 1;
                } else {
                    high = mid - 1;
                }
            }
            return result + 1;
        }
    }

    @FunctionalInterface
    private interface ReadOnlyTask<T> {
        T execute();
    }

    private record ModuleContext(
            IProject project,
            IFile file,
            XtextResource resource,
            Module module,
            String text) {
        ModuleContext {
            Objects.requireNonNull(project);
            Objects.requireNonNull(file);
            Objects.requireNonNull(resource);
            Objects.requireNonNull(module);
            Objects.requireNonNull(text);
        }
    }

    private record PositionContext(
            IProject project,
            IFile file,
            XtextResource resource,
            IResourceServiceProvider resourceServiceProvider,
            EObject element,
            int offset,
            String text) {
        PositionContext {
            Objects.requireNonNull(project);
            Objects.requireNonNull(file);
            Objects.requireNonNull(resource);
            Objects.requireNonNull(element);
            Objects.requireNonNull(text);
        }
    }
}
