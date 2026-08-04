package com.codepilot1c.core.edt.publication;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.v8.dt.platform.services.core.publication.IPublicationManager;
import com._1c.g5.v8.dt.platform.services.core.publication.IWebServerPublishDelegate;
import com._1c.g5.v8.dt.platform.services.core.publication.WebServerAccessException;
import com._1c.g5.v8.dt.platform.services.core.runtimes.environments.IResolvableRuntimeInstallation;
import com._1c.g5.v8.dt.platform.services.core.runtimes.environments.MatchingRuntimeNotFound;
import com._1c.g5.v8.dt.platform.services.core.webservers.IWebServerManager;
import com._1c.g5.v8.dt.platform.services.core.webservers.IWebServerTypes;
import com._1c.g5.v8.dt.platform.services.core.webservers.WebServers;
import com._1c.g5.v8.dt.platform.services.model.AppArch;
import com._1c.g5.v8.dt.platform.services.model.Arch;
import com._1c.g5.v8.dt.platform.services.model.HttpService;
import com._1c.g5.v8.dt.platform.services.model.HttpServices;
import com._1c.g5.v8.dt.platform.services.model.InfobasePublication;
import com._1c.g5.v8.dt.platform.services.model.ModelFactory;
import com._1c.g5.v8.dt.platform.services.model.OData;
import com._1c.g5.v8.dt.platform.services.model.Pool;
import com._1c.g5.v8.dt.platform.services.model.Publication;
import com._1c.g5.v8.dt.platform.services.model.PublicationType;
import com._1c.g5.v8.dt.platform.services.model.RuntimeInstallation;
import com._1c.g5.v8.dt.platform.services.model.WebServer;
import com._1c.g5.v8.dt.platform.services.model.WebServices;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.ILaunchableRuntimeComponent;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IRuntimeComponent;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IRuntimeComponentManager;
import com.codepilot1c.core.edt.runtime.EdtRuntimeGateway;
import com.codepilot1c.core.edt.runtime.EdtToolErrorCode;
import com.codepilot1c.core.edt.runtime.EdtToolException;
import com.codepilot1c.core.logging.VibeLogger;

/**
 * EDT-backed management of 1C web-server publications (Apache focus).
 *
 * <p>Wraps {@code IWebServerManager} (web-server registry) and {@code IPublicationManager}
 * (conf/vrd generation via {@code ApachePublishDelegateWin32}). Key empirics this service
 * encodes (decompile audit of services.core 21.0, 2026-06-04):</p>
 * <ul>
 *   <li>{@code ApachePublishDelegate.ConfigUpdate} is idempotent: existing
 *       {@code LoadModule _1cws_module} lines and the current alias's blocks are stripped and
 *       rewritten, so re-publish never duplicates directives.</li>
 *   <li>{@code ApachePublishDelegateWin32.restart()} is a literal {@code return false} — EDT
 *       cannot restart Apache on Windows. {@link #restartServer} therefore falls back to a
 *       kill+start of the foreground {@code httpd.exe} (portable, non-service installs).</li>
 *   <li>{@code IWebServerPublishDelegate.publish(..., Path webExtension)} takes the wsap module
 *       path from the caller; {@link #publish} resolves it from a pinned platform version when
 *       requested, mirroring the {@code runtime_version} pin (b8ad7c7) so a pre-release platform
 *       install can never silently hijack the publication's wsap module. The delegate writes that
 *       path verbatim, so it must be the module FILE, never the platform's {@code bin} directory
 *       (see {@link #webExtensionModule}).</li>
 *   <li>{@code PublicationManager} answers {@code getAll}/{@code get} from a per-server EMF Resource
 *       filled once via {@code computeIfAbsent} and invalidated only by its own publish/remove — a
 *       cache, not a view of the conf. Since publishing here goes straight to the delegate, all
 *       reads go to the delegate too (see {@link #publicationsInConf}).</li>
 * </ul>
 */
public class EdtWebPublicationService {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(EdtWebPublicationService.class);

    private static final String RUNTIME_TYPE_ENTERPRISE_PLATFORM =
            "com._1c.g5.v8.dt.platform.services.core.runtimeType.EnterprisePlatform"; //$NON-NLS-1$
    /** Every {@code IWebServerTypes.IIS_*} id shares this prefix; they all use one wsap module. */
    private static final String IIS_TYPE_ID_PREFIX =
            "com._1c.g5.v8.dt.platform.services.core.webServerType.IIS."; //$NON-NLS-1$
    private static final long PROCESS_STOP_TIMEOUT_MS = 10_000L;

    /** Outcome of a {@link #restartServer} call. */
    public record RestartOutcome(String method, List<Long> stoppedPids, long startedPid, String command) {
    }

    /** Outcome of a {@link #probe} call. */
    public record ProbeOutcome(int statusCode, long elapsedMs) {
    }

    /** Wsap module resolved from a pinned platform installation. */
    private record ResolvedWebExtension(RuntimeInstallation installation, Path modulePath) {
    }

    /** A custom HTTP-service publication entry (vrd {@code <httpServices><service …/>}). */
    public record HttpServiceSpec(String name, String rootUrl, boolean enable) {
    }

    /** Publication-level connection pool (vrd pool attributes). Null fields keep the model default. */
    public record PoolSpec(Integer size, Integer maxAge) {
    }

    /**
     * Optional model content a {@link #publish} caller can carry into the generated {@code default.vrd}
     * beyond the plain infobase binding — so an EDT-managed publication reproduces a hand-authored vrd's
     * custom HTTP services (per-service {@code rootUrl}), OData/analytics flags and pool. BF-12936: the
     * bsl-analyzer-workspace MCP depends on {@code <service rootUrl="bsl-analyzer">}, which the bare
     * publish never emitted. Any field left null keeps EDT's default for that facet.
     *
     * <p>Fidelity note: per-service pool tuning is NOT modelled ({@link HttpService} carries only
     * name/rootUrl/enable) — only the publication-level {@link PoolSpec} exists. A migrated vrd is
     * functionally faithful (the service publishes at its {@code rootUrl}) but not byte-identical on
     * per-service pool numbers.</p>
     */
    public record PublicationExtras(
            Boolean publishHttpByDefault,
            Boolean publishWebByDefault,
            List<HttpServiceSpec> httpServices,
            Boolean enableStandardOData,
            Boolean enableSystemAnalytics,
            PoolSpec pool,
            Boolean publishExtensionsByDefault) {

        /** True when nothing is set — {@link #publish} then behaves exactly as the bare overload. */
        public boolean isEmpty() {
            return publishHttpByDefault == null && publishWebByDefault == null
                    && (httpServices == null || httpServices.isEmpty())
                    && enableStandardOData == null && enableSystemAnalytics == null && pool == null
                    && publishExtensionsByDefault == null;
        }
    }

    private final EdtRuntimeGateway gateway;

    public EdtWebPublicationService() {
        this(new EdtRuntimeGateway());
    }

    public EdtWebPublicationService(EdtRuntimeGateway gateway) {
        this.gateway = gateway;
    }

    // -- web servers ------------------------------------------------------------------------

    public List<WebServer> listServers() {
        return gateway.getWebServerManager().getAll();
    }

    /**
     * Registry lookup by exact name; empty when the name is not registered. Lets a caller tell a
     * genuinely failed mutation from one that already persisted and only afterwards tripped an EDT
     * UI listener (see {@link #onUiThread}).
     */
    public Optional<WebServer> findServer(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(gateway.getWebServerManager().get(name));
        } catch (RuntimeException e) {
            LOG.warn("Could not re-read web server '%s' from the EDT registry: %s", name, e.getMessage()); //$NON-NLS-1$
            return Optional.empty();
        }
    }

    /**
     * Registers a web server in the EDT registry, idempotently: when a server with the same name
     * already exists with the same install/config locations it is returned as-is; a conflicting
     * existing registration is an error (use a different name or fix the registry in EDT).
     *
     * <p>Manual registration is the only path for portable Apache installs — EDT's
     * {@code search()} discovery scans the registry/SC/WMIC for installed services and never sees
     * a foreground {@code httpd.exe}.</p>
     */
    public WebServer registerServer(String name, Path installLocation, Path configLocation, String apacheVersion,
            String arch) {
        if (name == null || name.isBlank()) {
            throw new EdtToolException(EdtToolErrorCode.INVALID_ARGUMENT, "Web server name is required"); //$NON-NLS-1$
        }
        if (installLocation == null || !Files.isDirectory(installLocation)) {
            throw new EdtToolException(EdtToolErrorCode.INVALID_PATH,
                    "install_location must be an existing directory: " + installLocation); //$NON-NLS-1$
        }
        if (configLocation == null || !Files.isRegularFile(configLocation)) {
            throw new EdtToolException(EdtToolErrorCode.INVALID_PATH,
                    "config_location must be an existing httpd conf file: " + configLocation); //$NON-NLS-1$
        }
        IWebServerManager manager = gateway.getWebServerManager();
        WebServer existing = manager.get(name);
        if (existing != null) {
            if (samePath(existing.getInstallLocation(), installLocation)
                    && samePath(existing.getConfigLocation(), configLocation)) {
                return existing;
            }
            throw new EdtToolException(EdtToolErrorCode.WEB_SERVER_EXISTS,
                    "Web server '" + name + "' is already registered with different locations (install=" //$NON-NLS-1$ //$NON-NLS-2$
                            + existing.getInstallLocation() + ", config=" + existing.getConfigLocation() + ")"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        WebServer server = ModelFactory.eINSTANCE.createWebServer();
        server.setName(name);
        server.setTypeId(apacheTypeId(apacheVersion));
        server.setInstallLocation(installLocation);
        server.setConfigLocation(configLocation);
        server.setArch("x86".equalsIgnoreCase(arch) ? Arch.X86 : Arch.X86_64); //$NON-NLS-1$
        // add() persists and then fires its change event synchronously on THIS thread -> UI hop.
        onUiThread(() -> {
            manager.add(server);
            return Boolean.TRUE;
        });
        LOG.info("Registered web server '%s' (%s, install=%s, config=%s)", name, server.getTypeId(), //$NON-NLS-1$
                installLocation, configLocation);
        return server;
    }

    public WebServer requireServer(String name) {
        WebServer server = name == null ? null : gateway.getWebServerManager().get(name);
        if (server == null) {
            List<String> known = new ArrayList<>(WebServers.getNames(gateway.getWebServerManager().getAll()));
            throw new EdtToolException(EdtToolErrorCode.WEB_SERVER_NOT_FOUND,
                    "Web server not registered in EDT: '" + name + "'. Known: " + known //$NON-NLS-1$ //$NON-NLS-2$
                            + ". Use action=register_server for portable installs (search() cannot discover them)."); //$NON-NLS-1$
        }
        return server;
    }

    // -- publications -----------------------------------------------------------------------

    public List<Publication> listPublications(String serverName) {
        WebServer server = requireServer(serverName);
        try {
            return publicationsInConf(server);
        } catch (WebServerAccessException e) {
            throw accessFailed("list publications", serverName, e); //$NON-NLS-1$
        }
    }

    /**
     * The publications the web server's conf actually declares, re-parsed on every call.
     *
     * <p>Deliberately NOT {@code IPublicationManager.getAll}: that one serves a per-{@code WebServer}
     * EMF Resource built once through {@code computeIfAbsent} and dropped only from the manager's own
     * {@code firePublishedEvent}/{@code firePublicationRemovedEvent} (decompile of services.core 21.0
     * {@code PublicationManager}). {@link #publish} must drive the delegate directly — the manager
     * hands the delegate a null web-extension Path — so those events never fire, and the existence
     * check {@code publish} takes beforehand froze an EMPTY snapshot: every later get/list/remove
     * answered PUBLICATION_NOT_FOUND for an alias Apache had been serving for hours (feedback
     * 2026-08-04 …remove-get-list-lose-track-of-successfully-published-alias). The delegate reads the
     * conf itself, so it cannot drift from the file.</p>
     */
    private List<Publication> publicationsInConf(WebServer server) throws WebServerAccessException {
        IWebServerPublishDelegate delegate =
                gateway.getWebServerPublishDelegateRegistry().getDelegate(server.getTypeId());
        if (delegate == null) {
            return gateway.getPublicationManager().getAll(server);
        }
        return new ArrayList<>(delegate.getAll(server));
    }

    public Publication getPublication(String serverName, String name) {
        WebServer server = requireServer(serverName);
        try {
            return findPublication(server, name);
        } catch (WebServerAccessException e) {
            throw accessFailed("read publication '" + name + "'", serverName, e); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * Resolves a publication by name tolerating a leading or trailing slash on either side. EDT
     * stores the Apache publication name with a trailing slash (e.g. {@code agent-current/}, the form
     * {@code list} returns) while the schema documents the bare alias, and the conf's own
     * {@code Alias "/agent-current"} carries a leading one — so every comparison here is on the
     * slash-stripped form. Returns {@code null} when no publication matches.
     */
    private Publication findPublication(WebServer server, String name) throws WebServerAccessException {
        String wanted = stripSlashes(name);
        for (Publication candidate : publicationsInConf(server)) {
            if (wanted.equals(stripSlashes(candidate.getName()))) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * The published URL, computed from the conf. Goes through the delegate rather than
     * {@code IPublicationManager.getPublicationUrl}, whose {@code publicationUris} cache is only
     * cleared by the manager's own publish/remove/restart — which {@link #publish} bypasses (see
     * {@link #publicationsInConf}) — and whose cache key even compares its own publication name to
     * itself instead of the other key's ({@code PublicationManager.PublicationKey.equals}).
     */
    public Optional<URL> getPublicationUrl(String serverName, String name) {
        WebServer server = requireServer(serverName);
        try {
            IWebServerPublishDelegate delegate =
                    gateway.getWebServerPublishDelegateRegistry().getDelegate(server.getTypeId());
            if (delegate != null) {
                return Optional.ofNullable(delegate.getPublicationUrl(server, name));
            }
            return Optional.ofNullable(gateway.getPublicationManager().getPublicationUrl(server, name));
        } catch (RuntimeException e) {
            LOG.warn("Could not compute publication URL for '%s' on '%s': %s", name, serverName, e.getMessage()); //$NON-NLS-1$
            return Optional.empty();
        }
    }

    /**
     * Publishes (or idempotently re-publishes) an infobase under {@code name} on the web server.
     *
     * <p>When {@code wsapVersion} is set, the wsap module path is resolved from that pinned
     * platform installation and handed to the publish delegate directly — the conf's
     * {@code LoadModule _1cws_module} line is rewritten to the pinned module. When unset, EDT's
     * default resolution applies: an existing {@code LoadModule} line in the conf wins (the
     * delegate reads it back), so an already-pinned conf keeps its module.</p>
     */
    public InfobasePublication publish(String serverName, String name, Path location, String infobaseConnection,
            String wsapVersion) {
        return publish(serverName, name, location, infobaseConnection, wsapVersion, null);
    }

    /**
     * Publish overload that also carries {@link PublicationExtras} (custom HTTP services / OData /
     * analytics / pool) into the generated vrd — see {@link PublicationExtras}. A null/empty
     * {@code extras} is identical to the bare overload.
     */
    public InfobasePublication publish(String serverName, String name, Path location, String infobaseConnection,
            String wsapVersion, PublicationExtras extras) {
        WebServer server = requireServer(serverName);
        if (name == null || name.isBlank()) {
            throw new EdtToolException(EdtToolErrorCode.INVALID_ARGUMENT, "Publication name is required"); //$NON-NLS-1$
        }
        if (infobaseConnection == null || infobaseConnection.isBlank()) {
            throw new EdtToolException(EdtToolErrorCode.INVALID_ARGUMENT,
                    "Infobase connection string is required (e.g. File=\"C:\\db\\demo\";)"); //$NON-NLS-1$
        }
        IPublicationManager manager = gateway.getPublicationManager();
        Path effectiveLocation = location;
        if (effectiveLocation == null) {
            Path defaultRoot = manager.getDefaultPublicationLocation(server);
            if (defaultRoot == null) {
                throw new EdtToolException(EdtToolErrorCode.INVALID_ARGUMENT,
                        "location is required: the web server reports no default publication root"); //$NON-NLS-1$
            }
            effectiveLocation = defaultRoot.resolve(name);
        }
        try {
            Files.createDirectories(effectiveLocation);
        } catch (IOException e) {
            throw new EdtToolException(EdtToolErrorCode.INVALID_PATH,
                    "Cannot create publication directory " + effectiveLocation + ": " + e.getMessage(), e); //$NON-NLS-1$ //$NON-NLS-2$
        }

        // Re-point idempotency: when a publication already exists for this name (slash-tolerant),
        // reuse its exact stored name so the delegate updates that alias's blocks in place instead
        // of writing a second, differently-named publication next to it.
        String effectiveName = name;
        try {
            Publication existing = findPublication(server, name);
            if (existing != null && existing.getName() != null) {
                effectiveName = existing.getName();
            }
        } catch (WebServerAccessException e) {
            throw accessFailed("inspect existing publication '" + name + "'", serverName, e); //$NON-NLS-1$ //$NON-NLS-2$
        }

        InfobasePublication publication = ModelFactory.eINSTANCE.createInfobasePublication();
        publication.setName(effectiveName);
        publication.setLocation(vrdLocation(effectiveLocation.toString(), effectiveName));
        publication.setInfobaseConnection(infobaseConnection);
        publication.setEnable(true);
        applyExtras(publication, extras);

        try {
            if (wsapVersion != null && !wsapVersion.isBlank()) {
                ResolvedWebExtension wsap = resolveWebExtension(server, wsapVersion);
                IWebServerPublishDelegate delegate = gateway.getWebServerPublishDelegateRegistry()
                        .getDelegate(wsap.installation(), server.getTypeId());
                if (delegate == null) {
                    throw new EdtToolException(EdtToolErrorCode.WEB_SERVER_ACCESS_FAILED,
                            "No publish delegate registered for web server type " + server.getTypeId()); //$NON-NLS-1$
                }
                delegate.publish(publication, server, wsap.modulePath());
            } else {
                // PublicationManager.publish(pub, server) hands the delegate a NULL web-extension Path
                // (verified in services.core 21.0 bytecode: it does aconst_null), which the Apache delegate
                // dereferences with .toString() when the vrd carries <httpServices> -> NPE (feedback
                // 2026-07-16-web-publication-publish-npe-webextensions-null; unconditional on
                // publishExtensionsByDefault, which is why the earlier fix did not gate it). Resolve the
                // wsap module ourselves — getWebExtension reads it from the conf's LoadModule _1cws_module —
                // and drive the delegate directly with a non-null Path, mirroring the wsap_version branch.
                IWebServerPublishDelegate delegate =
                        gateway.getWebServerPublishDelegateRegistry().getDelegate(server.getTypeId());
                if (delegate == null) {
                    throw new EdtToolException(EdtToolErrorCode.WEB_SERVER_ACCESS_FAILED,
                            "No publish delegate registered for web server type " + server.getTypeId()); //$NON-NLS-1$
                }
                Path webExtension = manager.getWebExtension(publication, server);
                if (webExtension == null) {
                    throw new EdtToolException(EdtToolErrorCode.WEB_EXTENSION_NOT_FOUND,
                            "Could not resolve the wsap web-extension module for '" + name //$NON-NLS-1$
                                    + "' from the web server conf (no LoadModule _1cws_module?). " //$NON-NLS-1$
                                    + "Pass wsap_version to pin it explicitly."); //$NON-NLS-1$
                }
                delegate.publish(publication, server, webExtension);
            }
        } catch (WebServerAccessException e) {
            throw accessFailed("publish '" + name + "'", serverName, e); //$NON-NLS-1$ //$NON-NLS-2$
        } catch (EdtToolException e) {
            throw e;
        } catch (RuntimeException e) {
            // Never surface a raw NPE/ISE from EDT's publish delegate as an unstructured "Tool execution
            // failed" (feedback 2026-07-16-web-publication-publish-npe-webextensions-null): the delegate
            // can throw when it enumerates extension web services without a resolved project/platform
            // context. Wrap into a structured tool error naming the operation.
            throw new EdtToolException(EdtToolErrorCode.WEB_SERVER_ACCESS_FAILED,
                    "publish '" + name + "' on '" + serverName + "' failed inside the EDT publish delegate: " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                            + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()), e);
        }
        refreshManagerSnapshot(server, publication);
        LOG.info("Published '%s' on '%s' (location=%s)", name, serverName, effectiveLocation); //$NON-NLS-1$
        return publication;
    }

    /**
     * Best-effort: tell {@code PublicationManager} to drop its cached publication snapshot for this
     * server after we published straight through the delegate. Our own reads no longer depend on it
     * ({@link #publicationsInConf}), but EDT's publication editors and anything else on
     * {@code IPublicationManager} would otherwise keep serving the pre-publish snapshot for the rest
     * of the EDT session. {@code firePublishedEvent} is the manager's own invalidation hook — public
     * on the implementation, absent from {@code IPublicationManager}, hence reflection; a miss (other
     * EDT version, renamed hook) is logged and ignored, never a failed publish.
     */
    private void refreshManagerSnapshot(WebServer server, Publication publication) {
        IPublicationManager manager = gateway.getPublicationManager();
        try {
            Method hook = manager.getClass().getMethod("firePublishedEvent", WebServer.class, Publication.class); //$NON-NLS-1$
            // The hook notifies publication editors synchronously on the calling thread -> UI hop.
            onUiThread(() -> {
                try {
                    hook.invoke(manager, server, publication);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
                return Boolean.TRUE;
            });
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.warn("Published '%s', but could not refresh EDT's cached publication list (%s). " //$NON-NLS-1$
                    + "The EDT publication editor may still show the pre-publish state; the tool's own " //$NON-NLS-1$
                    + "list/get read the conf directly and are unaffected.", //$NON-NLS-1$
                    publication.getName(), e.toString());
        }
    }

    /**
     * Populates optional {@link PublicationExtras} onto a freshly-built publication so the publish
     * delegate serializes it into the vrd. No-op for null/empty extras (identical to the bare publish).
     * Custom HTTP services set name/rootUrl/enable per entry; per-service pool tuning is not modelled
     * (only the publication-level {@link Pool}) — see {@link PublicationExtras}.
     */
    private void applyExtras(InfobasePublication publication, PublicationExtras extras) {
        if (extras == null || extras.isEmpty()) {
            return;
        }
        boolean hasServices = extras.httpServices() != null && !extras.httpServices().isEmpty();
        if (hasServices || extras.publishHttpByDefault() != null
                || extras.publishExtensionsByDefault() != null) {
            HttpServices httpServices = ModelFactory.eINSTANCE.createHttpServices();
            httpServices.setPublishByDefault(Boolean.TRUE.equals(extras.publishHttpByDefault()));
            // Extension HTTP services: only override when the caller explicitly asks. The earlier forced
            // `false` (commit 38a6066) was a workaround for an NPE that was actually the NULL web-extension
            // Path that IPublicationManager.publish hands the delegate (aconst_null) — now fixed by driving
            // the delegate directly with a non-null Path (see #publish); the delegate does NOT enumerate
            // project extensions, so `true` is safe here. `false` instead SUPPRESSED the extension that
            // owns the published <service> (e.g. BSLAnalyzerService in the BSL_Analyzer extension) → a 404
            // for the fleet's primary use case (feedback 2026-07-18 …delegateregistry-unavailable, Gap B).
            // Leaving it unset keeps EMF's default (true), matching the hand-authored vrd that served it.
            if (extras.publishExtensionsByDefault() != null) {
                httpServices.setPublishExtensionsByDefault(extras.publishExtensionsByDefault().booleanValue());
            }
            if (extras.httpServices() != null) {
                for (HttpServiceSpec spec : extras.httpServices()) {
                    if (spec == null || spec.name() == null || spec.name().isBlank()) {
                        continue;
                    }
                    HttpService service = ModelFactory.eINSTANCE.createHttpService();
                    service.setName(spec.name());
                    if (spec.rootUrl() != null && !spec.rootUrl().isBlank()) {
                        service.setRootUrl(spec.rootUrl());
                    }
                    service.setEnable(spec.enable());
                    httpServices.getServices().add(service);
                }
            }
            publication.setHttpServices(httpServices);
        }
        if (extras.publishWebByDefault() != null) {
            WebServices webServices = ModelFactory.eINSTANCE.createWebServices();
            webServices.setPublishExtensionsByDefault(extras.publishWebByDefault().booleanValue());
            publication.setWebServices(webServices);
        }
        if (extras.enableStandardOData() != null) {
            boolean enabled = extras.enableStandardOData().booleanValue();
            publication.setEnableStandardOData(enabled);
            OData odata = ModelFactory.eINSTANCE.createOData();
            odata.setEnable(enabled);
            publication.setStandardOdata(odata);
        }
        if (extras.enableSystemAnalytics() != null) {
            publication.setEnableSystemAnalytics(extras.enableSystemAnalytics().booleanValue());
        }
        if (extras.pool() != null) {
            Pool pool = ModelFactory.eINSTANCE.createPool();
            if (extras.pool().size() != null) {
                pool.setSize(extras.pool().size().intValue());
            }
            if (extras.pool().maxAge() != null) {
                pool.setMaxAge(extras.pool().maxAge().intValue());
            }
            publication.setPool(pool);
        }
    }

    public boolean removePublication(String serverName, String name) {
        WebServer server = requireServer(serverName);
        IPublicationManager manager = gateway.getPublicationManager();
        Publication publication;
        try {
            publication = findPublication(server, name);
        } catch (WebServerAccessException e) {
            throw accessFailed("remove publication '" + name + "'", serverName, e); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (publication == null) {
            throw new EdtToolException(EdtToolErrorCode.PUBLICATION_NOT_FOUND,
                    "Publication '" + name + "' not found on web server '" + serverName + "'"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        Publication target = publication;
        // PublicationManager.remove fires firePublicationRemovedEvent synchronously on THIS thread and
        // AbstractPublicationEditor.publicationRemoved answers it with an unguarded close(false) -> UI hop.
        return onUiThread(() -> {
            try {
                return Boolean.valueOf(manager.remove(server, target));
            } catch (WebServerAccessException e) {
                throw accessFailed("remove publication '" + name + "'", serverName, e); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }).booleanValue();
    }

    // -- restart / probe --------------------------------------------------------------------

    /**
     * Restarts the web server so conf changes take effect.
     *
     * <p>Tries EDT's own delegate first (works for services / embedded Jetty). On Windows the
     * Apache delegate's {@code restart()} is a no-op returning {@code false}, and
     * {@code httpd -k restart} only signals a registered <em>service</em> (AH00436 otherwise) —
     * so for foreground/portable installs this falls back to kill+start: terminate every
     * {@code httpd.exe} whose binary lives under the server's install root, then start
     * {@code bin/httpd.exe -d <install> -f <config>} detached.</p>
     */
    public RestartOutcome restartServer(String serverName) {
        WebServer server = requireServer(serverName);
        try {
            if (gateway.getPublicationManager().restart(server)) {
                return new RestartOutcome("edt_delegate", List.of(), -1L, ""); //$NON-NLS-1$ //$NON-NLS-2$
            }
        } catch (WebServerAccessException e) {
            LOG.warn("EDT delegate restart failed for '%s', falling back to kill+start: %s", serverName, //$NON-NLS-1$
                    e.getMessage());
        }
        return killAndStart(server);
    }

    private RestartOutcome killAndStart(WebServer server) {
        Path install = server.getInstallLocation();
        Path config = server.getConfigLocation();
        if (install == null || config == null) {
            throw new EdtToolException(EdtToolErrorCode.WEB_SERVER_RESTART_FAILED,
                    "Web server '" + server.getName() + "' has no install/config location for kill+start restart"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        Path httpd = install.resolve("bin").resolve(httpdExecutableName()); //$NON-NLS-1$
        if (!Files.isRegularFile(httpd)) {
            throw new EdtToolException(EdtToolErrorCode.WEB_SERVER_RESTART_FAILED,
                    "httpd executable not found: " + httpd); //$NON-NLS-1$
        }

        List<Long> stopped = stopHttpdProcesses(install);

        ProcessBuilder builder = new ProcessBuilder(httpd.toString(),
                "-d", install.toString().replace('\\', '/'), //$NON-NLS-1$
                "-f", config.toString().replace('\\', '/')); //$NON-NLS-1$
        builder.directory(install.toFile());
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.redirectErrorStream(true);
        try {
            Process process = startProcess(builder);
            // Foreground httpd that dies within ~1.5s almost certainly hit a conf error; surface
            // it now instead of leaving the caller with a silently-down web server.
            if (process.waitFor(1500, TimeUnit.MILLISECONDS)) {
                throw new EdtToolException(EdtToolErrorCode.WEB_SERVER_RESTART_FAILED,
                        "httpd exited immediately with code " + process.exitValue() //$NON-NLS-1$
                                + " — check the conf (" + config + ") and the Apache error log"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            return new RestartOutcome("kill_start", stopped, process.pid(), //$NON-NLS-1$
                    String.join(" ", builder.command())); //$NON-NLS-1$
        } catch (IOException e) {
            throw new EdtToolException(EdtToolErrorCode.WEB_SERVER_RESTART_FAILED,
                    "Failed to start httpd: " + e.getMessage(), e); //$NON-NLS-1$
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EdtToolException(EdtToolErrorCode.WEB_SERVER_RESTART_FAILED,
                    "Interrupted while restarting httpd"); //$NON-NLS-1$
        }
    }

    /**
     * Terminates every running httpd process whose executable lives under {@code installRoot}.
     * Matching by binary path (not by bare image name) keeps other Apache instances on the same
     * box alive. Processes from other user sessions may refuse termination — those are reported
     * in the log but do not fail the restart (the subsequent bind would fail loudly anyway).
     */
    private List<Long> stopHttpdProcesses(Path installRoot) {
        String rootPrefix = installRoot.toString().toLowerCase(Locale.ROOT);
        List<Long> stopped = new ArrayList<>();
        List<ProcessHandle> targets = new ArrayList<>();
        ProcessHandle.allProcesses().forEach(handle -> {
            String command = handle.info().command().orElse(""); //$NON-NLS-1$
            if (!command.isEmpty() && command.toLowerCase(Locale.ROOT).startsWith(rootPrefix)) {
                targets.add(handle);
            }
        });
        for (ProcessHandle handle : targets) {
            long pid = handle.pid();
            if (handle.destroyForcibly()) {
                stopped.add(pid);
            } else {
                LOG.warn("Could not terminate httpd pid %d (other session / access denied)", pid); //$NON-NLS-1$
            }
        }
        long deadline = System.currentTimeMillis() + PROCESS_STOP_TIMEOUT_MS;
        for (ProcessHandle handle : targets) {
            while (handle.isAlive() && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(100L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return stopped;
    }

    /**
     * HTTP GET reachability probe for a freshly (re)published endpoint. When {@code user} is set,
     * sends HTTP Basic auth — 1C HTTP/web services with mandatory authentication answer 401
     * without it, so an unauthenticated probe is useless as a success gate (a live, auth-required
     * endpoint and a down one both look like failure).
     */
    public ProbeOutcome probe(String url, int timeoutMs, String user, String password) {
        long start = System.currentTimeMillis();
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(timeoutMs);
            connection.setReadTimeout(timeoutMs);
            connection.setRequestMethod("GET"); //$NON-NLS-1$
            if (user != null && !user.isBlank()) {
                String credentials = user + ":" + (password == null ? "" : password); //$NON-NLS-1$ //$NON-NLS-2$
                String token = java.util.Base64.getEncoder().encodeToString(
                        credentials.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                connection.setRequestProperty("Authorization", "Basic " + token); //$NON-NLS-1$ //$NON-NLS-2$
            }
            int status = connection.getResponseCode();
            connection.disconnect();
            return new ProbeOutcome(status, System.currentTimeMillis() - start);
        } catch (IOException e) {
            throw new EdtToolException(EdtToolErrorCode.PROBE_FAILED,
                    "Probe of " + url + " failed: " + e.getMessage(), e); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    // -- internals --------------------------------------------------------------------------

    /**
     * Runs an EDT registry mutation on the SWT UI thread when there is one.
     *
     * <p>Why (decompile audit of services.core 21.0): {@code IWebServerManager.add} and
     * {@code IPublicationManager.remove} both persist first ({@code save(webServers)} / vrd rewrite)
     * and only then fire their change event <em>synchronously on the calling thread</em>. Several
     * {@code com._1c.g5.v8.dt.platform.services.ui} listeners touch widgets straight from that
     * callback — {@code WebServerEditor.webServersReloaded} re-{@code bind}s, and
     * {@code AbstractPublicationEditor}/{@code InfobasePublicationEditor} call {@code close(false)} —
     * so a mutation issued from an MCP worker thread ends in a raw
     * {@code SWTException: Invalid thread access} <em>after</em> the change is already saved. The
     * trigger is an OPEN web-server/publication editor, not an open view ({@code WebServersView} is
     * {@code asyncExec}-guarded).</p>
     *
     * <p>Headless (no workbench / disposed display) and already-on-the-UI-thread both run the body
     * inline. Deliberately not {@code UiThreadExecutor}: that one reports failures as
     * {@code EdtAstException}, the wrong error family for this service. {@code protected} so tests
     * can replace the hop.</p>
     */
    protected <T> T onUiThread(Supplier<T> body) {
        Display display = uiThreadDisplay();
        if (display == null || display.getThread() == Thread.currentThread()) {
            return body.get();
        }
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        display.syncExec(() -> {
            try {
                result.set(body.get());
            } catch (RuntimeException e) {
                failure.set(e);
            }
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return result.get();
    }

    /**
     * The workbench display to hop onto, or {@code null} when running headless. Never calls
     * {@code Display.getDefault()} — that would CREATE a display on the calling worker thread
     * instead of finding the UI one.
     */
    private static Display uiThreadDisplay() {
        try {
            if (!PlatformUI.isWorkbenchRunning()) {
                return null;
            }
            Display display = PlatformUI.getWorkbench().getDisplay();
            return display == null || display.isDisposed() ? null : display;
        } catch (RuntimeException | LinkageError e) {
            LOG.debug("No UI display for the EDT registry mutation, running inline: %s", e.toString()); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Resolves the wsap web-extension module (e.g. {@code wsap24.dll}) from the platform
     * installation pinned by {@code versionMask} — never from "newest installed". This is the
     * publication-side twin of the {@code runtime_version} pin: without it a freshly installed
     * pre-release platform would be picked for new confs.
     */
    private ResolvedWebExtension resolveWebExtension(WebServer server, String versionMask) {
        String componentTypeId = WebServers.getWebExtensionsComponent(server.getTypeId());
        if (componentTypeId == null) {
            throw new EdtToolException(EdtToolErrorCode.WEB_EXTENSION_NOT_FOUND,
                    "Web server type " + server.getTypeId() + " has no web-extension component mapping"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        IResolvableRuntimeInstallation resolvable;
        try {
            resolvable = gateway.getResolvableRuntimeInstallationManager()
                    .resolveByVersionOrMask(RUNTIME_TYPE_ENTERPRISE_PLATFORM, versionMask);
        } catch (MatchingRuntimeNotFound e) {
            throw new EdtToolException(EdtToolErrorCode.RUNTIME_VERSION_NOT_FOUND,
                    "No installed 1C platform matches wsap_version '" + versionMask + "'", e); //$NON-NLS-1$ //$NON-NLS-2$
        }
        IRuntimeComponentManager componentManager = gateway.getRuntimeComponentManager();
        try {
            AppArch appArch = server.getArch() == Arch.X86 ? AppArch.X86 : AppArch.X86_64;
            RuntimeInstallation installation = resolvable.resolve(List.of(componentTypeId), appArch);
            IRuntimeComponent component = componentManager.getComponent(installation, componentTypeId);
            if (component == null || component.getLocation() == null) {
                throw new EdtToolException(EdtToolErrorCode.WEB_EXTENSION_NOT_FOUND,
                        "Platform " + versionMask + " is installed without the web-server extension component (" //$NON-NLS-1$ //$NON-NLS-2$
                                + componentTypeId + "). Install 'Web server extension modules' for that version."); //$NON-NLS-1$
            }
            return new ResolvedWebExtension(installation, webExtensionModule(component, server, versionMask));
        } catch (MatchingRuntimeNotFound e) {
            throw new EdtToolException(EdtToolErrorCode.WEB_EXTENSION_NOT_FOUND,
                    "Platform " + versionMask + " has no web-server extension component for " //$NON-NLS-1$ //$NON-NLS-2$
                            + server.getTypeId() + ": " + e.getMessage(), e); //$NON-NLS-1$
        }
    }

    /**
     * The wsap module FILE the publish delegate must write into {@code LoadModule _1cws_module}.
     *
     * <p>{@code IRuntimeComponent.getLocation()} is the platform's {@code bin} DIRECTORY, and that
     * directory carries every wsap flavour at once ({@code wsapch2.dll}, {@code wsap22.dll},
     * {@code wsap24.dll}). {@code ApachePublishDelegateWin32.formatPathToWebExtensionComponent} only
     * quotes what it is handed, so passing the directory wrote a {@code LoadModule} Apache refuses to
     * parse — httpd then exited with code 1 before it could even open its error log (feedback
     * 2026-08-04 …wsap-version-writes-directory-not-dll). EDT's own reader confirms the expected shape:
     * its {@code RUNTIME_PATH} pattern only matches a path ending in {@code wsap*|wsisapi.(dll|so)}.</p>
     *
     * <p>EDT models the component as {@link ILaunchableRuntimeComponent}, whose {@code getFile()} IS
     * the module — that is the primary path here. The by-name fallback mirrors EDT's own
     * {@code IRuntimeComponentFileNames} table for a component that is not launchable.</p>
     */
    private static Path webExtensionModule(IRuntimeComponent component, WebServer server, String versionMask) {
        if (component instanceof ILaunchableRuntimeComponent) {
            File file = ((ILaunchableRuntimeComponent)component).getFile();
            if (file != null) {
                return file.toPath();
            }
        }
        Path location = Paths.get(component.getLocation());
        Path module = wsapModule(location, server.getTypeId());
        if (module == null) {
            throw new EdtToolException(EdtToolErrorCode.WEB_EXTENSION_NOT_FOUND,
                    "Platform " + versionMask + " exposes its web-server extension as '" + location //$NON-NLS-1$ //$NON-NLS-2$
                            + "', which holds no module for web server type " + server.getTypeId() //$NON-NLS-1$
                            + " (expected " + wsapModuleFileName(server.getTypeId()) //$NON-NLS-1$
                            + " there). Install 'Web server extension modules' for that version."); //$NON-NLS-1$
        }
        return module;
    }

    /**
     * Resolves the wsap module inside a platform {@code bin} directory for a web-server type; returns
     * {@code location} unchanged when it already IS a file, and {@code null} when nothing matches.
     * Package visible so a plain JUnit test can drive it without an EDT runtime.
     */
    static Path wsapModule(Path location, String webServerTypeId) {
        if (location == null) {
            return null;
        }
        if (Files.isRegularFile(location)) {
            return location;
        }
        String moduleFileName = wsapModuleFileName(webServerTypeId);
        if (moduleFileName == null) {
            return null;
        }
        Path module = location.resolve(moduleFileName);
        if (Files.isRegularFile(module)) {
            return module;
        }
        // Non-Windows platforms ship the same module as a shared object under the same base name.
        Path sharedObject = location.resolve(moduleFileName.replace(".dll", ".so")); //$NON-NLS-1$ //$NON-NLS-2$
        return Files.isRegularFile(sharedObject) ? sharedObject : null;
    }

    /**
     * EDT's own web-server-type → module-file table ({@code IRuntimeComponentFileNames}); {@code null}
     * for a type that has no wsap module (e.g. Jetty).
     */
    static String wsapModuleFileName(String webServerTypeId) {
        if (webServerTypeId == null) {
            return null;
        }
        if (IWebServerTypes.APACHE_2_0.equals(webServerTypeId)) {
            return "wsapch2.dll"; //$NON-NLS-1$
        }
        if (IWebServerTypes.APACHE_2_2.equals(webServerTypeId)) {
            return "wsap22.dll"; //$NON-NLS-1$
        }
        if (IWebServerTypes.APACHE_2_4.equals(webServerTypeId)) {
            return "wsap24.dll"; //$NON-NLS-1$
        }
        return webServerTypeId.startsWith(IIS_TYPE_ID_PREFIX) ? "wsisapi.dll" : null; //$NON-NLS-1$
    }

    /** Hook for tests. */
    protected Process startProcess(ProcessBuilder builder) throws IOException {
        return builder.start();
    }

    /** Hook for tests / future Linux support. */
    protected String httpdExecutableName() {
        return "httpd.exe"; //$NON-NLS-1$
    }

    private static String apacheTypeId(String apacheVersion) {
        String version = apacheVersion == null || apacheVersion.isBlank() ? "2.4" : apacheVersion.trim(); //$NON-NLS-1$
        switch (version) {
        case "2.0": //$NON-NLS-1$
            return IWebServerTypes.APACHE_2_0;
        case "2.2": //$NON-NLS-1$
            return IWebServerTypes.APACHE_2_2;
        case "2.4": //$NON-NLS-1$
            return IWebServerTypes.APACHE_2_4;
        default:
            throw new EdtToolException(EdtToolErrorCode.INVALID_ARGUMENT,
                    "Unsupported Apache version '" + apacheVersion + "' (expected 2.0, 2.2 or 2.4)"); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * Aligns the publication's on-disk location with the Apache {@code Alias} the EDT delegate writes.
     * The delegate emits {@code Alias "/<name>" "<location>"} with {@code <location>} taken from
     * {@code publication.getLocation()} verbatim, while the URL side carries {@code <name>} — which, on
     * a re-point, is EDT's stored publication name and ends with {@code /}. Apache then resolves a
     * sub-path request by concatenating the unmatched remainder onto the target with NO separator, so a
     * slash-terminated alias against a non-terminated target yields {@code ...\agent-currenths\...}
     * (AH01630 → 403). When the name is slash-terminated we therefore give the location a matching
     * trailing separator; a bare name (fresh publish) keeps the remainder's own leading slash and is
     * left unchanged. (feedback 2026-07-18 …webserverpublishdelegateregistry-unavailable, Gap A)
     */
    public static String vrdLocation(String location, String publicationName) {
        if (location == null || location.isEmpty()) {
            return location;
        }
        boolean nameSlashTerminated = publicationName != null
                && (publicationName.endsWith("/") || publicationName.endsWith("\\")); //$NON-NLS-1$ //$NON-NLS-2$
        boolean locationSeparated = location.endsWith("/") || location.endsWith("\\"); //$NON-NLS-1$ //$NON-NLS-2$
        if (nameSlashTerminated && !locationSeparated) {
            return location + File.separator;
        }
        return location;
    }

    private static String stripTrailingSlash(String name) {
        if (name == null) {
            return ""; //$NON-NLS-1$
        }
        String trimmed = name.trim();
        while (trimmed.endsWith("/") || trimmed.endsWith("\\")) { //$NON-NLS-1$ //$NON-NLS-2$
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    /**
     * The comparable form of a publication alias: no surrounding whitespace, no leading and no
     * trailing slash. Package visible so a plain JUnit test can pin the matching rule.
     */
    static String stripSlashes(String name) {
        String trimmed = stripTrailingSlash(name);
        while (trimmed.startsWith("/") || trimmed.startsWith("\\")) { //$NON-NLS-1$ //$NON-NLS-2$
            trimmed = trimmed.substring(1);
        }
        return trimmed;
    }

    private static boolean samePath(Path left, Path right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.normalize().toString().equalsIgnoreCase(right.normalize().toString());
    }

    private static EdtToolException accessFailed(String operation, String serverName, WebServerAccessException e) {
        return new EdtToolException(EdtToolErrorCode.WEB_SERVER_ACCESS_FAILED,
                "Failed to " + operation + " on web server '" + serverName + "': " + e.getMessage(), e); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** Type of the publication for result rendering. */
    public static String publicationKind(Publication publication) {
        PublicationType type = publication.getPublicationType();
        return type == null ? "unknown" : type.getLiteral(); //$NON-NLS-1$
    }
}
