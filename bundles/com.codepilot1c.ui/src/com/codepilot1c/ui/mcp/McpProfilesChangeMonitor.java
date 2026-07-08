/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.ui.mcp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.mcp.host.McpHostConfig;
import com.codepilot1c.core.mcp.host.McpHostConfigStore;
import com.codepilot1c.core.mcp.host.McpHostManager;
import com.codepilot1c.core.mcp.host.SharedProfileStore;

/**
 * Watches the machine-shared MCP profiles file ({@link SharedProfileStore}) and,
 * when it changes on disk in a way that drifts from the currently running servers,
 * offers to reload + restart the MCP host (server-only, no EDT restart).
 *
 * <p>This closes the gap that the running {@link McpHostManager} captures its
 * profile snapshot at start and never re-reads the file: a direct edit of the JSON,
 * or an edit made in another EDT instance (the file is shared), is otherwise only
 * picked up on the next restart. The preference page already restarts on OK; this
 * monitor covers the out-of-band edits the page can't see.</p>
 *
 * <p>Self-writes (this instance's own preference-page save, which restarts) don't
 * nag: the prompt only fires when {@link McpHostManager#signatureOf} of a fresh
 * {@code load()} differs from {@link McpHostManager#getLastStartedSignature()},
 * and a self-save keeps those two in sync.</p>
 */
public final class McpProfilesChangeMonitor {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(McpProfilesChangeMonitor.class);

    private static final long POLL_MS = 3_000L;
    private static final long DEBOUNCE_MS = 700L;

    private final AtomicBoolean promptOpen = new AtomicBoolean(false);

    private volatile boolean running;
    private Thread thread;

    private long lastModified = -1L;
    private long lastSize = -1L;

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        thread = new Thread(this::loop, "mcp-profiles-watch"); //$NON-NLS-1$
        thread.setDaemon(true);
        thread.start();
    }

    public synchronized void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }

    private void loop() {
        // Baseline to the current on-disk state so we don't prompt for what is
        // already loaded at startup.
        readFileState();
        while (running) {
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                return;
            }
            if (!running) {
                return;
            }
            if (changedSinceBaseline()) {
                // Let an in-flight atomic write (temp file + move) settle before reading.
                try {
                    Thread.sleep(DEBOUNCE_MS);
                } catch (InterruptedException e) {
                    return;
                }
                readFileState();
                handleChange();
            }
        }
    }

    /** {@code true} (and updates the baseline) when the file's mtime/size moved. */
    private boolean changedSinceBaseline() {
        long m = -1L;
        long s = -1L;
        try {
            Path f = SharedProfileStore.defaultPath();
            if (Files.isRegularFile(f)) {
                m = Files.getLastModifiedTime(f).toMillis();
                s = Files.size(f);
            }
        } catch (IOException | RuntimeException e) {
            return false;
        }
        boolean changed = m != lastModified || s != lastSize;
        lastModified = m;
        lastSize = s;
        return changed;
    }

    private void readFileState() {
        try {
            Path f = SharedProfileStore.defaultPath();
            if (Files.isRegularFile(f)) {
                lastModified = Files.getLastModifiedTime(f).toMillis();
                lastSize = Files.size(f);
            } else {
                lastModified = -1L;
                lastSize = -1L;
            }
        } catch (IOException | RuntimeException e) {
            // leave the previous baseline
        }
    }

    private void handleChange() {
        final McpHostConfig disk;
        try {
            disk = McpHostConfigStore.getInstance().load();
        } catch (RuntimeException e) {
            LOG.error("Failed to load MCP config after profiles file change", e); //$NON-NLS-1$
            return;
        }
        if (!disk.isEnabled()) {
            return; // host disabled — nothing live to reconcile
        }
        String diskSig = McpHostManager.signatureOf(disk);
        String liveSig = McpHostManager.getInstance().getLastStartedSignature();
        if (diskSig.equals(liveSig)) {
            return; // self-write, or change with no effect on the served surface
        }
        if (!promptOpen.compareAndSet(false, true)) {
            return; // a prompt is already on screen
        }
        Display display = uiDisplay();
        if (display == null || display.isDisposed()) {
            promptOpen.set(false);
            return;
        }
        display.asyncExec(() -> {
            try {
                Shell shell = activeShell(display);
                boolean reload = MessageDialog.openQuestion(
                        shell,
                        "Профили MCP изменились", //$NON-NLS-1$
                        "Файл профилей MCP изменился на диске (правка вручную или из другого экземпляра EDT)." //$NON-NLS-1$
                                + "\n\nПеречитать настройки и перезапустить MCP-сервер? " //$NON-NLS-1$
                                + "Перезапустится только встроенный MCP-сервер (без перезапуска EDT); " //$NON-NLS-1$
                                + "активные подключения к нему будут разорваны."); //$NON-NLS-1$
                if (reload) {
                    McpHostManager.getInstance().restart();
                }
                // On decline the baseline is already advanced, so the same edit
                // won't nag again — only a further change will re-prompt.
            } catch (RuntimeException e) {
                LOG.error("Failed to reload MCP profiles after disk change", e); //$NON-NLS-1$
            } finally {
                promptOpen.set(false);
            }
        });
    }

    private static Display uiDisplay() {
        try {
            if (PlatformUI.isWorkbenchRunning()) {
                return PlatformUI.getWorkbench().getDisplay();
            }
        } catch (IllegalStateException ignored) {
            // workbench gone
        }
        return Display.getDefault();
    }

    private static Shell activeShell(Display display) {
        Shell shell = display.getActiveShell();
        if (shell != null) {
            return shell;
        }
        try {
            if (PlatformUI.isWorkbenchRunning()) {
                IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
                if (window != null) {
                    return window.getShell();
                }
            }
        } catch (IllegalStateException ignored) {
            // workbench gone
        }
        return null;
    }
}
