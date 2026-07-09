package com.codepilot1c.core.edt.runtime;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import com._1c.g5.v8.dt.core.platform.IExtensionProject;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAccessManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAccessSettings;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociation;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAccessType;
import com._1c.g5.v8.dt.platform.services.core.runtimes.environments.IResolvableRuntimeInstallation;
import com._1c.g5.v8.dt.platform.services.core.runtimes.environments.IResolvableRuntimeInstallationManager;
import com._1c.g5.v8.dt.platform.services.core.runtimes.environments.MatchingRuntimeNotFound;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.ComponentExecutorInfo;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.ILaunchableRuntimeComponent;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IRuntimeComponentManager;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IRuntimeComponentManager.ThickClientInfo;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IThickClientLauncher;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IThinClientLauncher;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.RuntimeExecutionException;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.impl.RuntimeExecutionCommandBuilder;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.impl.RuntimeExecutionCommandBuilder.ThickClientMode;
import com._1c.g5.v8.dt.platform.services.model.AppArch;
import com._1c.g5.v8.dt.platform.services.model.InfobaseAccess;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.services.model.RuntimeInstallation;
import com.codepilot1c.core.edt.runtime.lease.InfobaseLease;
import com.codepilot1c.core.edt.runtime.lease.InfobaseLeaseGuard;
import com.codepilot1c.core.logging.VibeLogger;
import com.e1c.g5.v8.dt.platform.standaloneserver.wst.core.IStandaloneServerService;
import com.e1c.g5.v8.dt.platform.standaloneserver.wst.core.StandaloneServerInfobase;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.wst.server.core.IModule;
import org.eclipse.wst.server.core.IServer;

public class EdtRuntimeService {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(EdtRuntimeService.class);

    /**
     * Runtime-type and component-type ids used to resolve the thick client launcher. EDT 2025.2
     * (services.core 21.x) dropped the {@code IRuntimeComponentManager.getThickClientInfo(...)}
     * convenience methods; their former bodies resolved an {@link IResolvableRuntimeInstallation}
     * for these ids and delegated to {@code resolveExecutor(...)}. We inline that logic in
     * {@link #resolveThickClient}.
     */
    private static final String RUNTIME_TYPE_ENTERPRISE_PLATFORM =
            "com._1c.g5.v8.dt.platform.services.core.runtimeType.EnterprisePlatform"; //$NON-NLS-1$
    private static final String COMPONENT_TYPE_THICK_CLIENT =
            "com._1c.g5.v8.dt.platform.services.core.componentTypes.ThickClient"; //$NON-NLS-1$
    private static final String COMPONENT_TYPE_THIN_CLIENT =
            "com._1c.g5.v8.dt.platform.services.core.componentTypes.ThinClient"; //$NON-NLS-1$

    private final EdtRuntimeGateway gateway;
    private final InfobaseLeaseGuard leaseGuard;

    public static final class AccessSettings {
        private final boolean osAuthentication;
        private final boolean infobaseAuthentication;
        private final String userName;
        private final String password;
        private final String additionalParameters;

        private AccessSettings(boolean osAuthentication, boolean infobaseAuthentication,
                               String userName, String password, String additionalParameters) {
            this.osAuthentication = osAuthentication;
            this.infobaseAuthentication = infobaseAuthentication;
            this.userName = userName;
            this.password = password;
            this.additionalParameters = additionalParameters;
        }

        public boolean isOsAuthentication() {
            return osAuthentication;
        }

        public boolean isInfobaseAuthentication() {
            return infobaseAuthentication;
        }

        public String getUserName() {
            return userName;
        }

        public String getPassword() {
            return password;
        }

        public String getAdditionalParameters() {
            return additionalParameters;
        }

        public static AccessSettings osAuthentication(String additionalParameters) {
            return new AccessSettings(true, false, null, null, additionalParameters);
        }

        public static AccessSettings infobaseAuthentication(String userName, String password,
                                                            String additionalParameters) {
            return new AccessSettings(false, true, userName, password, additionalParameters);
        }

        public static AccessSettings additionalParameters(String additionalParameters) {
            return new AccessSettings(false, false, null, null, additionalParameters);
        }

        public AccessSettings withAdditionalParameters(String additionalParameters) {
            return new AccessSettings(osAuthentication, infobaseAuthentication, userName, password,
                    additionalParameters);
        }
    }

    public EdtRuntimeService() {
        this(new EdtRuntimeGateway());
    }

    public EdtRuntimeService(EdtRuntimeGateway gateway) {
        this(gateway, InfobaseLeaseGuard.fromEnvironment());
    }

    public EdtRuntimeService(EdtRuntimeGateway gateway, InfobaseLeaseGuard leaseGuard) {
        this.gateway = gateway;
        this.leaseGuard = leaseGuard;
    }

    public InfobaseReference resolveDefaultInfobase(String projectName) {
        IProject project = gateway.resolveProject(projectName);
        if (project == null) {
            throw new IllegalStateException("EDT project not found: " + projectName); //$NON-NLS-1$
        }

        // Primary path: file-binding via IInfobaseAssociationManager.
        boolean associationPresent = false;
        InfobaseReference infobase = null;
        Throwable primaryFailure = null;
        try {
            IInfobaseAssociationManager manager = gateway.getInfobaseAssociationManager();
            java.util.Optional<IInfobaseAssociation> associationOpt = manager.getAssociation(project);
            if (associationOpt.isPresent()) {
                associationPresent = true;
                IInfobaseAssociation association = associationOpt.get();
                infobase = association.getDefaultInfobase();
                if (infobase == null && !association.getInfobases().isEmpty()) {
                    infobase = association.getInfobases().iterator().next();
                }
            }
        } catch (IllegalStateException e) {
            // IInfobaseAssociationManager service is unavailable; fall through to standalone path.
            LOG.warn("IInfobaseAssociationManager unavailable; attempting standalone-server fallback: " //$NON-NLS-1$
                    + e.getMessage(), e);
            primaryFailure = e;
        } catch (Exception e) {
            // EDT may throw InfobaseAssociationException for malformed/missing bindings;
            // treat as absent and fall back to the standalone path.
            LOG.warn("Failed to query IInfobaseAssociationManager for project " + projectName //$NON-NLS-1$
                    + "; attempting standalone-server fallback: " + e.getMessage(), e); //$NON-NLS-1$
            primaryFailure = e;
        }

        if (infobase != null) {
            return infobase;
        }

        // Fallback: extension projects don't carry their own infobase association — they publish
        // into the infobase of their owning (base configuration) project. Resolve the parent's
        // infobase and use it. The update itself still runs against THIS (extension) project, so
        // EDT syncs the extension's content into the shared infobase. See feedback
        // 2026-05-29-update-infobase-extension-project-association-not-found.
        InfobaseReference extensionParentInfobase = resolveExtensionParentInfobase(project);
        if (extensionParentInfobase != null) {
            return extensionParentInfobase;
        }

        // Fallback: standalone-server binding (com.e1c.g5.v8.dt.platform.standaloneserver.wst.core).
        InfobaseReference standaloneInfobase = resolveStandaloneInfobase(project);
        if (standaloneInfobase != null) {
            return standaloneInfobase;
        }

        String message;
        if (!associationPresent && primaryFailure == null) {
            message = "Infobase association not found for project: " + projectName; //$NON-NLS-1$
        } else {
            message = "Infobase reference not found for project: " + projectName; //$NON-NLS-1$
        }
        IllegalStateException failure = new IllegalStateException(message);
        if (primaryFailure != null) {
            // Preserve the original primary-path exception so diagnostics can trace back to the
            // underlying IInfobaseAssociationManager failure even when fallback also fails.
            failure.addSuppressed(primaryFailure);
        }
        throw failure;
    }

    /**
     * Best-effort predicate: {@code true} when the project's default infobase is a FILE infobase.
     * Never throws — a resolution failure, a missing association or a blank/absent connection string
     * all yield {@code false} (unknown is treated as non-file so server/unknown callers keep their
     * existing behaviour). Backs the BF-13140 file-IB test-client credential default in
     * {@code yaxunit_run} / {@code qa_run}: a file-IB test client launched with no explicit login logs
     * in as the {@code .1CD}'s cached last-user, silently producing {@code no_report} (BF-12562).
     */
    public boolean isFileInfobase(String projectName) {
        try {
            InfobaseReference ib = resolveDefaultInfobase(projectName);
            if (ib == null || ib.getConnectionString() == null) {
                return false;
            }
            return isFileConnectionString(ib.getConnectionString().asConnectionString());
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * True iff a 1C infobase connection string denotes a FILE infobase (starts with {@code File=}).
     * Server/standalone connections ({@code Srvr="...";Ref="...";}) and null/blank strings return
     * {@code false}. Package-visible for unit testing.
     */
    static boolean isFileConnectionString(String connectionString) {
        if (connectionString == null) {
            return false;
        }
        return connectionString.trim().toLowerCase(Locale.ROOT).startsWith("file="); //$NON-NLS-1$
    }

    /**
     * Attempts to resolve an {@link InfobaseReference} for a project bound through the standalone
     * server plugin. Returns {@code null} if the standalone service is not registered, no server
     * hosts an infobase module for this project, or the module cannot be adapted.
     *
     * <p>Uses the non-blocking {@code peekStandaloneServerService()} accessor so that a missing
     * standalone-server service does not stall the agent tool dispatcher while a 30-second
     * {@code ServiceTracker.waitForService} elapses.
     */
    private InfobaseReference resolveStandaloneInfobase(IProject project) {
        IStandaloneServerService service = gateway.peekStandaloneServerService();
        if (service == null) {
            return null;
        }
        String projectName = project.getName();
        java.util.List<IServer> servers;
        try {
            servers = service.getServers();
        } catch (Exception | NoSuchMethodError e) {
            // Standalone-server enumeration runs through EDT internal services that may invoke
            // interruptible operations. Restore the interrupt flag if it was raised so that
            // upstream cancellation propagates instead of being silently swallowed.
            if (e instanceof InterruptedException || Thread.interrupted()) {
                Thread.currentThread().interrupt();
            }
            LOG.warn("Standalone server enumeration failed: " + e.getMessage(), e); //$NON-NLS-1$
            return null;
        }
        if (servers == null || servers.isEmpty()) {
            return null;
        }
        for (IServer server : servers) {
            if (server == null) {
                continue;
            }
            IModule[] modules = server.getModules();
            if (modules == null) {
                continue;
            }
            for (IModule module : modules) {
                if (!(module instanceof StandaloneServerInfobase standaloneInfobase)) {
                    continue;
                }
                if (!matchesProject(standaloneInfobase, project, projectName)) {
                    continue;
                }
                InfobaseReference adapted = adaptStandaloneInfobase(standaloneInfobase);
                if (adapted != null) {
                    return adapted;
                }
            }
        }
        return null;
    }

    private static boolean matchesProject(StandaloneServerInfobase infobase, IProject project, String projectName) {
        IProject modProject = infobase.getProject();
        if (modProject != null && modProject.equals(project)) {
            return true;
        }
        String modProjectName = infobase.getProjectName();
        return modProjectName != null && modProjectName.equals(projectName);
    }

    private static InfobaseReference adaptStandaloneInfobase(StandaloneServerInfobase infobase) {
        try {
            Object adapter = infobase.getAdapter(InfobaseReference.class);
            if (adapter instanceof InfobaseReference ref) {
                return ref;
            }
            // Fallback to loadAdapter if the adapter factory has not yet been registered.
            Object loaded = infobase.loadAdapter(InfobaseReference.class, new NullProgressMonitor());
            if (loaded instanceof InfobaseReference ref) {
                return ref;
            }
        } catch (Exception | NoSuchMethodError e) {
            // EDT adapter factories may run user/internal code that performs interruptible
            // operations; if the worker thread was interrupted during the adapter resolution
            // we must restore the interrupt flag so callers (and the agent dispatcher) can
            // observe cancellation instead of treating the result as a benign null.
            if (e instanceof InterruptedException || Thread.interrupted()) {
                Thread.currentThread().interrupt();
            }
            LOG.warn("Failed to adapt standalone-server infobase to InfobaseReference: " //$NON-NLS-1$
                    + e.getMessage(), e);
        }
        return null;
    }

    /**
     * Resolves the infobase for an <em>extension</em> project by following its parent (base
     * configuration) project's association. Extension projects are published into the base
     * configuration's infobase and do not carry their own {@link IInfobaseAssociation}, so
     * {@code getAssociation(extensionProject)} returns empty even though a binding exists; the
     * binding lives on the owning project. Returns {@code null} when the project is not an
     * extension, has no parent, the parent has no association, or any lookup fails — all
     * best-effort, never blocking.
     *
     * <p>NB: the caller uses the returned reference only as the <em>target infobase</em>; the
     * actual {@code updateInfobase} sync still runs against the extension project, so EDT publishes
     * the extension's content (new modules) into the shared infobase.</p>
     */
    private InfobaseReference resolveExtensionParentInfobase(IProject project) {
        IV8ProjectManager v8ProjectManager = gateway.peekV8ProjectManager();
        if (v8ProjectManager == null) {
            return null;
        }
        IProject parent;
        try {
            IV8Project v8Project = v8ProjectManager.getProject(project);
            if (!(v8Project instanceof IExtensionProject extensionProject)) {
                return null;
            }
            parent = extensionProject.getParentProject();
        } catch (RuntimeException e) {
            LOG.warn("Failed to resolve extension parent for project " + project.getName() //$NON-NLS-1$
                    + ": " + e.getMessage(), e); //$NON-NLS-1$
            return null;
        }
        if (parent == null || parent.equals(project)) {
            return null;
        }
        try {
            IInfobaseAssociationManager manager = gateway.getInfobaseAssociationManager();
            java.util.Optional<IInfobaseAssociation> associationOpt = manager.getAssociation(parent);
            if (associationOpt.isPresent()) {
                IInfobaseAssociation association = associationOpt.get();
                InfobaseReference infobase = association.getDefaultInfobase();
                if (infobase == null && !association.getInfobases().isEmpty()) {
                    infobase = association.getInfobases().iterator().next();
                }
                if (infobase != null) {
                    LOG.info("Resolved infobase for extension project %s via parent project %s", //$NON-NLS-1$
                            project.getName(), parent.getName());
                    return infobase;
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to resolve parent infobase for extension project " + project.getName() //$NON-NLS-1$
                    + " (parent " + parent.getName() + "): " + e.getMessage(), e); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return null;
    }

    public AccessSettings resolveAccessSettings(String projectName) {
        InfobaseReference infobase = resolveDefaultInfobase(projectName);
        return resolveAccessSettings(infobase);
    }

    public AccessSettings resolveAccessSettings(InfobaseReference infobase) {
        if (infobase == null) {
            return null;
        }
        IInfobaseAccessSettings settings;
        try {
            IInfobaseAccessManager accessManager = gateway.getInfobaseAccessManager();
            settings = accessManager.resolveSettings(infobase);
        } catch (Exception | NoSuchMethodError e) {
            return null;
        }
        if (settings == null || settings == IInfobaseAccessSettings.NOT_DEFINED) {
            return null;
        }
        InfobaseAccess access = settings.access();
        boolean osAuth = access == InfobaseAccess.OS;
        boolean infobaseAuth = access == InfobaseAccess.INFOBASE;
        return new AccessSettings(osAuth, infobaseAuth, settings.userName(), settings.password(),
                settings.additionalProperties());
    }

    public ThickClientInfo resolveThickClientInfo(InfobaseReference infobase) {
        return resolveThickClientInfo(infobase, null);
    }

    public ThickClientInfo resolveThickClientInfo(InfobaseReference infobase, String versionMask) {
        return resolveThickClientInfo(infobase, versionMask, null);
    }

    public ThickClientInfo resolveThickClientInfo(InfobaseReference infobase, String versionMask, IProject project) {
        IRuntimeComponentManager runtimeComponentManager = gateway.getRuntimeComponentManager();
        ThickClientInfo info;
        try {
            IResolvableRuntimeInstallation resolvable = resolveInstallation(versionMask, project, infobase);
            info = resolveThickClient(runtimeComponentManager, resolvable, infobase);
        } catch (Exception | NoSuchMethodError e) {
            LOG.warn("Failed to resolve thick client (possible EDT API incompatibility): " + e.getMessage(), e); //$NON-NLS-1$
            return null;
        }
        if (info == null || info.component() == null || info.component().getFile() == null) {
            throw new IllegalStateException("Thick client runtime component not resolved for infobase"); //$NON-NLS-1$
        }
        return info;
    }

    /**
     * Resolves the {@link IResolvableRuntimeInstallation} for the enterprise platform runtime type.
     *
     * <p>Resolution priority mirrors the feedback in
     * {@code 2026-06-03-edt-diagnostics-runtime-version-uncontrollable.md}:</p>
     * <ol>
     *   <li>explicit {@code versionMask} (caller pin, e.g. {@code runtime_version} tool param) via
     *       {@code resolveByVersionOrMask};</li>
     *   <li>otherwise {@code resolveByProjectAndInfobase} with the REAL project — EDT then consults
     *       the per-project+infobase pinned installation
     *       ({@code IInfobaseAccessManager.loadSelectedInstallation}) before falling back to
     *       "newest compatible". Passing {@code null} project (the old behaviour) skipped the pin
     *       store entirely, so auto-resolution silently picked the newest installed platform —
     *       including pre-release builds (8.5.1 beta over the project's working 8.3.27).</li>
     * </ol>
     */
    private IResolvableRuntimeInstallation resolveInstallation(String versionMask, IProject project,
            InfobaseReference infobase) throws MatchingRuntimeNotFound {
        IResolvableRuntimeInstallationManager installationManager =
                gateway.getResolvableRuntimeInstallationManager();
        if (versionMask != null && !versionMask.isBlank()) {
            return installationManager.resolveByVersionOrMask(RUNTIME_TYPE_ENTERPRISE_PLATFORM, versionMask);
        }
        return installationManager.resolveByProjectAndInfobase(RUNTIME_TYPE_ENTERPRISE_PLATFORM,
                project, infobase, InfobaseAccessType.UPDATE);
    }

    /**
     * Resolves the thick client launcher for a runtime installation, replacing the
     * {@code IRuntimeComponentManager.getThickClientInfo(...)} methods removed in EDT 2025.2. This
     * mirrors their former private {@code resolveThickClient} body: pick the app architecture from
     * the infobase (or {@link AppArch#AUTO}), resolve the concrete {@link RuntimeInstallation} for
     * the thick client component, then ask {@code resolveExecutor} for the launchable component and
     * its launcher.
     */
    private static ThickClientInfo resolveThickClient(IRuntimeComponentManager runtimeComponentManager,
            IResolvableRuntimeInstallation resolvable, InfobaseReference infobase)
            throws MatchingRuntimeNotFound, RuntimeExecutionException {
        AppArch appArch = infobase != null ? infobase.getAppArch() : AppArch.AUTO;
        RuntimeInstallation installation = resolvable.resolve(List.of(COMPONENT_TYPE_THICK_CLIENT), appArch);
        ComponentExecutorInfo<ILaunchableRuntimeComponent, IThickClientLauncher> executorInfo =
                runtimeComponentManager.resolveExecutor(ILaunchableRuntimeComponent.class,
                        IThickClientLauncher.class, installation, COMPONENT_TYPE_THICK_CLIENT);
        return new ThickClientInfo(resolvable, executorInfo.getInstallation(), executorInfo.getComponent(),
                executorInfo.getExecutor());
    }

    /**
     * Resolves the thin client (1cv8c.exe) binary for the project's infobase. Required by
     * qa_run's TestManager/SingleClient spawn paths: Vanessa-Automation 6.x/7.x silently hangs
     * pre-FeaturePlayer when hosted on a thick (1cv8.exe) client — manual repro Test D from
     * codepilot1c-feedback/2026-05-28-qa-run-thin-client-breakthrough.md isolated this — and only
     * functions against the thin client. Returns {@code null} on resolution failure so the caller
     * can surface a typed error; the alternative (silent hang on the wrong binary) is what this
     * is preventing.
     *
     * <p>Mirrors {@link #resolveThickClientInfo} via the same {@code resolveExecutor} recipe with
     * {@link IThinClientLauncher} / {@link #COMPONENT_TYPE_THIN_CLIENT}. Returns the binary
     * {@link File} only — downstream {@link RuntimeExecutionCommandBuilder} accepts the same
     * ENTERPRISE arg shape for either binary; only the executable path differs.</p>
     */
    public File resolveThinClientFile(InfobaseReference infobase, String versionMask) {
        return resolveThinClientFile(infobase, versionMask, null);
    }

    public File resolveThinClientFile(InfobaseReference infobase, String versionMask, IProject project) {
        IRuntimeComponentManager runtimeComponentManager = gateway.getRuntimeComponentManager();
        try {
            IResolvableRuntimeInstallation resolvable = resolveInstallation(versionMask, project, infobase);
            AppArch appArch = infobase != null ? infobase.getAppArch() : AppArch.AUTO;
            RuntimeInstallation installation = resolvable.resolve(List.of(COMPONENT_TYPE_THIN_CLIENT), appArch);
            ComponentExecutorInfo<ILaunchableRuntimeComponent, IThinClientLauncher> executorInfo =
                    runtimeComponentManager.resolveExecutor(ILaunchableRuntimeComponent.class,
                            IThinClientLauncher.class, installation, COMPONENT_TYPE_THIN_CLIENT);
            if (executorInfo == null || executorInfo.getComponent() == null
                    || executorInfo.getComponent().getFile() == null) {
                return null;
            }
            return executorInfo.getComponent().getFile();
        } catch (Exception | NoSuchMethodError e) {
            LOG.warn("Failed to resolve thin client (possible EDT API incompatibility): " + e.getMessage(), e); //$NON-NLS-1$
            return null;
        }
    }

    public RuntimeExecutionCommandBuilder buildTestManagerCommand(String projectName, File epfPath,
                                                                  File vaParamsPath, File workspaceRoot,
                                                                  boolean showMainForm, boolean quietInstall,
                                                                  boolean clearStepsCache, File logFile) {
        return buildTestManagerCommand(projectName, epfPath, vaParamsPath, workspaceRoot, showMainForm,
                quietInstall, clearStepsCache, logFile, null);
    }

    public RuntimeExecutionCommandBuilder buildTestManagerCommand(String projectName, File epfPath,
                                                                  File vaParamsPath, File workspaceRoot,
                                                                  boolean showMainForm, boolean quietInstall,
                                                                  boolean clearStepsCache, File logFile,
                                                                  String versionMask) {
        return buildTestManagerCommand(projectName, epfPath, vaParamsPath, workspaceRoot, showMainForm,
                quietInstall, clearStepsCache, logFile, versionMask, null);
    }

    public RuntimeExecutionCommandBuilder buildTestManagerCommand(String projectName, File epfPath,
                                                                  File vaParamsPath, File workspaceRoot,
                                                                  boolean showMainForm, boolean quietInstall,
                                                                  boolean clearStepsCache, File logFile,
                                                                  String versionMask,
                                                                  AccessSettings explicitAccessSettings) {
        InfobaseReference infobase = resolveDefaultInfobase(projectName);
        // Vanessa-Automation hard-requires the thin client (1cv8c.exe) — thick host silent-hangs
        // pre-FeaturePlayer with descendants=0 and a zero-byte va.log (manual repro Test D in
        // codepilot1c-feedback/2026-05-28-qa-run-thin-client-breakthrough.md). Both qa_run spawn
        // paths (TestManager and SingleClient) flow through here, so both must use thin.
        File clientFile = resolveThinClientFile(infobase, versionMask, gateway.resolveProject(projectName));
        if (clientFile == null) {
            throw new IllegalStateException(
                    "Thin client (1cv8c.exe) runtime component not resolved — Vanessa-Automation requires it"); //$NON-NLS-1$
        }

        RuntimeExecutionCommandBuilder builder = new RuntimeExecutionCommandBuilder(clientFile,
                ThickClientMode.ENTERPRISE);
        if (infobase.getConnectionString() == null) {
            throw new IllegalStateException("Infobase connection string not available"); //$NON-NLS-1$
        }
        builder.forInfobase(infobase.getConnectionString(), false);
        if (explicitAccessSettings != null) {
            applyAccessSettings(builder, explicitAccessSettings);
        } else {
            applyAccessSettings(builder, infobase);
        }
        builder.testManagerMode();
        if (epfPath != null) {
            builder.execute(epfPath.getAbsolutePath());
        }
        builder.startupOption(buildStartupOption(vaParamsPath, workspaceRoot, showMainForm, quietInstall,
                clearStepsCache));
        builder.disableStartupDialogs();
        builder.disableStartupMessages();
        if (logFile != null) {
            builder.logTo(logFile, true);
        }
        return builder;
    }

    public RuntimeExecutionCommandBuilder buildSingleClientCommand(String projectName, File epfPath,
                                                                   File vaParamsPath, File workspaceRoot,
                                                                   boolean showMainForm, boolean quietInstall,
                                                                   boolean clearStepsCache, File logFile) {
        return buildSingleClientCommand(projectName, epfPath, vaParamsPath, workspaceRoot, showMainForm,
                quietInstall, clearStepsCache, logFile, null);
    }

    public RuntimeExecutionCommandBuilder buildSingleClientCommand(String projectName, File epfPath,
                                                                   File vaParamsPath, File workspaceRoot,
                                                                   boolean showMainForm, boolean quietInstall,
                                                                   boolean clearStepsCache, File logFile,
                                                                   String versionMask) {
        return buildSingleClientCommand(projectName, epfPath, vaParamsPath, workspaceRoot, showMainForm,
                quietInstall, clearStepsCache, logFile, versionMask, null);
    }

    public RuntimeExecutionCommandBuilder buildSingleClientCommand(String projectName, File epfPath,
                                                                   File vaParamsPath, File workspaceRoot,
                                                                   boolean showMainForm, boolean quietInstall,
                                                                   boolean clearStepsCache, File logFile,
                                                                   String versionMask,
                                                                   AccessSettings explicitAccessSettings) {
        InfobaseReference infobase = resolveDefaultInfobase(projectName);
        // Vanessa-Automation hard-requires the thin client (1cv8c.exe) — thick host silent-hangs
        // pre-FeaturePlayer with descendants=0 and a zero-byte va.log (manual repro Test D in
        // codepilot1c-feedback/2026-05-28-qa-run-thin-client-breakthrough.md). Both qa_run spawn
        // paths (TestManager and SingleClient) flow through here, so both must use thin.
        File clientFile = resolveThinClientFile(infobase, versionMask, gateway.resolveProject(projectName));
        if (clientFile == null) {
            throw new IllegalStateException(
                    "Thin client (1cv8c.exe) runtime component not resolved — Vanessa-Automation requires it"); //$NON-NLS-1$
        }

        RuntimeExecutionCommandBuilder builder = new RuntimeExecutionCommandBuilder(clientFile,
                ThickClientMode.ENTERPRISE);
        if (infobase.getConnectionString() == null) {
            throw new IllegalStateException("Infobase connection string not available"); //$NON-NLS-1$
        }
        builder.forInfobase(infobase.getConnectionString(), false);
        if (explicitAccessSettings != null) {
            applyAccessSettings(builder, explicitAccessSettings);
        } else {
            applyAccessSettings(builder, infobase);
        }
        if (epfPath != null) {
            builder.execute(epfPath.getAbsolutePath());
        }
        builder.startupOption(buildStartupOption(vaParamsPath, workspaceRoot, showMainForm, quietInstall,
                clearStepsCache));
        builder.disableStartupDialogs();
        builder.disableStartupMessages();
        if (logFile != null) {
            builder.logTo(logFile, true);
        }
        return builder;
    }

    /**
     * Builds a thin-client ({@code 1cv8c.exe}) ENTERPRISE command that launches a Vanessa-Automation
     * <em>TestClient</em> ({@code /TESTCLIENT -TPort <port> [-TestClientID <id>]}). In TestManager
     * mode VA connects to these external clients by port; headless, nothing else launches them, so
     * qa_run must spawn them itself before {@code StartFeaturePlayer} (the same thing rmcp's
     * {@code connect_test_client} does). See feedback
     * {@code 2026-05-29-qa-run-testmanager-no-va-log-no-junit.md}.
     *
     * <p>Uses EDT's supported {@link RuntimeExecutionCommandBuilder#testClientMode(Integer, String)}
     * so the {@code /TESTCLIENT}/{@code -TPort} arguments are produced by the platform API, not hand
     * rolled. Reuses the same thin-client resolution as the TestManager/SingleClient paths.</p>
     *
     * @param port          the {@code -TPort} the TestClient listens on; must match the
     *                      {@code ПортЗапускаТестКлиента} written into {@code va-params.json}
     * @param testClientId  optional {@code -TestClientID} (the client's configured name); may be null
     * @param accessSettings credentials to log the TestClient in with (the test account), or null to
     *                      fall back to the infobase's default access settings
     */
    public RuntimeExecutionCommandBuilder buildTestClientCommand(String projectName, Integer port,
            String testClientId, File logFile, String versionMask, AccessSettings accessSettings) {
        InfobaseReference infobase = resolveDefaultInfobase(projectName);
        File clientFile = resolveThinClientFile(infobase, versionMask, gateway.resolveProject(projectName));
        if (clientFile == null) {
            throw new IllegalStateException(
                    "Thin client (1cv8c.exe) runtime component not resolved — required to launch the Vanessa TestClient"); //$NON-NLS-1$
        }
        RuntimeExecutionCommandBuilder builder = new RuntimeExecutionCommandBuilder(clientFile,
                ThickClientMode.ENTERPRISE);
        if (infobase.getConnectionString() == null) {
            throw new IllegalStateException("Infobase connection string not available"); //$NON-NLS-1$
        }
        builder.forInfobase(infobase.getConnectionString(), false);
        if (accessSettings != null) {
            applyAccessSettings(builder, accessSettings);
        } else {
            applyAccessSettings(builder, infobase);
        }
        builder.testClientMode(port, testClientId);
        builder.disableStartupDialogs();
        builder.disableStartupMessages();
        if (logFile != null) {
            builder.logTo(logFile, true);
        }
        return builder;
    }

    public RuntimeExecutionCommandBuilder buildUnitTestCommand(String projectName, File configPath, File logFile) {
        return buildUnitTestCommand(projectName, configPath, logFile, null, null);
    }

    /**
     * Builds a thin-client ({@code 1cv8c.exe}) ENTERPRISE command that runs YAxUnit in-process via
     * the {@code RunUnitTests=<config>} startup parameter. Unlike the Vanessa paths there is **no**
     * TestManager mode and **no** external {@code .epf} — the YAxUnit framework is loaded from the
     * extension installed in the infobase, and its session-start handler reads the JSON run config
     * (filter / reportPath / exitCode / closeAfterTests) from the path passed here.
     *
     * @param explicitAccessSettings session credentials to use instead of the infobase defaults
     *                               (e.g. when the caller passes {@code test_client_login}); may be
     *                               {@code null} to fall back to the EDT-resolved access settings.
     */
    public RuntimeExecutionCommandBuilder buildUnitTestCommand(String projectName, File configPath, File logFile,
                                                               String versionMask,
                                                               AccessSettings explicitAccessSettings) {
        if (configPath == null) {
            throw new IllegalArgumentException("YAxUnit config path is required"); //$NON-NLS-1$
        }
        InfobaseReference infobase = resolveDefaultInfobase(projectName);
        // Thin client (1cv8c.exe) is this plugin's validated launch surface on EDT 2025.2
        // (resolveExecutor path). YAxUnit runs fine in-process on it; thick is unnecessary.
        File clientFile = resolveThinClientFile(infobase, versionMask, gateway.resolveProject(projectName));
        if (clientFile == null) {
            throw new IllegalStateException(
                    "Thin client (1cv8c.exe) runtime component not resolved — YAxUnit requires a 1C client"); //$NON-NLS-1$
        }

        RuntimeExecutionCommandBuilder builder = new RuntimeExecutionCommandBuilder(clientFile,
                ThickClientMode.ENTERPRISE);
        if (infobase.getConnectionString() == null) {
            throw new IllegalStateException("Infobase connection string not available"); //$NON-NLS-1$
        }
        builder.forInfobase(infobase.getConnectionString(), false);
        if (explicitAccessSettings != null) {
            applyAccessSettings(builder, explicitAccessSettings);
        } else {
            applyAccessSettings(builder, infobase);
        }
        builder.startupOption("RunUnitTests=" + configPath.getAbsolutePath()); //$NON-NLS-1$
        builder.disableStartupDialogs();
        builder.disableStartupMessages();
        if (logFile != null) {
            builder.logTo(logFile, true);
        }
        return builder;
    }

    public RuntimeExecutionCommandBuilder buildUpdateCommand(String projectName, File logFile) {
        InfobaseReference infobase = resolveDefaultInfobase(projectName);
        ThickClientInfo info = resolveThickClientInfo(infobase, null, gateway.resolveProject(projectName));
        File clientFile = info.component().getFile();

        RuntimeExecutionCommandBuilder builder = new RuntimeExecutionCommandBuilder(clientFile,
                ThickClientMode.DESIGNER);
        if (infobase.getConnectionString() == null) {
            throw new IllegalStateException("Infobase connection string not available"); //$NON-NLS-1$
        }
        builder.forInfobase(infobase.getConnectionString(), false);
        applyAccessSettings(builder, infobase);
        builder.updateDatabaseConfiguration();
        builder.disableStartupDialogs();
        builder.disableStartupMessages();
        if (logFile != null) {
            builder.logTo(logFile, true);
        }
        return builder;
    }

    public ProcessBuilder buildEnterpriseLaunchProcess(EdtResolvedLaunchContext context,
            String additionalParameters, File logFile) {
        if (context == null) {
            throw new IllegalArgumentException("Launch context is required"); //$NON-NLS-1$
        }
        InfobaseReference infobase = context.infobase();
        if (infobase == null) {
            throw new IllegalStateException("Infobase reference not available"); //$NON-NLS-1$
        }
        if (context.clientFile() == null) {
            throw new IllegalStateException("Client executable not available"); //$NON-NLS-1$
        }
        RuntimeExecutionCommandBuilder builder = new RuntimeExecutionCommandBuilder(
                context.clientFile(), ThickClientMode.ENTERPRISE);
        if (infobase.getConnectionString() == null) {
            throw new IllegalStateException("Infobase connection string not available"); //$NON-NLS-1$
        }
        builder.forInfobase(infobase.getConnectionString(), false);
        AccessSettings effectiveSettings = mergeAdditionalParameters(context.accessSettings(), additionalParameters);
        applyAccessSettings(builder, effectiveSettings);
        builder.disableStartupDialogs();
        builder.disableStartupMessages();
        if (logFile != null) {
            builder.logTo(logFile, true);
        }
        return builder.toProcessBuilder();
    }

    /**
     * Builds a generic launch {@link ProcessBuilder} for an explicit client mode, resolving the
     * infobase and the right client binary from scratch. Backs {@code edt_launch_app}'s mode/creds
     * path; the default (thick + ENTERPRISE, no explicit credentials) still flows through
     * {@link #buildEnterpriseLaunchProcess} so existing launch behaviour is byte-for-byte unchanged.
     *
     * @param mode {@code "thin"} (1cv8c, ENTERPRISE), {@code "thick"} (1cv8, ENTERPRISE) or
     *             {@code "designer"} (1cv8, DESIGNER); {@code null}/blank means thick.
     * @param explicitAccessSettings session credentials to use instead of the infobase defaults;
     *             {@code null} falls back to the EDT-resolved access settings.
     */
    public ProcessBuilder buildModeLaunchProcess(String projectName, String mode, String additionalParameters,
            AccessSettings explicitAccessSettings, File logFile) {
        return buildModeLaunchProcess(projectName, mode, additionalParameters, explicitAccessSettings, logFile,
                null);
    }

    /**
     * Same as {@link #buildModeLaunchProcess(String, String, String, AccessSettings, File)} but with
     * an explicit runtime version pin. {@code runtimeVersionMask} accepts a line ({@code "8.3.27"} —
     * newest build of that line) or an exact build ({@code "8.3.27.2074"}); {@code null}/blank keeps
     * project+infobase-aware auto-resolution (which honours the EDT-stored per-infobase pin, see
     * {@link #resolveInstallation}).
     */
    public ProcessBuilder buildModeLaunchProcess(String projectName, String mode, String additionalParameters,
            AccessSettings explicitAccessSettings, File logFile, String runtimeVersionMask) {
        InfobaseReference infobase = resolveDefaultInfobase(projectName);
        IProject project = gateway.resolveProject(projectName);
        String requested = mode == null ? "thick" : mode.trim(); //$NON-NLS-1$
        File clientFile;
        ThickClientMode clientMode;
        if (requested.equalsIgnoreCase("thin")) { //$NON-NLS-1$
            clientFile = resolveThinClientFile(infobase, runtimeVersionMask, project);
            clientMode = ThickClientMode.ENTERPRISE;
        } else if (requested.equalsIgnoreCase("designer")) { //$NON-NLS-1$
            clientFile = resolveThickClientInfo(infobase, runtimeVersionMask, project).component().getFile();
            clientMode = ThickClientMode.DESIGNER;
        } else if (requested.isEmpty() || requested.equalsIgnoreCase("thick")) { //$NON-NLS-1$
            clientFile = resolveThickClientInfo(infobase, runtimeVersionMask, project).component().getFile();
            clientMode = ThickClientMode.ENTERPRISE;
        } else {
            throw new IllegalArgumentException("Unknown launch mode: " + mode + " (use thin|thick|designer)"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (clientFile == null) {
            throw new IllegalStateException("Client runtime component not resolved for mode " + requested); //$NON-NLS-1$
        }

        RuntimeExecutionCommandBuilder builder = new RuntimeExecutionCommandBuilder(clientFile, clientMode);
        if (infobase.getConnectionString() == null) {
            throw new IllegalStateException("Infobase connection string not available"); //$NON-NLS-1$
        }
        builder.forInfobase(infobase.getConnectionString(), false);
        AccessSettings base = explicitAccessSettings != null ? explicitAccessSettings
                : resolveAccessSettings(infobase);
        AccessSettings effective = mergeAdditionalParameters(base, additionalParameters);
        if (effective != null) {
            applyAccessSettings(builder, effective);
        } else {
            applyAccessSettings(builder, infobase);
        }
        builder.disableStartupDialogs();
        builder.disableStartupMessages();
        if (logFile != null) {
            builder.logTo(logFile, true);
        }
        return builder.toProcessBuilder();
    }

    /**
     * What concrete platform installation EDT resolves for a project+infobase pair.
     *
     * @param version  full version with build, e.g. {@code 8.3.27.2074}
     * @param location installation root URI as a string, or {@code null} if unknown
     * @param pinned   {@code true} when the version came from the EDT-stored per-project+infobase
     *                 selected installation ({@code IInfobaseAccessManager.loadSelectedInstallation});
     *                 {@code false} means auto-resolution picked it (newest compatible installed
     *                 platform, INCLUDING pre-release builds)
     */
    public record ResolvedRuntimeInfo(String version, String location, boolean pinned) {
    }

    /**
     * Best-effort description of the platform installation EDT will use for an infobase update of
     * this project. Runs the same {@code resolveByProjectAndInfobase(..., UPDATE)} resolution the
     * EDT synchronization manager performs internally, so callers (update_infobase dry_run and
     * real runs) can surface {@code runtime_used} BEFORE the designer agent touches the infobase —
     * the silent 8.5-beta-updates-a-8.3.27-infobase scenario from feedback
     * {@code 2026-06-03-edt-diagnostics-runtime-version-uncontrollable.md}.
     *
     * @return resolved info, or {@code null} when resolution fails (never throws)
     */
    public ResolvedRuntimeInfo describeUpdateRuntime(String projectName) {
        try {
            IProject project = gateway.resolveProject(projectName);
            InfobaseReference infobase = resolveDefaultInfobase(projectName);
            boolean pinned = false;
            try {
                pinned = project != null && infobase != null
                        && gateway.getInfobaseAccessManager().loadSelectedInstallation(project, infobase).isPresent();
            } catch (Exception | NoSuchMethodError e) {
                LOG.warn("Failed to query selected installation: " + e.getMessage(), e); //$NON-NLS-1$
            }
            IResolvableRuntimeInstallation resolvable = resolveInstallation(null, project, infobase);
            AppArch appArch = infobase != null ? infobase.getAppArch() : AppArch.AUTO;
            RuntimeInstallation installation = resolvable.resolve(List.of(COMPONENT_TYPE_THICK_CLIENT), appArch);
            return new ResolvedRuntimeInfo(installation.getVersionWithBuild(),
                    installation.getLocation() == null ? null : installation.getLocation().toString(), pinned);
        } catch (Exception | NoSuchMethodError e) {
            LOG.warn("Failed to describe update runtime for project " + projectName //$NON-NLS-1$
                    + ": " + e.getMessage(), e); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Pins the platform version EDT uses for this project+infobase pair by storing the selected
     * installation via {@code IInfobaseAccessManager.updateSelectedInstallation} — the same store
     * the EDT launch-configuration UI writes and {@code resolveByProjectAndInfobase} reads first.
     * The pin is PERSISTENT and EDT-native: subsequent infobase updates and launches (both from
     * this plugin and from the EDT UI in auto mode) resolve to it instead of "newest installed".
     *
     * @param versionMask version line ({@code "8.3.27"} — newest matching build) or exact build
     *                    ({@code "8.3.27.2074"})
     * @return the concrete installation the mask resolved to
     * @throws IllegalStateException when no installed platform matches the mask, the project or
     *                               infobase cannot be resolved, or the store rejects the update
     */
    public ResolvedRuntimeInfo pinRuntimeVersion(String projectName, String versionMask) {
        if (versionMask == null || versionMask.isBlank()) {
            throw new IllegalArgumentException("runtime version mask is required"); //$NON-NLS-1$
        }
        IProject project = gateway.resolveProject(projectName);
        if (project == null || !project.exists()) {
            throw new IllegalStateException("EDT project not found: " + projectName); //$NON-NLS-1$
        }
        InfobaseReference infobase = resolveDefaultInfobase(projectName);
        try {
            IResolvableRuntimeInstallation resolvable = gateway.getResolvableRuntimeInstallationManager()
                    .resolveByVersionOrMask(RUNTIME_TYPE_ENTERPRISE_PLATFORM, versionMask);
            gateway.getInfobaseAccessManager().updateSelectedInstallation(project, infobase, resolvable);
            AppArch appArch = infobase != null ? infobase.getAppArch() : AppArch.AUTO;
            RuntimeInstallation installation = resolvable.resolve(List.of(COMPONENT_TYPE_THICK_CLIENT), appArch);
            return new ResolvedRuntimeInfo(installation.getVersionWithBuild(),
                    installation.getLocation() == null ? null : installation.getLocation().toString(), true);
        } catch (MatchingRuntimeNotFound e) {
            throw new IllegalStateException("No installed 1C platform matches runtime_version '" //$NON-NLS-1$
                    + versionMask + "': " + e.getMessage(), e); //$NON-NLS-1$
        } catch (CoreException e) {
            throw new IllegalStateException("Failed to pin runtime version '" + versionMask //$NON-NLS-1$
                    + "': " + e.getMessage(), e); //$NON-NLS-1$
        }
    }

    public boolean updateInfobase(String projectName) throws Exception {
        return updateInfobase(projectName, true, new NullProgressMonitor());
    }

    public boolean updateInfobase(String projectName, boolean keepConnected, IProgressMonitor monitor)
            throws Exception {
        return updateInfobaseWithStatus(projectName, keepConnected, monitor).updated();
    }

    /**
     * Result of an EDT updateInfobase call carrying the lock-acquisition state so callers can
     * distinguish "schema fully applied" from "EDT accepted but ran in dynamic mode because the
     * exclusive lock was not available — handler bindings and new metadata may not be live yet".
     *
     * <p>{@code dynamicOnly} is true when EDT invoked the {@code onConfirm} callback to ask
     * whether to proceed without an exclusive lock — the standard path EDT takes when an
     * existing client session holds the infobase. The dialog the user sees in EDT-UI mode is
     * the same decision point; our callback proxy answers TRUE so EDT proceeds, but the
     * resulting update is dynamic-only.</p>
     */
    public record UpdateInfobaseStatus(boolean updated, boolean dynamicOnly) {
    }

    public UpdateInfobaseStatus updateInfobaseWithStatus(String projectName, boolean keepConnected,
            IProgressMonitor monitor) throws Exception {
        IProject project = gateway.resolveProject(projectName);
        if (project == null) {
            throw new IllegalStateException("EDT project not found: " + projectName); //$NON-NLS-1$
        }
        InfobaseReference infobase = resolveDefaultInfobase(projectName);
        enforceUpdateLease(project, infobase);
        Object manager = gateway.getInfobaseSynchronizationManager();
        IProgressMonitor usedMonitor = monitor != null ? monitor : new NullProgressMonitor();
        AtomicBoolean exclusiveLockUnavailable = new AtomicBoolean(false);
        Object callback = createAutoUpdateCallback(manager.getClass().getClassLoader(), exclusiveLockUnavailable);
        Method updateMethod = findUpdateMethod(manager.getClass());
        if (updateMethod == null) {
            throw new IllegalStateException("EDT updateInfobase method not found"); //$NON-NLS-1$
        }
        try {
            Object result = updateMethod.invoke(manager, project, infobase, callback, Boolean.valueOf(keepConnected),
                    usedMonitor);
            boolean updated = result instanceof Boolean ? ((Boolean) result).booleanValue() : false;
            return new UpdateInfobaseStatus(updated, exclusiveLockUnavailable.get());
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw new IllegalStateException("EDT updateInfobase failed: " + e.getMessage(), e); //$NON-NLS-1$
        }
    }

    /**
     * Reads EDT's in-memory equality state between the project configuration and its target
     * infobase, WITHOUT spawning a configurator/DESIGNER. Resolves the same project + infobase pair
     * {@link #updateInfobaseWithStatus} uses and reflectively invokes
     * {@code IInfobaseSynchronizationManager.getEqualityState(project, infobase)} — kept reflective
     * to match how this class already binds the sync manager (no hard compile dependency on the
     * {@code InfobaseEqualityState} enum).
     *
     * <p>Backs the {@code edt_update_infobase} {@code skip_if_current} pre-check. It is best-effort:
     * it never throws. Returns the enum constant name ({@code "EQUAL"}, {@code "NOT_EQUAL"},
     * {@code "LOADING"}), or {@code null} when the state cannot be determined — the project or
     * infobase does not resolve, the method is absent on this EDT version, or the call throws. A
     * {@code null} result must make the caller fall back to a normal update, never fail it.</p>
     */
    public String readInfobaseEqualityState(String projectName) {
        try {
            IProject project = gateway.resolveProject(projectName);
            if (project == null) {
                return null;
            }
            InfobaseReference infobase = resolveDefaultInfobase(projectName);
            if (infobase == null) {
                return null;
            }
            Object manager = gateway.getInfobaseSynchronizationManager();
            Method method = findMethod(manager.getClass(), "getEqualityState", 2); //$NON-NLS-1$
            if (method == null) {
                LOG.warn("IInfobaseSynchronizationManager.getEqualityState(project, infobase) not found on %s;" //$NON-NLS-1$
                        + " skipping equality pre-check for project %s", manager.getClass().getName(), projectName); //$NON-NLS-1$
                return null;
            }
            Object state = method.invoke(manager, project, infobase);
            if (state == null) {
                return null;
            }
            return state instanceof Enum<?> enumState ? enumState.name() : String.valueOf(state);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            LOG.warn("getEqualityState threw for project %s: %s — falling back to a normal update", //$NON-NLS-1$
                    projectName, cause.getMessage());
            return null;
        } catch (Exception | NoSuchMethodError e) {
            LOG.warn("Failed to read infobase equality state for project %s: %s — falling back to a normal update", //$NON-NLS-1$
                    projectName, e.getMessage());
            return null;
        }
    }

    /**
     * Pool-exclusivity gate before the configurator writes into the infobase (multi-EDT stack
     * pools): refuses the update when another stack holds the lease for the project's current
     * branch or for this physical infobase, and auto-claims a free lease (working the task claims
     * it — same semantics as connect_infobase). Active only when the guard is configured (env
     * {@code CODEPILOT1C_LEASE_DIR}); otherwise this is a no-op, and so is any non-branch context.
     */
    protected void enforceUpdateLease(IProject project, InfobaseReference infobase) {
        if (leaseGuard == null || !leaseGuard.isEnabled()) {
            return;
        }
        String contextValue = null;
        try {
            var provider = gateway.peekInfobaseAssociationContextProvider();
            if (provider != null) {
                var context = provider.get(project);
                contextValue = context == null ? null : context.getContext().orElse(null);
            }
        } catch (RuntimeException e) {
            LOG.warn("update_infobase: failed to resolve association context for lease check: %s", //$NON-NLS-1$
                    e.getMessage());
        }
        String branch = InfobaseLeaseGuard.branchFromContext(contextValue);
        String identity = InfobaseIdentity.identityOf(infobase);
        InfobaseLeaseGuard.Decision decision = leaseGuard.checkOrAcquire(branch, identity, identity, null);
        if (decision.outcome() != InfobaseLeaseGuard.Outcome.DENIED) {
            return;
        }
        InfobaseLease holder = decision.lease();
        throw new EdtToolException(EdtToolErrorCode.EDT_LEASE_HELD,
                "lease_held: " //$NON-NLS-1$
                        + (identity == null ? "branch '" + branch + "'" //$NON-NLS-1$ //$NON-NLS-2$
                                : "infobase " + identity //$NON-NLS-1$
                                        + (branch == null ? "" : " (branch '" + branch + "')")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        + " is leased by " //$NON-NLS-1$
                        + (holder == null ? "another stack" : holder.describeHolder()) //$NON-NLS-1$
                        + ". Two EDT instances must not work the same file infobase. " //$NON-NLS-1$
                        + "Release the lease on the holding stack (manage_leases action=release), " //$NON-NLS-1$
                        + "or steal a stale one with manage_leases action=take force=true, then retry.", //$NON-NLS-1$
                holder == null ? null : holder.holderFields());
    }

    /**
     * Early lease gate for {@code update_infobase}, meant to run BEFORE every other pre-flight —
     * notably the webserver guard: on live pools a web server is ALWAYS running, so checking it
     * first would mask the more specific {@code EDT_LEASE_HELD} from a non-holder behind
     * {@code UPDATE_BLOCKED_BY_WEBSERVER} (consumer feedback 2026-07-03). Resolution failures are
     * swallowed on purpose: the main update path surfaces its own canonical error for a missing
     * project/infobase, and the in-path {@link #enforceUpdateLease} still guards the update itself.
     */
    public void checkUpdateLease(String projectName) {
        if (leaseGuard == null || !leaseGuard.isEnabled()) {
            return;
        }
        IProject project;
        InfobaseReference infobase;
        try {
            project = gateway.resolveProject(projectName);
            if (project == null) {
                return;
            }
            infobase = resolveDefaultInfobase(projectName);
        } catch (RuntimeException e) {
            LOG.warn("update_infobase: early lease check skipped — could not resolve project/infobase: %s", //$NON-NLS-1$
                    e.getMessage());
            return;
        }
        enforceUpdateLease(project, infobase);
    }

    public void applyAccessSettings(RuntimeExecutionCommandBuilder builder, AccessSettings settings) {
        if (builder == null || settings == null) {
            return;
        }
        if (settings.isOsAuthentication()) {
            builder.osAuthentication(true);
        } else if (settings.isInfobaseAuthentication()) {
            String user = settings.getUserName();
            if (user != null && !user.isBlank()) {
                builder.userName(user);
            }
            String password = settings.getPassword();
            if (password != null && !password.isBlank()) {
                builder.userPassword(password);
            }
        }
        String additional = settings.getAdditionalParameters();
        if (additional != null && !additional.isBlank()) {
            builder.additionalParameters(additional);
        }
    }

    public AccessSettings mergeAdditionalParameters(AccessSettings settings, String additionalParameters) {
        if (settings == null) {
            String merged = normalizeAdditionalParameters(null, additionalParameters);
            return merged == null ? null : AccessSettings.additionalParameters(merged);
        }
        String merged = normalizeAdditionalParameters(settings.getAdditionalParameters(), additionalParameters);
        return settings.withAdditionalParameters(merged);
    }

    private static Object createAutoUpdateCallback(ClassLoader loader, AtomicBoolean exclusiveLockUnavailable)
            throws ClassNotFoundException {
        Class<?> callbackInterface = Class.forName(
                "com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseUpdateCallback", //$NON-NLS-1$
                true,
                loader);
        return Proxy.newProxyInstance(callbackInterface.getClassLoader(),
                new Class<?>[] { callbackInterface },
                (Object proxy, Method method, Object[] args) -> handleUpdateCallback(proxy, method, args,
                        exclusiveLockUnavailable));
    }

    private static Object handleUpdateCallback(Object proxy, Method method, Object[] args,
            AtomicBoolean exclusiveLockUnavailable) {
        String name = method.getName();
        if ("onConfirm".equals(name)) { //$NON-NLS-1$
            // EDT calls onConfirm when it cannot acquire an exclusive lock and asks whether to
            // proceed with a dynamic-mode update. We answer TRUE (proceed) but remember the
            // flag — schema changes are NOT applied in dynamic mode.
            if (exclusiveLockUnavailable != null) {
                exclusiveLockUnavailable.set(true);
            }
            return Boolean.TRUE;
        }
        // EDT 2025.2.x invokes IInfobaseChangesResolver.resolveInfobaseChanges (inherited into
        // IInfobaseUpdateCallback); the older onInfobaseChanges name is kept as a legacy alias for
        // other API versions. For a headless update the policy is "project overrides the infobase":
        // return OVERRIDDEN (the same outcome the GUI callback produces under allowOverrideConflict)
        // WITHOUT calling resolver.overrideConflict — that method needs an IUpdateInfobaseFlow which
        // is absent from the resolveInfobaseChanges arguments. Returning null here is exactly what
        // made EDT call InfobaseConflictResolutionResult.ordinal() on null -> NPE on any conflict.
        if ("resolveInfobaseChanges".equals(name) || "onInfobaseChanges".equals(name)) { //$NON-NLS-1$ //$NON-NLS-2$
            Object overridden = enumValue(method.getReturnType(), "OVERRIDDEN"); //$NON-NLS-1$
            if (overridden != null) {
                return overridden;
            }
            Object deferred = enumValue(method.getReturnType(), "DEFERRED"); //$NON-NLS-1$
            return deferred != null ? deferred : defaultValue(method.getReturnType());
        }
        if ("toString".equals(name) && method.getParameterCount() == 0) { //$NON-NLS-1$
            return "AutoUpdateCallbackProxy"; //$NON-NLS-1$
        }
        if ("hashCode".equals(name) && method.getParameterCount() == 0) { //$NON-NLS-1$
            return Integer.valueOf(System.identityHashCode(proxy));
        }
        if ("equals".equals(name) && method.getParameterCount() == 1) { //$NON-NLS-1$
            return Boolean.valueOf(args != null && args.length == 1 && proxy == args[0]);
        }
        // Never hand a bare null back to EDT for a callback method with a primitive return type;
        // coerce to the type's default so an unmodelled callback can't trigger an NPE downstream.
        return defaultValue(method.getReturnType());
    }

    private static Method findUpdateMethod(Class<?> managerClass) {
        return findMethod(managerClass, "updateInfobase", 5); //$NON-NLS-1$
    }

    private static Method findMethod(Class<?> type, String name, int paramCount) {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == paramCount) {
                return method;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Object enumValue(Class<?> type, String name) {
        if (type != null && type.isEnum()) {
            try {
                return Enum.valueOf((Class<Enum>) type, name);
            } catch (IllegalArgumentException e) {
                // The enum does not declare a constant with this name on this EDT version.
                return null;
            }
        }
        return null;
    }

    /**
     * Returns a non-null default for a primitive return type so a dynamic-proxy callback never
     * hands {@code null} back where the platform expects a primitive (which would NPE on unboxing).
     * Reference and {@code void} return types yield {@code null}.
     */
    private static Object defaultValue(Class<?> type) {
        if (type == null || type == Void.TYPE || !type.isPrimitive()) {
            return null;
        }
        if (type == Boolean.TYPE) {
            return Boolean.FALSE;
        }
        if (type == Character.TYPE) {
            return Character.valueOf('\0');
        }
        if (type == Byte.TYPE) {
            return Byte.valueOf((byte) 0);
        }
        if (type == Short.TYPE) {
            return Short.valueOf((short) 0);
        }
        if (type == Integer.TYPE) {
            return Integer.valueOf(0);
        }
        if (type == Long.TYPE) {
            return Long.valueOf(0L);
        }
        if (type == Float.TYPE) {
            return Float.valueOf(0F);
        }
        if (type == Double.TYPE) {
            return Double.valueOf(0D);
        }
        return null;
    }

    private void applyAccessSettings(RuntimeExecutionCommandBuilder builder, InfobaseReference infobase) {
        if (builder == null || infobase == null) {
            return;
        }
        IInfobaseAccessSettings settings = null;
        try {
            IInfobaseAccessManager accessManager = gateway.getInfobaseAccessManager();
            settings = accessManager.resolveSettings(infobase);
        } catch (Exception | NoSuchMethodError e) {
            LOG.warn("Failed to resolve access settings (possible EDT 2025.2 API change): " + e.getMessage(), e); //$NON-NLS-1$
            settings = null;
        }

        if (settings != null && settings != IInfobaseAccessSettings.NOT_DEFINED) {
            InfobaseAccess access = settings.access();
            if (access == InfobaseAccess.OS) {
                builder.osAuthentication(true);
            } else if (access == InfobaseAccess.INFOBASE) {
                String user = settings.userName();
                if (user != null && !user.isBlank()) {
                    builder.userName(user);
                }
                String password = settings.password();
                if (password != null && !password.isBlank()) {
                    builder.userPassword(password);
                }
            }
            String additional = settings.additionalProperties();
            if (additional != null && !additional.isBlank()) {
                builder.additionalParameters(additional);
                return;
            }
        }

        String fallback = infobase.getAdditionalParameters();
        if (fallback != null && !fallback.isBlank()) {
            builder.additionalParameters(fallback);
        }
    }

    private String buildStartupOption(File vaParamsPath, File workspaceRoot, boolean showMainForm,
                                      boolean quietInstall, boolean clearStepsCache) {
        StringBuilder sb = new StringBuilder();
        sb.append("StartFeaturePlayer"); //$NON-NLS-1$
        if (vaParamsPath != null) {
            // Vanessa-Automation 6.x/7.x parses the StartFeaturePlayer payload looking for the
            // key VBParams (not VAParams). Sending the wrong key makes the EPF write "Не найден
            // путь к файлу JSON. Параметр: VBParams." to va.log and bail BEFORE spawning any
            // TestClient — visible as descendants=0 / va.log=100b in qa_run heartbeats.
            sb.append(";VBParams=").append(vaParamsPath.getAbsolutePath()); //$NON-NLS-1$
        }
        if (workspaceRoot != null) {
            sb.append(";WorkspaceRoot=").append(workspaceRoot.getAbsolutePath()); //$NON-NLS-1$
        }
        if (quietInstall) {
            sb.append(";QuietInstallVanessaExt"); //$NON-NLS-1$
        }
        if (!showMainForm) {
            sb.append(";ShowMainForm=Ложь"); //$NON-NLS-1$
        }
        if (clearStepsCache) {
            sb.append(";ClearStepsCache"); //$NON-NLS-1$
        }
        return sb.toString();
    }

    static String normalizeAdditionalParameters(String base, String extra) {
        String left = base == null ? "" : base.trim(); //$NON-NLS-1$
        String right = extra == null ? "" : extra.trim(); //$NON-NLS-1$
        if (left.isBlank()) {
            return right.isBlank() ? null : right;
        }
        if (right.isBlank()) {
            return left;
        }
        if (left.contains(right)) {
            return left;
        }
        return left + " " + right; //$NON-NLS-1$
    }
}
