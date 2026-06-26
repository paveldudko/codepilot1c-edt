package com.codepilot1c.ui.preferences;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import com.codepilot1c.core.mcp.host.DefaultMcpToolExposurePolicy;
import com.codepilot1c.core.mcp.host.McpHostConfig;
import com.codepilot1c.core.mcp.host.McpHostConfigStore;
import com.codepilot1c.core.mcp.host.McpHostManager;
import com.codepilot1c.core.mcp.host.ProfileEndpoint;
import com.codepilot1c.core.tools.ITool;
import com.codepilot1c.core.tools.ToolRegistry;
import com.codepilot1c.ui.internal.Messages;

/**
 * Preference page for the inbound MCP host. Host-level settings (enabled, HTTP,
 * bind address, auth mode, mutation policy) are shared; each row of the endpoints
 * table is a {@link ProfileEndpoint} — its own port + bearer token + tool set —
 * brought up concurrently by one EDT.
 */
public class McpHostPreferencePage extends PreferencePage implements IWorkbenchPreferencePage {

    private Button enabledCheckbox;
    private Button httpEnabledCheckbox;
    private Text bindAddressText;
    private Combo authModeCombo;
    private Combo mutationPolicyCombo;
    private Label warningLabel;

    private Table table;
    private Button editButton;
    private Button duplicateButton;
    private Button deleteButton;
    private Button regenButton;
    private Button copyButton;
    private Button checkStatusButton;
    private Text installHintsText;

    private McpHostConfig config;
    private final List<ProfileEndpoint> profiles = new ArrayList<>();

    @Override
    public void init(IWorkbench workbench) {
        config = McpHostConfigStore.getInstance().load();
        replaceProfiles(config.getProfiles());
    }

    private void replaceProfiles(List<ProfileEndpoint> source) {
        profiles.clear();
        if (source != null) {
            for (ProfileEndpoint p : source) {
                profiles.add(p.copy());
            }
        }
    }

    @Override
    protected Control createContents(Composite parent) {
        Composite container = new Composite(parent, SWT.NONE);
        container.setLayout(new GridLayout(2, false));

        Label description = new Label(container, SWT.WRAP);
        description.setText(Messages.McpHostPreferencePage_Description);
        GridData descriptionGd = new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1);
        descriptionGd.widthHint = 560;
        description.setLayoutData(descriptionGd);

        enabledCheckbox = createCheckbox(container, Messages.McpHostPreferencePage_Enabled, config.isEnabled(), 2);
        httpEnabledCheckbox = createCheckbox(container, Messages.McpHostPreferencePage_HttpEnabled, config.isHttpEnabled(), 2);

        createLabel(container, Messages.McpHostPreferencePage_BindAddress);
        bindAddressText = new Text(container, SWT.BORDER);
        bindAddressText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        bindAddressText.setText(config.getBindAddress() != null ? config.getBindAddress() : "127.0.0.1"); //$NON-NLS-1$
        bindAddressText.addModifyListener(e -> refreshWarning());

        createLabel(container, Messages.McpHostPreferencePage_AuthMode);
        authModeCombo = new Combo(container, SWT.DROP_DOWN | SWT.READ_ONLY);
        authModeCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        authModeCombo.setItems(new String[] {
            Messages.McpHostPreferencePage_AuthModeOauthBearer,
            Messages.McpHostPreferencePage_AuthModeOauth,
            Messages.McpHostPreferencePage_AuthModeBearer,
            Messages.McpHostPreferencePage_AuthModeNone
        });
        authModeCombo.select(Math.max(0, config.getAuthMode().ordinal()));
        authModeCombo.addListener(SWT.Selection, e -> updateInstallHints());

        createLabel(container, Messages.McpHostPreferencePage_MutationPolicy);
        mutationPolicyCombo = new Combo(container, SWT.DROP_DOWN | SWT.READ_ONLY);
        mutationPolicyCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        mutationPolicyCombo.setItems(new String[] {
            Messages.McpHostPreferencePage_MutationAsk,
            Messages.McpHostPreferencePage_MutationDeny,
            Messages.McpHostPreferencePage_MutationAllow
        });
        mutationPolicyCombo.select(Math.max(0, config.getMutationPolicy().ordinal()));

        warningLabel = new Label(container, SWT.WRAP);
        warningLabel.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1));
        warningLabel.setForeground(container.getDisplay().getSystemColor(SWT.COLOR_DARK_YELLOW));

        createEndpointsSection(container);
        createInstallHints(container);

        refreshWarning();
        refreshTable();
        updateButtons();
        updateInstallHints();
        return container;
    }

    private void createEndpointsSection(Composite parent) {
        Label header = new Label(parent, SWT.NONE);
        header.setText(Messages.McpHostPreferencePage_Endpoints);
        GridData headerGd = new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1);
        headerGd.verticalIndent = 10;
        header.setLayoutData(headerGd);

        Label hint = new Label(parent, SWT.WRAP);
        hint.setText(Messages.McpHostPreferencePage_EndpointsHint);
        hint.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1));

        table = new Table(parent, SWT.BORDER | SWT.CHECK | SWT.FULL_SELECTION);
        GridData tableGd = new GridData(SWT.FILL, SWT.FILL, true, true);
        tableGd.heightHint = 160;
        tableGd.widthHint = 560;
        table.setLayoutData(tableGd);
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        addColumn(Messages.McpHostPreferencePage_ColName, 110);
        addColumn(Messages.McpHostPreferencePage_ColPort, 60);
        addColumn(Messages.McpHostPreferencePage_ColTools, 230);
        addColumn(Messages.McpHostPreferencePage_ColToken, 90);
        addColumn(Messages.McpHostPreferencePage_ColStatus, 90);
        table.addListener(SWT.Selection, e -> {
            if (e.detail == SWT.CHECK) {
                syncEnabledFromChecks();
            }
            updateButtons();
            updateInstallHints();
        });
        table.addListener(SWT.MouseDoubleClick, e -> editSelected());

        Composite buttons = new Composite(parent, SWT.NONE);
        buttons.setLayoutData(new GridData(SWT.FILL, SWT.TOP, false, false));
        buttons.setLayout(new GridLayout(1, true));
        Button addButton = rowButton(buttons, Messages.McpHostPreferencePage_Add, e -> addProfile());
        editButton = rowButton(buttons, Messages.McpHostPreferencePage_Edit, e -> editSelected());
        duplicateButton = rowButton(buttons, Messages.McpHostPreferencePage_Duplicate, e -> duplicateSelected());
        deleteButton = rowButton(buttons, Messages.McpHostPreferencePage_Delete, e -> deleteSelected());
        regenButton = rowButton(buttons, Messages.McpHostPreferencePage_RegenerateToken, e -> regenerateSelected());
        copyButton = rowButton(buttons, Messages.McpHostPreferencePage_CopyConnection, e -> copyConnection());
        checkStatusButton = rowButton(buttons, Messages.McpHostPreferencePage_CheckStatus, e -> checkStatus());
        addButton.setEnabled(true);
    }

    private void createInstallHints(Composite parent) {
        Label hintsLabel = new Label(parent, SWT.NONE);
        hintsLabel.setText(Messages.McpHostPreferencePage_InstallHints);
        GridData hintsLabelGd = new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1);
        hintsLabelGd.verticalIndent = 8;
        hintsLabel.setLayoutData(hintsLabelGd);

        installHintsText = new Text(parent, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL | SWT.READ_ONLY);
        GridData hintsGd = new GridData(SWT.FILL, SWT.FILL, true, false, 2, 1);
        hintsGd.widthHint = 620;
        hintsGd.heightHint = 200;
        installHintsText.setLayoutData(hintsGd);
    }

    private void addColumn(String text, int width) {
        TableColumn column = new TableColumn(table, SWT.NONE);
        column.setText(text);
        column.setWidth(width);
    }

    private Button rowButton(Composite parent, String text, Listener onClick) {
        Button button = new Button(parent, SWT.PUSH);
        button.setText(text);
        button.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        button.addListener(SWT.Selection, onClick);
        return button;
    }

    // --- table <-> model -----------------------------------------------------

    private void refreshTable() {
        int selected = table.getSelectionIndex();
        table.removeAll();
        for (ProfileEndpoint p : profiles) {
            TableItem item = new TableItem(table, SWT.NONE);
            item.setChecked(p.isEnabled());
            int announced = countAnnounced(p);
            String tools = p.toolsSummary();
            if (announced >= 0) {
                tools = tools + " (" + announced + ")"; //$NON-NLS-1$ //$NON-NLS-2$
            }
            item.setText(new String[] {
                nullToEmpty(p.getName()),
                Integer.toString(p.getPort()),
                tools,
                maskToken(p.getBearerToken()),
                p.isEnabled() ? Messages.McpHostPreferencePage_StatusUnknown
                              : Messages.McpHostPreferencePage_StatusDisabled
            });
        }
        if (selected >= 0 && selected < profiles.size()) {
            table.setSelection(selected);
        }
    }

    private void syncEnabledFromChecks() {
        TableItem[] items = table.getItems();
        for (int i = 0; i < items.length && i < profiles.size(); i++) {
            profiles.get(i).setEnabled(items[i].getChecked());
        }
    }

    private ProfileEndpoint selectedProfile() {
        int index = table.getSelectionIndex();
        return (index >= 0 && index < profiles.size()) ? profiles.get(index) : null;
    }

    private void updateButtons() {
        boolean has = selectedProfile() != null;
        editButton.setEnabled(has);
        duplicateButton.setEnabled(has);
        deleteButton.setEnabled(has);
        regenButton.setEnabled(has);
        copyButton.setEnabled(has);
        checkStatusButton.setEnabled(has);
    }

    private void addProfile() {
        String name = ProfileEndpoint.suggestUniqueName("endpoint", existingNames(null)); //$NON-NLS-1$
        ProfileEndpoint fresh = new ProfileEndpoint(name, suggestFreePort(), McpHostConfig.generateToken(), false);
        fresh.setExposedToolsFilter("*"); //$NON-NLS-1$
        McpHostProfileDialog dialog = new McpHostProfileDialog(getShell(), fresh, existingNames(null));
        if (dialog.open() == org.eclipse.jface.window.Window.OK) {
            profiles.add(dialog.getResult());
            refreshTable();
            table.setSelection(profiles.size() - 1);
            updateButtons();
            updateInstallHints();
        }
    }

    private void editSelected() {
        ProfileEndpoint current = selectedProfile();
        if (current == null) {
            return;
        }
        McpHostProfileDialog dialog = new McpHostProfileDialog(getShell(), current, existingNames(current.getName()));
        if (dialog.open() == org.eclipse.jface.window.Window.OK) {
            int index = table.getSelectionIndex();
            profiles.set(index, dialog.getResult());
            refreshTable();
            table.setSelection(index);
            updateButtons();
            updateInstallHints();
        }
    }

    private void duplicateSelected() {
        ProfileEndpoint current = selectedProfile();
        if (current == null) {
            return;
        }
        ProfileEndpoint copy = current.copy();
        copy.setName(ProfileEndpoint.suggestUniqueName(current.getName(), existingNames(null)));
        copy.setPort(suggestFreePort());
        copy.setBearerToken(McpHostConfig.generateToken());
        copy.setEnabled(false);
        profiles.add(copy);
        refreshTable();
        table.setSelection(profiles.size() - 1);
        updateButtons();
        updateInstallHints();
    }

    private void deleteSelected() {
        ProfileEndpoint current = selectedProfile();
        if (current == null) {
            return;
        }
        boolean ok = MessageDialog.openConfirm(getShell(),
                Messages.McpHostPreferencePage_DeleteTitle,
                NLS(Messages.McpHostPreferencePage_DeleteMessage, current.getName()));
        if (ok) {
            profiles.remove(table.getSelectionIndex());
            refreshTable();
            updateButtons();
            updateInstallHints();
        }
    }

    private void regenerateSelected() {
        ProfileEndpoint current = selectedProfile();
        if (current == null) {
            return;
        }
        current.setBearerToken(McpHostConfig.generateToken());
        refreshTable();
        updateInstallHints();
    }

    private void copyConnection() {
        ProfileEndpoint current = selectedProfile();
        if (current == null) {
            return;
        }
        Clipboard clipboard = new Clipboard(getShell().getDisplay());
        try {
            clipboard.setContents(
                new Object[] { buildHints(current) },
                new Transfer[] { TextTransfer.getInstance() });
        } finally {
            clipboard.dispose();
        }
        MessageDialog.openInformation(getShell(),
                Messages.McpHostPreferencePage_CopiedTitle,
                NLS(Messages.McpHostPreferencePage_CopiedMessage, current.getName()));
    }

    private void checkStatus() {
        ProfileEndpoint current = selectedProfile();
        if (current == null) {
            return;
        }
        int index = table.getSelectionIndex();
        boolean healthy = checkHealth(current.getPort());
        String status = !current.isEnabled()
                ? Messages.McpHostPreferencePage_StatusDisabled
                : healthy ? Messages.McpHostPreferencePage_StatusUp
                          : Messages.McpHostPreferencePage_StatusDown;
        table.getItem(index).setText(4, status);
    }

    // --- helpers -------------------------------------------------------------

    private java.util.Set<String> existingNames(String exclude) {
        return profiles.stream()
                .map(ProfileEndpoint::getName)
                .filter(n -> n != null && !n.equalsIgnoreCase(exclude))
                .collect(Collectors.toSet());
    }

    private int suggestFreePort() {
        int max = 8765;
        for (ProfileEndpoint p : profiles) {
            max = Math.max(max, p.getPort());
        }
        return max + 1;
    }

    private int countAnnounced(ProfileEndpoint profile) {
        try {
            DefaultMcpToolExposurePolicy policy = new DefaultMcpToolExposurePolicy(
                    McpHostConfig.defaults(), profile.getExposedToolsFilter(), profile.toGroupVisibility());
            int count = 0;
            for (ITool tool : ToolRegistry.getInstance().getAllTools()) {
                if (policy.isExposed(tool.getName())) {
                    count++;
                }
            }
            return count;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private static String maskToken(String token) {
        if (token == null || token.isBlank()) {
            return ""; //$NON-NLS-1$
        }
        return token.length() <= 6 ? "******" : "…" + token.substring(token.length() - 6); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private void refreshWarning() {
        String bind = bindAddressText.getText().trim();
        boolean local = "127.0.0.1".equals(bind) || "localhost".equalsIgnoreCase(bind); //$NON-NLS-1$ //$NON-NLS-2$
        warningLabel.setText(local
                ? Messages.McpHostPreferencePage_LocalOnlyInfo
                : Messages.McpHostPreferencePage_NonLocalWarning);
    }

    private void updateInstallHints() {
        ProfileEndpoint current = selectedProfile();
        installHintsText.setText(current != null ? buildHints(current) : ""); //$NON-NLS-1$
    }

    private boolean checkHealth(int port) {
        String bind = bindAddressText.getText().trim();
        if (bind.isBlank() || "0.0.0.0".equals(bind)) { //$NON-NLS-1$
            bind = "127.0.0.1"; //$NON-NLS-1$
        }
        String url = "http://" + bind + ":" + port + "/health"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(3)).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return response.statusCode() == 200 && "ok".equalsIgnoreCase(response.body().trim()); //$NON-NLS-1$
        } catch (Exception e) {
            return false;
        }
    }

    private String buildHints(ProfileEndpoint profile) {
        String bind = bindAddressText.getText().trim();
        if (bind.isBlank() || "0.0.0.0".equals(bind)) { //$NON-NLS-1$
            bind = "127.0.0.1"; //$NON-NLS-1$
        }
        String endpoint = "http://" + bind + ":" + profile.getPort() + "/mcp"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String token = profile.getBearerToken() != null ? profile.getBearerToken() : ""; //$NON-NLS-1$
        McpHostConfig.AuthMode authMode = McpHostConfig.AuthMode.values()[authModeCombo.getSelectionIndex()];
        boolean oauth = authMode == McpHostConfig.AuthMode.OAUTH_OR_BEARER || authMode == McpHostConfig.AuthMode.OAUTH_ONLY;
        StringBuilder sb = new StringBuilder();
        sb.append(profile.getName()).append("  —  ").append(endpoint).append('\n'); //$NON-NLS-1$
        if (oauth) {
            sb.append("claude mcp add --transport http -s user codepilot1c-") //$NON-NLS-1$
              .append(profile.getName()).append(' ').append(endpoint).append('\n').append('\n');
        }
        if (authMode != McpHostConfig.AuthMode.OAUTH_ONLY) {
            sb.append("{\n  \"mcpServers\": {\n    \"codepilot1c-").append(profile.getName()).append("\": {\n"); //$NON-NLS-1$ //$NON-NLS-2$
            sb.append("      \"url\": \"").append(endpoint).append("\",\n"); //$NON-NLS-1$ //$NON-NLS-2$
            sb.append("      \"headers\": { \"Authorization\": \"Bearer ").append(token).append("\" }\n"); //$NON-NLS-1$ //$NON-NLS-2$
            sb.append("    }\n  }\n}\n"); //$NON-NLS-1$
        }
        return sb.toString();
    }

    private static String NLS(String pattern, Object arg) {
        return org.eclipse.osgi.util.NLS.bind(pattern, arg);
    }

    private Button createCheckbox(Composite parent, String text, boolean selected, int span) {
        Button checkbox = new Button(parent, SWT.CHECK);
        checkbox.setText(text);
        checkbox.setSelection(selected);
        checkbox.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, span, 1));
        return checkbox;
    }

    private void createLabel(Composite parent, String text) {
        Label label = new Label(parent, SWT.NONE);
        label.setText(text);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s; //$NON-NLS-1$
    }

    @Override
    public boolean performOk() {
        syncEnabledFromChecks();
        McpHostConfig newConfig = McpHostConfig.defaults();
        newConfig.setEnabled(enabledCheckbox.getSelection());
        newConfig.setHttpEnabled(httpEnabledCheckbox.getSelection());
        newConfig.setBindAddress(bindAddressText.getText().trim());
        newConfig.setAuthMode(McpHostConfig.AuthMode.values()[authModeCombo.getSelectionIndex()]);
        newConfig.setMutationPolicy(McpHostConfig.MutationPolicy.values()[mutationPolicyCombo.getSelectionIndex()]);

        // Keep the legacy single port/token/filter tracking the 'full' (or first)
        // endpoint for back-compat with any client still configured against them.
        ProfileEndpoint primary = profiles.stream()
                .filter(p -> "full".equals(p.getName())) //$NON-NLS-1$
                .findFirst()
                .orElse(profiles.isEmpty() ? null : profiles.get(0));
        if (primary != null) {
            newConfig.setPort(primary.getPort());
            newConfig.setBearerToken(primary.getBearerToken());
            newConfig.setExposedToolsFilter(primary.getExposedToolsFilter());
        }
        newConfig.setProfiles(new ArrayList<>(profiles));

        McpHostConfigStore.getInstance().save(newConfig);
        McpHostManager.getInstance().restart();

        config = McpHostConfigStore.getInstance().load();
        replaceProfiles(config.getProfiles());
        refreshTable();
        updateButtons();
        updateInstallHints();
        return true;
    }

    @Override
    protected void performDefaults() {
        McpHostConfig defaults = McpHostConfig.defaults();
        enabledCheckbox.setSelection(defaults.isEnabled());
        httpEnabledCheckbox.setSelection(defaults.isHttpEnabled());
        bindAddressText.setText(defaults.getBindAddress());
        authModeCombo.select(defaults.getAuthMode().ordinal());
        mutationPolicyCombo.select(defaults.getMutationPolicy().ordinal());
        // Reload the persisted profiles (don't wipe the user's endpoint list).
        replaceProfiles(McpHostConfigStore.getInstance().load().getProfiles());
        refreshWarning();
        refreshTable();
        updateButtons();
        updateInstallHints();
        super.performDefaults();
    }
}
