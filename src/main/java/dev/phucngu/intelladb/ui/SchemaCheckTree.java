package dev.phucngu.intelladb.ui;

import com.intellij.ui.CheckboxTree;
import com.intellij.ui.CheckedTreeNode;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.util.ui.tree.TreeUtil;
import dev.phucngu.intelladb.IntellaDbIcons;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JTree;
import javax.swing.tree.DefaultTreeModel;
import java.util.ArrayList;
import java.util.List;

/**
 * The connection dialog's schema picker: schemas with checkboxes, directly under the
 * (hidden) root, or under an expandable node per database when the connection browses
 * every database — checking a database checks all its schemas.
 */
final class SchemaCheckTree {

    /** A schema; {@code value} is what the connection saves (qualified by database where there are several). */
    record Item(@NotNull String value, @NotNull String schema, boolean system) {
    }

    private final CheckedTreeNode root = new CheckedTreeNode(null);
    private boolean enabled = true;
    private final CheckboxTree tree = new CheckboxTree(new CheckboxTree.CheckboxTreeCellRenderer() {
        @Override
        public void customizeRenderer(JTree tree, Object value, boolean selected, boolean expanded, boolean leaf,
                                      int row, boolean hasFocus) {
            if (!(value instanceof CheckedTreeNode node)) {
                return;
            }
            if (node.getUserObject() instanceof Item item) {
                getTextRenderer().append(item.schema());
                if (item.system()) {
                    getTextRenderer().append("  (system)", SimpleTextAttributes.GRAYED_ATTRIBUTES);
                }
                getTextRenderer().setIcon(IntellaDbIcons.SCHEMA);
            } else if (node.getUserObject() instanceof String database) {
                getTextRenderer().append(database);
                getTextRenderer().append("  " + node.getChildCount(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
                getTextRenderer().setIcon(IntellaDbIcons.DATABASE);
            }
        }
    }, root);

    SchemaCheckTree() {
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
    }

    @NotNull JComponent component() {
        return tree;
    }

    void clear() {
        root.removeAllChildren();
        reload();
    }

    /** Adds a schema, under {@code database}'s node (created on first use) unless it is null. */
    void add(@Nullable String database, @NotNull Item item, boolean checked) {
        CheckedTreeNode parent = database == null ? root : databaseNode(database);
        CheckedTreeNode node = new CheckedTreeNode(item);
        node.setChecked(checked);
        node.setEnabled(enabled);
        parent.add(node);
        if (parent != root) {
            // A database is checked when all its schemas are; the renderer shows a partial pick.
            boolean all = true;
            for (int i = 0; i < parent.getChildCount(); i++) {
                all &= ((CheckedTreeNode) parent.getChildAt(i)).isChecked();
            }
            parent.setChecked(all);
        }
    }

    /** Shows what was added, databases expanded. */
    void reload() {
        ((DefaultTreeModel) tree.getModel()).reload();
        TreeUtil.expandAll(tree);
    }

    /** Values of the checked schemas, in display order. */
    @NotNull List<String> checked() {
        List<String> values = new ArrayList<>();
        for (Item item : tree.getCheckedNodes(Item.class, null)) {
            values.add(item.value());
        }
        return values;
    }

    /** Number of schemas listed (databases not counted). */
    int schemaCount() {
        int count = 0;
        for (int i = 0; i < root.getChildCount(); i++) {
            CheckedTreeNode child = (CheckedTreeNode) root.getChildAt(i);
            count += child.getUserObject() instanceof Item ? 1 : child.getChildCount();
        }
        return count;
    }

    /** A disabled tree still toggles on click; disabled nodes don't. */
    void setEnabled(boolean enabled) {
        this.enabled = enabled;
        tree.setEnabled(enabled);
        java.util.Enumeration<javax.swing.tree.TreeNode> nodes = root.depthFirstEnumeration();
        while (nodes.hasMoreElements()) {
            ((CheckedTreeNode) nodes.nextElement()).setEnabled(enabled);
        }
    }

    private @NotNull CheckedTreeNode databaseNode(@NotNull String database) {
        for (int i = 0; i < root.getChildCount(); i++) {
            CheckedTreeNode child = (CheckedTreeNode) root.getChildAt(i);
            if (database.equals(child.getUserObject())) {
                return child;
            }
        }
        CheckedTreeNode node = new CheckedTreeNode(database);
        node.setEnabled(enabled);
        root.add(node);
        return node;
    }
}
