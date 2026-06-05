/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.settings;

import java.io.File;
import java.net.URL;

import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Platform;
import org.eclipse.equinox.security.storage.ISecurePreferences;
import org.eclipse.equinox.security.storage.SecurePreferencesFactory;
import org.eclipse.equinox.security.storage.StorageException;
import org.osgi.framework.FrameworkUtil;

import com.codepilot1c.core.internal.VibeCorePlugin;

/**
 * Utility class for secure storage of sensitive data like API keys.
 *
 * <p>Uses Eclipse Secure Storage to encrypt sensitive information.</p>
 *
 * <p>Two stores are exposed:</p>
 * <ul>
 * <li>the <b>default</b> store ({@link #storeSecurely}/{@link #retrieveSecurely}/
 * {@link #removeSecurely}) — Eclipse's per-OS-user shared secure storage, used for
 * machine-wide data such as the account login;</li>
 * <li>a <b>per-workspace</b> store ({@link #storeWorkspaceSecurely}/
 * {@link #retrieveWorkspaceSecurely}/{@link #removeWorkspaceSecurely}) — a separate
 * secure-storage file rooted in this bundle's workspace state location.</li>
 * </ul>
 *
 * <p>The per-workspace store exists because the shared default file is a single
 * file per OS user: when two EDT instances both flush it, the second writer trips
 * Equinox's <i>"secure storage modified by another program"</i> dialog. High-frequency
 * MCP-host writes (bearer token, OAuth state) therefore go to the per-workspace file
 * so parallel instances never contend on the same physical file. When the workspace
 * location cannot be resolved (e.g. headless tests) the per-workspace methods fall
 * back to the default store.</p>
 */
public final class SecureStorageUtil {

    private static final String SECURE_NODE_PATH = "/" + VibeCorePlugin.PLUGIN_ID; //$NON-NLS-1$
    private static final String WORKSPACE_STORE_FILE = "secure_storage"; //$NON-NLS-1$

    private static boolean workspaceRootResolved;
    private static ISecurePreferences workspaceRoot;

    private SecureStorageUtil() {
        // Utility class
    }

    /**
     * Opens (once) the per-workspace secure-storage file rooted in this bundle's
     * state location, or {@code null} when the location cannot be resolved.
     */
    private static synchronized ISecurePreferences workspaceRoot() {
        if (!workspaceRootResolved) {
            workspaceRootResolved = true;
            try {
                IPath state = Platform.getStateLocation(FrameworkUtil.getBundle(SecureStorageUtil.class));
                if (state != null) {
                    File file = state.append(WORKSPACE_STORE_FILE).toFile();
                    URL location = file.toURI().toURL();
                    workspaceRoot = SecurePreferencesFactory.open(location, null);
                }
            } catch (Exception | LinkageError e) {
                VibeCorePlugin.logError("Failed to open per-workspace secure storage; using default store", e); //$NON-NLS-1$
            }
        }
        return workspaceRoot;
    }

    /**
     * Returns the per-workspace node, falling back to the default store's node
     * when the per-workspace file could not be opened.
     */
    private static ISecurePreferences workspaceNode() {
        ISecurePreferences root = workspaceRoot();
        if (root == null) {
            root = SecurePreferencesFactory.getDefault();
        }
        return root != null ? root.node(SECURE_NODE_PATH) : null;
    }

    /**
     * Stores a value securely.
     *
     * @param key   the key
     * @param value the value to store
     * @return true if stored successfully
     */
    public static boolean storeSecurely(String key, String value) {
        try {
            ISecurePreferences root = SecurePreferencesFactory.getDefault();
            ISecurePreferences node = root.node(SECURE_NODE_PATH);
            node.put(key, value, true); // true = encrypt
            node.flush();
            return true;
        } catch (Exception e) {
            VibeCorePlugin.logError("Failed to store secure value for key: " + key, e); //$NON-NLS-1$
            return false;
        }
    }

    /**
     * Retrieves a value from secure storage.
     *
     * @param key          the key
     * @param defaultValue the default value if not found
     * @return the stored value or default
     */
    public static String retrieveSecurely(String key, String defaultValue) {
        try {
            ISecurePreferences root = SecurePreferencesFactory.getDefault();
            ISecurePreferences node = root.node(SECURE_NODE_PATH);
            return node.get(key, defaultValue);
        } catch (StorageException e) {
            // Log error: "Failed to retrieve secure value for key: {}", key, e //$NON-NLS-1$
            return defaultValue;
        }
    }

    /**
     * Removes a value from secure storage.
     *
     * @param key the key to remove
     */
    public static void removeSecurely(String key) {
        try {
            ISecurePreferences root = SecurePreferencesFactory.getDefault();
            ISecurePreferences node = root.node(SECURE_NODE_PATH);
            node.remove(key);
            node.flush();
        } catch (Exception e) {
            // Log error: "Failed to remove secure value for key: {}", key, e //$NON-NLS-1$
        }
    }

    /**
     * Stores a value securely in the per-workspace store (isolated per Eclipse
     * instance so parallel EDT instances do not contend on the shared file).
     *
     * @param key   the key
     * @param value the value to store
     * @return true if stored successfully
     */
    public static boolean storeWorkspaceSecurely(String key, String value) {
        try {
            ISecurePreferences node = workspaceNode();
            if (node == null) {
                return false;
            }
            node.put(key, value, true); // true = encrypt
            node.flush();
            return true;
        } catch (Exception e) {
            VibeCorePlugin.logError("Failed to store per-workspace secure value for key: " + key, e); //$NON-NLS-1$
            return false;
        }
    }

    /**
     * Retrieves a value from the per-workspace store.
     *
     * @param key          the key
     * @param defaultValue the default value if not found
     * @return the stored value or default
     */
    public static String retrieveWorkspaceSecurely(String key, String defaultValue) {
        try {
            ISecurePreferences node = workspaceNode();
            if (node == null) {
                return defaultValue;
            }
            return node.get(key, defaultValue);
        } catch (StorageException e) {
            return defaultValue;
        }
    }

    /**
     * Removes a value from the per-workspace store.
     *
     * @param key the key to remove
     */
    public static void removeWorkspaceSecurely(String key) {
        try {
            ISecurePreferences node = workspaceNode();
            if (node == null) {
                return;
            }
            node.remove(key);
            node.flush();
        } catch (Exception e) {
            VibeCorePlugin.logError("Failed to remove per-workspace secure value for key: " + key, e); //$NON-NLS-1$
        }
    }

    /**
     * Checks if secure storage is available.
     *
     * @return true if available
     */
    public static boolean isAvailable() {
        try {
            ISecurePreferences root = SecurePreferencesFactory.getDefault();
            return root != null;
        } catch (Exception e) {
            // Log warn: "Secure storage not available", e //$NON-NLS-1$
            return false;
        }
    }

    /**
     * Stores an API key securely.
     *
     * @param providerId the provider ID
     * @param apiKey     the API key
     * @return true if stored successfully
     */
    public static boolean storeApiKey(String providerId, String apiKey) {
        return storeSecurely(providerId + ".apiKey", apiKey); //$NON-NLS-1$
    }

    /**
     * Retrieves an API key from secure storage.
     *
     * @param providerId the provider ID
     * @return the API key or empty string if not found
     */
    public static String retrieveApiKey(String providerId) {
        return retrieveSecurely(providerId + ".apiKey", ""); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
