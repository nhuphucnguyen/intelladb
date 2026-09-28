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
import com.intellij.ui.JBSplitter;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTabbedPane;
import community.intelladb.IntellaDbIcons;
import community.intelladb.connection.ConnectionManager;
import community.intelladb.connection.DbConfig;
import community.intelladb.connection.DbSession;
import community.intelladb.schema.TableMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.BorderFactory;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.util.function.Consumer;

/**
 * Main "DB Explorer" panel: connection tree on the left, work tabs (SQL console,
 * table data, AI assistant) on the right.
 */
public final class DbExplorerPanel extends SimpleToolWindowPanel implements Disposable {

    private final Project project;
    private final ConnectionManager manager;
    private final ConnectionTreePanel treePanel;
    private JBTabbedPane tabs;

    public DbExplorerPanel(@NotNull Project project) {
        super(true, true);
        this.project = project;
        this.manager = ConnectionManager.getInstance(project);
        this.treePanel = new ConnectionTreePanel(project, this);

        setToolbar(createToolbar().getComponent());

        JBSplitter splitter = new JBSplitter(false, 0.32f);
        splitter.setFirstComponent(new JBScrollPane(treePanel.tree()));
        splitter.setSecondComponent(buildRightPane());
        setContent(splitter);

        manager.addListener(this::refreshTree);
        Disposer.register(this, treePanel);
    }

    private javax.swing.JComponent buildRightPane() {
        tabs = new JBTabbedPane(SwingConstants.TOP);
        tabs.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        tabs.addChangeListener(e -> tabs.repaint()); // redraw header fill for the new selection
        tabs.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(@NotNull java.awt.event.MouseEvent e) {
                // middle-click closes the tab under the pointer, like editor tabs
                if (e.getButton() == java.awt.event.MouseEvent.BUTTON2) {
                    int index = tabs.indexAtLocation(e.getX(), e.getY());
                    if (index >= 0) {
                        closeTab(tabs.getComponentAt(index));
                    }
                }
            }
        });
        javax.swing.JPanel right = new javax.swing.JPanel(new BorderLayout());
        right.add(tabs, BorderLayout.CENTER);
        return right;
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
        group.addSeparator();
        group.add(new AnAction("AI Assistant", "Open the AI Assistant tool window", IntellaDbIcons.AI) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                AiChatPanel.openWithDraft(project, "");
            }
        });
        group.add(new AnAction("AI Provider Settings", "Configure the AI provider (provider, key, model)",
                AllIcons.General.Settings) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                        .showSettingsDialog(project, "community.intelladb.ai.provider");
            }
        });
        ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar("IntellaDbExplorer", group, true);
        toolbar.setTargetComponent(this);
        return toolbar;
    }

    // ------------------------------------------------------------------ tab management

    /** Opens (or focuses) a SQL console tab for the connection. */
    public @Nullable ConsolePanel openConsole(@NotNull DbConfig config) {
        ConsolePanel existing = findConsole(config);
        if (existing == null) {
            existing = new ConsolePanel(project, this, config);
            addClosableTab("Console — " + config.name, AllIcons.Nodes.Console, existing);
        }
        tabs.setSelectedComponent(existing);
        return existing;
    }

    /**
     * Adds a tab whose header carries a close (×) button — without it the work tabs
     * (console, data preview) could never be closed. Each header is drawn as a bordered
     * pill, and the selected tab gets a filled background, so tabs read as separate
     * clickable units.
     */
    private void addClosableTab(@NotNull String title, @NotNull javax.swing.Icon icon,
                                @NotNull javax.swing.JComponent component) {
        tabs.addTab(title, icon, component);
        javax.swing.JPanel header = new javax.swing.JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0)) {
            @Override
            protected void paintComponent(@NotNull java.awt.Graphics g) {
                if (tabs.getSelectedIndex() == tabs.indexOfComponent(component)) {
                    java.awt.Graphics2D gr = (java.awt.Graphics2D) g.create();
                    gr.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                            java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                    gr.setColor(com.intellij.util.ui.JBUI.CurrentTheme.DefaultTabs.background());
                    gr.fillRoundRect(1, 1, getWidth() - 2, getHeight() - 2, 12, 12);
                    gr.dispose();
                }
                super.paintComponent(g);
            }
        };
        header.setOpaque(false);
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(com.intellij.ui.JBColor.border(), 1, true),
                com.intellij.util.ui.JBUI.Borders.empty(3, 8)));
        header.setToolTipText("Middle-click closes the tab");
        header.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(@NotNull java.awt.event.MouseEvent e) {
                tabs.setSelectedComponent(component);
            }
        });
        header.add(new JBLabel(title, icon, javax.swing.SwingConstants.LEFT));
        javax.swing.JButton close = new javax.swing.JButton(AllIcons.Actions.Close);
        close.setBorder(com.intellij.util.ui.JBUI.Borders.empty(2, 4));
        close.setContentAreaFilled(false);
        close.setFocusable(false);
        close.setToolTipText("Close tab (middle-click also works)");
        close.addActionListener(e -> closeTab(component));
        header.add(close);
        tabs.setTabComponentAt(tabs.indexOfComponent(component), header);
    }

    /** Opens (or focuses) a data-preview tab for the table and (re)loads its rows. */
    public void openTableData(@NotNull TableRef table) {
        String title = "Data — " + table.schema() + "." + table.name();
        ResultsPanel panel = findResultsTab(title);
        if (panel == null) {
            panel = new ResultsPanel(project);
            addClosableTab(title, IntellaDbIcons.TABLE, panel);
        }
        tabs.setSelectedComponent(panel);
        table.showIn(this, panel);
    }

    /** Closes the given (or the selected) tab. */
    public void closeTab(@Nullable java.awt.Component component) {
        int index = component != null ? tabs.indexOfComponent(component) : tabs.getSelectedIndex();
        if (index >= 0) {
            tabs.removeTabAt(index);
        }
    }

    private @Nullable ConsolePanel findConsole(@NotNull DbConfig config) {
        for (int i = 0; i < tabs.getTabCount(); i++) {
            if (tabs.getComponentAt(i) instanceof ConsolePanel console && console.config().id.equals(config.id)) {
                return console;
            }
        }
        return null;
    }

    private @Nullable ResultsPanel findResultsTab(@NotNull String title) {
        for (int i = 0; i < tabs.getTabCount(); i++) {
            if (title.equals(tabs.getTitleAt(i)) && tabs.getComponentAt(i) instanceof ResultsPanel panel) {
                return panel;
            }
        }
        return null;
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
        treePanel.rebuild();
    }

    @Override
    public void dispose() {
        manager.disconnectAll();
    }
}
