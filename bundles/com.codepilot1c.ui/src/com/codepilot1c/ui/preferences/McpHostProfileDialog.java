/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.ui.preferences;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.IMessageProvider;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.Text;

import com.codepilot1c.core.mcp.host.McpHostConfig;
import com.codepilot1c.core.mcp.host.ProfileEndpoint;
import com.codepilot1c.core.tools.surface.ToolGroupTaxonomy;
import com.codepilot1c.ui.internal.Messages;

/**
 * Editor for a single {@link ProfileEndpoint} (multi-endpoint MCP profile):
 * name, port, enabled, bearer token, and a tool-set picker (announce-all vs a
 * per-group allowlist using the {@link ToolGroupTaxonomy} vocabulary, plus
 * advanced per-tool / name-filter overrides).
 */
public class McpHostProfileDialog extends TitleAreaDialog {

    private final ProfileEndpoint working;
    private final Set<String> otherNamesLower;

    private Text nameText;
    private Button enabledCheck;
    private Spinner portSpinner;
    private Text tokenText;
    private Button announceAllCheck;
    private final Map<String, Button> groupChecks = new LinkedHashMap<>();
    private Text enableToolsText;
    private Text disableToolsText;
    private Text disableGroupsText;
    private Text nameFilterText;

    public McpHostProfileDialog(Shell parentShell, ProfileEndpoint profile, Set<String> otherNames) {
        super(parentShell);
        this.working = profile.copy();
        this.otherNamesLower = otherNames.stream()
                .map(n -> n.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
    }

    /** The edited profile (only valid after OK). */
    public ProfileEndpoint getResult() {
        return working;
    }

    @Override
    protected void configureShell(Shell newShell) {
        super.configureShell(newShell);
        newShell.setText(Messages.McpHostProfileDialog_Title);
    }

    @Override
    protected boolean isResizable() {
        return true;
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        setTitle(Messages.McpHostProfileDialog_Title);
        setMessage(Messages.McpHostProfileDialog_Header);

        Composite area = (Composite) super.createDialogArea(parent);
        Composite container = new Composite(area, SWT.NONE);
        container.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        container.setLayout(new GridLayout(2, false));

        label(container, Messages.McpHostProfileDialog_Name);
        nameText = new Text(container, SWT.BORDER);
        nameText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        nameText.setText(nullToEmpty(working.getName()));
        nameText.addModifyListener(e -> validate());

        enabledCheck = new Button(container, SWT.CHECK);
        enabledCheck.setText(Messages.McpHostProfileDialog_Enabled);
        enabledCheck.setSelection(working.isEnabled());
        enabledCheck.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

        label(container, Messages.McpHostProfileDialog_Port);
        portSpinner = new Spinner(container, SWT.BORDER);
        portSpinner.setMinimum(1);
        portSpinner.setMaximum(65535);
        portSpinner.setSelection(working.getPort() > 0 ? working.getPort() : 8765);

        label(container, Messages.McpHostProfileDialog_Token);
        Composite tokenRow = new Composite(container, SWT.NONE);
        tokenRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout tokenLayout = new GridLayout(2, false);
        tokenLayout.marginWidth = 0;
        tokenLayout.marginHeight = 0;
        tokenRow.setLayout(tokenLayout);
        tokenText = new Text(tokenRow, SWT.BORDER);
        tokenText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        tokenText.setText(nullToEmpty(working.getBearerToken()));
        Button regen = new Button(tokenRow, SWT.PUSH);
        regen.setText(Messages.McpHostProfileDialog_Regenerate);
        regen.addListener(SWT.Selection, e -> tokenText.setText(McpHostConfig.generateToken()));

        new Label(container, SWT.SEPARATOR | SWT.HORIZONTAL)
                .setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

        announceAllCheck = new Button(container, SWT.CHECK);
        announceAllCheck.setText(Messages.McpHostProfileDialog_AnnounceAll);
        announceAllCheck.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
        boolean announceAll = working.getEnableGroups() == null || working.getEnableGroups().isBlank();
        announceAllCheck.setSelection(announceAll);
        announceAllCheck.addListener(SWT.Selection, e -> updateGroupEnablement());

        createGroupPicker(container);
        createAdvanced(container);

        updateGroupEnablement();
        validate();
        return area;
    }

    private void createGroupPicker(Composite parent) {
        Group group = new Group(parent, SWT.NONE);
        group.setText(Messages.McpHostProfileDialog_Groups);
        group.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, false, 2, 1));
        group.setLayout(new GridLayout(2, true));

        Set<String> selected = parseTokens(working.getEnableGroups());
        for (String token : ToolGroupTaxonomy.ANNOUNCEABLE_GROUPS) {
            Button check = new Button(group, SWT.CHECK);
            check.setText(token);
            // A bare base token (e.g. "files") in config selects both facets.
            String base = ToolGroupTaxonomy.baseGroup(token);
            check.setSelection(selected.contains(token) || selected.contains(base));
            groupChecks.put(token, check);
        }
    }

    private void createAdvanced(Composite parent) {
        Group group = new Group(parent, SWT.NONE);
        group.setText(Messages.McpHostProfileDialog_Advanced);
        group.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, false, 2, 1));
        group.setLayout(new GridLayout(2, false));

        label(group, Messages.McpHostProfileDialog_EnableTools);
        enableToolsText = advancedField(group, working.getEnableTools(), "git_inspect"); //$NON-NLS-1$

        label(group, Messages.McpHostProfileDialog_DisableTools);
        disableToolsText = advancedField(group, working.getDisableTools(), "connect_infobase"); //$NON-NLS-1$

        label(group, Messages.McpHostProfileDialog_DisableGroups);
        disableGroupsText = advancedField(group, working.getDisableGroups(), "metadata.write,qa"); //$NON-NLS-1$

        label(group, Messages.McpHostProfileDialog_NameFilter);
        nameFilterText = advancedField(group, working.getExposedToolsFilter(), "*, -edit_file"); //$NON-NLS-1$
    }

    private Text advancedField(Composite parent, String value, String hint) {
        Text text = new Text(parent, SWT.BORDER);
        text.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        text.setMessage(hint);
        text.setText(nullToEmpty(value));
        return text;
    }

    private void updateGroupEnablement() {
        boolean announceAll = announceAllCheck.getSelection();
        for (Button check : groupChecks.values()) {
            check.setEnabled(!announceAll);
        }
    }

    private void validate() {
        String name = nameText.getText().trim();
        if (name.isEmpty()) {
            setMessage(Messages.McpHostProfileDialog_ErrNameEmpty, IMessageProvider.ERROR);
            setOkEnabled(false);
            return;
        }
        if (otherNamesLower.contains(name.toLowerCase(Locale.ROOT))) {
            setMessage(Messages.McpHostProfileDialog_ErrNameDup, IMessageProvider.ERROR);
            setOkEnabled(false);
            return;
        }
        setMessage(Messages.McpHostProfileDialog_Header);
        setOkEnabled(true);
    }

    private void setOkEnabled(boolean enabled) {
        Button ok = getButton(IDialogConstants.OK_ID);
        if (ok != null) {
            ok.setEnabled(enabled);
        }
    }

    @Override
    protected void okPressed() {
        working.setName(nameText.getText().trim());
        working.setEnabled(enabledCheck.getSelection());
        working.setPort(portSpinner.getSelection());
        working.setBearerToken(tokenText.getText().trim());

        if (announceAllCheck.getSelection()) {
            working.setEnableGroups(""); //$NON-NLS-1$ everything (denylist mode)
        } else {
            List<String> chosen = new ArrayList<>();
            for (Map.Entry<String, Button> entry : groupChecks.entrySet()) {
                if (entry.getValue().getSelection()) {
                    chosen.add(entry.getKey());
                }
            }
            working.setEnableGroups(String.join(",", chosen)); //$NON-NLS-1$
        }
        working.setEnableTools(emptyToNull(enableToolsText.getText().trim()));
        working.setDisableTools(emptyToNull(disableToolsText.getText().trim()));
        working.setDisableGroups(emptyToNull(disableGroupsText.getText().trim()));
        String filter = nameFilterText.getText().trim();
        working.setExposedToolsFilter(filter.isEmpty() ? "*" : filter); //$NON-NLS-1$

        super.okPressed();
    }

    private static Set<String> parseTokens(String csv) {
        Set<String> out = new java.util.HashSet<>();
        if (csv == null) {
            return out;
        }
        for (String token : csv.split(",")) { //$NON-NLS-1$
            String trimmed = token.trim().toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    private static Label label(Composite parent, String text) {
        Label label = new Label(parent, SWT.NONE);
        label.setText(text);
        return label;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s; //$NON-NLS-1$
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }
}
