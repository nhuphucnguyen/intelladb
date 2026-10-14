package dev.phucngu.intelladb.ui.actions.explorer;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import dev.phucngu.intelladb.ui.ConnectionTreePanel;
import dev.phucngu.intelladb.ui.TableRef;
import org.jetbrains.annotations.NotNull;

/**
 * "Edit Data": opens the selected table's rows in a data tab. IntelliJ binds this to
 * EditSource (F4) on tree nodes; {@link #installTreeShortcuts} wires that up.
 */
public final class EditDataAction extends AnAction {

    private final ConnectionTreePanel treePanel;

    public EditDataAction(@NotNull ConnectionTreePanel treePanel) {
        super("Edit Data", "Preview and edit the selected table's rows", AllIcons.Actions.Preview);
        this.treePanel = treePanel;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        TableRef table = treePanel.selectedTable();
        if (table != null) {
            treePanel.viewTableData(table);
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
