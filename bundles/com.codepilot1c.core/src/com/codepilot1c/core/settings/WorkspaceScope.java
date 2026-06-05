package com.codepilot1c.core.settings;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Platform;
import org.osgi.framework.FrameworkUtil;

/**
 * Derives a short, stable identifier for the current Eclipse instance
 * (workspace).
 *
 * <p>Eclipse Secure Storage is shared per OS user across all workspaces and
 * EDT instances, while the MCP host port and its preferences live in the
 * per-workspace {@code InstanceScope}. To keep parallel instances isolated,
 * MCP-host secure-storage keys are suffixed with this per-workspace id so two
 * instances on different ports do not clobber each other's bearer token or
 * OAuth state.</p>
 *
 * <p>The id is derived from the bundle state location (which is rooted at the
 * workspace {@code .metadata} directory) and is therefore stable for a given
 * workspace and distinct between workspaces. When the platform location cannot
 * be resolved (e.g. headless tests), {@link #instanceId()} returns an empty
 * string so callers fall back to the legacy, unscoped key.</p>
 */
public final class WorkspaceScope {

    private WorkspaceScope() {
        // Utility class
    }

    /**
     * Returns a short, filesystem-agnostic, stable identifier for the current
     * workspace, or an empty string when it cannot be determined.
     *
     * @return a 16-char hex id, or {@code ""} when unavailable
     */
    public static String instanceId() {
        try {
            IPath stateLocation = Platform.getStateLocation(
                    FrameworkUtil.getBundle(WorkspaceScope.class));
            if (stateLocation == null) {
                return ""; //$NON-NLS-1$
            }
            return shortHash(stateLocation.toString());
        } catch (Exception | LinkageError e) {
            // Platform / instance location unavailable (e.g. headless tests).
            return ""; //$NON-NLS-1$
        }
    }

    /**
     * Suffixes a secure-storage key with the current workspace id so the value
     * is isolated per instance. Returns {@code baseKey} unchanged when the
     * workspace id is unavailable, preserving the legacy unscoped key.
     *
     * @param baseKey the legacy, unscoped key
     * @return the workspace-scoped key, or {@code baseKey} when unscoped
     */
    public static String scopedKey(String baseKey) {
        String id = instanceId();
        return id.isEmpty() ? baseKey : baseKey + "." + id; //$NON-NLS-1$
    }

    private static String shortHash(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256"); //$NON-NLS-1$
            byte[] digest = md.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", Byte.valueOf(digest[i]))); //$NON-NLS-1$
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
