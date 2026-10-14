package dev.phucngu.intelladb.ui;

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileTypes.PlainTextFileType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.LightVirtualFile;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import dev.phucngu.intelladb.IntellaDbIcons;
import dev.phucngu.intelladb.connection.ConnectionManager;
import dev.phucngu.intelladb.connection.DbConfig;
import dev.phucngu.intelladb.connection.DbSession;
import dev.phucngu.intelladb.schema.SchemaCatalog;
import dev.phucngu.intelladb.schema.TableMeta;
import dev.phucngu.intelladb.ui.actions.explorer.AddDataSourceGroup;
import dev.phucngu.intelladb.ui.actions.explorer.DisconnectAction;
import dev.phucngu.intelladb.ui.actions.explorer.EditDataAction;
import dev.phucngu.intelladb.ui.actions.explorer.GoToDdlAction;
import dev.phucngu.intelladb.ui.actions.explorer.OpenConsoleAction;
import dev.phucngu.intelladb.ui.actions.explorer.OpenDataSourcesDialogAction;
import dev.phucngu.intelladb.ui.actions.explorer.RefreshConnectionAction;
import dev.phucngu.intelladb.ui.actions.explorer.ViewOptionsAction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.List;
import java.util.function.Consumer;

/**
 * "Explorer" tab of the DB Explorer tool window: the connection tree, full width, with
 * database actions only — IntelliJ's grammar of [+ ▾ · Properties… | Refresh · Disconnect |
 * Console · Edit Data · Go to DDL | Eye ▾]. The AI chat is a sibling tab of the tool window
 * (not a split inside this panel); consoles and data grids open as editor tabs.
 */
public final class DbExplorerPanel extends SimpleToolWindowPanel implements Disposable {

    private static final Key<String> DDL_CONFIG_KEY = Key.create("IntellaDb.Ddl.configId");

    private final Project project;
    private final ConnectionManager manager;
    private final ConnectionTreePanel treePanel;
    /** Owned here so its lifetime follows the explorer; shown as its own tool-window tab. */
    private final AiChatPanel aiPanel;
    private final JBScrollPane treeScroll;
    private final JComponent emptyPanel;

    public DbExplorerPanel(@NotNull Project project) {
        super(true, true);
        this.project = project;
        this.manager = ConnectionManager.getInstance(project);
        this.treePanel = new ConnectionTreePanel(project, this);
        this.aiPanel = new AiChatPanel(project);
        this.treeScroll = new JBScrollPane(treePanel.tree());
        this.treeScroll.setBorder(JBUI.Borders.empty());
        this.emptyPanel = createEmptyPanel();

        setToolbar(createToolbar().getComponent());
        updateEmptyState();

        manager.addListener(this::refreshTree);
        Disposer.register(this, treePanel);
    }

    /**
     * IntelliJ's toolbar grammar: [+ New] [Properties] | [Refresh] [Disconnect] |
     * [Console] [Edit Data] [DDL] | [Eye options] — four icon clusters, everything
     * else lives in the tree's context menus.
     */
    private ActionToolbar createToolbar() {
        DefaultActionGroup group = new DefaultActionGroup();
        group.add(new AddDataSourceGroup(project, treePanel));
        group.add(new OpenDataSourcesDialogAction(project, treePanel));
        group.addSeparator();
        group.add(new RefreshConnectionAction(treePanel));
        group.add(new DisconnectAction(treePanel));
        group.addSeparator();
        group.add(new OpenConsoleAction(treePanel));
        group.add(new EditDataAction(treePanel));
        group.add(new GoToDdlAction(treePanel));
        group.addSeparator();
        group.add(new ViewOptionsAction(project, treePanel));
        ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar("IntellaDbExplorer", group, true);
        toolbar.setTargetComponent(this);
        return toolbar;
    }

    // ------------------------------------------------------------------ onboarding

    /**
     * IntelliJ's tool window is useful before any configuration: with no data sources the
     * tree gives way to quick-create buttons, one per dialect, plus the full dialog.
     */
    private @NotNull JComponent createEmptyPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(JBUI.Borders.empty(16, 20));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.anchor = GridBagConstraints.LINE_START;
        gbc.insets = JBUI.insetsBottom(4);

        JBLabel title = new JBLabel("No data sources");
        title.setFont(UIUtil.getLabelFont().deriveFont(Font.BOLD, JBUI.scaleFontSize(15f)));
        panel.add(title, gbc);
        gbc.gridy++;
        JBLabel subtitle = new JBLabel("Connect a database to browse its schema, run SQL, and edit data.");
        subtitle.setForeground(UIUtil.getContextHelpForeground());
        subtitle.setBorder(JBUI.Borders.emptyBottom(8));
        panel.add(subtitle, gbc);

        gbc.insets = JBUI.insets(1, 0);
        for (var dialect : dev.phucngu.intelladb.connection.Dialects.all()) {
            String dialectId = dialect.id();
            JButton button = new JButton(dialect.displayName(), IntellaDbIcons.connection(dialectId, false));
            button.setToolTipText("New " + dialect.displayName() + " connection");
            button.putClientProperty("JButton.buttonType", "toolBarButton");
            button.setBorder(JBUI.Borders.empty(4, 8));
            button.addActionListener(event -> treePanel.addConnection(dialectId));
            gbc.gridy++;
            panel.add(button, gbc);
        }
        JButton all = new JButton("Data Sources…");
        all.setToolTipText("Open the Data Sources dialog");
        all.setBorder(JBUI.Borders.empty(4, 8));
        all.addActionListener(event -> treePanel.showDataSourcesDialog(null, null));
        gbc.gridy++;
        gbc.insets = JBUI.insets(10, 0, 0, 0);
        panel.add(all, gbc);
        return panel;
    }

    /** Shows the onboarding panel instead of the tree while there is nothing to browse. */
    private void updateEmptyState() {
        setContent(manager.configs().isEmpty() ? emptyPanel : treeScroll);
    }

    // ------------------------------------------------------------------ tab management

    /** Every live console by its {@link ConsoleStore} id; they live on after their tabs close. */
    private final java.util.Map<String, SqlConsole> consoles = new java.util.HashMap<>();

    /**
     * Opens (or focuses) the connection's default console — the first one it owns — as an
     * editor tab, the way IntelliJ's database tools do it: a text editor with a console
     * toolbar, results in the DB Services tool window.
     */
    public @NotNull SqlConsole openConsole(@NotNull DbConfig config) {
        return openConsole(config, null);
    }

    /** As {@link #openConsole(DbConfig)}; a non-null {@code sql} replaces the console text. */
    public @NotNull SqlConsole openConsole(@NotNull DbConfig config, @Nullable String sql) {
        ConsoleStore store = ConsoleStore.getInstance(project);
        java.util.List<ConsoleStore.ConsoleState> states = store.ofConnection(config.id);
        ConsoleStore.ConsoleState state = states.isEmpty() ? store.create(config.id) : states.get(0);
        return openConsoleState(config, state, sql);
    }

    /**
     * As {@link #openConsole(DbConfig, String)}; a non-null {@code schema} becomes the console's
     * default schema, and a non-null {@code database} its database when the connection
     * browses every database.
     */
    public @NotNull SqlConsole openConsole(@NotNull DbConfig config, @Nullable String sql, @Nullable String database,
                                           @Nullable String schema) {
        ConsoleStore store = ConsoleStore.getInstance(project);
        java.util.List<ConsoleStore.ConsoleState> states = store.ofConnection(config.id);
        ConsoleStore.ConsoleState state = states.isEmpty() ? store.create(config.id) : states.get(0);
        return openConsole(config, state, sql, database, schema, true);
    }

    /** Opens (or focuses) one of the connection's saved consoles. */
    public @NotNull SqlConsole openConsoleState(@NotNull DbConfig config,
                                                @NotNull ConsoleStore.ConsoleState state) {
        return openConsoleState(config, state, null);
    }

    private @NotNull SqlConsole openConsoleState(@NotNull DbConfig config,
                                                 @NotNull ConsoleStore.ConsoleState state,
                                                 @Nullable String sql) {
        return openConsole(config, state, sql, null, null, true);
    }

    /** Opens a brand-new named console for the connection, optionally scoped to a schema. */
    public @NotNull SqlConsole newConsole(@NotNull DbConfig config, @Nullable String database,
                                          @Nullable String schema) {
        return openConsole(config, ConsoleStore.getInstance(project).create(config.id),
                null, database, schema, true);
    }

    /** Reopens (without focus) the console tabs that were open when the IDE last closed. */
    public void restoreConsoles() {
        ConsoleStore store = ConsoleStore.getInstance(project);
        for (String id : store.openConsoles()) {
            ConsoleStore.ConsoleState state = store.find(id);
            DbConfig config = state == null ? null : manager.findConfig(state.connectionId);
            if (config != null) {
                openConsole(config, state, null, null, null, false);
            }
        }
    }

    private @NotNull SqlConsole openConsole(@NotNull DbConfig config, @NotNull ConsoleStore.ConsoleState state,
                                            @Nullable String sql, @Nullable String database,
                                            @Nullable String schema, boolean focus) {
        SqlConsole console = consoles.computeIfAbsent(state.id, id -> {
            SqlConsole created = new SqlConsole(project, this, config, state.id, state.name);
            Disposer.register(this, created);
            return created;
        });
        if (sql != null) {
            console.setSql(sql);
        }
        if (database != null || schema != null) {
            console.setSchema(database, schema);
        }
        var editors = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
                .openFile(console.file(), focus);
        for (var editor : editors) {
            console.installHeader(editor); // idempotent; the file listener covers later reopens
            if (editor instanceof com.intellij.openapi.fileEditor.TextEditor textEditor) {
                SqlConsole.caretAtFreshLine(project, textEditor.getEditor());
            }
        }
        return console;
    }

    /** The explorer of the project's DB Explorer tool window, or null if it was never opened. */
    public static @Nullable DbExplorerPanel find(@NotNull Project project) {
        var toolWindow = com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
                .getToolWindow(DbToolWindowFactory.TOOL_WINDOW_ID);
        if (toolWindow == null) {
            return null;
        }
        for (var content : toolWindow.getContentManager().getContents()) {
            if (content.getComponent() instanceof DbExplorerPanel explorer) {
                return explorer;
            }
        }
        return null;
    }

    /** Selects the AI Assistant tab of the tool window (the chat itself always exists). */
    public void openAiAssistant() {
        var toolWindow = com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
                .getToolWindow(DbToolWindowFactory.TOOL_WINDOW_ID);
        if (toolWindow == null) {
            return;
        }
        var contents = toolWindow.getContentManager();
        for (var content : contents.getContents()) {
            if (content.getComponent() == aiPanel) {
                contents.setSelectedContent(content, true);
            }
        }
    }

    public @NotNull AiChatPanel aiPanel() {
        return aiPanel;
    }

    /** Opens a data-preview editor tab for the table and loads its rows. */
    public void openTableData(@NotNull TableRef table) {
        com.intellij.testFramework.LightVirtualFile file = new com.intellij.testFramework.LightVirtualFile(
                table.name() + " @" + table.config().name,
                com.intellij.openapi.fileTypes.PlainTextFileType.INSTANCE, "");
        ResultsPanel panel = new ResultsPanel(project, new ResultsPanel.Host() {
            @Override
            public void rerun(@NotNull ResultsPanel target) {
                table.showIn(DbExplorerPanel.this, target);
            }

            @Override
            public void runQuery(@NotNull ResultsPanel target, @NotNull String sql) {
                table.showSql(DbExplorerPanel.this, target, sql);
            }

            @Override
            public boolean isTablePreview() {
                return true;
            }

            @Override
            public @Nullable String baseQuery() {
                return table.baseQuery();
            }

            @Override
            public @NotNull DbConfig config() {
                return table.config();
            }

            @Override
            public void cancel() {
                DbSession session = sessionOf(table.config());
                if (session != null) {
                    ApplicationManager.getApplication().executeOnPooledThread(session::cancel);
                }
            }
        }, false);
        panel.setSourceTable(table.qualifiedName());
        TableEditorPanel editor = new TableEditorPanel(project, this, table, panel);
        Disposer.register(this, editor);
        IntellaDbFileEditorProvider.attach(file, () -> editor);
        com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFile(file, true);
        panel.openInitial(); // page one, at the panel's page size
    }

    /**
     * Opens the table's DDL in a read-only editor tab — IntelliJ's "Go to DDL". A tab
     * already open for the same table is focused and its text refreshed.
     */
    public void openDdl(@NotNull TableRef table) {
        String ddl = table.config().dialect().describeSchema(new SchemaCatalog(List.of(
                new SchemaCatalog.Schema(table.schema(), List.of(table.meta())))));
        String title = table.schema() + "." + table.name() + ".ddl";
        FileEditorManager editors = FileEditorManager.getInstance(project);
        for (VirtualFile open : editors.getOpenFiles()) {
            if (title.equals(open.getName()) && table.config().id.equals(open.getUserData(DDL_CONFIG_KEY))) {
                editors.openFile(open, true);
                setDdlText(open, ddl);
                return;
            }
        }
        LightVirtualFile file = new LightVirtualFile(title, PlainTextFileType.INSTANCE, ddl);
        file.putUserData(DDL_CONFIG_KEY, table.config().id);
        editors.openFile(file, true);
        setDdlText(file, ddl);
    }

    private static void setDdlText(@NotNull VirtualFile file, @NotNull String ddl) {
        Document document = FileDocumentManager.getInstance().getDocument(file);
        if (document == null || ddl.equals(document.getText())) {
            return;
        }
        document.setReadOnly(false);
        WriteCommandAction.runWriteCommandAction(null, () -> document.setText(ddl));
        document.setReadOnly(true);
    }

    // ------------------------------------------------------------------ helpers for children

    /** Connects (prompting for a password if needed) and then runs {@code action} on the EDT. */
    public void withSession(@NotNull DbConfig config, @NotNull Consumer<DbSession> action) {
        dev.phucngu.intelladb.connection.SessionOpener.getInstance(project).withSession(config, action);
    }

    public @NotNull ConnectionTreePanel tree() {
        return treePanel;
    }

    public @NotNull ConnectionManager manager() {
        return manager;
    }

    public @NotNull Project project() {
        return project;
    }

    public @Nullable DbConfig selectedConfig() {
        return treePanel.selectedConfig();
    }

    public @Nullable TableRef selectedTable() {
        return treePanel.selectedTable();
    }

    public @Nullable DbSession sessionOf(@Nullable DbConfig config) {
        return config == null ? null : manager.session(config.id);
    }

    public void refreshTree() {
        if (project.isDisposed()) {
            return; // change listeners can fire while the project is shutting down
        }
        treePanel.rebuild();
        updateEmptyState();
    }

    @Override
    public void dispose() {
        aiPanel.dispose();
        manager.disconnectAll();
    }
}
