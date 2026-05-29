package com.codepilot1c.core.edt.runtime;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.wst.server.core.IRuntime;
import org.eclipse.wst.server.core.IServer;

import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAccessManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAccessSettings;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociation;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationContextProvider;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAccessSettings;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAssociationContext;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAssociationException;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAssociationSettings;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseReferenceException;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseReferences;
import com._1c.g5.v8.dt.platform.services.model.InfobaseAccess;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.services.model.Section;
import com.codepilot1c.core.logging.VibeLogger;
import com.e1c.g5.v8.dt.platform.standaloneserver.core.StandaloneServerException;
import com.e1c.g5.v8.dt.platform.standaloneserver.wst.core.IStandaloneServerService;
import com.e1c.g5.v8.dt.platform.standaloneserver.wst.core.StandaloneServerInfobase;

/**
 * Service that programmatically binds an infobase (file- or standalone-server-based) to an EDT project.
 *
 * <p>This encapsulates the EDT API calls used by the {@code connect_infobase} tool so the tool itself
 * stays easy to unit-test with a stub service.</p>
 */
public class EdtInfobaseConnectService {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(EdtInfobaseConnectService.class);

    private static final int DEFAULT_CLUSTER_PORT = 1541;

    public enum ConnectionKind {
        FILE,
        STANDALONE,
        SERVER;

        public static ConnectionKind parse(String raw) {
            if (raw == null) {
                return null;
            }
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) {
                return null;
            }
            if ("file".equalsIgnoreCase(trimmed)) { //$NON-NLS-1$
                return FILE;
            }
            if ("standalone".equalsIgnoreCase(trimmed)) { //$NON-NLS-1$
                return STANDALONE;
            }
            if ("server".equalsIgnoreCase(trimmed)) { //$NON-NLS-1$
                return SERVER;
            }
            return null;
        }
    }

    public static final class ConnectRequest {
        private final String projectName;
        private final String databasePath;
        private final ConnectionKind kind;
        private final String login;
        private final String password;
        private final boolean setPrimary;
        private final Integer serverPort;
        private final String runtimeVersion;
        private final boolean force;
        private final String infobaseName;
        private final String serverAddress;
        private final String serverRef;

        public ConnectRequest(String projectName, String databasePath, ConnectionKind kind, String login,
                String password, boolean setPrimary, Integer serverPort, String runtimeVersion) {
            this(projectName, databasePath, kind, login, password, setPrimary, serverPort, runtimeVersion, false);
        }

        public ConnectRequest(String projectName, String databasePath, ConnectionKind kind, String login,
                String password, boolean setPrimary, Integer serverPort, String runtimeVersion, boolean force) {
            this(projectName, databasePath, kind, login, password, setPrimary, serverPort, runtimeVersion, force,
                    null);
        }

        public ConnectRequest(String projectName, String databasePath, ConnectionKind kind, String login,
                String password, boolean setPrimary, Integer serverPort, String runtimeVersion, boolean force,
                String infobaseName) {
            this(projectName, databasePath, kind, login, password, setPrimary, serverPort, runtimeVersion, force,
                    infobaseName, null, null);
        }

        public ConnectRequest(String projectName, String databasePath, ConnectionKind kind, String login,
                String password, boolean setPrimary, Integer serverPort, String runtimeVersion, boolean force,
                String infobaseName, String serverAddress, String serverRef) {
            this.projectName = projectName;
            this.databasePath = databasePath;
            this.kind = kind;
            this.login = login;
            this.password = password;
            this.setPrimary = setPrimary;
            this.serverPort = serverPort;
            this.runtimeVersion = runtimeVersion;
            this.force = force;
            this.infobaseName = infobaseName;
            this.serverAddress = serverAddress;
            this.serverRef = serverRef;
        }

        public String projectName() { return projectName; }
        public String databasePath() { return databasePath; }
        public ConnectionKind kind() { return kind; }
        public String login() { return login; }
        public String password() { return password; }
        public boolean setPrimary() { return setPrimary; }
        public Integer serverPort() { return serverPort; }
        public String runtimeVersion() { return runtimeVersion; }
        public boolean force() { return force; }
        public String infobaseName() { return infobaseName; }
        /** kind=server: cluster server address (the {@code Srvr} key), e.g. "host" or "host:port". */
        public String serverAddress() { return serverAddress; }
        /** kind=server: infobase name on the cluster (the {@code Ref} key). */
        public String serverRef() { return serverRef; }
    }

    public static final class ConnectResult {
        private final ConnectionKind kind;
        private final String resolvedPath;
        private final String infobaseName;
        private final String login;
        private final Integer serverPort;
        private final boolean primary;
        private final String replacedPrevious;
        private final boolean idempotent;

        public ConnectResult(ConnectionKind kind, String resolvedPath, String infobaseName, String login,
                Integer serverPort, boolean primary) {
            this(kind, resolvedPath, infobaseName, login, serverPort, primary, null);
        }

        public ConnectResult(ConnectionKind kind, String resolvedPath, String infobaseName, String login,
                Integer serverPort, boolean primary, String replacedPrevious) {
            this(kind, resolvedPath, infobaseName, login, serverPort, primary, replacedPrevious, false);
        }

        public ConnectResult(ConnectionKind kind, String resolvedPath, String infobaseName, String login,
                Integer serverPort, boolean primary, String replacedPrevious, boolean idempotent) {
            this.kind = kind;
            this.resolvedPath = resolvedPath;
            this.infobaseName = infobaseName;
            this.login = login;
            this.serverPort = serverPort;
            this.primary = primary;
            this.replacedPrevious = replacedPrevious;
            this.idempotent = idempotent;
        }

        public ConnectionKind kind() { return kind; }
        public String resolvedPath() { return resolvedPath; }
        public String infobaseName() { return infobaseName; }
        public String login() { return login; }
        public Integer serverPort() { return serverPort; }
        public boolean primary() { return primary; }
        public String replacedPrevious() { return replacedPrevious; }
        /** True when the requested binding was already the project's primary (no change applied). */
        public boolean idempotent() { return idempotent; }
    }

    private final EdtRuntimeGateway gateway;

    public EdtInfobaseConnectService() {
        this(new EdtRuntimeGateway());
    }

    public EdtInfobaseConnectService(EdtRuntimeGateway gateway) {
        this.gateway = gateway;
    }

    public ConnectResult connect(ConnectRequest request) {
        if (request == null) {
            throw new EdtToolException(EdtToolErrorCode.INVALID_ARGUMENT, "request is required"); //$NON-NLS-1$
        }
        validate(request);

        IProject project = resolveProject(request.projectName());
        return switch (request.kind()) {
            case FILE -> connectFile(project, request);
            case STANDALONE -> connectStandalone(project, request);
            case SERVER -> connectServer(project, request);
        };
    }

    private void validate(ConnectRequest request) {
        if (request.projectName() == null || request.projectName().isBlank()) {
            throw new EdtToolException(EdtToolErrorCode.INVALID_ARGUMENT, "project_name is required"); //$NON-NLS-1$
        }
        if (request.kind() == null) {
            throw new EdtToolException(EdtToolErrorCode.INVALID_ARGUMENT,
                    "kind is required and must be 'file', 'standalone' or 'server'"); //$NON-NLS-1$
        }
        if (request.kind() == ConnectionKind.SERVER) {
            // Server binding identifies the infobase by Srvr/Ref, not a filesystem path.
            if (request.serverAddress() == null || request.serverAddress().isBlank()) {
                throw new EdtToolException(EdtToolErrorCode.INVALID_ARGUMENT,
                        "srvr is required for kind=server (cluster server address)"); //$NON-NLS-1$
            }
            if (request.serverRef() == null || request.serverRef().isBlank()) {
                throw new EdtToolException(EdtToolErrorCode.INVALID_ARGUMENT,
                        "ref is required for kind=server (infobase name on the cluster)"); //$NON-NLS-1$
            }
        } else if (request.databasePath() == null || request.databasePath().isBlank()) {
            throw new EdtToolException(EdtToolErrorCode.INVALID_ARGUMENT, "database_path is required"); //$NON-NLS-1$
        }
    }

    /**
     * Binds a client/server (cluster) infobase identified by {@code Srvr}/{@code Ref}. The only
     * server-specific step is building the {@link InfobaseReference} via
     * {@code newServerInfobaseReference(server, ref)} — the rest reuses the same name-collision,
     * adopt-existing, persist, access-settings and associate machinery as {@link #connectFile},
     * which all operate on the reference's identity (UUID / connection string) regardless of kind.
     */
    private ConnectResult connectServer(IProject project, ConnectRequest request) {
        String server = request.serverAddress().trim();
        String ref = request.serverRef().trim();

        InfobaseReference reference = InfobaseReferences.newServerInfobaseReference(server, ref);
        String infobaseName = request.infobaseName();
        if (infobaseName == null || infobaseName.isBlank()) {
            infobaseName = reference.getName();
        }
        if (infobaseName == null || infobaseName.isBlank()) {
            infobaseName = ref;
        }
        reference.setName(infobaseName);

        String connectionString = infobaseIdentity(reference);
        if (connectionString == null || connectionString.isBlank()) {
            connectionString = "Srvr=\"" + server + "\";Ref=\"" + ref + "\";"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }

        PrimaryOutcome primaryOutcome = evaluatePrimary(project, request, reference);
        if (primaryOutcome.idempotent()) {
            LOG.info("connect_infobase(server) project=%s srvr=%s ref=%s already primary — idempotent no-op", //$NON-NLS-1$
                    request.projectName(), server, ref);
            return new ConnectResult(ConnectionKind.SERVER, connectionString, infobaseName,
                    sanitizeLogin(request.login()), null, true, null, true);
        }
        String replacedPrevious = primaryOutcome.replacedPrevious();

        // Adopt an existing same-connection association entry (name+UUID) so setDefaultInfobase
        // targets it; throws PATH_ALREADY_ASSOCIATED_AS on an explicit conflicting infobase_name.
        adoptExistingAssociationName(project, reference, request.infobaseName());
        if (reference.getName() != null && !reference.getName().isBlank()) {
            infobaseName = reference.getName();
        }
        persistReference(reference);
        storeAccessSettings(reference, request.login(), request.password());
        boolean primary = associate(project, reference, request.setPrimary());

        LOG.info("connect_infobase(server) project=%s srvr=%s ref=%s primary=%s", //$NON-NLS-1$
                request.projectName(), server, ref, Boolean.valueOf(primary));

        return new ConnectResult(ConnectionKind.SERVER, connectionString, infobaseName,
                sanitizeLogin(request.login()), null, primary, replacedPrevious);
    }

    private IProject resolveProject(String projectName) {
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace() == null
                ? null : ResourcesPlugin.getWorkspace().getRoot();
        IProject project = root == null ? null : root.getProject(projectName);
        if (project == null || !project.exists()) {
            throw new EdtToolException(EdtToolErrorCode.PROJECT_NOT_FOUND,
                    "EDT project not found: " + projectName); //$NON-NLS-1$
        }
        return project;
    }

    private ConnectResult connectFile(IProject project, ConnectRequest request) {
        Path resolvedPath = ensureFileInfobasePath(request.databasePath());
        String filePathArg = resolvedPath.toAbsolutePath().toString();

        InfobaseReference reference = InfobaseReferences.newFileInfobaseReference(filePathArg);
        String infobaseName = request.infobaseName();
        if (infobaseName == null || infobaseName.isBlank()) {
            infobaseName = reference.getName();
        }
        if (infobaseName == null || infobaseName.isBlank()) {
            // Fall back to the folder name. NB: this can collide with an existing infobase of the
            // same name (e.g. an auto-provisioned server infobase); persistReference() detects that
            // and fails with NAME_COLLISION so the caller can retry with an explicit infobase_name.
            infobaseName = resolvedPath.getFileName() == null
                    ? "infobase" //$NON-NLS-1$
                    : resolvedPath.getFileName().toString();
        }
        reference.setName(infobaseName);

        PrimaryOutcome primaryOutcome = evaluatePrimary(project, request, reference);
        if (primaryOutcome.idempotent()) {
            LOG.info("connect_infobase(file) project=%s path=%s already primary — idempotent no-op", //$NON-NLS-1$
                    request.projectName(), filePathArg);
            return new ConnectResult(ConnectionKind.FILE, filePathArg, infobaseName,
                    sanitizeLogin(request.login()), null, true, null, true);
        }
        String replacedPrevious = primaryOutcome.replacedPrevious();
        // If the project's association already binds this exact path under another name (e.g. the
        // user GUI-bound it earlier, or v8i kept an older entry), adopt that name+UUID onto our
        // reference so the downstream associate()/setDefaultInfobase target the existing entry
        // — otherwise EDT fails with "Association does not contain ...". If the caller passed an
        // explicit infobase_name that *conflicts* with the existing one, throws PATH_ALREADY_ASSOCIATED_AS.
        adoptExistingAssociationName(project, reference, request.infobaseName());
        // Refresh the local name in case it was adopted from the existing association entry.
        if (reference.getName() != null && !reference.getName().isBlank()) {
            infobaseName = reference.getName();
        }
        persistReference(reference);
        storeAccessSettings(reference, request.login(), request.password());
        boolean primary = associate(project, reference, request.setPrimary());

        LOG.info("connect_infobase(file) project=%s path=%s primary=%s", //$NON-NLS-1$
                request.projectName(), filePathArg, Boolean.valueOf(primary));

        return new ConnectResult(ConnectionKind.FILE, filePathArg, infobaseName,
                sanitizeLogin(request.login()), null, primary, replacedPrevious);
    }

    // Visible for testing.
    protected ConnectResult connectStandalone(IProject project, ConnectRequest request) {
        Path resolvedPath = ensureStandaloneDataPath(request.databasePath());
        String filePathArg = resolvedPath.toAbsolutePath().toString();
        int port = request.serverPort() != null && request.serverPort().intValue() > 0
                ? request.serverPort().intValue() : DEFAULT_CLUSTER_PORT;

        InfobaseReference reference = InfobaseReferences.newFileInfobaseReference(filePathArg);
        String infobaseName = request.infobaseName();
        if (infobaseName == null || infobaseName.isBlank()) {
            infobaseName = reference.getName();
        }
        if (infobaseName == null || infobaseName.isBlank()) {
            // Fall back to the folder name. NB: this can collide with an existing infobase of the
            // same name (e.g. an auto-provisioned server infobase); persistReference() detects that
            // and fails with NAME_COLLISION so the caller can retry with an explicit infobase_name.
            infobaseName = resolvedPath.getFileName() == null
                    ? "infobase" //$NON-NLS-1$
                    : resolvedPath.getFileName().toString();
        }
        reference.setName(infobaseName);

        PrimaryOutcome primaryOutcome = evaluatePrimary(project, request, reference);
        if (primaryOutcome.idempotent()) {
            LOG.info("connect_infobase(standalone) project=%s path=%s already primary — idempotent no-op", //$NON-NLS-1$
                    request.projectName(), filePathArg);
            return new ConnectResult(ConnectionKind.STANDALONE, filePathArg, infobaseName,
                    sanitizeLogin(request.login()), Integer.valueOf(port), true, null, true);
        }
        String replacedPrevious = primaryOutcome.replacedPrevious();

        // EDT's StandaloneServerInfobase constructor requires a non-null UUID;
        // newFileInfobaseReference() does not populate one, so assign before the EDT call.
        if (reference.getUuid() == null) {
            reference.setUuid(UUID.randomUUID());
        }

        IStandaloneServerService service = gateway.getStandaloneServerService();
        String version = request.runtimeVersion();
        IRuntime runtime = findRuntime(service, version);
        if (runtime == null) {
            throw new EdtToolException(EdtToolErrorCode.STANDALONE_RUNTIME_NOT_FOUND,
                    "Standalone runtime not found for version: " //$NON-NLS-1$
                            + (version == null ? "<default>" : version)); //$NON-NLS-1$
        }

        Path operationRoot = resolvedPath.resolve(".codepilot-standalone"); //$NON-NLS-1$
        ensureDirectory(operationRoot);
        String clusterRegistryDirectory = operationRoot.resolve("cluster-registry").toString(); //$NON-NLS-1$
        String publicationPath = operationRoot.resolve("publication").toString(); //$NON-NLS-1$
        ensureDirectory(Path.of(clusterRegistryDirectory));
        ensureDirectory(Path.of(publicationPath));

        InfobaseReference boundReference;
        try {
            Object pair = invokeCreateServerWithInfobase(service,
                    version == null ? "" : version, //$NON-NLS-1$
                    request.projectName(),
                    reference,
                    port,
                    clusterRegistryDirectory,
                    publicationPath,
                    new NullProgressMonitor());
            Object first = readPairValue(pair, "first", "getFirst"); //$NON-NLS-1$ //$NON-NLS-2$
            Object second = readPairValue(pair, "second", "getSecond"); //$NON-NLS-1$ //$NON-NLS-2$
            IServer server = first instanceof IServer s ? s : null;
            StandaloneServerInfobase standaloneInfobase = second instanceof StandaloneServerInfobase sai ? sai : null;
            if (server == null || standaloneInfobase == null) {
                throw new EdtToolException(EdtToolErrorCode.STANDALONE_SERVER_CREATE_FAILED,
                        "Standalone server creation returned incomplete result"); //$NON-NLS-1$
            }
            boundReference = resolveBoundReference(standaloneInfobase, reference);
        } catch (EdtToolException e) {
            throw e;
        } catch (StandaloneServerException e) {
            String detail = e.getMessage() != null && !e.getMessage().isBlank()
                    ? e.getMessage() : e.getClass().getSimpleName();
            throw new EdtToolException(EdtToolErrorCode.STANDALONE_SERVER_CREATE_FAILED,
                    "Standalone server operation failed: " + detail, e); //$NON-NLS-1$
        } catch (ReflectiveOperationException e) {
            String detail = e.getMessage() != null && !e.getMessage().isBlank()
                    ? e.getMessage() : e.getClass().getSimpleName();
            throw new EdtToolException(EdtToolErrorCode.STANDALONE_SERVER_CREATE_FAILED,
                    "Failed to create standalone server: " + detail, e); //$NON-NLS-1$
        } catch (RuntimeException e) {
            // Surface unchecked exceptions from EDT (e.g. IllegalArgumentException from a
            // Preconditions check inside the standalone API) as a typed error instead of
            // letting them escape and be mislabelled as EDT_SERVICE_UNAVAILABLE upstream.
            String detail = e.getMessage() != null && !e.getMessage().isBlank()
                    ? e.getMessage() : e.getClass().getSimpleName();
            throw new EdtToolException(EdtToolErrorCode.STANDALONE_SERVER_CREATE_FAILED,
                    "Failed to create standalone server: " + detail, e); //$NON-NLS-1$
        }

        if (boundReference.getName() == null || boundReference.getName().isBlank()) {
            boundReference.setName(infobaseName);
        }

        storeAccessSettings(boundReference, request.login(), request.password());
        boolean primary = associate(project, boundReference, request.setPrimary());

        LOG.info("connect_infobase(standalone) project=%s path=%s port=%d primary=%s", //$NON-NLS-1$
                request.projectName(), filePathArg, Integer.valueOf(port), Boolean.valueOf(primary));

        return new ConnectResult(ConnectionKind.STANDALONE, filePathArg, boundReference.getName(),
                sanitizeLogin(request.login()), Integer.valueOf(port), primary, replacedPrevious);
    }

    /**
     * When {@code set_primary=true} is requested and the project already has a primary infobase:
     * <ul>
     *   <li>If {@code force=false}, throws {@link EdtToolException} with
     *       {@link EdtToolErrorCode#PRIMARY_EXISTS} — callers surface this so the user can decide.</li>
     *   <li>If {@code force=true}, returns the name of the infobase being replaced so it can be
     *       included in the success payload.</li>
     * </ul>
     * Returns {@code null} when there is no existing primary or {@code set_primary=false}.
     */
    /** Outcome of the primary-infobase pre-check. */
    public record PrimaryOutcome(boolean idempotent, String replacedPrevious) {
    }

    /**
     * Decides what to do about the project's existing primary infobase when {@code set_primary=true}.
     * Behaviour is identical to the historical {@code checkExistingPrimary} EXCEPT for the new
     * idempotent case:
     * <ul>
     *   <li>{@code set_primary=false} or no existing primary → {@code PROCEED} (idempotent=false,
     *       replacedPrevious=null).</li>
     *   <li>The existing primary is <em>exactly</em> the infobase being connected (same connection
     *       identity AND same login) and the caller did not request a rename → {@code IDEMPOTENT}
     *       no-op: nothing is mutated and the call succeeds without needing {@code force} (closes the
     *       "every reconnect needs force=true" papercut from the 2026-05-29 server smoke).</li>
     *   <li>A <em>different</em> infobase is primary (or the same one but with a changed login) and
     *       {@code force=false} → throw {@link EdtToolErrorCode#PRIMARY_EXISTS}; with {@code force=true}
     *       → returns the replaced name. A login change still requires {@code force} so credentials are
     *       never replaced by an unwitting reconnect.</li>
     * </ul>
     */
    protected PrimaryOutcome evaluatePrimary(IProject project, ConnectRequest request, InfobaseReference reference) {
        if (!request.setPrimary()) {
            return new PrimaryOutcome(false, null);
        }
        IInfobaseAssociationManager associationManager;
        try {
            associationManager = gateway.getInfobaseAssociationManager();
        } catch (IllegalStateException e) {
            // Association manager not available — nothing to check; a later associate() call will fail
            // with its own error. Do not block the primary-exists check here.
            return new PrimaryOutcome(false, null);
        }
        IInfobaseAssociation association;
        try {
            association = associationManager.getAssociation(project).orElse(null);
        } catch (RuntimeException e) {
            return new PrimaryOutcome(false, null);
        }
        if (association == null) {
            return new PrimaryOutcome(false, null);
        }
        InfobaseReference existing = association.getDefaultInfobase();
        if (existing == null) {
            return new PrimaryOutcome(false, null);
        }
        boolean noExplicitRename = request.infobaseName() == null || request.infobaseName().isBlank();
        if (noExplicitRename && sameInfobaseIdentity(existing, reference) && sameLogin(existing, request)) {
            return new PrimaryOutcome(true, null);
        }
        String existingName = existing.getName();
        if (existingName == null || existingName.isBlank()) {
            existingName = "<unnamed>"; //$NON-NLS-1$
        }
        if (!request.force()) {
            throw new EdtToolException(EdtToolErrorCode.PRIMARY_EXISTS,
                    "primary_exists: current_primary=" + existingName //$NON-NLS-1$
                            + ", pass force=true to replace"); //$NON-NLS-1$
        }
        return new PrimaryOutcome(false, existingName);
    }

    /**
     * True when the login the caller requested matches the existing primary's stored access settings
     * (both OS-auth, or both infobase-auth with the same user name). Returns {@code false} on any
     * resolution failure so an indeterminate state never silently no-ops a credential change.
     */
    private boolean sameLogin(InfobaseReference existing, ConnectRequest request) {
        String requestedLogin = request.login();
        boolean requestedOs = requestedLogin == null || requestedLogin.isBlank();
        boolean existingOs = true;
        String existingLogin = null;
        try {
            IInfobaseAccessManager accessManager = gateway.getInfobaseAccessManager();
            IInfobaseAccessSettings settings = accessManager.resolveSettings(existing);
            if (settings != null && settings != IInfobaseAccessSettings.NOT_DEFINED) {
                existingOs = settings.access() == InfobaseAccess.OS;
                existingLogin = settings.userName();
            }
        } catch (Exception | NoSuchMethodError e) {
            return false;
        }
        if (requestedOs && existingOs) {
            return true;
        }
        if (!requestedOs && !existingOs) {
            return requestedLogin.equals(existingLogin);
        }
        return false;
    }

    // -- EDT write-side operations --------------------------------------------------------------

    protected void persistReference(InfobaseReference reference) {
        IInfobaseManager manager;
        try {
            manager = gateway.getInfobaseManager();
        } catch (IllegalStateException e) {
            // Do NOT swallow: without the manager nothing is actually persisted and later reads fail.
            throw new IllegalStateException(
                    "IInfobaseManager service unavailable \u2014 EDT may not be fully initialized", e); //$NON-NLS-1$
        }
        if (manager == null) {
            throw new IllegalStateException(
                    "IInfobaseManager service unavailable \u2014 EDT may not be fully initialized"); //$NON-NLS-1$
        }
        // The downstream EDT call {@code IInfobaseAccessManager.storeSettings} NPEs when the
        // reference has no UUID. Ensure every return path below leaves the reference with a
        // non-null UUID. See GH issue #31.
        if (!manager.isPersistenceSupported()) {
            if (reference.getUuid() == null) {
                reference.setUuid(UUID.randomUUID());
            }
            return;
        }
        Optional<InfobaseReference> existing = findExisting(manager, reference);
        if (existing.isPresent()) {
            // Copy the existing entry's UUID onto the in-memory reference so downstream calls
            // (storeSettings, associate) target the already-registered row.
            UUID existingUuid = existing.get().getUuid();
            if (existingUuid != null && reference.getUuid() == null) {
                reference.setUuid(existingUuid);
            }
            if (reference.getUuid() == null) {
                // Defense-in-depth: existing entry had no UUID either — assign a fresh one so
                // storeSettings doesn't NPE.
                reference.setUuid(UUID.randomUUID());
            }
            return;
        }
        // findExisting() returned empty, so no registered infobase shares our identity. If one
        // nonetheless shares our NAME, it is a genuine collision (e.g. an auto-provisioned server
        // infobase named like the file-infobase folder). EDT enforces unique names, so a bare
        // manager.add() would throw an opaque "already connected" and, as observed, can disturb the
        // project's current association. Fail BEFORE any mutation, with an actionable code.
        String referenceName = reference.getName();
        if (referenceName != null && !referenceName.isBlank()
                && !findCandidatesByName(manager, referenceName).isEmpty()) {
            throw new EdtToolException(EdtToolErrorCode.NAME_COLLISION,
                    "name_collision: an infobase named '" + referenceName //$NON-NLS-1$
                            + "' already exists with a different connection. " //$NON-NLS-1$
                            + "Pass a distinct infobase_name to connect this one."); //$NON-NLS-1$
        }
        try {
            manager.add(reference, null);
        } catch (InfobaseReferenceException e) {
            String detail = e.getMessage() != null && !e.getMessage().isBlank()
                    ? e.getMessage() : e.getClass().getSimpleName();
            throw new EdtToolException(EdtToolErrorCode.EDT_SERVICE_UNAVAILABLE,
                    "Failed to register infobase reference: " + detail, e); //$NON-NLS-1$
        }
        // manager.add() normally populates the UUID; if it didn't, assign one locally so the
        // subsequent storeSettings call has a non-null key.
        if (reference.getUuid() == null) {
            reference.setUuid(UUID.randomUUID());
        }
    }

    private Optional<InfobaseReference> findExisting(IInfobaseManager manager, InfobaseReference reference) {
        try {
            if (reference.getUuid() != null) {
                Optional<InfobaseReference> byUuid = manager.findInfobaseByUuid(reference.getUuid());
                if (byUuid.isPresent()) {
                    return byUuid;
                }
            }
            return findExistingByIdentity(manager, reference);
        } catch (RuntimeException ignored) {
            // Best-effort lookup; caller falls through to manager.add() or a local UUID assignment.
        }
        return Optional.empty();
    }

    private Optional<InfobaseReference> findExistingByIdentity(IInfobaseManager manager, InfobaseReference reference) {
        String name = reference.getName();
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        for (InfobaseReference candidate : findCandidatesByName(manager, name)) {
            if (sameInfobaseIdentity(candidate, reference)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private List<InfobaseReference> findCandidatesByName(IInfobaseManager manager, String name) {
        List<InfobaseReference> candidates = new ArrayList<>();
        try {
            List<InfobaseReference> byNames = manager.findInfobasesByNames(List.of(name));
            if (byNames != null) {
                candidates.addAll(byNames);
            }
        } catch (RuntimeException ignored) {
            // Fall back to the single-name lookup below.
        }
        if (candidates.isEmpty()) {
            try {
                manager.findInfobaseByName(name).ifPresent(candidates::add);
            } catch (RuntimeException ignored) {
                // Best-effort lookup only.
            }
        }
        // EDT's findInfobasesByNames / findInfobaseByName sometimes miss entries — observed live
        // on 2025.2.3 where a server infobase shared a display name with a file folder but did not
        // surface via these targeted lookups (the NAME_COLLISION check then let the call proceed
        // and a later EDT step surfaced an "Association does not contain ..." error). Sweep
        // manager.getAll() as a backstop so both the collision check AND idempotent reconnect see
        // the full v8i registry.
        try {
            for (Section section : manager.getAll()) {
                if (section instanceof InfobaseReference ref
                        && name.equals(ref.getName())
                        && !candidates.contains(ref)) {
                    candidates.add(ref);
                }
            }
        } catch (RuntimeException ignored) {
            // Best-effort sweep; the targeted lookups above are still authoritative.
        }
        return candidates;
    }

    private static boolean sameInfobaseIdentity(InfobaseReference left, InfobaseReference right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        UUID leftUuid = left.getUuid();
        UUID rightUuid = right.getUuid();
        if (leftUuid != null && rightUuid != null) {
            return leftUuid.equals(rightUuid);
        }
        String leftConnection = infobaseIdentity(left);
        String rightConnection = infobaseIdentity(right);
        if (leftConnection != null && rightConnection != null) {
            return leftConnection.equals(rightConnection);
        }
        return false;
    }

    private static String infobaseIdentity(InfobaseReference reference) {
        try {
            if (reference == null || reference.getConnectionString() == null) {
                return null;
            }
            String value = reference.getConnectionString().asConnectionString();
            return value == null || value.isBlank() ? null : value.trim();
        } catch (RuntimeException e) {
            return null;
        }
    }

    protected void storeAccessSettings(InfobaseReference reference, String login, String password) {
        // Guard against EDT's storeSettings NPE when the reference has no UUID. persistReference()
        // is responsible for assigning one; bail early with a clear diagnostic if it didn't so the
        // failure doesn't surface as an opaque ": null" message. See GH issue #31.
        if (reference.getUuid() == null) {
            throw new EdtToolException(EdtToolErrorCode.EDT_SERVICE_UNAVAILABLE,
                    "Cannot store access settings: infobase reference has no UUID " //$NON-NLS-1$
                            + "(persistReference did not assign one)"); //$NON-NLS-1$
        }
        IInfobaseAccessManager accessManager = gateway.getInfobaseAccessManager();
        InfobaseAccess access = (login != null && !login.isBlank())
                ? InfobaseAccess.INFOBASE : InfobaseAccess.OS;
        InfobaseAccessSettings settings = new InfobaseAccessSettings(
                access,
                access == InfobaseAccess.INFOBASE ? login : null,
                access == InfobaseAccess.INFOBASE ? (password == null ? "" : password) : null, //$NON-NLS-1$
                null);
        try {
            // EDT 2025.2 (services.core 21.x) replaced storeSettings(ref, settings) with
            // updateSettings(ref, settings) — same signature. storeSettings is gone from the 21.x
            // API entirely, so the prior 20.x/21.x runtime fallback no longer compiles against this
            // target; call updateSettings directly.
            accessManager.updateSettings(reference, settings);
        } catch (Exception e) {
            String detail = e.getMessage() != null && !e.getMessage().isBlank()
                    ? e.getMessage() : e.getClass().getSimpleName();
            throw new EdtToolException(EdtToolErrorCode.EDT_SERVICE_UNAVAILABLE,
                    "Failed to store infobase access settings: " + detail, e); //$NON-NLS-1$
        }
    }

    /**
     * If the project's infobase association already has an entry bound to this exact connection
     * (i.e., a same-path InfobaseReference under any name), adopt that entry's name and UUID onto
     * the caller's reference. EDT's {@link IInfobaseAssociationManager#setDefaultInfobase} looks up
     * the reference inside the association by name; without this step, a reconnect whose display
     * name diverges from the registered one fails with "Association does not contain ...".
     *
     * <p>If the caller passed an explicit {@code infobase_name} that differs from the registered
     * one, this method throws {@link EdtToolErrorCode#PATH_ALREADY_ASSOCIATED_AS} with the existing
     * name in the message so the agent can retry with the right value (or omit the parameter and
     * let the call adopt silently).</p>
     */
    protected void adoptExistingAssociationName(IProject project, InfobaseReference reference,
            String explicitInfobaseName) {
        if (project == null || reference == null) {
            return;
        }
        IInfobaseAssociationManager associationManager;
        try {
            associationManager = gateway.getInfobaseAssociationManager();
        } catch (IllegalStateException e) {
            return; // association manager unavailable — downstream associate() will surface its own error.
        }
        if (associationManager == null) {
            return;
        }
        Optional<IInfobaseAssociation> assoc;
        try {
            assoc = associationManager.getAssociation(project);
        } catch (RuntimeException e) {
            return;
        }
        if (assoc.isEmpty()) {
            return;
        }
        String ourConnection = infobaseIdentity(reference);
        if (ourConnection == null) {
            return;
        }
        java.util.Collection<InfobaseReference> bound;
        try {
            bound = assoc.get().getInfobases();
        } catch (RuntimeException e) {
            return;
        }
        if (bound == null) {
            return;
        }
        for (InfobaseReference candidate : bound) {
            if (candidate == null) {
                continue;
            }
            String candidateConnection = infobaseIdentity(candidate);
            if (!ourConnection.equals(candidateConnection)) {
                continue;
            }
            String candidateName = candidate.getName();
            if (explicitInfobaseName != null && !explicitInfobaseName.isBlank()
                    && candidateName != null && !candidateName.isBlank()
                    && !explicitInfobaseName.equals(candidateName)) {
                throw new EdtToolException(EdtToolErrorCode.PATH_ALREADY_ASSOCIATED_AS,
                        "path_already_associated_as: this path is already bound to the project under " //$NON-NLS-1$
                                + "name '" + candidateName + "'; retry with infobase_name=\"" //$NON-NLS-1$ //$NON-NLS-2$
                                + candidateName + "\" (or omit infobase_name to reuse it)"); //$NON-NLS-1$
            }
            if (candidateName != null && !candidateName.isBlank()) {
                reference.setName(candidateName);
            }
            if (candidate.getUuid() != null && reference.getUuid() == null) {
                reference.setUuid(candidate.getUuid());
            }
            return;
        }
    }

    protected boolean associate(IProject project, InfobaseReference reference, boolean setPrimary) {
        IInfobaseAssociationManager associationManager = gateway.getInfobaseAssociationManager();
        // Write the binding under the project's *effective* association context — the same context
        // that EDT's no-arg getAssociation(project)/setDefaultInfobase/update_infobase reads back
        // from (it resolves the context via IInfobaseAssociationContextProvider, not the empty one).
        // Using the empty context unconditionally — as alreadySynchronized() does — silently
        // partitions the write away from the read when an association-context extension is active
        // (e.g. remote/SSH workspaces), so associate() reports success yet the association is
        // invisible to every subsequent lookup. See feedback 2026-05-29-connect-infobase-success-
        // not-persisted: setDefaultInfobase then threw "Project ... is not associated with infobase
        // ..." and update_infobase returned INFOBASE_ASSOCIATION_NOT_FOUND. When no extension is
        // contributed the provider returns empty(), preserving the historical behaviour.
        InfobaseAssociationContext context = resolveAssociationContext(project);
        InfobaseAssociationSettings settings = new InfobaseAssociationSettings(false, context);
        try {
            associationManager.associate(project, reference, settings);
        } catch (InfobaseAssociationException e) {
            String detail = e.getMessage() != null && !e.getMessage().isBlank()
                    ? e.getMessage() : e.getClass().getSimpleName();
            throw new EdtToolException(EdtToolErrorCode.EDT_SERVICE_UNAVAILABLE,
                    "Failed to associate infobase with project: " + detail, e); //$NON-NLS-1$
        }
        if (setPrimary) {
            try {
                associationManager.setDefaultInfobase(project, reference, context);
            } catch (InfobaseAssociationException e) {
                String detail = e.getMessage() != null && !e.getMessage().isBlank()
                        ? e.getMessage() : e.getClass().getSimpleName();
                throw new EdtToolException(EdtToolErrorCode.EDT_SERVICE_UNAVAILABLE,
                        "Failed to set default infobase for project: " + detail, e); //$NON-NLS-1$
            }
            return true;
        }
        return false;
    }

    /**
     * Resolves the project's effective {@link InfobaseAssociationContext} — the context EDT's
     * association manager uses internally for the no-arg {@code getAssociation(project)} reads that
     * {@code setDefaultInfobase} and {@code update_infobase} rely on. Falls back to
     * {@link InfobaseAssociationContext#empty()} whenever the provider is unavailable, contributes
     * no extension, or fails, so a missing provider never blocks or breaks a connect.
     *
     * <p>Visible for testing.</p>
     */
    protected InfobaseAssociationContext resolveAssociationContext(IProject project) {
        if (project == null) {
            return InfobaseAssociationContext.empty();
        }
        IInfobaseAssociationContextProvider provider;
        try {
            provider = gateway.peekInfobaseAssociationContextProvider();
        } catch (RuntimeException e) {
            return InfobaseAssociationContext.empty();
        }
        if (provider == null) {
            return InfobaseAssociationContext.empty();
        }
        try {
            // get(project) declares InfobaseAssociationException, which is itself a RuntimeException,
            // so a single RuntimeException catch covers both it and any other unchecked failure.
            InfobaseAssociationContext context = provider.get(project);
            return context != null ? context : InfobaseAssociationContext.empty();
        } catch (RuntimeException e) {
            LOG.warn("connect_infobase: failed to resolve association context for project=%s, " //$NON-NLS-1$
                    + "falling back to empty context: %s", //$NON-NLS-1$
                    project.getName(), e.getMessage());
            return InfobaseAssociationContext.empty();
        }
    }

    // -- Helpers --------------------------------------------------------------------------------

    public static Path ensureFileInfobasePath(String rawPath) {
        // kind=file only *associates* an existing infobase folder with the project — it never
        // writes into it (unlike standalone, which creates .codepilot-standalone/ there). So the
        // workspace/home root constraint is relaxed here: an infobase directory may live anywhere
        // (e.g. per-branch sandboxes under db\Branches\...). Path-traversal ('..') is still rejected,
        // and we never CREATE a directory outside the sanctioned roots — an out-of-root path must
        // already exist.
        Path path = normalizeAndRejectTraversal(rawPath);
        boolean exists = Files.exists(path);
        if (exists && !Files.isDirectory(path)) {
            throw new EdtToolException(EdtToolErrorCode.INVALID_ARGUMENT,
                    "database_path must be a directory: " + path); //$NON-NLS-1$
        }
        if (!isInsideAllowedRoot(path)) {
            if (!exists) {
                throw new EdtToolException(EdtToolErrorCode.INVALID_PATH,
                        "invalid_path: a file database_path outside the workspace/home must be an existing infobase directory: " //$NON-NLS-1$
                                + path);
            }
            return path; // associate the existing folder; do not create anything
        }
        ensureDirectory(path);
        return path;
    }

    public static Path ensureStandaloneDataPath(String rawPath) {
        // Standalone WRITES into database_path (.codepilot-standalone/ registry + publication),
        // so it stays constrained to the workspace/home roots via validateAndNormalizePath.
        Path path = validateAndNormalizePath(rawPath);
        if (Files.exists(path) && !Files.isDirectory(path)) {
            throw new EdtToolException(EdtToolErrorCode.INVALID_ARGUMENT,
                    "database_path must be a directory: " + path); //$NON-NLS-1$
        }
        ensureDirectory(path);
        return path;
    }

    /**
     * Prevent path-traversal: reject raw {@code ..} segments, then require the resolved absolute
     * path to live inside the Eclipse workspace root or the current user's home directory.
     */
    public static Path validateAndNormalizePath(String rawPath) {
        Path path = normalizeAndRejectTraversal(rawPath);
        if (!isInsideAllowedRoot(path)) {
            throw new EdtToolException(EdtToolErrorCode.INVALID_PATH,
                    "invalid_path: database_path must be inside workspace or home directory"); //$NON-NLS-1$
        }
        return path;
    }

    /**
     * Blank-check, reject raw {@code ..} segments (defense-in-depth before normalization folds
     * them away), and return the normalized absolute path. Does NOT apply the workspace/home
     * root constraint — callers add that where the path is written to.
     */
    private static Path normalizeAndRejectTraversal(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new EdtToolException(EdtToolErrorCode.INVALID_ARGUMENT,
                    "database_path is required"); //$NON-NLS-1$
        }
        Path raw;
        try {
            raw = Path.of(rawPath);
        } catch (RuntimeException e) {
            throw new EdtToolException(EdtToolErrorCode.INVALID_PATH,
                    "invalid_path: database_path is not a valid filesystem path"); //$NON-NLS-1$
        }
        for (Path segment : raw) {
            if ("..".equals(segment.toString())) { //$NON-NLS-1$
                throw new EdtToolException(EdtToolErrorCode.INVALID_PATH,
                        "invalid_path: database_path must not contain '..' segments"); //$NON-NLS-1$
            }
        }
        return raw.toAbsolutePath().normalize();
    }

    private static boolean isInsideAllowedRoot(Path candidate) {
        Path workspaceRoot = null;
        try {
            if (ResourcesPlugin.getWorkspace() != null
                    && ResourcesPlugin.getWorkspace().getRoot() != null
                    && ResourcesPlugin.getWorkspace().getRoot().getLocation() != null) {
                workspaceRoot = ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile().toPath()
                        .toAbsolutePath().normalize();
            }
        } catch (RuntimeException ignored) {
            workspaceRoot = null;
        }
        Path home = null;
        String userHome = System.getProperty("user.home"); //$NON-NLS-1$
        if (userHome != null && !userHome.isBlank()) {
            try {
                home = Path.of(userHome).toAbsolutePath().normalize();
            } catch (RuntimeException ignored) {
                home = null;
            }
        }
        return (workspaceRoot != null && candidate.startsWith(workspaceRoot))
                || (home != null && candidate.startsWith(home));
    }

    private static void ensureDirectory(Path path) {
        try {
            Files.createDirectories(path);
        } catch (Exception e) {
            String detail = e.getMessage() != null && !e.getMessage().isBlank()
                    ? e.getMessage() : e.getClass().getSimpleName();
            throw new EdtToolException(EdtToolErrorCode.INVALID_ARGUMENT,
                    "Failed to create directory: " + path + " (" + detail + ")", e); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
    }

    private static IRuntime findRuntime(IStandaloneServerService service, String version) {
        String normalized = version == null ? "" : version.trim(); //$NON-NLS-1$
        if (!normalized.isEmpty()) {
            Optional<IRuntime> explicit = service.findRuntime(normalized, new NullProgressMonitor());
            if (explicit.isPresent()) {
                return explicit.get();
            }
        }
        Collection<IRuntime> runtimes = service.getRuntimes();
        return runtimes == null || runtimes.isEmpty() ? null : runtimes.iterator().next();
    }

    private static Object invokeCreateServerWithInfobase(IStandaloneServerService service, String platformVersion,
            String projectName, InfobaseReference infobaseReference, int clusterPort,
            String clusterRegistryDirectory, String publicationPath, IProgressMonitor monitor)
            throws ReflectiveOperationException {
        Method method = service.getClass().getMethod(
                "createServerWithInfobase", //$NON-NLS-1$
                String.class,
                String.class,
                InfobaseReference.class,
                int.class,
                String.class,
                String.class,
                IProgressMonitor.class);
        try {
            return method.invoke(service, platformVersion, projectName, infobaseReference,
                    Integer.valueOf(clusterPort), clusterRegistryDirectory, publicationPath, monitor);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ReflectiveOperationException reflective) {
                throw reflective;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new ReflectiveOperationException(cause);
        }
    }

    private static Object readPairValue(Object pair, String methodName, String getterName)
            throws ReflectiveOperationException {
        if (pair == null) {
            return null;
        }
        try {
            Method method = pair.getClass().getMethod(methodName);
            return method.invoke(pair);
        } catch (NoSuchMethodException ignored) {
            // Try bean getter fallback.
        }
        try {
            Method getter = pair.getClass().getMethod(getterName);
            return getter.invoke(pair);
        } catch (NoSuchMethodException ignored) {
            // Try public field fallback.
        }
        try {
            Field field = pair.getClass().getField(methodName);
            return field.get(pair);
        } catch (NoSuchFieldException e) {
            throw new ReflectiveOperationException("Pair accessor not found: " + methodName, e); //$NON-NLS-1$
        }
    }

    private InfobaseReference resolveBoundReference(StandaloneServerInfobase standaloneInfobase,
            InfobaseReference fallback) {
        if (standaloneInfobase == null) {
            return fallback;
        }
        try {
            IInfobaseManager manager = gateway.getInfobaseManager();
            if (standaloneInfobase.getInfobaseId() != null) {
                return manager.findInfobaseByUuid(standaloneInfobase.getInfobaseId()).orElse(fallback);
            }
            if (standaloneInfobase.getName() != null) {
                return manager.findInfobaseByName(standaloneInfobase.getName()).orElse(fallback);
            }
        } catch (RuntimeException e) {
            String detail = e.getMessage() != null && !e.getMessage().isBlank()
                    ? e.getMessage() : e.getClass().getSimpleName();
            LOG.warn("Failed to resolve bound standalone infobase reference: %s", detail); //$NON-NLS-1$
        }
        return fallback;
    }

    private static String sanitizeLogin(String login) {
        return login == null || login.isBlank() ? null : login;
    }
}
