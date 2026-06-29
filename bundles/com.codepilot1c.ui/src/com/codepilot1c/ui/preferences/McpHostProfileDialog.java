/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.ui.preferences;

import java.util.ArrayList;
import java.util.HashSet;
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
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeColumn;
import org.eclipse.swt.widgets.TreeItem;

import com.codepilot1c.core.mcp.host.DefaultMcpToolExposurePolicy;
import com.codepilot1c.core.mcp.host.McpHostConfig;
import com.codepilot1c.core.mcp.host.ProfileEndpoint;
import com.codepilot1c.core.tools.ITool;
import com.codepilot1c.core.tools.ToolRegistry;
import com.codepilot1c.core.tools.surface.ToolGroupTaxonomy;
import com.codepilot1c.core.tools.surface.ToolSelectionModel;
import com.codepilot1c.ui.internal.Messages;

/**
 * Editor for a single {@link ProfileEndpoint} (machine-shared half: name, enabled,
 * bearer token, tool set). The tool set is picked per-tool in a checkbox tree
 * (groups → individual tools, with descriptions); the per-instance port is edited
 * in the endpoints table, not here.
 */
public class McpHostProfileDialog extends TitleAreaDialog {

    private final ProfileEndpoint working;
    private final Set<String> otherNamesLower;

    private Text nameText;
    private Button enabledCheck;
    private Text tokenText;
    private Button announceAllCheck;
    private Tree toolTree;
    private TreeColumn descColumn;
    private Label countLabel;
    private boolean adjustingColumns;

    private static final int NAME_COL_WIDTH = 260;

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

        Label sharedHint = new Label(container, SWT.WRAP);
        sharedHint.setText(Messages.McpHostProfileDialog_SharedHint);
        GridData sharedGd = new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1);
        sharedGd.widthHint = 540;
        sharedHint.setLayoutData(sharedGd);

        new Label(container, SWT.SEPARATOR | SWT.HORIZONTAL)
                .setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

        announceAllCheck = new Button(container, SWT.CHECK);
        announceAllCheck.setText(Messages.McpHostProfileDialog_AnnounceAll);
        announceAllCheck.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
        announceAllCheck.setSelection(working.announcesEverything());
        announceAllCheck.addListener(SWT.Selection, e -> {
            updateTreeEnablement();
            updateCount();
            validate();
        });

        createToolTree(container);

        updateTreeEnablement();
        updateCount();
        validate();
        return area;
    }

    private void createToolTree(Composite parent) {
        Group group = new Group(parent, SWT.NONE);
        group.setText(Messages.McpHostProfileDialog_Tools);
        group.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true, 2, 1));
        group.setLayout(new GridLayout(1, false));

        Label hint = new Label(group, SWT.WRAP);
        hint.setText(Messages.McpHostProfileDialog_ToolsHint);
        GridData hintGd = new GridData(SWT.FILL, SWT.TOP, true, false);
        hintGd.widthHint = 540;
        hint.setLayoutData(hintGd);

        toolTree = new Tree(group, SWT.BORDER | SWT.CHECK | SWT.FULL_SELECTION | SWT.V_SCROLL);
        GridData treeGd = new GridData(SWT.FILL, SWT.FILL, true, true);
        treeGd.heightHint = 320;
        treeGd.widthHint = 580;
        toolTree.setLayoutData(treeGd);
        toolTree.setHeaderVisible(true);
        toolTree.setLinesVisible(true);
        TreeColumn nameCol = new TreeColumn(toolTree, SWT.NONE);
        nameCol.setText(Messages.McpHostProfileDialog_ColTool);
        nameCol.setWidth(NAME_COL_WIDTH);
        descColumn = new TreeColumn(toolTree, SWT.NONE);
        descColumn.setText(Messages.McpHostProfileDialog_ColDescription);
        descColumn.setWidth(300);

        populateTree();

        toolTree.addListener(SWT.Selection, e -> {
            if (e.detail == SWT.CHECK && e.item instanceof TreeItem item) {
                onCheck(item);
            }
        });
        toolTree.addListener(SWT.Resize, e -> fitDescriptionColumn());

        countLabel = new Label(group, SWT.NONE);
        countLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    }

    /** Build the group → tools rows and seed check state from the profile's current surface. */
    private void populateTree() {
        Map<String, List<ITool>> byGroup = toolsByGroup();
        DefaultMcpToolExposurePolicy policy = new DefaultMcpToolExposurePolicy(
                McpHostConfig.defaults(), working.getExposedToolsFilter(), working.toGroupVisibility());
        boolean announceAll = working.announcesEverything();
        for (Map.Entry<String, List<ITool>> entry : byGroup.entrySet()) {
            TreeItem groupItem = new TreeItem(toolTree, SWT.NONE);
            groupItem.setText(0, entry.getKey());
            groupItem.setData(entry.getKey());
            for (ITool tool : entry.getValue()) {
                TreeItem toolItem = new TreeItem(groupItem, SWT.NONE);
                toolItem.setText(0, tool.getName());
                toolItem.setText(1, normalize(tool.getDescription()));
                toolItem.setChecked(announceAll || policy.isExposed(tool.getName()));
            }
            refreshParentState(groupItem);
            groupItem.setExpanded(true);
        }
    }

    /** Tools grouped by announce-surface group, in {@link ToolGroupTaxonomy#ANNOUNCEABLE_GROUPS} order. */
    private Map<String, List<ITool>> toolsByGroup() {
        Map<String, List<ITool>> byGroup = new LinkedHashMap<>();
        for (String token : ToolGroupTaxonomy.ANNOUNCEABLE_GROUPS) {
            byGroup.put(token, new ArrayList<>());
        }
        try {
            List<ITool> all = new ArrayList<>(ToolRegistry.getInstance().getAllTools());
            all.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            for (ITool tool : all) {
                String groupToken = ToolGroupTaxonomy.groupOf(tool);
                List<ITool> bucket = byGroup.get(groupToken);
                if (bucket != null) {
                    bucket.add(tool);
                }
            }
        } catch (RuntimeException e) {
            // Tool registry unavailable (very early startup) — leave groups empty.
        }
        byGroup.values().removeIf(List::isEmpty);
        return byGroup;
    }

    private void onCheck(TreeItem item) {
        if (item.getParentItem() == null) {
            // Group toggled — cascade to its tools.
            boolean checked = item.getChecked();
            item.setGrayed(false);
            for (TreeItem child : item.getItems()) {
                child.setChecked(checked);
            }
        } else {
            refreshParentState(item.getParentItem());
        }
        updateCount();
        validate();
    }

    /** Set a group's tri-state from its tools (checked = all, grayed = some). */
    private void refreshParentState(TreeItem groupItem) {
        int checked = 0;
        TreeItem[] children = groupItem.getItems();
        for (TreeItem child : children) {
            if (child.getChecked()) {
                checked++;
            }
        }
        groupItem.setChecked(checked > 0);
        groupItem.setGrayed(checked > 0 && checked < children.length);
    }

    private void updateTreeEnablement() {
        if (toolTree != null && !toolTree.isDisposed()) {
            toolTree.setEnabled(!announceAllCheck.getSelection());
        }
    }

    private void updateCount() {
        if (countLabel == null || countLabel.isDisposed()) {
            return;
        }
        int count;
        if (announceAllCheck.getSelection()) {
            count = totalTools();
        } else {
            count = selectedToolNames().size();
        }
        countLabel.setText(org.eclipse.osgi.util.NLS.bind(Messages.McpHostProfileDialog_ToolCount, Integer.valueOf(count)));
    }

    private int totalTools() {
        int total = 0;
        for (TreeItem groupItem : toolTree.getItems()) {
            total += groupItem.getItemCount();
        }
        return total;
    }

    private void fitDescriptionColumn() {
        if (adjustingColumns || toolTree == null || toolTree.isDisposed()) {
            return;
        }
        Rectangle clientArea = toolTree.getClientArea();
        int target = clientArea.width - toolTree.getColumn(0).getWidth() - 4;
        if (target > 120 && Math.abs(target - descColumn.getWidth()) > 1) {
            adjustingColumns = true;
            try {
                descColumn.setWidth(target);
            } finally {
                adjustingColumns = false;
            }
        }
    }

    /** The tool names currently checked (tool leaves only). */
    private Set<String> selectedToolNames() {
        Set<String> selected = new HashSet<>();
        for (TreeItem groupItem : toolTree.getItems()) {
            for (TreeItem child : groupItem.getItems()) {
                if (child.getChecked()) {
                    selected.add(child.getText(0));
                }
            }
        }
        return selected;
    }

    /** Group token → its full tool-name membership, mirroring the tree structure. */
    private Map<String, List<String>> currentToolsByGroup() {
        Map<String, List<String>> byGroup = new LinkedHashMap<>();
        for (TreeItem groupItem : toolTree.getItems()) {
            List<String> tools = new ArrayList<>();
            for (TreeItem child : groupItem.getItems()) {
                tools.add(child.getText(0));
            }
            byGroup.put((String) groupItem.getData(), tools);
        }
        return byGroup;
    }

    private static String normalize(String s) {
        if (s == null) {
            return ""; //$NON-NLS-1$
        }
        return s.replaceAll("[ \\t]+", " ").trim(); //$NON-NLS-1$ //$NON-NLS-2$
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
        if (!announceAllCheck.getSelection() && selectedToolNames().isEmpty()) {
            setMessage(Messages.McpHostProfileDialog_ErrNoTools, IMessageProvider.ERROR);
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
        working.setBearerToken(tokenText.getText().trim());
        readToolSetInto(working);
        super.okPressed();
    }

    /** Translate the announce-all toggle / checkbox tree into the profile's facets. */
    private void readToolSetInto(ProfileEndpoint target) {
        // The per-tool tree fully captures the surface, so the legacy per-tool enable /
        // disable-groups / name-filter facets are no longer needed from this editor.
        target.setEnableTools(null);
        target.setDisableGroups(null);
        target.setExposedToolsFilter("*"); //$NON-NLS-1$
        if (announceAllCheck.getSelection()) {
            target.setEnableGroups(""); //$NON-NLS-1$ empty allowlist = announce everything (denylist mode)
            target.setDisableTools(null);
            return;
        }
        ToolSelectionModel selection = ToolSelectionModel.fromSelection(currentToolsByGroup(), selectedToolNames());
        target.setEnableGroups(selection.getEnableGroups());
        target.setDisableTools(emptyToNull(selection.getDisableTools()));
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
