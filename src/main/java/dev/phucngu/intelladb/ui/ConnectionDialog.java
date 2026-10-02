package dev.phucngu.intelladb.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.ui.SimpleTextAttributes;
import dev.phucngu.intelladb.IntellaDbIcons;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.ui.popup.Balloon;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.AnimatedIcon;
import com.intellij.ui.JBColor;
import com.intellij.ui.awt.RelativePoint;
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
import dev.phucngu.intelladb.connection.Dialects;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Action;
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
import java.awt.Point;
import java.awt.datatransfer.StringSelection;
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
 * The "Data Sources" dialog, laid out like IntelliJ's database tools: on the left every
 * connection, grouped into Global (all projects) and Project data sources, with add /
 * remove / duplicate / make global-or-project; on the right the selected one's name row,
 * General / Options / SSL / Schemas / Advanced tabs and Test Connection. Changes are kept
 * per connection while switching and only saved by Apply or OK; Cancel drops them all.
 */
public final class ConnectionDialog extends DialogWrapper {

    private static final String[] SAVE_MODES = {"Forever", "Until restart", "Never"};
    private static final String[] AUTH_MODES = {"User & Password", "No auth"};

    /** One connection as edited in the dialog, saved only by Apply / OK. */
    private static final class Draft {
        /** The connection as last saved; null while it is new. */
        @Nullable DbConfig saved;
        /** Working copy the form reads from and writes to. */
        DbConfig config;
        /**
         * What the form made of {@link #saved} when first shown: the form normalises a few
         * fields, so "changed" compares against this rather than the stored connection.
         */
        @Nullable DbConfig baseline;
        boolean global;
        boolean savedGlobal;
        String password = "";
        String savedPassword = "";
        boolean nameEdited;

        Draft(@Nullable DbConfig saved, @NotNull DbConfig config, boolean global) {
            this.saved = saved;
            this.config = config;
            this.global = global;
            this.savedGlobal = global;
            this.nameEdited = saved != null;
        }
    }

    private final Project project;
    private final ConnectionManager manager;
    private final List<Draft> drafts = new ArrayList<>();
    /** Saved connections removed in the dialog, deleted on Apply / OK. */
    private final List<String> removed = new ArrayList<>();
    private @Nullable Draft current;
    /** Filling the form from a draft: field listeners must not treat that as user edits. */
    private boolean loading;
    private final javax.swing.DefaultListModel<Object> listModel = new javax.swing.DefaultListModel<>();
    private final com.intellij.ui.components.JBList<Object> sourceList = new com.intellij.ui.components.JBList<>(listModel);
    private final JPanel formPanel = new JPanel(new BorderLayout());
    private static final String GLOBAL_HEADER = "Global Data Sources";
    private static final String PROJECT_HEADER = "Project Data Sources";

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
    private final SchemaCheckTree schemaList = new SchemaCheckTree();
    private final JBCheckBox showSystemSchemas = new JBCheckBox("Show internal system schemas");
    private final JBLabel schemaStatus = new JBLabel(" ");
    private boolean schemasLoaded;
    /** Whether the Schemas tab lists {@code database.schema} (no database given, see {@link DbConfig#allDatabases()}). */
    private boolean schemasQualified;

    // Advanced
    private final DefaultTableModel propertiesModel = new DefaultTableModel(new Object[]{"Name", "Value"}, 0);
    private final JBTable propertiesTable = new JBTable(propertiesModel);

    private final JBLabel testStatus = new JBLabel();
    /** Re-shows the last test's balloon when the status is clicked; null before the first test. */
    private @Nullable Runnable showTestResult;
    private @Nullable Balloon testBalloon;
    private final ActionLink testLink = new ActionLink("Test Connection", (java.awt.event.ActionListener) e -> testConnection());

    /**
     * Opens the dialog with every connection listed and {@code select} selected; null
     * starts a new (project) connection instead.
     */
    public ConnectionDialog(@NotNull Project project, @Nullable DbConfig select) {
        super(project);
        this.project = project;
        this.manager = ConnectionManager.getInstance(project);
        setTitle("Data Sources");
        for (DbDialect dialect : Dialects.all()) {
            dialectCombo.addItem(dialect.displayName());
        }
        Draft selected = null;
        for (DbConfig config : manager.configs()) {
            Draft draft = new Draft(config, config.copy(), manager.isGlobal(config.id));
            String password = manager.readPassword(config);
            draft.password = draft.savedPassword = password == null ? "" : password;
            drafts.add(draft);
            if (select != null && config.id.equals(select.id)) {
                selected = draft;
            }
        }
        init();
        wireListeners();
        if (selected == null && (select == null || drafts.isEmpty())) {
            selected = newDraft(Dialects.all().get(0));
        } else if (selected == null) {
            selected = drafts.get(0);
        }
        refreshList();
        sourceList.setSelectedValue(selected, true);
    }

    private @NotNull Draft newDraft(@NotNull DbDialect dialect) {
        DbConfig config = new DbConfig();
        config.dialectId = dialect.id();
        config.port = dialect.defaultPort();
        config.noAuth = dialect.noAuthByDefault();
        Draft draft = new Draft(null, config, false);
        drafts.add(draft);
        return draft;
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
        testStatus.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (showTestResult != null) {
                    showTestResult.run();
                }
            }
        });
        testRow.setBorder(JBUI.Borders.emptyTop(8));

        formPanel.add(nameRow, BorderLayout.NORTH);
        formPanel.add(tabs, BorderLayout.CENTER);
        formPanel.add(testRow, BorderLayout.SOUTH);
        formPanel.setBorder(JBUI.Borders.emptyLeft(12));

        com.intellij.ui.JBSplitter splitter = new com.intellij.ui.JBSplitter(false, "IntellaDb.DataSources.split", 0.26f);
        splitter.setFirstComponent(sourcesPanel());
        splitter.setSecondComponent(formPanel);
        splitter.setPreferredSize(JBUI.size(900, 560));
        return splitter;
    }

    // ------------------------------------------------------------------ data source list

    private @NotNull JComponent sourcesPanel() {
        sourceList.getSelectionModel().setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
        sourceList.setCellRenderer(new com.intellij.ui.ColoredListCellRenderer<>() {
            @Override
            protected void customizeCellRenderer(@NotNull javax.swing.JList<?> list, Object value, int index,
                                                 boolean selected, boolean hasFocus) {
                if (value instanceof String header) {
                    append(header, SimpleTextAttributes.GRAYED_ATTRIBUTES);
                    setBorder(JBUI.Borders.empty(index == 0 ? 2 : 10, 0, 2, 0));
                    return;
                }
                Draft draft = (Draft) value;
                setIcon(IntellaDbIcons.connection(draft.config.dialectId, false));
                String name = draft.config.name.isBlank() ? "<unnamed>" : draft.config.name;
                append(name, draft.saved == null ? SimpleTextAttributes.REGULAR_ITALIC_ATTRIBUTES
                        : SimpleTextAttributes.REGULAR_ATTRIBUTES);
                setBorder(JBUI.Borders.emptyLeft(8));
            }
        });
        sourceList.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) {
                return;
            }
            Object value = sourceList.getSelectedValue();
            if (value instanceof String) { // headers can't be selected: stay on the current one
                if (current != null) {
                    sourceList.setSelectedValue(current, false);
                }
                return;
            }
            select((Draft) value);
        });

        DefaultActionGroup add = new DefaultActionGroup("Add", true);
        add.getTemplatePresentation().setIcon(AllIcons.General.Add);
        add.getTemplatePresentation().setDescription("Add a data source");
        for (DbDialect dialect : Dialects.all()) {
            add.add(new DumbAwareAction(dialect.displayName()) {
                @Override
                public void actionPerformed(@NotNull AnActionEvent e) {
                    commitCurrent();
                    Draft draft = newDraft(dialect);
                    refreshList();
                    sourceList.setSelectedValue(draft, true);
                    nameField.requestFocusInWindow();
                }
            });
        }
        DefaultActionGroup group = new DefaultActionGroup();
        group.add(add);
        group.add(new ListAction("Remove", "Remove the data source (on Apply / OK)", AllIcons.General.Remove) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                removeCurrent();
            }
        });
        group.add(new ListAction("Duplicate", "Copy the data source", AllIcons.Actions.Copy) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                duplicateCurrent();
            }
        });
        group.add(new ListAction("Make Global", "Share the data source with all projects", IntellaDbIcons.MAKE_GLOBAL) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                if (current != null) {
                    current.global = !current.global;
                    refreshList();
                    sourceList.setSelectedValue(current, true);
                }
            }

            @Override
            public void update(@NotNull AnActionEvent e) {
                super.update(e);
                boolean global = current != null && current.global;
                e.getPresentation().setText(global ? "Make Project" : "Make Global");
                e.getPresentation().setDescription(global ? "Keep the data source in this project only"
                        : "Share the data source with all projects");
                e.getPresentation().setIcon(global ? IntellaDbIcons.MAKE_PROJECT : IntellaDbIcons.MAKE_GLOBAL);
            }
        });
        com.intellij.openapi.actionSystem.ActionToolbar toolbar = com.intellij.openapi.actionSystem.ActionManager
                .getInstance().createActionToolbar("IntellaDbDataSources", group, true);
        toolbar.setTargetComponent(sourceList);

        JBLabel title = new JBLabel("Data Sources");
        title.setFont(JBUI.Fonts.label().asBold());
        title.setBorder(JBUI.Borders.empty(0, 4, 4, 0));
        JPanel top = new JPanel(new BorderLayout());
        top.add(title, BorderLayout.NORTH);
        top.add(toolbar.getComponent(), BorderLayout.CENTER);
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(top, BorderLayout.NORTH);
        JBScrollPane scroll = new JBScrollPane(sourceList);
        scroll.setBorder(JBUI.Borders.empty());
        panel.add(scroll, BorderLayout.CENTER);
        return panel;
    }

    /** Toolbar action on the selected data source. */
    private abstract class ListAction extends DumbAwareAction {
        ListAction(@NotNull String text, @NotNull String description, @NotNull javax.swing.Icon icon) {
            super(text, description, icon);
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabled(current != null);
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }
    }

    /** Rebuilds the list: global data sources, then the project's, each under its header when non-empty. */
    private void refreshList() {
        Draft keep = current;
        loading = true; // selection events while rebuilding are not user picks
        try {
            listModel.clear();
            for (boolean global : new boolean[]{true, false}) {
                List<Draft> group = drafts.stream().filter(d -> d.global == global).toList();
                if (!group.isEmpty()) {
                    listModel.addElement(global ? GLOBAL_HEADER : PROJECT_HEADER);
                    group.forEach(listModel::addElement);
                }
            }
        } finally {
            loading = false;
        }
        if (keep != null && drafts.contains(keep)) {
            sourceList.setSelectedValue(keep, true);
        }
    }

    /** Shows {@code draft} in the form, keeping what was typed for the previous one. */
    private void select(@Nullable Draft draft) {
        if (loading || draft == current) {
            return;
        }
        commitCurrent();
        current = draft;
        formPanel.setVisible(draft != null);
        if (draft != null) {
            loadFrom(draft);
            if (draft.saved != null && draft.baseline == null) {
                DbConfig baseline = draft.config.copy();
                applyTo(baseline);
                if (baseline.name.isBlank()) {
                    baseline.name = autoName();
                }
                draft.baseline = baseline;
            }
        }
    }

    /** Copies the form into the current draft. */
    private void commitCurrent() {
        if (current == null) {
            return;
        }
        applyTo(current.config);
        if (current.config.name.isBlank()) {
            current.config.name = autoName();
        }
        current.password = new String(passwordField.getPassword());
        current.nameEdited = nameEdited;
        sourceList.repaint();
    }

    private void removeCurrent() {
        if (current == null) {
            return;
        }
        Draft gone = current;
        int index = drafts.indexOf(gone);
        drafts.remove(gone);
        if (gone.saved != null) {
            removed.add(gone.saved.id);
        }
        current = null; // nothing to commit for it
        refreshList();
        if (drafts.isEmpty()) {
            select(null);
        } else {
            sourceList.setSelectedValue(drafts.get(Math.min(index, drafts.size() - 1)), true);
        }
    }

    private void duplicateCurrent() {
        if (current == null) {
            return;
        }
        commitCurrent();
        DbConfig copy = current.config.copy();
        copy.id = java.util.UUID.randomUUID().toString();
        copy.name = current.config.name + " (copy)";
        Draft draft = new Draft(null, copy, current.global);
        draft.password = current.password;
        draft.nameEdited = true;
        drafts.add(drafts.indexOf(current) + 1, draft);
        refreshList();
        sourceList.setSelectedValue(draft, true);
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
        panel.add(new JBScrollPane(schemaList.component()), BorderLayout.CENTER);
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

    private void loadFrom(@NotNull Draft draft) {
        loading = true;
        try {
            loadFields(draft);
        } finally {
            loading = false;
        }
        hideTestBalloon();
        showTestResult = null;
        testStatus.setIcon(null);
        testStatus.setText("");
        refreshEnabledState();
        if (!nameEdited) {
            updateAutoName();
        }
    }

    private void loadFields(@NotNull Draft draft) {
        DbConfig config = draft.config;
        dialectCombo.setSelectedItem(config.dialect().displayName());
        nameField.setText(config.name);
        nameEdited = draft.nameEdited;
        databasesLoaded = false;
        schemasLoaded = false;
        urlOnlyType.setSelected(config.urlOnly);
        defaultType.setSelected(!config.urlOnly);
        hostField.setText(config.host);
        portSpinner.setNumber(config.port);
        authCombo.setSelectedIndex(config.noAuth ? 1 : 0);
        userField.setText(config.user);
        saveCombo.setSelectedIndex(config.savePassword ? 0 : config.neverRememberPassword ? 2 : 1);
        databaseCombo.setModel(new DefaultComboBoxModel<>());
        databaseCombo.setSelectedItem(config.database);
        urlOverridden = !config.jdbcUrlOverride.isBlank();
        urlField.setText(urlOverridden ? config.jdbcUrlOverride : config.dialect().jdbcUrl(config));
        passwordField.setText(draft.password);

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
        schemasQualified = config.allDatabases();
        schemaList.clear();
        schemaStatus.setText(" ");
        for (String schema : config.schemas) {
            // Until the tab loads the server's list: saved database.schema entries grouped by
            // their first dot (display only; the saved value is kept as is).
            int dot = schemasQualified ? schema.indexOf('.') : -1;
            schemaList.add(dot < 0 ? null : schema.substring(0, dot),
                    new SchemaCheckTree.Item(schema, dot < 0 ? schema : schema.substring(dot + 1), false), true);
        }
        schemaList.reload();
        showSystemSchemas.setSelected(config.showSystemSchemas);

        stopEditing();
        propertiesModel.setRowCount(0);
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
        DbConfig probe = current != null ? current.config.copy() : new DbConfig();
        applyTo(probe);
        return probe;
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        if (current == null) {
            return null;
        }
        if (urlOnlyType.isSelected() || urlOverridden) {
            if (urlField.getText().isBlank()) {
                return new ValidationInfo("Enter the JDBC URL", urlField);
            }
        } else if (hostField.getText().isBlank()) {
            return new ValidationInfo("Enter the host", hostField);
        }
        if (!allSchemas.isSelected() && checkedSchemas().isEmpty()) {
            return new ValidationInfo("Select at least one schema, or check All schemas", schemaList.component());
        }
        return null;
    }

    @Override
    protected void doOKAction() {
        if (save()) {
            super.doOKAction();
        }
    }

    @Override
    protected Action @NotNull [] createActions() {
        return new Action[]{getCancelAction(), new DialogWrapperAction("Apply") {
            @Override
            protected void doAction(java.awt.event.ActionEvent e) {
                if (doValidate() == null) {
                    save();
                }
            }
        }, getOKAction()};
    }

    /**
     * Writes every draft that changed (and deletes the removed ones); a changed connection is
     * disconnected so its next use picks up the new settings. False — with the offending
     * data source selected — when one isn't valid.
     */
    private boolean save() {
        commitCurrent();
        for (Draft draft : drafts) {
            if (problem(draft.config) != null) {
                sourceList.setSelectedValue(draft, true);
                return false;
            }
        }
        for (String id : removed) {
            manager.deleteConfig(id);
            ConsoleStore.getInstance(project).remove(id);
        }
        removed.clear();
        for (Draft draft : drafts) {
            DbConfig target = draft.config;
            String password = target.noAuth ? "" : draft.password;
            boolean settingsChanged = draft.saved == null
                    || !sameSettings(draft.baseline != null ? draft.baseline : draft.saved, target);
            boolean passwordChanged = !password.equals(draft.savedPassword) || (draft.saved != null
                    && (draft.saved.savePassword != target.savePassword
                    || draft.saved.neverRememberPassword != target.neverRememberPassword));
            if (!settingsChanged && !passwordChanged && draft.global == draft.savedGlobal) {
                continue;
            }
            boolean existed = draft.saved != null;
            manager.saveConfig(target, password, draft.saved == null || passwordChanged, draft.global);
            if (existed && (settingsChanged || passwordChanged)) {
                manager.disconnect(target.id);
            }
            draft.saved = target;
            draft.baseline = target.copy();
            draft.config = target.copy();
            draft.savedGlobal = draft.global;
            draft.savedPassword = password;
        }
        sourceList.repaint();
        return true;
    }

    /** Why a draft can't be saved, or null (for drafts not shown in the form; the form has doValidate). */
    private static @Nullable String problem(@NotNull DbConfig config) {
        if (config.urlOnly || !config.jdbcUrlOverride.isBlank()) {
            return config.jdbcUrlOverride.isBlank() ? "Enter the JDBC URL" : null;
        }
        return config.host.isBlank() ? "Enter the host" : null;
    }

    private static boolean sameSettings(@NotNull DbConfig a, @NotNull DbConfig b) {
        return com.intellij.openapi.util.JDOMUtil.areElementsEqual(
                com.intellij.util.xmlb.XmlSerializer.serialize(a), com.intellij.util.xmlb.XmlSerializer.serialize(b));
    }

    @Override
    protected @Nullable String getDimensionServiceKey() {
        return "IntellaDb.ConnectionDialog";
    }

    // ------------------------------------------------------------------ behaviour

    private void wireListeners() {
        dialectCombo.addActionListener(e -> {
            if (loading) {
                return;
            }
            portSpinner.setNumber(selectedDialect().defaultPort());
            if (selectedDialect().noAuthByDefault() && userField.getText().isBlank()) {
                authCombo.setSelectedIndex(1); // "No auth"
            }
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
            if (schemasLoaded && !loading) {
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
            if (!updatingUrl && !loading) {
                urlOverridden = !urlField.getText().trim().equals(generatedUrl());
                updateUrlHint();
            }
        });
        onChange(nameField, () -> {
            if (!updatingName && !loading) {
                nameEdited = !nameField.getText().isBlank();
            }
            if (!loading && current != null) { // the list shows the name as it is typed
                current.config.name = nameField.getText().trim();
                sourceList.repaint();
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
    }

    /** Fills the SSL mode dropdown from the selected dialect, keeping {@code keep} when it is offered. */
    private void fillSslModes(@Nullable String keep) {
        List<String> modes = selectedDialect().sslModes();
        sslModeCombo.setModel(new DefaultComboBoxModel<>(modes.toArray(String[]::new)));
        sslModeCombo.setSelectedItem(keep != null && modes.contains(keep) ? keep : modes.get(0));
    }

    /** Host, port or database changed: regenerate the URL (dropping a typed override) and the name. */
    private void fieldsChanged() {
        if (loading) {
            return;
        }
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
        resetSchemasOnScopeChange();
    }

    /**
     * Giving or clearing the database switches the Schemas tab between one database's
     * schemas and every database's {@code database.schema}: the picked ones no longer apply.
     */
    private void resetSchemasOnScopeChange() {
        DbConfig probe = new DbConfig();
        probe.dialectId = selectedDialect().id();
        probe.database = databaseText();
        probe.urlOnly = urlOnlyType.isSelected();
        probe.jdbcUrlOverride = probe.urlOnly || urlOverridden ? urlField.getText().trim() : "";
        boolean qualified = probe.allDatabases();
        if (qualified != schemasQualified) {
            schemasQualified = qualified;
            schemasLoaded = false;
            schemaList.clear();
            allSchemas.setSelected(true);
            schemaList.setEnabled(false);
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
        if (!loading) {
            resetSchemasOnScopeChange();
        }
    }

    /** Lists the server's databases into the Database dropdown (on first open). */
    private void loadDatabases() {
        DbConfig probe = snapshot();
        String typed = databaseText();
        if (probe.database.isBlank() && !probe.urlOnly) {
            probe.database = probe.dialect().maintenanceDatabase();
            probe.jdbcUrlOverride = "";
        }
        withProbe(probe, probe.dialect()::probeDatabases, names -> {
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
            showTestResult = null;
            testStatus.setIcon(AllIcons.General.Error);
            testStatus.setText("Could not list databases: " + error);
            testStatus.setForeground(JBUI.CurrentTheme.Label.errorForeground());
        });
    }

    /**
     * Lists the connected database's schemas with checkboxes; system schemas only on request.
     * Without a database (PostgreSQL), every database with its schemas beneath it.
     */
    private void loadSchemas() {
        schemasLoaded = true;
        DbConfig probe = snapshot();
        boolean qualified = probe.allDatabases();
        Set<String> checked = new HashSet<>(checkedSchemas());
        schemaStatus.setText(qualified ? "Loading every database's schemas…" : "Loading schemas…");
        DbDialect dialect = probe.dialect();
        withProbe(probe, (config, password) -> qualified ? dialect.probeDatabaseSchemas(config, password)
                : java.util.Map.of("", dialect.probeSchemaNames(config, password)), byDatabase -> {
            schemaList.clear();
            int hidden = 0;
            for (var entry : byDatabase.entrySet()) {
                for (String schema : entry.getValue()) {
                    String value = qualified ? DbConfig.qualify(entry.getKey(), schema) : schema;
                    boolean isSystem = dialect.isSystemSchema(schema);
                    if (isSystem && !showSystemSchemas.isSelected() && !checked.contains(value)) {
                        hidden++;
                        continue;
                    }
                    boolean selected = checked.isEmpty() ? !isSystem : checked.contains(value);
                    schemaList.add(qualified ? entry.getKey() : null,
                            new SchemaCheckTree.Item(value, schema, isSystem), selected);
                }
            }
            schemaList.reload();
            schemaStatus.setText(schemaList.schemaCount() == 0 && hidden > 0
                    ? "The server has only system schemas (" + hidden + "); tick \"Show internal system schemas\" to list them."
                    : " ");
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
        hideTestBalloon();
        showTestResult = null;
        testStatus.setIcon(AnimatedIcon.Default.INSTANCE);
        testStatus.setText("Testing…");
        testStatus.setForeground(UIUtil.getContextHelpForeground());
        testLink.setEnabled(false);
        withProbe(probe, probe.dialect()::testConnection, report -> {
            testStatus.setIcon(AllIcons.General.InspectionsOK);
            testStatus.setText(report.summary());
            testStatus.setForeground(UIUtil.getContextHelpForeground());
            testLink.setEnabled(true);
            showTestResult = () -> showTestBalloon(true, report.lines(), report.text());
            showTestResult.run();
        }, error -> {
            testStatus.setIcon(AllIcons.General.Error);
            testStatus.setText(error.length() > 80 ? error.substring(0, 80) + "…" : error);
            testStatus.setForeground(JBUI.CurrentTheme.Label.errorForeground());
            testLink.setEnabled(true);
            showTestResult = () -> showTestBalloon(false, List.of(error), error);
            showTestResult.run();
        });
    }

    /** The "Succeeded" / "Failed" balloon above the test status, with a Copy link. */
    private void showTestBalloon(boolean succeeded, @NotNull List<String> lines, @NotNull String copyText) {
        hideTestBalloon();
        JBLabel title = new JBLabel(succeeded ? "Succeeded" : "Failed");
        title.setFont(title.getFont().deriveFont(java.awt.Font.BOLD));
        title.setForeground(succeeded ? new JBColor(0x208A3C, 0x5FB865) : JBUI.CurrentTheme.Label.errorForeground());
        ActionLink copy = new ActionLink("Copy");
        copy.addActionListener(e -> {
            CopyPasteManager.getInstance().setContents(new StringSelection(copyText));
            copy.setText("Copied");
        });
        JPanel header = new JPanel(new BorderLayout(JBUI.scale(40), 0));
        header.setOpaque(false);
        header.add(title, BorderLayout.WEST);
        header.add(copy, BorderLayout.EAST);

        // Errors can be long: wrap them at a fixed width instead of one very wide line.
        StringBuilder html = new StringBuilder(succeeded ? "<html>" : "<html><body style='width:" + JBUI.scale(360) + "px'>");
        for (String line : lines) {
            html.append(line == null ? "" : StringUtil.escapeXmlEntities(line)).append("<br>");
        }
        JBLabel body = new JBLabel(html.toString());

        JPanel content = new JPanel(new BorderLayout(0, JBUI.scale(12)));
        content.setOpaque(false);
        content.add(header, BorderLayout.NORTH);
        content.add(body, BorderLayout.CENTER);

        testBalloon = JBPopupFactory.getInstance().createBalloonBuilder(content)
                .setFillColor(UIUtil.getListBackground())
                .setBorderColor(JBColor.border())
                .setBorderInsets(JBUI.insets(10, 14))
                .setHideOnClickOutside(true)
                .setHideOnKeyOutside(true)
                .setHideOnAction(false)
                .setAnimationCycle(0)
                .setDisposable(getDisposable())
                .createBalloon();
        testBalloon.show(new RelativePoint(testStatus, new Point(JBUI.scale(8), 0)), Balloon.Position.above);
    }

    private void hideTestBalloon() {
        if (testBalloon != null) {
            testBalloon.hide();
            testBalloon = null;
        }
    }

    /** Asks the server something over a throwaway connection (see the dialect's probe methods). */
    private interface ProbeQuery<T> {
        T run(@NotNull DbConfig probe, @Nullable String password) throws Exception;
    }

    /**
     * Runs a query over a throwaway connection with the dialog's current settings on a pooled
     * thread and hands the result (or error message) back on the EDT.
     */
    private <T> void withProbe(@NotNull DbConfig probe, @NotNull ProbeQuery<T> query,
                               @NotNull Consumer<T> onSuccess, @NotNull Consumer<String> onError) {
        String password = probe.noAuth ? null : new String(passwordField.getPassword());
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            T result = null;
            String error = null;
            try {
                result = query.run(probe, password);
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
        return schemaList.checked();
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
