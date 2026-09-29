package dev.phucngu.intelladb.ui;

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
import com.intellij.icons.AllIcons;
import com.intellij.ui.ClientProperty;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.JBColor;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.tree.ui.ClassicPainter;
import com.intellij.ui.tree.ui.Control;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import dev.phucngu.intelladb.IntellaDbIcons;
import dev.phucngu.intelladb.connection.ConnectionManager;
import dev.phucngu.intelladb.connection.DbConfig;
import dev.phucngu.intelladb.connection.DbDialect;
import dev.phucngu.intelladb.connection.DbSession;
import dev.phucngu.intelladb.connection.NamespaceModel;
import dev.phucngu.intelladb.schema.DdlGenerator;
import dev.phucngu.intelladb.schema.SchemaCatalog;
import dev.phucngu.intelladb.schema.TableMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import javax.swing.JPanel;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
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

    public record DatabaseEntry(DbConfig config, String name, int shownSchemas, int totalSchemas) {
    }

    public record SchemaEntry(DbConfig config, String name) {
    }

    /** A grouping node such as "tables 15"; {@code owner} tells same-kind folders apart. */
    public record FolderEntry(DbConfig config, Folder folder, String owner, int count) {
    }

    /** A leaf object other than a column: routine, sequence, type, key, index, extension, role. */
    public record ObjectEntry(DbConfig config, ObjectKind kind, String name, String detail) {
    }

    public record TableEntry(DbConfig config, String schema, TableMeta meta) {
    }

    public record ColumnEntry(DbConfig config, String schema, String table, String name, String type, boolean pk) {
    }

    /** Folder nodes, in display order within their parent. */
    public enum Folder {
        TABLES("tables"), VIEWS("views"), MATERIALIZED_VIEWS("materialized views"),
        FOREIGN_TABLES("foreign tables"), ROUTINES("routines"), AGGREGATES("aggregates"),
        SEQUENCES("sequences"), OBJECT_TYPES("object types"),
        COLUMNS("columns"), KEYS("keys"), FOREIGN_KEYS("foreign keys"), INDEXES("indexes"), CHECKS("checks"),
        DATABASE_OBJECTS("Database Objects"), EXTENSIONS("extensions"),
        SERVER_OBJECTS("Server Objects"), ROLES("roles");

        final String label;

        Folder(String label) {
            this.label = label;
        }

        /** The two top-level groups are titled and carry no count, as in IntelliJ's database tools. */
        boolean isGroup() {
            return this == DATABASE_OBJECTS || this == SERVER_OBJECTS;
        }
    }

    public enum ObjectKind {
        FUNCTION(AllIcons.Nodes.Function), PROCEDURE(AllIcons.Nodes.Method), AGGREGATE(AllIcons.Nodes.Function),
        SEQUENCE(IntellaDbIcons.SEQUENCE), OBJECT_TYPE(AllIcons.Nodes.Type),
        PRIMARY_KEY(IntellaDbIcons.KEY), UNIQUE_KEY(IntellaDbIcons.KEY), FOREIGN_KEY(IntellaDbIcons.FOREIGN_KEY),
        INDEX(IntellaDbIcons.INDEX), CHECK(AllIcons.Nodes.Constant),
        EXTENSION(AllIcons.Nodes.Plugin), ROLE(AllIcons.General.User);

        final Icon icon;

        ObjectKind(Icon icon) {
            this.icon = icon;
        }
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
        // Vertical indent guides regardless of the IDE-wide appearance setting.
        ClientProperty.put(tree, Control.Painter.KEY, new ClassicPainter(true, null, null, null));
        javax.swing.ToolTipManager.sharedInstance().registerComponent(tree);
        tree.getEmptyText().setText("No connections yet — click + to add one");
        tree.addMouseListener(new MouseHandler());

        dev.phucngu.intelladb.connection.SessionOpener.getInstance(project)
                .addListener(new dev.phucngu.intelladb.connection.SessionOpener.Listener() {
                    @Override
                    public void connecting(@NotNull DbConfig config) {
                        onConnecting(config);
                    }

                    @Override
                    public void connected(@NotNull DbConfig config,
                                          @NotNull dev.phucngu.intelladb.connection.DbSession session) {
                        onConnected(config);
                    }

                    @Override
                    public void failed(@NotNull DbConfig config, @NotNull String message) {
                        onFailed(config, message);
                    }
                });

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

    /**
     * connection → database → schema → folders ("tables 15", "views 4", "routines 9"…) →
     * objects → per-table folders (columns, keys, foreign keys, indexes, checks), plus the
     * "Database Objects" and "Server Objects" groups — the layout of IntelliJ's database tools.
     * Where a database is just a schema (MySQL) there is no database node: the schemas hang
     * off the connection, which carries the "shown of total" badge. Empty folders are left out.
     */
    private void appendCatalog(@NotNull DefaultMutableTreeNode configNode, @NotNull DbConfig config,
                               @NotNull SchemaCatalog catalog) {
        boolean schemasOnly = config.dialect().namespaces() == NamespaceModel.SCHEMAS_ONLY;
        String database = catalog.database().isEmpty() ? config.database : catalog.database();
        DefaultMutableTreeNode databaseNode = schemasOnly ? configNode : new DefaultMutableTreeNode(
                new DatabaseEntry(config, database, catalog.schemas().size(), catalog.totalSchemas()));
        if (!schemasOnly) {
            configNode.add(databaseNode);
        }
        for (SchemaCatalog.Schema schema : catalog.schemas()) {
            DefaultMutableTreeNode schemaNode = new DefaultMutableTreeNode(new SchemaEntry(config, schema.name()));
            databaseNode.add(schemaNode);
            appendTables(schemaNode, config, schema, Folder.TABLES, TableMeta.Kind.TABLE);
            appendTables(schemaNode, config, schema, Folder.VIEWS, TableMeta.Kind.VIEW);
            appendTables(schemaNode, config, schema, Folder.MATERIALIZED_VIEWS, TableMeta.Kind.MATERIALIZED_VIEW);
            appendTables(schemaNode, config, schema, Folder.FOREIGN_TABLES, TableMeta.Kind.FOREIGN_TABLE);
            appendObjects(schemaNode, config, Folder.ROUTINES, schema.name(), schema.routinesOf(false).stream()
                    .map(r -> new ObjectEntry(config,
                            r.kind() == SchemaCatalog.Routine.Kind.PROCEDURE ? ObjectKind.PROCEDURE : ObjectKind.FUNCTION,
                            r.name(), signature(r)))
                    .toList());
            appendObjects(schemaNode, config, Folder.AGGREGATES, schema.name(), schema.routinesOf(true).stream()
                    .map(r -> new ObjectEntry(config, ObjectKind.AGGREGATE, r.name(), signature(r)))
                    .toList());
            appendObjects(schemaNode, config, Folder.SEQUENCES, schema.name(), schema.sequences().stream()
                    .map(name -> new ObjectEntry(config, ObjectKind.SEQUENCE, name, ""))
                    .toList());
            appendObjects(schemaNode, config, Folder.OBJECT_TYPES, schema.name(), schema.objectTypes().stream()
                    .map(t -> new ObjectEntry(config, ObjectKind.OBJECT_TYPE, t.name(), t.kind()))
                    .toList());
        }
        if (!schemasOnly && !catalog.extensions().isEmpty()) {
            DefaultMutableTreeNode group = folderNode(config, Folder.DATABASE_OBJECTS, database, 0);
            databaseNode.add(group);
            appendObjects(group, config, Folder.EXTENSIONS, database, catalog.extensions().stream()
                    .map(e -> new ObjectEntry(config, ObjectKind.EXTENSION, e.name(), e.version()))
                    .toList());
        }
        if (!catalog.roles().isEmpty()) {
            DefaultMutableTreeNode group = folderNode(config, Folder.SERVER_OBJECTS, "", 0);
            configNode.add(group);
            appendObjects(group, config, Folder.ROLES, "", catalog.roles().stream()
                    .map(role -> new ObjectEntry(config, ObjectKind.ROLE, role, ""))
                    .toList());
        }
    }

    private static void appendTables(@NotNull DefaultMutableTreeNode schemaNode, @NotNull DbConfig config,
                                     @NotNull SchemaCatalog.Schema schema, @NotNull Folder folder,
                                     TableMeta.@NotNull Kind kind) {
        List<TableMeta> tables = schema.tablesOf(kind);
        if (tables.isEmpty()) {
            return;
        }
        DefaultMutableTreeNode folderNode = folderNode(config, folder, schema.name(), tables.size());
        schemaNode.add(folderNode);
        for (TableMeta table : tables) {
            DefaultMutableTreeNode tableNode = new DefaultMutableTreeNode(new TableEntry(config, schema.name(), table));
            folderNode.add(tableNode);
            String owner = schema.name() + "." + table.name;
            if (!table.columns.isEmpty()) {
                DefaultMutableTreeNode columns = folderNode(config, Folder.COLUMNS, owner, table.columns.size());
                tableNode.add(columns);
                for (dev.phucngu.intelladb.schema.ColumnMeta column : table.columns) {
                    columns.add(new DefaultMutableTreeNode(new ColumnEntry(
                            config, schema.name(), table.name, column.name, column.typeName, column.primaryKey), false));
                }
            }
            appendObjects(tableNode, config, Folder.KEYS, owner, table.keys.stream()
                    .map(k -> new ObjectEntry(config, k.primary() ? ObjectKind.PRIMARY_KEY : ObjectKind.UNIQUE_KEY,
                            k.name(), "(" + String.join(", ", k.columns()) + ")"))
                    .toList());
            appendObjects(tableNode, config, Folder.FOREIGN_KEYS, owner, table.foreignKeys.stream()
                    .map(fk -> new ObjectEntry(config, ObjectKind.FOREIGN_KEY, fk.name(),
                            "(" + String.join(", ", fk.columns()) + ") → "
                                    + (fk.refSchema().equals(schema.name()) ? "" : fk.refSchema() + ".")
                                    + fk.refTable() + "(" + String.join(", ", fk.refColumns()) + ")"))
                    .toList());
            appendObjects(tableNode, config, Folder.INDEXES, owner, table.indexes.stream()
                    .map(i -> new ObjectEntry(config, ObjectKind.INDEX, i.name(),
                            "(" + String.join(", ", i.columns()) + ")" + (i.unique() ? " UNIQUE" : "")))
                    .toList());
            appendObjects(tableNode, config, Folder.CHECKS, owner, table.checks.stream()
                    .map(c -> new ObjectEntry(config, ObjectKind.CHECK, c.name(), c.definition()))
                    .toList());
        }
    }

    private static void appendObjects(@NotNull DefaultMutableTreeNode parent, @NotNull DbConfig config,
                                      @NotNull Folder folder, @NotNull String owner, @NotNull List<ObjectEntry> objects) {
        if (objects.isEmpty()) {
            return;
        }
        DefaultMutableTreeNode folderNode = folderNode(config, folder, owner, objects.size());
        parent.add(folderNode);
        for (ObjectEntry object : objects) {
            folderNode.add(new DefaultMutableTreeNode(object, false));
        }
    }

    private static @NotNull DefaultMutableTreeNode folderNode(@NotNull DbConfig config, @NotNull Folder folder,
                                                              @NotNull String owner, int count) {
        return new DefaultMutableTreeNode(new FolderEntry(config, folder, owner, count));
    }

    private static @NotNull String signature(SchemaCatalog.@NotNull Routine routine) {
        String returns = routine.returns().isEmpty() ? "" : ": " + routine.returns();
        return "(" + routine.arguments() + ")" + returns;
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
        TreePath selectedPath = tree.getSelectionPath();
        String selected = selectedPath == null ? null : keyOf(selectedPath);
        fill();
        Map<String, DefaultMutableTreeNode> byKey = nodesByKey();
        for (String key : expanded) {
            DefaultMutableTreeNode node = byKey.get(key);
            if (node != null) {
                tree.expandPath(new TreePath(node.getPath()));
            }
        }
        DefaultMutableTreeNode selectedNode = selected == null ? null : byKey.get(selected);
        if (selectedNode != null) {
            tree.setSelectionPath(new TreePath(selectedNode.getPath()));
        }
    }

    private static @NotNull String keyOf(@NotNull TreePath path) {
        StringBuilder sb = new StringBuilder();
        for (Object element : path.getPath()) {
            if (element instanceof DefaultMutableTreeNode node) {
                Object userObject = node.getUserObject();
                if (userObject == null) {
                    continue; // e.g. the invisible root during dispose-time rebuilds
                }
                switch (userObject) {
                    case ConfigEntry c -> sb.append("/c:").append(c.config().id);
                    case DatabaseEntry d -> sb.append("/d:").append(d.name());
                    case SchemaEntry s -> sb.append("/s:").append(s.name());
                    case FolderEntry f -> sb.append("/f:").append(f.folder());
                    case TableEntry t -> sb.append("/t:").append(t.meta().name);
                    case ColumnEntry col -> sb.append("/col:").append(col.name());
                    case ObjectEntry o -> sb.append("/o:").append(o.kind()).append(':').append(o.name())
                            .append(o.detail());
                    default -> {
                    }
                }
            }
        }
        return sb.toString();
    }

    private @NotNull Map<String, DefaultMutableTreeNode> nodesByKey() {
        Map<String, DefaultMutableTreeNode> byKey = new HashMap<>();
        Enumeration<?> depthFirst = ((DefaultMutableTreeNode) model.getRoot()).depthFirstEnumeration();
        while (depthFirst.hasMoreElements()) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) depthFirst.nextElement();
            byKey.putIfAbsent(keyOf(new TreePath(node.getPath())), node);
        }
        return byKey;
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

    /** Connect lifecycle arrives via {@link dev.phucngu.intelladb.connection.SessionOpener}. */
    private void onConnecting(@NotNull DbConfig config) {
        transientState.put(config.id, "connecting");
        rebuildOnEdt();
    }

    private void onConnected(@NotNull DbConfig config) {
        transientState.remove(config.id);
        runOnEdt(() -> {
            rebuild();
            expandDatabase(config);
        });
    }

    /** Opens the connection down to its database so a fresh connect shows the schemas. */
    private void expandDatabase(@NotNull DbConfig config) {
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) model.getRoot();
        for (int i = 0; i < root.getChildCount(); i++) {
            DefaultMutableTreeNode configNode = (DefaultMutableTreeNode) root.getChildAt(i);
            if (configNode.getUserObject() instanceof ConfigEntry c && c.config().id.equals(config.id)
                    && configNode.getChildCount() > 0
                    && configNode.getFirstChild() instanceof DefaultMutableTreeNode databaseNode) {
                if (databaseNode.getUserObject() instanceof DatabaseEntry) {
                    tree.expandPath(new TreePath(databaseNode.getPath()));
                } else if (config.dialect().namespaces() == NamespaceModel.SCHEMAS_ONLY) {
                    tree.expandPath(new TreePath(configNode.getPath())); // the schemas are right here
                }
            }
        }
    }

    private void onFailed(@NotNull DbConfig config, @NotNull String message) {
        transientState.put(config.id, "error:" + message);
        rebuildOnEdt();
    }

    /** Callers may be on pooled threads; tree model changes must run on the EDT. */
    private void rebuildOnEdt() {
        runOnEdt(this::rebuild);
    }

    private static void runOnEdt(@NotNull Runnable runnable) {
        if (ApplicationManager.getApplication().isDispatchThread()) {
            runnable.run();
        } else {
            ApplicationManager.getApplication().invokeLater(runnable);
        }
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
            ConsoleStore.getInstance(project).remove(config.id);
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
                        onFailed(config, ex.getMessage() == null ? ex.toString() : ex.getMessage()));
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
        openConsole(config, null);
    }

    /** Opens the connection's console; a non-null {@code schema} becomes its default schema. */
    private void openConsole(@NotNull DbConfig config, @Nullable String schema) {
        if (manager.session(config.id) != null) {
            explorer.openConsole(config, null, schema);
        } else {
            explorer.withSession(config, session -> explorer.openConsole(config, null, schema));
        }
    }

    private void openTableData(@NotNull TableRef table) {
        if (manager.session(table.config().id) != null) {
            explorer.openTableData(table);
        } else {
            explorer.withSession(table.config(), session -> explorer.openTableData(table));
        }
    }

    private void copyDdl(@NotNull SchemaCatalog catalog, @NotNull DbDialect dialect, @NotNull String what) {
        CopyPasteManager.getInstance().setContents(new StringSelection(DdlGenerator.generate(catalog, dialect)));
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
        } else if (entry instanceof DatabaseEntry databaseEntry) {
            group.add(action("Refresh Schema", "Reload metadata",
                    com.intellij.icons.AllIcons.Actions.Refresh, this::refreshSelected));
            group.add(action("New SQL Console", "Open a SQL console",
                    com.intellij.icons.AllIcons.Nodes.Console, () -> openConsole(databaseEntry.config())));
        } else if (entry instanceof FolderEntry) {
            group.add(action("Refresh Schema", "Reload metadata",
                    com.intellij.icons.AllIcons.Actions.Refresh, this::refreshSelected));
        } else if (entry instanceof SchemaEntry schemaEntry) {
            DbConfig config = schemaEntry.config();
            group.add(action("Refresh Schema", "Reload metadata",
                    com.intellij.icons.AllIcons.Actions.Refresh, this::refreshSelected));
            group.add(action("New SQL Console", "Open a SQL console",
                    com.intellij.icons.AllIcons.Nodes.Console, () -> openConsole(config, schemaEntry.name())));
            group.add(action("Copy Schema DDL", "Copy CREATE TABLE statements",
                    com.intellij.icons.AllIcons.Actions.Copy, () -> {
                        DbSession session = manager.session(config.id);
                        if (session != null && session.catalog() != null) {
                            List<TableMeta> tables = session.catalog().schemas().stream()
                                    .filter(s -> s.name().equals(schemaEntry.name()))
                                    .findFirst().map(SchemaCatalog.Schema::tables).orElse(List.of());
                            copyDdl(new SchemaCatalog(List.of(
                                    new SchemaCatalog.Schema(schemaEntry.name(), tables))), config.dialect(), "Schema");
                        }
                    }));
        } else if (entry instanceof TableEntry tableEntry) {
            TableRef ref = new TableRef(tableEntry.config(), tableEntry.schema(), tableEntry.meta());
            group.add(action("View Data", "Preview first 200 rows",
                    com.intellij.icons.AllIcons.Actions.Preview, () -> openTableData(ref)));
            group.add(action("New SQL Console", "Open a SQL console",
                    com.intellij.icons.AllIcons.Nodes.Console, () -> openConsole(tableEntry.config(), tableEntry.schema())));
            group.addSeparator();
            group.add(action("Copy Table DDL", "Copy CREATE TABLE statement",
                    com.intellij.icons.AllIcons.Actions.Copy, () -> copyDdl(new SchemaCatalog(List.of(
                            new SchemaCatalog.Schema(tableEntry.schema(), List.of(tableEntry.meta())))),
                            tableEntry.config().dialect(), "Table")));
            group.add(action("Ask AI about this table", "Explain this table with the AI assistant",
                    IntellaDbIcons.AI, () -> AiChatPanel.openInExplorer(project,
                            "Explain the table " + tableEntry.schema() + "."
                                    + tableEntry.meta().name + " and how it relates to other tables.")));
        } else if (entry instanceof ColumnEntry columnEntry) {
            group.add(action("Copy Name", "Copy column name", com.intellij.icons.AllIcons.Actions.Copy,
                    () -> CopyPasteManager.getInstance().setContents(new StringSelection(columnEntry.name()))));
        } else if (entry instanceof ObjectEntry objectEntry) {
            group.add(action("Copy Name", "Copy the object name", com.intellij.icons.AllIcons.Actions.Copy,
                    () -> CopyPasteManager.getInstance().setContents(new StringSelection(objectEntry.name()))));
        } else {
            return;
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

    private static final Object BADGE = new Object();
    private static final JBColor BADGE_BORDER = JBColor.namedColor("Tree.Badge.borderColor",
            new JBColor(0xC9CCD6, 0x5A5D63));
    /** Opaque so the component routes the fragment through {@code doPaintFragmentBackground}. */
    private static final SimpleTextAttributes BADGE_ATTRIBUTES = new SimpleTextAttributes(
            JBColor.background(), UIUtil.getContextHelpForeground(), null,
            SimpleTextAttributes.STYLE_OPAQUE | SimpleTextAttributes.STYLE_SMALLER);

    private final class Renderer extends ColoredTreeCellRenderer {
        @Override
        public void customizeCellRenderer(@NotNull javax.swing.JTree tree, Object value, boolean selected,
                                          boolean expanded, boolean leaf, int row, boolean hasFocus) {
            if (!(value instanceof DefaultMutableTreeNode node)) {
                return;
            }
            StringBuilder plain = new StringBuilder(); // tooltip: full label when the pane truncates it
            switch (node.getUserObject()) {
                case ConfigEntry c -> {
                    DbConfig config = c.config();
                    boolean connected = manager.session(config.id) != null;
                    String state = connected ? null : transientState.get(config.id);
                    String name = config.name.isEmpty() ? config.describe() : config.name;
                    plain.append(name);
                    append(name, SimpleTextAttributes.REGULAR_ATTRIBUTES);
                    // Connected state is carried by the icon's green dot; the suffix only
                    // calls out transitional/failed states and otherwise shows the target.
                    String suffix = "  " + config.describe();
                    if ("connecting".equals(state)) {
                        suffix = "  connecting…";
                    } else if (state != null && state.startsWith("error:")) {
                        suffix = "  failed";
                    }
                    plain.append(suffix);
                    append(suffix, state != null && state.startsWith("error:")
                            ? SimpleTextAttributes.ERROR_ATTRIBUTES : SimpleTextAttributes.GRAYED_ATTRIBUTES);
                    setIcon(connected ? IntellaDbIcons.CONNECTION_CONNECTED : IntellaDbIcons.CONNECTION);
                    DbSession session = manager.session(config.id);
                    SchemaCatalog catalog = session == null ? null : session.catalog();
                    if (catalog != null && config.dialect().namespaces() == NamespaceModel.SCHEMAS_ONLY) {
                        if (catalog.totalSchemas() > 0) {
                            appendBadge(plain, catalog.schemas().size(), catalog.totalSchemas());
                        }
                    } else if (catalog != null && !catalog.databases().isEmpty()) {
                        appendBadge(plain, 1, catalog.databases().size());
                    }
                }
                case DatabaseEntry d -> {
                    plain.append(d.name());
                    append(d.name(), SimpleTextAttributes.REGULAR_ATTRIBUTES);
                    setIcon(IntellaDbIcons.DATABASE);
                    if (d.totalSchemas() > 0) {
                        appendBadge(plain, d.shownSchemas(), d.totalSchemas());
                    }
                }
                case FolderEntry f -> {
                    plain.append(f.folder().label);
                    append(f.folder().label, SimpleTextAttributes.REGULAR_ATTRIBUTES);
                    if (f.folder().isGroup()) {
                        setIcon(f.folder() == Folder.SERVER_OBJECTS ? AllIcons.Nodes.Services : AllIcons.Nodes.ConfigFolder);
                    } else {
                        setIcon(AllIcons.Nodes.Folder);
                        plain.append(' ').append(f.count());
                        append("  " + f.count(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
                    }
                }
                case ObjectEntry o -> {
                    plain.append(o.name());
                    append(o.name(), SimpleTextAttributes.REGULAR_ATTRIBUTES);
                    if (!o.detail().isEmpty()) {
                        // Signatures and column lists hug the name; kinds and versions stand apart.
                        String gap = o.detail().startsWith("(") ? "" : "  ";
                        plain.append(gap.isEmpty() ? "" : " ").append(o.detail());
                        append(gap + o.detail(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
                    }
                    setIcon(o.kind().icon);
                }
                case SchemaEntry s -> {
                    plain.append(s.name());
                    append(s.name(), SimpleTextAttributes.REGULAR_ATTRIBUTES);
                    setIcon(IntellaDbIcons.SCHEMA);
                }
                case TableEntry t -> {
                    plain.append(t.meta().name);
                    append(t.meta().name, SimpleTextAttributes.REGULAR_ATTRIBUTES);
                    setIcon(t.meta().isView() ? IntellaDbIcons.VIEW : IntellaDbIcons.TABLE);
                    if (!t.meta().remarks.isEmpty()) {
                        plain.append(" — ").append(t.meta().remarks);
                    }
                }
                case ColumnEntry col -> {
                    plain.append(col.name()).append(' ').append(col.type());
                    append(col.name() + "  ", col.pk() ? SimpleTextAttributes.REGULAR_ATTRIBUTES
                            : SimpleTextAttributes.GRAYED_ATTRIBUTES);
                    append(col.type(), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
                    setIcon(col.pk() ? IntellaDbIcons.KEY : IntellaDbIcons.COLUMN);
                }
                case String s -> {
                    plain.append(s);
                    append(s, "connecting…".equals(s)
                            ? SimpleTextAttributes.GRAYED_ATTRIBUTES : SimpleTextAttributes.ERROR_ATTRIBUTES);
                }
                case null, default -> {
                }
            }
            // Guard with isShowing: setting tooltip text during off-screen layout passes
            // makes ToolTipManager throw IllegalComponentStateException on hidden trees.
            setToolTipText(tree.isShowing() && plain.length() > 0 ? plain.toString() : null);
        }

        /** "1 of 5": how many children (databases, schemas) are introspected out of all there are. */
        private void appendBadge(@NotNull StringBuilder plain, int shown, int total) {
            String text = shown + " of " + total;
            plain.append(" (").append(text).append(')');
            append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES);
            append(" " + text + " ", BADGE_ATTRIBUTES, BADGE);
        }

        /** Badge fragments get a rounded outline instead of a filled background. */
        @Override
        protected void doPaintFragmentBackground(@NotNull Graphics2D g, int index, @NotNull Color bgColor,
                                                 int x, int y, int width, int height) {
            if (getFragmentTag(index) != BADGE) {
                super.doPaintFragmentBackground(g, index, bgColor, x, y, width, height);
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int inset = JBUI.scale(2);
                int arc = JBUI.scale(6);
                g2.setColor(BADGE_BORDER);
                g2.drawRoundRect(x, y + inset, width - 1, height - 1 - 2 * inset, arc, arc);
            } finally {
                g2.dispose();
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
