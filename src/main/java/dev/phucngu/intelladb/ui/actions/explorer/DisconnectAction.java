package dev.phucngu.intelladb.ui.actions.explorer;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import dev.phucngu.intelladb.ui.ConnectionTreePanel;
import org.jetbrains.annotations.NotNull;

/** Disconnects the selected connection (IntelliJ's "Deactivate"). */
public final class DisconnectAction extends AnAction {

    private final ConnectionTreePanel treePanel;

    public DisconnectAction(@NotNull ConnectionTreePanel treePanel) {
        super("Disconnect", "Disconnect the selected connection", AllIcons.Actions.Suspend);
        this.treePanel = treePanel;
    }

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
}
