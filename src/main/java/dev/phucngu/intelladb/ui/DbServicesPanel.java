package dev.phucngu.intelladb.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.tree.TreeUtil;
import dev.phucngu.intelladb.IntellaDbIcons;
import dev.phucngu.intelladb.connection.ConnectionManager;
import dev.phucngu.intelladb.connection.DbConfig;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JPanel;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Content of the "DB Services" tool window, modelled on IntelliJ's Services view. Left:
 * the tree of connections and their consoles, with the query history list below it.
 * Right: the selected console's results (Output + result tabs), or — when a history entry
 * is selected — that query's SQL and cached result. Double-clicking a console jumps to
 * its editor tab.
 */
final class DbServicesPanel extends JPanel implements Disposable {

    private static final String EMPTY_CARD = "empty";
    private static final String HISTORY_CARD = "history";

    private final Project project;
    private final ResultsHub hub;
    private final DefaultTreeModel model = new DefaultTreeModel(new DefaultMutableTreeNode());
    private final Tree tree = new Tree(model);
    private final CardLayout cards = new CardLayout();
    private final JPanel content = new JPanel(cards);
    private final Map<SqlConsole, String> cardIds = new LinkedHashMap<>();
    private final QueryHistoryView history;

    DbServicesPanel(@NotNull Project project) {
        super(new BorderLayout());
        this.project = project;
        this.hub = ResultsHub.getInstance(project);

        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setCellRenderer(new Renderer());
        tree.getEmptyText().setText("Run a console to see it here");
        history = new QueryHistoryView(project, () -> {
            tree.clearSelection();
            cards.show(content, HISTORY_CARD);
        });
        tree.addTreeSelectionListener(e -> {
            if (selectedConsole() != null) {
                history.clearSelection(); // one selection drives the right-hand side
            }
            showSelected();
        });
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(@NotNull MouseEvent e) {
                SqlConsole console = selectedConsole();
                if (e.getClickCount() == 2 && console != null) {
                    FileEditorManager.getInstance(project).openFile(console.file(), true);
                }
            }
        });

        JPanel empty = new JPanel(new BorderLayout());
        com.intellij.ui.components.JBLabel hint = new com.intellij.ui.components.JBLabel(
                "Execute a statement in a SQL console (Ctrl/Cmd+Enter) — its output and results appear here.");
        hint.setForeground(JBUI.CurrentTheme.ContextHelp.FOREGROUND);
        hint.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        empty.add(hint, BorderLayout.CENTER);
        content.add(empty, EMPTY_CARD);
        content.add(history.detailComponent(), HISTORY_CARD);

        JBScrollPane treeScroll = new JBScrollPane(tree);
        treeScroll.setBorder(JBUI.Borders.empty());
        JBSplitter left = new JBSplitter(true, 0.35f);
        left.setFirstComponent(treeScroll);
        left.setSecondComponent(history.listComponent());
        JBSplitter splitter = new JBSplitter(false, 0.22f);
        splitter.setFirstComponent(left);
        splitter.setSecondComponent(content);
        add(splitter, BorderLayout.CENTER);

        hub.addListener(this::rebuild, this);
        hub.setSelector(this::select);
        hub.setHistoryFocuser(history::focusList);
        com.intellij.openapi.util.Disposer.register(this, history);
        ConnectionManager.getInstance(project).addListener(() -> {
            if (!project.isDisposed()) {
                tree.repaint(); // connected dots / renamed connections
            }
        });
        rebuild();
    }

    /** Re-reads the hub: connection nodes grouping their consoles, plus a card per console. */
    private void rebuild() {
        SqlConsole selected = selectedConsole();
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) model.getRoot();
        root.removeAllChildren();
        Map<String, DefaultMutableTreeNode> byConnection = new LinkedHashMap<>();
        for (ConsoleResultsView view : hub.views()) {
            SqlConsole console = view.console();
            DefaultMutableTreeNode connectionNode = byConnection.computeIfAbsent(console.config().id, id -> {
                DefaultMutableTreeNode node = new DefaultMutableTreeNode(console.config());
                root.add(node);
                return node;
            });
            connectionNode.add(new DefaultMutableTreeNode(console));
            if (!cardIds.containsKey(console)) {
                String id = "console-" + cardIds.size();
                cardIds.put(console, id);
                content.add(view.component(), id);
            }
        }
        model.reload();
        TreeUtil.expandAll(tree);
        if (selected != null) {
            select(selected);
        }
    }

    void select(@NotNull SqlConsole console) {
        DefaultMutableTreeNode node = TreeUtil.findNodeWithObject((DefaultMutableTreeNode) model.getRoot(), console);
        if (node == null) {
            rebuild(); // the console was registered after our last rebuild
            node = TreeUtil.findNodeWithObject((DefaultMutableTreeNode) model.getRoot(), console);
        }
        if (node != null) {
            TreePath path = new TreePath(node.getPath());
            tree.setSelectionPath(path);
            tree.scrollPathToVisible(path);
        }
        showSelected();
    }

    private @Nullable SqlConsole selectedConsole() {
        TreePath path = tree.getSelectionPath();
        return path != null && path.getLastPathComponent() instanceof DefaultMutableTreeNode node
                && node.getUserObject() instanceof SqlConsole console ? console : null;
    }

    private void showSelected() {
        SqlConsole console = selectedConsole();
        if (console != null && cardIds.containsKey(console)) {
            cards.show(content, cardIds.get(console));
        } else if (history.isEmptySelection()) {
            cards.show(content, EMPTY_CARD);
        }
    }

    private final class Renderer extends ColoredTreeCellRenderer {
        @Override
        public void customizeCellRenderer(@NotNull JTree tree, Object value, boolean selected, boolean expanded,
                                          boolean leaf, int row, boolean hasFocus) {
            Object user = value instanceof DefaultMutableTreeNode node ? node.getUserObject() : null;
            if (user instanceof DbConfig config) {
                boolean connected = ConnectionManager.getInstance(project).session(config.id) != null;
                setIcon(connected ? IntellaDbIcons.CONNECTION_CONNECTED : IntellaDbIcons.CONNECTION);
                append("@" + config.name, SimpleTextAttributes.REGULAR_ATTRIBUTES);
            } else if (user instanceof SqlConsole) {
                setIcon(AllIcons.Nodes.Console);
                append("console", SimpleTextAttributes.REGULAR_ATTRIBUTES);
            }
        }
    }

    @Override
    public void dispose() {
        hub.setSelector(null);
        hub.setHistoryFocuser(null);
    }
}
