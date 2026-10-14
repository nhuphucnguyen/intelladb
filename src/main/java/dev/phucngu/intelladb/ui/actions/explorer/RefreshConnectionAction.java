package dev.phucngu.intelladb.ui.actions.explorer;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import dev.phucngu.intelladb.ui.ConnectionTreePanel;
import org.jetbrains.annotations.NotNull;

/** Reloads the schema of the selected connection, connecting it first if needed. */
public final class RefreshConnectionAction extends AnAction {

    private final ConnectionTreePanel treePanel;

    public RefreshConnectionAction(@NotNull ConnectionTreePanel treePanel) {
        super("Refresh", "Reload the schema of the selected connection", AllIcons.Actions.Refresh);
        this.treePanel = treePanel;
    }

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
}
