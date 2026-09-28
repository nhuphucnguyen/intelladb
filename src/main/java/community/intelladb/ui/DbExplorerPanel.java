package community.intelladb.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import community.intelladb.connection.ConnectionManager;
import community.intelladb.connection.DbConfig;
import community.intelladb.connection.DbSession;
import community.intelladb.schema.TableMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/**
 * "Explorer" tab of the DB Explorer tool window: the connection tree, full width, with
 * database actions only. The AI chat is a sibling tab of the tool window (not a split
 * inside this panel); consoles and data grids open as editor tabs.
 */
public final class DbExplorerPanel extends SimpleToolWindowPanel implements Disposable {

    private final Project project;
    private final ConnectionManager manager;
    private final ConnectionTreePanel treePanel;
    /** Owned here so its lifetime follows the explorer; shown as its own tool-window tab. */
    private final AiChatPanel aiPanel;

    public DbExplorerPanel(@NotNull Project project) {
        super(true, true);
        this.project = project;
        this.manager = ConnectionManager.getInstance(project);
        this.treePanel = new ConnectionTreePanel(project, this);
        this.aiPanel = new AiChatPanel(project);

        setToolbar(createToolbar().getComponent());

        JBScrollPane treeScroll = new JBScrollPane(treePanel.tree());
        treeScroll.setBorder(JBUI.Borders.empty());
        setContent(treeScroll);

        manager.addListener(this::refreshTree);
        Disposer.register(this, treePanel);
    }

    private ActionToolbar createToolbar() {
        DefaultActionGroup group = new DefaultActionGroup();
        group.add(new AnAction("Add Connection", "Add a new database connection", AllIcons.General.Add) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                treePanel.addConnection();
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }
        });
        group.add(new AnAction("Connect", "Connect the selected connection", AllIcons.Actions.Execute) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                treePanel.connectSelected();
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }

            @Override
            public void update(@NotNull AnActionEvent e) {
                e.getPresentation().setEnabled(treePanel.selectedConfig() != null);
            }
        });
        group.add(new AnAction("Disconnect", "Disconnect the selected connection", AllIcons.Actions.Suspend) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                treePanel.disconnectSelected();
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }

            @Override
            public void update(@NotNull AnActionEvent e) {
                e.getPresentation().setEnabled(treePanel.selectedConfig() != null);
            }
        });
        group.addSeparator();
        group.add(new AnAction("Refresh", "Reload the schema of the selected connection", AllIcons.Actions.Refresh) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                treePanel.refreshSelected();
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }

            @Override
            public void update(@NotNull AnActionEvent e) {
                e.getPresentation().setEnabled(treePanel.selectedConfig() != null);
            }
        });
        group.addSeparator();
        group.add(new AnAction("SQL Console", "Open a SQL console for the selected connection", AllIcons.Nodes.Console) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                openConsole(selectedConfig());
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }

            @Override
            public void update(@NotNull AnActionEvent e) {
                e.getPresentation().setEnabled(selectedConfig() != null);
            }
        });
        group.add(new AnAction("View Data", "Preview the first rows of the selected table", AllIcons.Actions.Preview) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                TableRef table = treePanel.selectedTable();
                if (table != null) {
                    openTableData(table);
                }
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }

            @Override
            public void update(@NotNull AnActionEvent e) {
                e.getPresentation().setEnabled(treePanel.selectedTable() != null);
            }
        });
        // AI actions (new chat, provider settings) live in the AI Assistant tab's own toolbar.
        ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar("IntellaDbExplorer", group, true);
        toolbar.setTargetComponent(this);
        return toolbar;
    }

    // ------------------------------------------------------------------ tab management

    /** One console per connection; it lives on (text included) after its tab is closed. */
    private final java.util.Map<String, SqlConsole> consoles = new java.util.HashMap<>();

    /**
     * Opens (or focuses) the SQL console for the connection as an editor tab — the way
     * IntelliJ's database tools do it: a text editor with a console toolbar, results in
     * the DB Services tool window.
     */
    public @NotNull SqlConsole openConsole(@NotNull DbConfig config) {
        return openConsole(config, null);
    }

    /** As {@link #openConsole(DbConfig)}; a non-null {@code sql} replaces the console text. */
    public @NotNull SqlConsole openConsole(@NotNull DbConfig config, @Nullable String sql) {
        SqlConsole console = consoles.computeIfAbsent(config.id, id -> {
            SqlConsole created = new SqlConsole(project, this, config);
            Disposer.register(this, created);
            return created;
        });
        if (sql != null) {
            console.setSql(sql);
        }
        var editors = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
                .openFile(console.file(), true);
        for (var editor : editors) {
            console.installHeader(editor); // idempotent; the file listener covers later reopens
        }
        return console;
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
            public void cancel() {
                DbSession session = sessionOf(table.config());
                if (session != null) {
                    ApplicationManager.getApplication().executeOnPooledThread(session::cancel);
                }
            }
        }, false);
        panel.setSourceTable(table.qualifiedName());
        IntellaDbFileEditorProvider.attach(file, () -> panel);
        com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFile(file, true);
        table.showIn(this, panel);
    }

    // ------------------------------------------------------------------ helpers for children

    /** Connects (prompting for a password if needed) and then runs {@code action} on the EDT. */
    public void withSession(@NotNull DbConfig config, @NotNull Consumer<DbSession> action) {
        community.intelladb.connection.SessionOpener.getInstance(project).withSession(config, action);
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
    }

    @Override
    public void dispose() {
        aiPanel.dispose();
        manager.disconnectAll();
    }
}
