package dev.phucngu.intelladb.ui.actions;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import dev.phucngu.intelladb.connection.DbConfig;
import dev.phucngu.intelladb.ui.DbExplorerPanel;
import org.jetbrains.annotations.NotNull;

/**
 * "New SQL Console" as a registered action: opens a fresh console for the connection
 * selected in the Database tool window (connecting it first if needed).
 */
public final class NewConsoleAction extends DumbAwareAction {

    public NewConsoleAction() {
        super("New SQL Console", "Open a fresh SQL console for the selected connection",
                AllIcons.Nodes.Console);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        DbExplorerPanel explorer = project == null ? null : DbExplorerPanel.find(project);
        DbConfig config = explorer == null ? null : explorer.selectedConfig();
        if (config != null) {
            explorer.tree().newConsoleFor(config, null, null);
        }
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        DbExplorerPanel explorer = project == null ? null : DbExplorerPanel.find(project);
        e.getPresentation().setEnabled(explorer != null && explorer.selectedConfig() != null);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.EDT;
    }
}
