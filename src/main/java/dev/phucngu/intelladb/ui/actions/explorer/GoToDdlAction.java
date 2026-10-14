package dev.phucngu.intelladb.ui.actions.explorer;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import dev.phucngu.intelladb.ui.ConnectionTreePanel;
import dev.phucngu.intelladb.ui.TableRef;
import org.jetbrains.annotations.NotNull;

/**
 * "Go to DDL": opens the selected table's CREATE statement in a read-only editor tab,
 * IntelliJ's code-editor face of a table. Bound to Go to Declaration (⌘B / Ctrl+B) on
 * tree nodes.
 */
public final class GoToDdlAction extends AnAction {

    private final ConnectionTreePanel treePanel;

    public GoToDdlAction(@NotNull ConnectionTreePanel treePanel) {
        super("Go to DDL", "Open the table's DDL in an editor tab", AllIcons.Actions.EditSource);
        this.treePanel = treePanel;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        TableRef table = treePanel.selectedTable();
        if (table != null) {
            treePanel.explorer().openDdl(table);
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
}
