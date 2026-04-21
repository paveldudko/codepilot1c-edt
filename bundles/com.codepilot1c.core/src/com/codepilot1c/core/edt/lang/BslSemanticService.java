package com.codepilot1c.core.edt.lang;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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

import com._1c.g5.v8.dt.bsl.resource.TypesComputer;
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

    private static final int RESOURCE_SET_RETRY_ATTEMPTS = 10;
    private static final long RESOURCE_SET_RETRY_DELAY_MS = 300L;
    private static final long CONTENT_ASSIST_FALLBACK_TIMEOUT_MS = 5_000L;

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
        PositionContext context = resolveContext(request);

        String symbolName = firstNonBlank(
                getStringFeature(context.element(), "name"), //$NON-NLS-1$
                getStringFeature(context.element(), "nameRu")); //$NON-NLS-1$

        INode node = NodeModelUtils.findActualNodeFor(context.element());
        String symbolText = node != null ? safeTrim(NodeModelUtils.getTokenText(node)) : null;
        if (symbolName == null) {
            symbolName = symbolText;
        }

        int[] start = lineColFromOffset(context.text(), node != null ? node.getOffset() : context.offset());
        int[] end = lineColFromOffset(context.text(), node != null ? node.getEndOffset() : context.offset());
        EObject container = context.element().eContainer();

        return new BslSymbolResult(
                request.getProjectName(),
                request.getFilePath(),
                request.getLine(),
                request.getColumn(),
                context.offset(),
                symbolKind(context.element()),
                symbolName,
                symbolText,
                context.element().eClass().getName(),
                EcoreUtil.getURI(context.element()).toString(),
                container != null ? container.eClass().getName() : null,
                container != null ? firstNonBlank(
                        getStringFeature(container, "name"), //$NON-NLS-1$
                        getStringFeature(container, "nameRu")) : null, //$NON-NLS-1$
                start[0],
                start[1],
                end[0],
                end[1]);
    }

    public BslTypeResult getTypeAtPosition(BslPositionRequest request) {
        request.validate();
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
        all.addAll(collectMembersFromPlatformDoc(request, types));
        LOG.debug("getScopeMembers: collectMembersFromPlatformDoc took %d ms, n=%d", //$NON-NLS-1$
                (System.nanoTime() - tDoc) / 1_000_000, all.size());
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
        ModuleContext context = resolveModuleContext(request.getProjectName(), request.getFilePath());
        LineIndex lineIndex = new LineIndex(context.text());
        List<ResolvedMethod> methods = collectMethods(context.module(), context.text(), lineIndex);

        String kindFilter = request.normalizedKind();
        String nameFilter = request.normalizedName();
        List<ResolvedMethod> matches = new ArrayList<>();
        for (ResolvedMethod method : methods) {
            if (!"any".equals(kindFilter) && !kindFilter.equals(method.kind())) { //$NON-NLS-1$
                continue;
            }
            if (!normalize(method.name()).equals(nameFilter)) {
                continue;
            }
            matches.add(method);
        }

        Integer startLineFilter = request.getStartLine();
        if (startLineFilter != null) {
            List<ResolvedMethod> filtered = new ArrayList<>();
            for (ResolvedMethod method : matches) {
                if (method.startLine() == startLineFilter.intValue()) {
                    filtered.add(method);
                }
            }
            matches = filtered;
        }

        if (matches.isEmpty()) {
            throw new EdtAstException(EdtAstErrorCode.METHOD_NOT_FOUND,
                    "Method not found: " + request.getName(), false); //$NON-NLS-1$
        }

        if (matches.size() > 1) {
            ResolvedMethod preferred = selectPreferredMethod(matches);
            if (preferred != null) {
                matches = List.of(preferred);
            } else {
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
                        "Ambiguous method name: " + request.getName(), //$NON-NLS-1$
                        true,
                        candidates);
            }
        }

        ResolvedMethod method = matches.get(0);
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
        if (root == null) {
            throw new EdtAstException(EdtAstErrorCode.MODULE_PARSE_ERROR,
                    "Failed to parse BSL module: " + filePath, true); //$NON-NLS-1$
        }
        if (root.eClass().getEStructuralFeature("methods") == null //$NON-NLS-1$
                && root.eClass().getEStructuralFeature("allMethods") == null) { //$NON-NLS-1$
            throw new EdtAstException(EdtAstErrorCode.MODULE_PARSE_ERROR,
                    "Root AST element is not a BSL module for: " + filePath, true); //$NON-NLS-1$
        }

        return new ModuleContext(project, file, resource, root, text);
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
                    Thread.sleep(RESOURCE_SET_RETRY_DELAY_MS);
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

        List<TypeItem> typeItems;
        try {
            typeItems = typesComputer.computeTypes(context.element(), Environments.ALL);
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
            if (typeItems == null || typeItems.isEmpty()) {
                return List.of();
            }
            LOG.debug("computeTypes: AST-walk fallback produced %d type(s) for %s", //$NON-NLS-1$
                    typeItems.size(), context.element().eClass().getName());
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
     * {@code SimpleStatement} whose {@code left} is a
     * Static/DynamicFeatureAccess resolving to {@code variable}. Returns
     * the {@code right} side of that assignment, or {@code null}.
     */
    private EObject findFirstAssignedRight(EObject usage, EObject variable) {
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
            EObject leftFeature = eGetChild(left, "feature"); //$NON-NLS-1$
            if (leftFeature == variable) {
                return eGetChild(node, "right"); //$NON-NLS-1$
            }
        }
        return null;
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
                null);
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

    private String symbolKind(EObject element) {
        String className = element.eClass().getName();
        String normalized = className.toLowerCase(Locale.ROOT);
        if (normalized.contains("variable")) { //$NON-NLS-1$
            return "variable"; //$NON-NLS-1$
        }
        if (normalized.contains("invocation") || normalized.contains("call")) { //$NON-NLS-1$ //$NON-NLS-2$
            return "invocation"; //$NON-NLS-1$
        }
        if (normalized.contains("method")) { //$NON-NLS-1$
            return "method"; //$NON-NLS-1$
        }
        if (normalized.contains("property")) { //$NON-NLS-1$
            return "property"; //$NON-NLS-1$
        }
        if (normalized.contains("module")) { //$NON-NLS-1$
            return "module"; //$NON-NLS-1$
        }
        return normalized;
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

    private List<ResolvedMethod> collectMethods(EObject module, String text, LineIndex lineIndex) {
        List<EObject> methods = collectMethodElements(module);

        List<ResolvedMethod> result = new ArrayList<>();
        for (EObject method : methods) {
            if (method == null) {
                continue;
            }
            String name = firstNonBlank(
                    getStringFeature(method, "name"), //$NON-NLS-1$
                    getStringFeature(method, "nameRu")); //$NON-NLS-1$
            if (name == null) {
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

            String kind = methodKind(method);
            boolean isExport = getBooleanFeature(method, "export"); //$NON-NLS-1$
            boolean isAsync = getBooleanFeature(method, "async"); //$NON-NLS-1$
            boolean isEvent = getBooleanFeature(method, "event"); //$NON-NLS-1$
            List<BslMethodParamInfo> params = collectParams(method);

            result.add(new ResolvedMethod(
                    name,
                    kind,
                    startLine,
                    endLine,
                    startOffset,
                    endOffset,
                    isExport,
                    isAsync,
                    isEvent,
                    params));
        }
        return result;
    }

    /**
     * Collects Method/Procedure/Function children of the Module AST root.
     * Tries multiple strategies in order of cost:
     * <ol>
     *   <li>Named feature {@code methods} (legacy BSL model).</li>
     *   <li>Named feature {@code allMethods} (current BSL model, often
     *       populated eagerly after parse).</li>
     *   <li>Walking {@code eContents()} and filtering by eClass name — works
     *       even when derived features are not populated.</li>
     *   <li>As last resort, {@link EcoreUtil#resolveAll(Resource)} and retry
     *       the named features — recovers on lazy-xref regressions.</li>
     * </ol>
     */
    private List<EObject> collectMethodElements(EObject module) {
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
        List<EObject> fromChildren = filterMethodChildren(module);
        if (!fromChildren.isEmpty()) {
            LOG.debug("collectMethodElements: via eContents() filter n=%d", fromChildren.size()); //$NON-NLS-1$
            return fromChildren;
        }
        LOG.debug("collectMethodElements: all fast paths empty — invoking EcoreUtil.resolveAll"); //$NON-NLS-1$
        // Last-resort xref resolution — expensive on large modules, so only
        // applied when the cheaper strategies all returned empty.
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
        List<EObject> finalChildren = filterMethodChildren(module);
        LOG.debug("collectMethodElements: after resolveAll, eContents() filter n=%d (dumping child eClass names): %s", //$NON-NLS-1$
                finalChildren.size(), childEClassesSummary(module));
        return finalChildren;
    }

    private String childEClassesSummary(EObject module) {
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

    private List<EObject> filterMethodChildren(EObject module) {
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
                it = skipSubtree(it, child);
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
    private java.util.Iterator<EObject> skipSubtree(java.util.Iterator<EObject> it, EObject current) {
        // EMF's TreeIterator supports prune() which skips descent into the
        // current node's children. Fall back to plain iteration if the
        // concrete type differs.
        if (it instanceof org.eclipse.emf.common.util.TreeIterator<EObject> tree) {
            tree.prune();
            return tree;
        }
        return it;
    }

    private List<BslMethodParamInfo> collectParams(EObject method) {
        List<EObject> params = getEObjectList(method, "formalParams"); //$NON-NLS-1$
        if (params.isEmpty()) {
            return List.of();
        }
        List<BslMethodParamInfo> result = new ArrayList<>();
        for (EObject param : params) {
            if (param == null) {
                continue;
            }
            String name = firstNonBlank(
                    getStringFeature(param, "name"), //$NON-NLS-1$
                    getStringFeature(param, "nameRu")); //$NON-NLS-1$
            boolean byValue = getBooleanFeature(param, "byValue"); //$NON-NLS-1$
            EObject defaultValue = getEObjectFeature(param, "defaultValue"); //$NON-NLS-1$
            String defaultText = defaultValue != null ? extractLiteralText(defaultValue) : null;
            result.add(new BslMethodParamInfo(name, byValue, defaultText));
        }
        return result;
    }

    private String methodKind(EObject method) {
        if (method == null) {
            return "method"; //$NON-NLS-1$
        }
        String className = method.eClass().getName();
        if (className == null) {
            return "method"; //$NON-NLS-1$
        }
        String normalized = className.toLowerCase(Locale.ROOT);
        if (normalized.contains("procedure")) { //$NON-NLS-1$
            return "procedure"; //$NON-NLS-1$
        }
        if (normalized.contains("function")) { //$NON-NLS-1$
            return "function"; //$NON-NLS-1$
        }
        return "method"; //$NON-NLS-1$
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

    private EObject getEObjectFeature(EObject object, String featureName) {
        if (object == null || featureName == null) {
            return null;
        }
        EStructuralFeature feature = object.eClass().getEStructuralFeature(featureName);
        if (feature == null) {
            return null;
        }
        Object value = object.eGet(feature);
        return value instanceof EObject eObject ? eObject : null;
    }

    private boolean getBooleanFeature(EObject object, String featureName) {
        if (object == null || featureName == null) {
            return false;
        }
        EStructuralFeature feature = object.eClass().getEStructuralFeature(featureName);
        if (feature == null) {
            return false;
        }
        Object value = object.eGet(feature);
        return value instanceof Boolean bool ? bool : false;
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

    private record ResolvedMethod(
            String name,
            String kind,
            int startLine,
            int endLine,
            int startOffset,
            int endOffset,
            boolean exportFlag,
            boolean asyncFlag,
            boolean eventFlag,
            List<BslMethodParamInfo> params) {
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
                    compact ? null : params);
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

    private record ModuleContext(
            IProject project,
            IFile file,
            XtextResource resource,
            EObject module,
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
