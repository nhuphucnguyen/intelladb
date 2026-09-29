package dev.phucngu.intelladb.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.CheckBoxList;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.JBIntSpinner;
import com.intellij.ui.TitledSeparator;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.components.ActionLink;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPasswordField;
import com.intellij.ui.components.JBRadioButton;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.components.panels.HorizontalLayout;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import dev.phucngu.intelladb.connection.ConnectionManager;
import dev.phucngu.intelladb.connection.DbConfig;
import dev.phucngu.intelladb.connection.DbDialect;
import dev.phucngu.intelladb.connection.DbSession;
import dev.phucngu.intelladb.connection.Dialects;
import dev.phucngu.intelladb.schema.MetadataLoader;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.ButtonGroup;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.table.DefaultTableModel;
import javax.swing.text.JTextComponent;
import java.awt.BorderLayout;
import java.sql.Connection;
import java.text.ParseException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Add/Edit connection dialog, laid out like IntelliJ's database tools: a name row, then
 * General / Options / SSL / Schemas / Advanced tabs, and Test Connection at the bottom.
 */
public final class ConnectionDialog extends DialogWrapper {

    private static final String[] SAVE_MODES = {"Forever", "Until restart", "Never"};
    private static final String[] AUTH_MODES = {"User & Password", "No auth"};

    private final Project project;
    private final ConnectionManager manager;
    private final DbConfig original;   // null when creating

    private final JBTextField nameField = new JBTextField();
    /** The name follows host and database until the user types their own. */
    private boolean nameEdited;
    private boolean updatingName;

    // General
    private final ComboBox<String> dialectCombo = new ComboBox<>();
    private final JBRadioButton defaultType = new JBRadioButton("default", true);
    private final JBRadioButton urlOnlyType = new JBRadioButton("URL only");
    private final JBTextField hostField = new JBTextField("localhost");
    private final JBIntSpinner portSpinner = new JBIntSpinner(5432, 1, 65535);
    private final ComboBox<String> authCombo = new ComboBox<>(AUTH_MODES);
    private final JBTextField userField = new JBTextField();
    private final JBPasswordField passwordField = new JBPasswordField();
    private final ComboBox<String> saveCombo = new ComboBox<>(SAVE_MODES);
    private final ComboBox<String> databaseCombo = new ComboBox<>();
    private final JBTextField urlField = new JBTextField();
    private final JBLabel urlHint = new JBLabel("Overrides settings above");
    private final List<JComponent> hostRows = new ArrayList<>();
    private final List<JComponent> credentialRows = new ArrayList<>();
    /** The URL was typed by the user rather than generated from the fields. */
    private boolean urlOverridden;
    private boolean updatingUrl;
    private boolean databasesLoaded;
    /** Filling the Database dropdown rewrites its editor; that is not a user edit. */
    private boolean updatingDatabases;

    // Options
    private final JBCheckBox readOnly = new JBCheckBox("Read-only");
    private final ComboBox<String> txCombo = new ComboBox<>(new String[]{"Auto", "Manual"});
    private final ComboBox<String> timeZoneCombo = new ComboBox<>();
    private final JBCheckBox keepAlive = new JBCheckBox("Run keep-alive query each");
    private final JBIntSpinner keepAliveSeconds = new JBIntSpinner(60, 5, 86_400);
    private final JBCheckBox autoDisconnect = new JBCheckBox("Auto-disconnect after");
    private final JBIntSpinner autoDisconnectSeconds = new JBIntSpinner(300, 10, 604_800);
    private final JBTextArea startupScript = new JBTextArea(4, 40);

    // SSL
    private final JBCheckBox useSsl = new JBCheckBox("Use SSL");
    private final ComboBox<String> sslModeCombo = new ComboBox<>();
    private final TextFieldWithBrowseButton caFile = new TextFieldWithBrowseButton();
    private final TextFieldWithBrowseButton certFile = new TextFieldWithBrowseButton();
    private final TextFieldWithBrowseButton keyFile = new TextFieldWithBrowseButton();

    // Schemas
    private final JBCheckBox allSchemas = new JBCheckBox("All schemas", true);
    private final CheckBoxList<String> schemaList = new CheckBoxList<>();
    private final JBCheckBox showSystemSchemas = new JBCheckBox("Show internal system schemas");
    private final JBLabel schemaStatus = new JBLabel(" ");
    private boolean schemasLoaded;

    // Advanced
    private final DefaultTableModel propertiesModel = new DefaultTableModel(new Object[]{"Name", "Value"}, 0);
    private final JBTable propertiesTable = new JBTable(propertiesModel);

    private final JBLabel testStatus = new JBLabel();
    private final ActionLink testLink = new ActionLink("Test Connection", (java.awt.event.ActionListener) e -> testConnection());
    private String originalPassword = "";

    public ConnectionDialog(@NotNull Project project, @Nullable DbConfig config) {
        super(project);
        this.project = project;
        this.manager = ConnectionManager.getInstance(project);
        this.original = config == null ? null : config.copy();
        setTitle(original == null ? "New Database Connection" : "Edit Connection '" + original.name + "'");
        init();
        loadFrom(original != null ? original : new DbConfig());
        wireListeners();
        refreshEnabledState();
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JBTabbedPane tabs = new JBTabbedPane();
        tabs.addTab("General", padded(generalTab()));
        tabs.addTab("Options", scroll(padded(optionsTab())));
        tabs.addTab("SSL", padded(sslTab()));
        tabs.addTab("Schemas", schemasTab());
        tabs.addTab("Advanced", advancedTab());
        tabs.addChangeListener(e -> {
            if (tabs.getSelectedIndex() == 3 && !schemasLoaded) {
                loadSchemas();
            }
        });

        JPanel nameRow = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        nameRow.add(new JLabel("Name:"), BorderLayout.WEST);
        nameRow.add(nameField, BorderLayout.CENTER);
        nameRow.setBorder(JBUI.Borders.emptyBottom(8));

        JPanel testRow = new JPanel(new HorizontalLayout(JBUI.scale(12)));
        testRow.add(testLink);
        testRow.add(testStatus);
        testRow.setBorder(JBUI.Borders.emptyTop(8));

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(nameRow, BorderLayout.NORTH);
        panel.add(tabs, BorderLayout.CENTER);
        panel.add(testRow, BorderLayout.SOUTH);
        panel.setPreferredSize(JBUI.size(640, 520));
        return panel;
    }

    private @NotNull JComponent generalTab() {
        ButtonGroup types = new ButtonGroup();
        types.add(defaultType);
        types.add(urlOnlyType);
        JPanel typeRow = row(defaultType, urlOnlyType);

        databaseCombo.setEditable(true);
        urlHint.setForeground(UIUtil.getContextHelpForeground());
        urlHint.setFont(JBUI.Fonts.smallFont());

        JLabel hostLabel = new JLabel("Host:");
        JComponent hostRow = pair(hostField, "Port:", portSpinner);
        JLabel userLabel = new JLabel("User:");
        JLabel passwordLabel = new JLabel("Password:");
        JComponent passwordRow = pair(passwordField, "Save:", saveCombo);
        JLabel databaseLabel = new JLabel("Database:");
        JComponent databaseRow = stretch(databaseCombo);
        hostRows.addAll(List.of(hostLabel, hostRow, databaseLabel, databaseRow));
        credentialRows.addAll(List.of(userLabel, userField, passwordLabel, passwordRow));

        return FormBuilder.createFormBuilder()
                .setHorizontalGap(8)
                .addLabeledComponent("Driver:", stretch(dialectCombo))
                .addLabeledComponent("Connection type:", typeRow)
                .addVerticalGap(8)
                .addLabeledComponent(hostLabel, hostRow)
                .addVerticalGap(8)
                .addLabeledComponent("Authentication:", stretch(authCombo))
                .addLabeledComponent(userLabel, userField)
                .addLabeledComponent(passwordLabel, passwordRow)
                .addVerticalGap(8)
                .addLabeledComponent(databaseLabel, databaseRow)
                .addVerticalGap(16)
                .addLabeledComponent("URL:", urlField)
                .addLabeledComponent("", urlHint)
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
    }

    private @NotNull JComponent optionsTab() {
        timeZoneCombo.setEditable(true);
        timeZoneCombo.addItem("");
        ZoneId.getAvailableZoneIds().stream().sorted().forEach(timeZoneCombo::addItem);
        JBScrollPane scriptScroll = new JBScrollPane(startupScript);

        return FormBuilder.createFormBuilder()
                .setHorizontalGap(8)
                .addComponent(new TitledSeparator("Connection"))
                .addComponent(readOnly)
                .addLabeledComponent("Transaction control:", left(txCombo))
                .addLabeledComponent("Time zone:", timeZoneCombo)
                .addComponent(row(keepAlive, keepAliveSeconds, new JLabel("sec.")))
                .addComponent(row(autoDisconnect, autoDisconnectSeconds, new JLabel("sec.")))
                .addLabeledComponent("Startup script:", scriptScroll)
                .addComponent(hint("Runs after every connect, e.g. SET statement_timeout = '30s';"))
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
    }

    private @NotNull JComponent sslTab() {
        caFile.addBrowseFolderListener(project, FileChooserDescriptorFactory.singleFile().withTitle("CA File"));
        certFile.addBrowseFolderListener(project,
                FileChooserDescriptorFactory.singleFile().withTitle("Client Certificate File"));
        keyFile.addBrowseFolderListener(project, FileChooserDescriptorFactory.singleFile().withTitle("Client Key File"));
        return FormBuilder.createFormBuilder()
                .setHorizontalGap(8)
                .addComponent(useSsl)
                .addLabeledComponent("Mode:", left(sslModeCombo))
                .addLabeledComponent("CA file:", caFile)
                .addLabeledComponent("Client certificate file:", certFile)
                .addLabeledComponent("Client key file:", keyFile)
                .addComponent(hint("verify-ca / verify-full check the server certificate against the CA file."))
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
    }

    private @NotNull JComponent schemasTab() {
        JButton refresh = new JButton("Refresh", AllIcons.Actions.Refresh);
        refresh.addActionListener(e -> loadSchemas());
        JPanel top = new JPanel(new BorderLayout());
        top.add(allSchemas, BorderLayout.WEST);
        top.add(refresh, BorderLayout.EAST);

        JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(6)));
        panel.add(top, BorderLayout.NORTH);
        panel.add(new JBScrollPane(schemaList), BorderLayout.CENTER);
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(showSystemSchemas, BorderLayout.NORTH);
        bottom.add(schemaStatus, BorderLayout.SOUTH);
        panel.add(bottom, BorderLayout.SOUTH);
        panel.setBorder(JBUI.Borders.empty(8));
        return panel;
    }

    private @NotNull JComponent advancedTab() {
        JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(6)));
        panel.add(new JBLabel("Driver properties (override the ones derived from the other tabs):"),
                BorderLayout.NORTH);
        panel.add(ToolbarDecorator.createDecorator(propertiesTable)
                .setAddAction(b -> {
                    propertiesModel.addRow(new Object[]{"", ""});
                    int row = propertiesModel.getRowCount() - 1;
                    propertiesTable.editCellAt(row, 0);
                    propertiesTable.getSelectionModel().setSelectionInterval(row, row);
                })
                .setRemoveAction(b -> {
                    stopEditing();
                    int[] rows = propertiesTable.getSelectedRows();
                    for (int i = rows.length - 1; i >= 0; i--) {
                        propertiesModel.removeRow(rows[i]);
                    }
                })
                .disableUpDownActions()
                .createPanel(), BorderLayout.CENTER);
        panel.setBorder(JBUI.Borders.empty(8));
        return panel;
    }

    // ------------------------------------------------------------------ model <-> fields

    private void loadFrom(@NotNull DbConfig config) {
        for (DbDialect dialect : Dialects.all()) {
            dialectCombo.addItem(dialect.displayName());
            if (dialect.id().equals(config.dialectId)) {
                dialectCombo.setSelectedItem(dialect.displayName());
            }
        }
        nameField.setText(config.name);
        nameEdited = original != null;
        urlOnlyType.setSelected(config.urlOnly);
        defaultType.setSelected(!config.urlOnly);
        hostField.setText(config.host);
        portSpinner.setNumber(config.port);
        authCombo.setSelectedIndex(config.noAuth ? 1 : 0);
        userField.setText(config.user);
        saveCombo.setSelectedIndex(config.savePassword ? 0 : config.neverRememberPassword ? 2 : 1);
        databaseCombo.setSelectedItem(config.database);
        if (!config.jdbcUrlOverride.isBlank()) {
            urlOverridden = true;
            urlField.setText(config.jdbcUrlOverride);
        } else {
            urlField.setText(config.dialect().jdbcUrl(config));
        }
        if (original != null) {
            String saved = manager.readPassword(original);
            if (saved != null) {
                passwordField.setText(saved);
                originalPassword = saved;
            }
        }

        readOnly.setSelected(config.readOnly);
        txCombo.setSelectedIndex(config.autoCommit ? 0 : 1);
        timeZoneCombo.setSelectedItem(config.timeZone);
        keepAlive.setSelected(config.keepAlive);
        keepAliveSeconds.setNumber(config.keepAliveSeconds);
        autoDisconnect.setSelected(config.autoDisconnect);
        autoDisconnectSeconds.setNumber(config.autoDisconnectSeconds);
        startupScript.setText(config.startupScript);

        useSsl.setSelected(config.sslMode);
        fillSslModes(config.sslModeName);
        caFile.setText(config.sslRootCert);
        certFile.setText(config.sslCert);
        keyFile.setText(config.sslKey);

        allSchemas.setSelected(config.schemas.isEmpty());
        for (String schema : config.schemas) {
            schemaList.addItem(schema, schema, true);
        }
        showSystemSchemas.setSelected(config.showSystemSchemas);

        config.driverProperties.forEach((key, value) -> propertiesModel.addRow(new Object[]{key, value}));
        updateUrlHint();
    }

    /** Copies the fields into {@code config}; no side effects, so Test Connection can use it too. */
    private void applyTo(@NotNull DbConfig config) {
        config.name = nameField.getText().trim();
        config.dialectId = selectedDialect().id();
        config.urlOnly = urlOnlyType.isSelected();
        config.host = hostField.getText().trim();
        config.port = committed(portSpinner);
        config.database = databaseText();
        config.noAuth = authCombo.getSelectedIndex() == 1;
        config.user = userField.getText().trim();
        config.savePassword = saveCombo.getSelectedIndex() == 0;
        config.neverRememberPassword = saveCombo.getSelectedIndex() == 2;
        config.jdbcUrlOverride = config.urlOnly || urlOverridden ? urlField.getText().trim() : "";

        config.readOnly = readOnly.isSelected();
        config.autoCommit = txCombo.getSelectedIndex() == 0;
        Object zone = timeZoneCombo.getEditor().getItem();
        config.timeZone = zone == null ? "" : zone.toString().trim();
        config.keepAlive = keepAlive.isSelected();
        config.keepAliveSeconds = committed(keepAliveSeconds);
        config.autoDisconnect = autoDisconnect.isSelected();
        config.autoDisconnectSeconds = committed(autoDisconnectSeconds);
        config.startupScript = startupScript.getText();

        config.sslMode = useSsl.isSelected();
        config.sslModeName = String.valueOf(sslModeCombo.getSelectedItem());
        config.sslRootCert = caFile.getText().trim();
        config.sslCert = certFile.getText().trim();
        config.sslKey = keyFile.getText().trim();

        config.schemas = allSchemas.isSelected() ? new ArrayList<>() : checkedSchemas();
        config.showSystemSchemas = showSystemSchemas.isSelected();

        stopEditing();
        Map<String, String> properties = new LinkedHashMap<>();
        for (int row = 0; row < propertiesModel.getRowCount(); row++) {
            String key = String.valueOf(propertiesModel.getValueAt(row, 0)).trim();
            if (!key.isEmpty()) {
                properties.put(key, String.valueOf(propertiesModel.getValueAt(row, 1)));
            }
        }
        config.driverProperties = properties;
    }

    private @NotNull DbConfig snapshot() {
        DbConfig probe = original != null ? original.copy() : new DbConfig();
        applyTo(probe);
        return probe;
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        if (urlOnlyType.isSelected() || urlOverridden) {
            if (urlField.getText().isBlank()) {
                return new ValidationInfo("Enter the JDBC URL", urlField);
            }
        } else if (hostField.getText().isBlank()) {
            return new ValidationInfo("Enter the host", hostField);
        }
        if (!allSchemas.isSelected() && checkedSchemas().isEmpty()) {
            return new ValidationInfo("Select at least one schema, or check All schemas", schemaList);
        }
        return null;
    }

    @Override
    protected void doOKAction() {
        DbConfig target = original != null ? original : new DbConfig();
        String password = new String(passwordField.getPassword());
        boolean storageChanged = original != null && (original.savePassword != (saveCombo.getSelectedIndex() == 0)
                || original.neverRememberPassword != (saveCombo.getSelectedIndex() == 2));
        applyTo(target);
        if (target.name.isBlank()) {
            target.name = autoName();
        }
        if (target.noAuth) {
            password = "";
        }
        manager.saveConfig(target, password, storageChanged || !password.equals(originalPassword));
        super.doOKAction();
    }

    @Override
    protected @Nullable String getDimensionServiceKey() {
        return "IntellaDb.ConnectionDialog";
    }

    // ------------------------------------------------------------------ behaviour

    private void wireListeners() {
        dialectCombo.addActionListener(e -> {
            portSpinner.setNumber(selectedDialect().defaultPort());
            fillSslModes(String.valueOf(sslModeCombo.getSelectedItem()));
            fieldsChanged();
        });
        defaultType.addActionListener(e -> {
            if (!urlOverridden) {
                fieldsChanged();
            }
            refreshEnabledState();
        });
        urlOnlyType.addActionListener(e -> refreshEnabledState());
        authCombo.addActionListener(e -> refreshEnabledState());
        useSsl.addActionListener(e -> refreshEnabledState());
        keepAlive.addActionListener(e -> refreshEnabledState());
        autoDisconnect.addActionListener(e -> refreshEnabledState());
        allSchemas.addActionListener(e -> refreshEnabledState());
        showSystemSchemas.addActionListener(e -> {
            if (schemasLoaded) {
                loadSchemas();
            }
        });
        onChange(hostField, this::fieldsChanged);
        onChange((JTextComponent) databaseCombo.getEditor().getEditorComponent(), () -> {
            if (!updatingDatabases) {
                fieldsChanged();
            }
        });
        portSpinner.addChangeListener(e -> fieldsChanged());
        onChange(urlField, () -> {
            if (!updatingUrl) {
                urlOverridden = !urlField.getText().trim().equals(generatedUrl());
                updateUrlHint();
            }
        });
        onChange(nameField, () -> {
            if (!updatingName) {
                nameEdited = !nameField.getText().isBlank();
            }
        });
        databaseCombo.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                if (!databasesLoaded) {
                    databasesLoaded = true;
                    loadDatabases();
                }
            }

            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
            }

            @Override
            public void popupMenuCanceled(PopupMenuEvent e) {
            }
        });
        if (!nameEdited) {
            updateAutoName();
        }
    }

    /** Fills the SSL mode dropdown from the selected dialect, keeping {@code keep} when it is offered. */
    private void fillSslModes(@Nullable String keep) {
        List<String> modes = selectedDialect().sslModes();
        sslModeCombo.setModel(new DefaultComboBoxModel<>(modes.toArray(String[]::new)));
        sslModeCombo.setSelectedItem(keep != null && modes.contains(keep) ? keep : modes.get(0));
    }

    /** Host, port or database changed: regenerate the URL (dropping a typed override) and the name. */
    private void fieldsChanged() {
        if (!urlOnlyType.isSelected()) {
            urlOverridden = false;
            updatingUrl = true;
            try {
                urlField.setText(generatedUrl());
            } finally {
                updatingUrl = false;
            }
            updateUrlHint();
        }
        databasesLoaded = false;
        schemasLoaded = false;
        if (!nameEdited) {
            updateAutoName();
        }
    }

    private void updateAutoName() {
        updatingName = true;
        try {
            nameField.setText(autoName());
        } finally {
            updatingName = false;
        }
    }

    /** "shop@db.example.com", or "@localhost" before a database is chosen — as IntelliJ names them. */
    private @NotNull String autoName() {
        return databaseText() + "@" + (urlOnlyType.isSelected() ? "url" : hostField.getText().trim());
    }

    private @NotNull String generatedUrl() {
        DbConfig probe = new DbConfig();
        probe.host = hostField.getText().trim();
        probe.port = portSpinner.getNumber();
        probe.database = databaseText();
        return selectedDialect().jdbcUrl(probe);
    }

    private void updateUrlHint() {
        urlHint.setVisible(urlOverridden && !urlOnlyType.isSelected());
    }

    private void refreshEnabledState() {
        boolean urlOnly = urlOnlyType.isSelected();
        hostRows.forEach(c -> c.setVisible(!urlOnly));
        credentialRows.forEach(c -> c.setVisible(authCombo.getSelectedIndex() == 0));
        updateUrlHint();
        boolean ssl = useSsl.isSelected();
        sslModeCombo.setEnabled(ssl);
        caFile.setEnabled(ssl);
        certFile.setEnabled(ssl);
        keyFile.setEnabled(ssl);
        keepAliveSeconds.setEnabled(keepAlive.isSelected());
        autoDisconnectSeconds.setEnabled(autoDisconnect.isSelected());
        schemaList.setEnabled(!allSchemas.isSelected());
    }

    /** Lists the server's databases into the Database dropdown (on first open). */
    private void loadDatabases() {
        DbConfig probe = snapshot();
        String typed = databaseText();
        if (probe.database.isBlank() && !probe.urlOnly) {
            probe.database = probe.dialect().maintenanceDatabase();
            probe.jdbcUrlOverride = "";
        }
        withProbe(probe, connection -> probe.dialect().listDatabases(connection), names -> {
            updatingDatabases = true;
            try {
                databaseCombo.setModel(new DefaultComboBoxModel<>(names.toArray(String[]::new)));
                databaseCombo.setSelectedItem(typed);
            } finally {
                updatingDatabases = false;
            }
            // The popup opened empty while loading; reopen it with the names.
            if (databaseCombo.isShowing()) {
                databaseCombo.hidePopup();
                databaseCombo.showPopup();
            }
        }, error -> {
            databasesLoaded = false;
            testStatus.setText("Could not list databases: " + error);
            testStatus.setForeground(JBUI.CurrentTheme.Label.errorForeground());
        });
    }

    /** Lists the connected database's schemas with checkboxes; system schemas only on request. */
    private void loadSchemas() {
        schemasLoaded = true;
        DbConfig probe = snapshot();
        Set<String> checked = new HashSet<>(checkedSchemas());
        schemaStatus.setText("Loading schemas…");
        withProbe(probe, connection -> MetadataLoader.schemaNames(connection, probe.dialect()), names -> {
            schemaList.clear();
            for (String name : names) {
                boolean isSystem = probe.dialect().isSystemSchema(name);
                if (isSystem && !showSystemSchemas.isSelected() && !checked.contains(name)) {
                    continue;
                }
                boolean selected = checked.isEmpty() ? !isSystem : checked.contains(name);
                schemaList.addItem(name, isSystem ? name + "  (system)" : name, selected);
            }
            schemaStatus.setText(" ");
        }, error -> {
            schemasLoaded = false;
            schemaStatus.setText("Could not load schemas: " + error);
        });
    }

    private void testConnection() {
        if (!testLink.isEnabled()) {
            return;
        }
        DbConfig probe = snapshot();
        testStatus.setText("Testing…");
        testStatus.setForeground(UIUtil.getContextHelpForeground());
        testStatus.setToolTipText(null);
        testLink.setEnabled(false);
        withProbe(probe, connection -> {
            var meta = connection.getMetaData();
            return meta.getDatabaseProductName() + " " + meta.getDatabaseProductVersion();
        }, version -> {
            testStatus.setText(version);
            testStatus.setForeground(JBUI.CurrentTheme.Label.foreground());
            testLink.setEnabled(true);
        }, error -> {
            testStatus.setText(error.length() > 80 ? error.substring(0, 80) + "…" : error);
            testStatus.setToolTipText(error);
            testStatus.setForeground(JBUI.CurrentTheme.Label.errorForeground());
            testLink.setEnabled(true);
        });
    }

    private interface ProbeQuery<T> {
        T run(@NotNull Connection connection) throws Exception;
    }

    /**
     * Opens a throwaway connection with the dialog's current settings on a pooled thread and
     * hands the query result (or error message) back on the EDT.
     */
    private <T> void withProbe(@NotNull DbConfig probe, @NotNull ProbeQuery<T> query,
                               @NotNull Consumer<T> onSuccess, @NotNull Consumer<String> onError) {
        String password = probe.noAuth ? null : new String(passwordField.getPassword());
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            T result = null;
            String error = null;
            try (Connection connection = DbSession.open(probe, password, 5)) {
                result = query.run(connection);
            } catch (Exception ex) {
                error = ex.getMessage() == null ? ex.toString() : ex.getMessage();
            }
            T finalResult = result;
            String finalError = error;
            // The dialog is modal: a plain invokeLater would queue until it closes.
            ApplicationManager.getApplication().invokeLater(() -> {
                if (finalError != null) {
                    onError.accept(finalError);
                } else {
                    onSuccess.accept(finalResult);
                }
            }, ModalityState.stateForComponent(getContentPane()));
        });
    }

    // ------------------------------------------------------------------ helpers

    private @NotNull DbDialect selectedDialect() {
        return Dialects.all().get(Math.max(0, dialectCombo.getSelectedIndex()));
    }

    private @NotNull String databaseText() {
        Object item = databaseCombo.getEditor().getItem();
        return item == null ? "" : item.toString().trim();
    }

    private @NotNull List<String> checkedSchemas() {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < schemaList.getModel().getSize(); i++) {
            if (schemaList.isItemSelected(i)) {
                names.add(schemaList.getItemAt(i));
            }
        }
        return names;
    }

    /** A spinner only commits typed text on focus loss or Enter; OK can arrive before either. */
    private static int committed(@NotNull JBIntSpinner spinner) {
        try {
            spinner.commitEdit();
        } catch (ParseException ignored) {
            // Invalid text: keep the last valid value.
        }
        return spinner.getNumber();
    }

    private void stopEditing() {
        if (propertiesTable.isEditing()) {
            propertiesTable.getCellEditor().stopCellEditing();
        }
    }

    private static void onChange(@NotNull JTextComponent field, @NotNull Runnable runnable) {
        field.getDocument().addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull DocumentEvent e) {
                runnable.run();
            }
        });
    }

    /** {@code main} stretching, then "label: other" on the right — the Host/Port and Password/Save rows. */
    private static @NotNull JComponent pair(@NotNull JComponent main, @NotNull String label, @NotNull JComponent other) {
        JPanel east = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        east.add(new JLabel(label), BorderLayout.WEST);
        east.add(other, BorderLayout.CENTER);
        east.setBorder(JBUI.Borders.emptyLeft(12));
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(main, BorderLayout.CENTER);
        panel.add(east, BorderLayout.EAST);
        return panel;
    }

    private static @NotNull JPanel row(@NotNull JComponent... components) {
        JPanel panel = new JPanel(new HorizontalLayout(JBUI.scale(6)));
        for (JComponent component : components) {
            panel.add(component);
        }
        return panel;
    }

    /** FormBuilder keeps combo boxes at their preferred width; IntelliJ's dialog stretches them. */
    private static @NotNull JComponent stretch(@NotNull JComponent component) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(component, BorderLayout.CENTER);
        return panel;
    }

    private static @NotNull JPanel left(@NotNull JComponent component) {
        return row(component);
    }

    private static @NotNull JComponent hint(@NotNull String text) {
        JBLabel label = new JBLabel(text);
        label.setForeground(UIUtil.getContextHelpForeground());
        label.setFont(JBUI.Fonts.smallFont());
        return label;
    }

    private static @NotNull JComponent padded(@NotNull JComponent content) {
        content.setBorder(JBUI.Borders.empty(8, 4, 0, 4));
        return content;
    }

    private static @NotNull JComponent scroll(@NotNull JComponent content) {
        JBScrollPane pane = new JBScrollPane(content);
        pane.setBorder(JBUI.Borders.empty());
        return pane;
    }
}
