package community.intelladb.ui;

import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.ide.DataManager;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.treeStructure.Tree;
import community.intelladb.IntellaDbIcons;
import community.intelladb.connection.ConnectionManager;
import community.intelladb.connection.DbConfig;
import community.intelladb.connection.DbSession;
import community.intelladb.schema.DdlGenerator;
import community.intelladb.schema.SchemaCatalog;
import community.intelladb.schema.TableMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import javax.swing.JPanel;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Connection/schema tree with context actions. */
public final class ConnectionTreePanel implements Disposable {

    /** Selection payloads (tree user objects). */
    public record ConfigEntry(DbConfig config) {
    }

    public record SchemaEntry(DbConfig config, String name) {
    }

    public record TableEntry(DbConfig config, String schema, TableMeta meta) {
    }

    public record ColumnEntry(DbConfig config, String schema, String table, String name, String type, boolean pk) {
    }

    private final Project project;
    private final DbExplorerPanel explorer;
    private final ConnectionManager manager;
    private final JPanel wrapper = new JPanel(new BorderLayout());
    private final JBLabel hint = new JBLabel("  No connections yet — use + to add one");
    private final Tree tree;
    private DefaultTreeModel model;
    /** configId -> "connecting" | "error:<message>"; connected state is derived from the manager. */
    private final Map<String, String> transientState = new HashMap<>();

    public ConnectionTreePanel(@NotNull Project project, @NotNull DbExplorerPanel explorer) {
        this.project = project;
        this.explorer = explorer;
        this.manager = ConnectionManager.getInstance(project);

        model = new DefaultTreeModel(new DefaultMutableTreeNode());
        tree = new Tree(model);
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setCellRenderer(new Renderer());
        tree.getEmptyText().setText("No connections yet — click + to add one");
        tree.addMouseListener(new MouseHandler());

        fill();
        wrapper.add(tree, BorderLayout.CENTER);
        wrapper.add(hint, BorderLayout.SOUTH);
    }

    public @NotNull Tree tree() {
        return tree;
    }

    public void setHint(@NotNull String text) {
        hint.setText("  " + text);
    }

    // ------------------------------------------------------------------ model building

    private void fill() {
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) model.getRoot();
        root.removeAllChildren();
        for (DbConfig config : manager.configs()) {
            DefaultMutableTreeNode configNode = new DefaultMutableTreeNode(new ConfigEntry(config));
            root.add(configNode);
            DbSession session = manager.session(config.id);
            String state = transientState.get(config.id);
            if (session != null && session.catalog() != null) {
                appendCatalog(configNode, config, session.catalog());
            } else if ("connecting".equals(state)) {
                configNode.add(new DefaultMutableTreeNode("connecting…", false));
            } else if (state != null && state.startsWith("error:")) {
                configNode.add(new DefaultMutableTreeNode("failed: " + state.substring(6), false));
            }
        }
        model.reload();
        updateHint();
    }

    private void appendCatalog(@NotNull DefaultMutableTreeNode configNode, @NotNull DbConfig config,
                               @NotNull SchemaCatalog catalog) {
        for (SchemaCatalog.Schema schema : catalog.schemas()) {
            DefaultMutableTreeNode schemaNode = new DefaultMutableTreeNode(new SchemaEntry(config, schema.name()));
            configNode.add(schemaNode);
            for (TableMeta table : schema.tables()) {
                DefaultMutableTreeNode tableNode =
                        new DefaultMutableTreeNode(new TableEntry(config, schema.name(), table));
                schemaNode.add(tableNode);
                for (community.intelladb.schema.ColumnMeta column : table.columns) {
                    tableNode.add(new DefaultMutableTreeNode(new ColumnEntry(
                            config, schema.name(), table.name, column.name, column.typeName, column.primaryKey)));
                }
            }
        }
    }

    /** Rebuilds the tree, preserving expansion and (best effort) selection. */
    public void rebuild() {
        List<String> expanded = new ArrayList<>();
        Enumeration<TreePath> expandedPaths = tree.getExpandedDescendants(new TreePath(model.getRoot()));
        if (expandedPaths != null) {
            while (expandedPaths.hasMoreElements()) {
                expanded.add(keyOf(expandedPaths.nextElement()));
            }
        }
        Object selected = selectedEntry();
        fill();
        for (String key : expanded) {
            DefaultMutableTreeNode node = findByKey(key);
            if (node != null) {
                tree.expandPath(new TreePath(node.getPath()));
            }
        }
        if (selected != null) {
            for (int i = 0; i < tree.getRowCount(); i++) {
                TreePath path = tree.getPathForRow(i);
                if (selected.equals(path.getLastPathComponent())) {
                    tree.setSelectionPath(path);
                    break;
                }
            }
        }
    }

    private static @NotNull String keyOf(@NotNull TreePath path) {
        StringBuilder sb = new StringBuilder();
        for (Object element : path.getPath()) {
            if (element instanceof DefaultMutableTreeNode node) {
                switch (node.getUserObject()) {
                    case ConfigEntry c -> sb.append("/c:").append(c.config().id);
                    case SchemaEntry s -> sb.append("/s:").append(s.name());
                    case TableEntry t -> sb.append("/t:").append(t.meta().name);
                    default -> {
                    }
                }
            }
        }
        return sb.toString();
    }

    private @Nullable DefaultMutableTreeNode findByKey(@NotNull String key) {
        Enumeration<?> depthFirst = ((DefaultMutableTreeNode) model.getRoot()).depthFirstEnumeration();
        while (depthFirst.hasMoreElements()) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) depthFirst.nextElement();
            if (keyOf(new TreePath(node.getPath())).equals(key)) {
                return node;
            }
        }
        return null;
    }

    private void updateHint() {
        List<DbConfig> configs = manager.configs();
        if (configs.isEmpty()) {
            setHint("No connections yet — use + to add one");
        } else {
            long connected = configs.stream().filter(c -> manager.session(c.id) != null).count();
            setHint(configs.size() + " connection(s) · " + connected + " connected");
        }
    }

    // ------------------------------------------------------------------ selection helpers

    private @Nullable Object selectedEntry() {
        TreePath path = tree.getSelectionPath();
        return path == null ? null : ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
    }

    public @Nullable DbConfig selectedConfig() {
        TreePath path = tree.getSelectionPath();
        if (path == null) {
            return null;
        }
        for (Object element : path.getPath()) {
            if (element instanceof DefaultMutableTreeNode node
                    && node.getUserObject() instanceof ConfigEntry config) {
                return config.config();
            }
        }
        return null;
    }

    public @Nullable TableRef selectedTable() {
        Object entry = selectedEntry();
        if (entry instanceof TableEntry table) {
            return new TableRef(table.config(), table.schema(), table.meta());
        }
        return null;
    }

    // ------------------------------------------------------------------ state

    public void setConnecting(@NotNull DbConfig config, boolean connecting) {
        if (connecting) {
            transientState.put(config.id, "connecting");
        } else {
            transientState.remove(config.id);
        }
        rebuild();
    }

    public void setError(@NotNull DbConfig config, @NotNull String message) {
        transientState.put(config.id, "error:" + message);
        rebuild();
    }

    // ------------------------------------------------------------------ actions

    public void addConnection() {
        ConnectionDialog dialog = new ConnectionDialog(project, null);
        if (dialog.showAndGet()) {
            explorer.refreshTree();
        }
    }

    private void editConnection(@NotNull DbConfig config) {
        ConnectionDialog dialog = new ConnectionDialog(project, config);
        if (dialog.showAndGet()) {
            // Settings may have changed; force a fresh session next time.
            manager.disconnect(config.id);
            explorer.refreshTree();
        }
    }

    private void deleteConnection(@NotNull DbConfig config) {
        int answer = Messages.showYesNoDialog(project,
                "Delete connection '" + config.name + "'?", "Intella DB", Messages.getQuestionIcon());
        if (answer == Messages.YES) {
            manager.deleteConfig(config.id);
            explorer.refreshTree();
        }
    }

    public void connectSelected() {
        DbConfig config = selectedConfig();
        if (config != null) {
            connect(config);
        }
    }

    public void disconnectSelected() {
        DbConfig config = selectedConfig();
        if (config != null) {
            manager.disconnect(config.id);
            explorer.refreshTree();
        }
    }

    public void refreshSelected() {
        DbConfig config = selectedConfig();
        if (config == null) {
            return;
        }
        DbSession session = manager.session(config.id);
        if (session == null) {
            connect(config);
            return;
        }
        setHint("Refreshing schema…");
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                session.loadCatalog();
                ApplicationManager.getApplication().invokeLater(() -> {
                    transientState.remove(config.id);
                    explorer.refreshTree();
                    setHint("Schema refreshed");
                });
            } catch (Exception ex) {
                ApplicationManager.getApplication().invokeLater(() ->
                        setError(config, ex.getMessage() == null ? ex.toString() : ex.getMessage()));
            }
        });
    }

    private void connect(@NotNull DbConfig config) {
        transientState.remove(config.id);
        explorer.withSession(config, session -> {
            explorer.refreshTree();
            setHint("Connected: " + config.name + "  ·  " + session.serverVersion());
        });
    }

    private void openConsole(@NotNull DbConfig config) {
        if (manager.session(config.id) != null) {
            explorer.openConsole(config);
        } else {
            explorer.withSession(config, session -> explorer.openConsole(config));
        }
    }

    private void openTableData(@NotNull TableRef table) {
        if (manager.session(table.config().id) != null) {
            explorer.openTableData(table);
        } else {
            explorer.withSession(table.config(), session -> explorer.openTableData(table));
        }
    }

    private void copyDdl(@NotNull SchemaCatalog catalog, @NotNull String what) {
        CopyPasteManager.getInstance().setContents(new StringSelection(DdlGenerator.generate(catalog)));
        NotificationGroupManager.getInstance().getNotificationGroup("IntellaDB")
                .createNotification(what + " DDL copied to clipboard", NotificationType.INFORMATION)
                .notify(project);
    }

    // ------------------------------------------------------------------ popup menu

    private void showPopup(@NotNull MouseEvent e) {
        TreePath path = tree.getPathForLocation(e.getX(), e.getY());
        if (path == null) {
            return;
        }
        tree.setSelectionPath(path);
        Object entry = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
        DefaultActionGroup group = new DefaultActionGroup();

        if (entry instanceof ConfigEntry configEntry) {
            DbConfig config = configEntry.config();
            boolean connected = manager.session(config.id) != null;
            if (!connected) {
                group.add(action("Connect", "Connect to " + config.describe(),
                        com.intellij.icons.AllIcons.Actions.Execute, () -> connect(config)));
            } else {
                group.add(action("Disconnect", "Disconnect", com.intellij.icons.AllIcons.Actions.Suspend,
                        this::disconnectSelected));
            }
            group.add(action("Refresh Schema", "Reload metadata",
                    com.intellij.icons.AllIcons.Actions.Refresh, this::refreshSelected));
            group.addSeparator();
            group.add(action("New SQL Console", "Open a SQL console",
                    com.intellij.icons.AllIcons.Nodes.Console, () -> openConsole(config)));
            group.addSeparator();
            group.add(action("Edit Connection…", "Edit connection settings",
                    com.intellij.icons.AllIcons.Actions.Edit, () -> editConnection(config)));
            group.add(action("Delete Connection…", "Remove this connection",
                    com.intellij.icons.AllIcons.General.Remove, () -> deleteConnection(config)));
        } else if (entry instanceof SchemaEntry schemaEntry) {
            DbConfig config = schemaEntry.config();
            group.add(action("Refresh Schema", "Reload metadata",
                    com.intellij.icons.AllIcons.Actions.Refresh, this::refreshSelected));
            group.add(action("New SQL Console", "Open a SQL console",
                    com.intellij.icons.AllIcons.Nodes.Console, () -> openConsole(config)));
            group.add(action("Copy Schema DDL", "Copy CREATE TABLE statements",
                    com.intellij.icons.AllIcons.Actions.Copy, () -> {
                        DbSession session = manager.session(config.id);
                        if (session != null && session.catalog() != null) {
                            List<TableMeta> tables = session.catalog().schemas().stream()
                                    .filter(s -> s.name().equals(schemaEntry.name()))
                                    .findFirst().map(SchemaCatalog.Schema::tables).orElse(List.of());
                            copyDdl(new SchemaCatalog(List.of(
                                    new SchemaCatalog.Schema(schemaEntry.name(), tables))), "Schema");
                        }
                    }));
        } else if (entry instanceof TableEntry tableEntry) {
            TableRef ref = new TableRef(tableEntry.config(), tableEntry.schema(), tableEntry.meta());
            group.add(action("View Data", "Preview first 200 rows",
                    com.intellij.icons.AllIcons.Actions.Preview, () -> openTableData(ref)));
            group.add(action("New SQL Console", "Open a SQL console",
                    com.intellij.icons.AllIcons.Nodes.Console, () -> openConsole(tableEntry.config())));
            group.addSeparator();
            group.add(action("Copy Table DDL", "Copy CREATE TABLE statement",
                    com.intellij.icons.AllIcons.Actions.Copy, () -> copyDdl(new SchemaCatalog(List.of(
                            new SchemaCatalog.Schema(tableEntry.schema(), List.of(tableEntry.meta())))), "Table")));
            group.add(action("Ask AI about this table", "Explain this table with the AI assistant",
                    IntellaDbIcons.AI, () -> {
                        explorer.openAiAssistant();
                        AiChatPanel ai = explorer.aiPanel();
                        if (ai != null) {
                            ai.setDraft("Explain the table " + tableEntry.schema() + "."
                                    + tableEntry.meta().name + " and how it relates to other tables.");
                        }
                    }));
        } else if (entry instanceof ColumnEntry columnEntry) {
            group.add(action("Copy Name", "Copy column name", com.intellij.icons.AllIcons.Actions.Copy,
                    () -> CopyPasteManager.getInstance().setContents(new StringSelection(columnEntry.name()))));
        }

        var popup = JBPopupFactory.getInstance()
                .createActionGroupPopup("Intella DB", group,
                        DataManager.getInstance().getDataContext(tree),
                        JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, false);
        popup.show(new RelativePoint(tree, e.getPoint()));
    }

    private static @NotNull AnAction action(@NotNull String text, @NotNull String description,
                                            Icon icon, @NotNull Runnable runnable) {
        return new AnAction(text, description, icon) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                runnable.run();
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }
        };
    }

    // ------------------------------------------------------------------ rendering & mouse

    private final class Renderer extends ColoredTreeCellRenderer {
        @Override
        public void customizeCellRenderer(@NotNull javax.swing.JTree tree, Object value, boolean selected,
                                          boolean expanded, boolean leaf, int row, boolean hasFocus) {
            if (!(value instanceof DefaultMutableTreeNode node)) {
                return;
            }
            switch (node.getUserObject()) {
                case ConfigEntry c -> {
                    DbConfig config = c.config();
                    boolean connected = manager.session(config.id) != null;
                    String state = connected ? null : transientState.get(config.id);
                    append(config.name.isEmpty() ? config.describe() : config.name,
                            SimpleTextAttributes.REGULAR_ATTRIBUTES);
                    String suffix = "  —  " + config.describe();
                    if ("connecting".equals(state)) {
                        suffix = "  —  connecting…";
                    } else if (state != null && state.startsWith("error:")) {
                        suffix = "  —  failed";
                    } else if (connected) {
                        suffix = "  —  connected";
                    }
                    append(suffix, SimpleTextAttributes.GRAYED_ATTRIBUTES);
                    setIcon(IntellaDbIcons.CONNECTION);
                }
                case SchemaEntry s -> {
                    append(s.name(), SimpleTextAttributes.REGULAR_ATTRIBUTES);
                    setIcon(IntellaDbIcons.SCHEMA);
                }
                case TableEntry t -> {
                    append(t.meta().name, SimpleTextAttributes.REGULAR_ATTRIBUTES);
                    setIcon(t.meta().isView() ? IntellaDbIcons.VIEW : IntellaDbIcons.TABLE);
                }
                case ColumnEntry col -> {
                    append(col.name() + "  ", col.pk() ? SimpleTextAttributes.REGULAR_ATTRIBUTES
                            : SimpleTextAttributes.GRAYED_ATTRIBUTES);
                    append(col.type(), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
                    setIcon(col.pk() ? IntellaDbIcons.KEY : IntellaDbIcons.COLUMN);
                }
                case String s -> append(s, "connecting…".equals(s)
                        ? SimpleTextAttributes.GRAYED_ATTRIBUTES : SimpleTextAttributes.ERROR_ATTRIBUTES);
                case null, default -> {
                }
            }
        }
    }

    private final class MouseHandler extends MouseAdapter {
        @Override
        public void mouseClicked(MouseEvent e) {
            if (e.getClickCount() == 2) {
                Object entry = selectedEntry();
                if (entry instanceof ConfigEntry config) {
                    if (manager.session(config.config().id) != null) {
                        openConsole(config.config());
                    } else {
                        connect(config.config());
                    }
                } else if (entry instanceof TableEntry table) {
                    openTableData(new TableRef(table.config(), table.schema(), table.meta()));
                }
            }
        }

        @Override
        public void mousePressed(MouseEvent e) {
            maybePopup(e);
        }

        @Override
        public void mouseReleased(MouseEvent e) {
            maybePopup(e);
        }

        private void maybePopup(MouseEvent e) {
            if (e.isPopupTrigger()) {
                showPopup(e);
            }
        }
    }

    @Override
    public void dispose() {
    }
}
