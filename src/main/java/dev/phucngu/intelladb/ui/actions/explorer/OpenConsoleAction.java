package dev.phucngu.intelladb.ui.actions.explorer;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import dev.phucngu.intelladb.connection.DbConfig;
import dev.phucngu.intelladb.ui.ConnectionTreePanel;
import org.jetbrains.annotations.NotNull;

/** Opens the SQL console of the selected connection (connecting it first if needed). */
public final class OpenConsoleAction extends AnAction {

    private final ConnectionTreePanel treePanel;

    public OpenConsoleAction(@NotNull ConnectionTreePanel treePanel) {
        super("SQL Console", "Open a SQL console for the selected connection", AllIcons.Nodes.Console);
        this.treePanel = treePanel;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        DbConfig config = treePanel.selectedConfig();
        if (config != null) {
            treePanel.openConsoleFor(config);
        }
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
