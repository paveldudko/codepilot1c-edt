/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.ui.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.handlers.HandlerUtil;

import com.codepilot1c.core.mcp.host.McpHostManager;

/**
 * Restarts the inbound MCP server(s) from the saved settings — re-reads the shared
 * profiles file and rebinds every enabled endpoint, without restarting EDT. Lets
 * an out-of-band profiles edit (direct JSON / another EDT instance) be applied to
 * the live tool surface without bouncing the whole IDE.
 */
public class RestartMcpHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        Shell shell = HandlerUtil.getActiveShell(event);
        boolean confirmed = MessageDialog.openConfirm(
                shell,
                "Перезапустить MCP-сервер", //$NON-NLS-1$
                "Перезапустить встроенный MCP-сервер из сохранённых настроек?" //$NON-NLS-1$
                        + "\n\nПрофили будут перечитаны, EDT не перезапускается; " //$NON-NLS-1$
                        + "активные подключения к MCP-серверу будут разорваны."); //$NON-NLS-1$
        if (!confirmed) {
            return null;
        }
        McpHostManager.getInstance().restart();
        boolean running = McpHostManager.getInstance().isRunning();
        MessageDialog.openInformation(
                shell,
                "MCP-сервер", //$NON-NLS-1$
                running
                        ? "MCP-сервер перезапущен." //$NON-NLS-1$
                        : "MCP-сервер остановлен (хост выключен в настройках)."); //$NON-NLS-1$
        return null;
    }
}
