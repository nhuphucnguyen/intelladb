package dev.phucngu.intelladb.ui.actions;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import dev.phucngu.intelladb.ui.DbExplorerPanel;
import org.jetbrains.annotations.NotNull;

/**
 * "Data Sources…" as a registered action — Find Action and Keymap can reach it even when
 * the Database tool window is closed. Works on the tool window's connection selection.
 */
public final class OpenDataSourcesAction extends DumbAwareAction {

    public OpenDataSourcesAction() {
        super("Data Sources…", "Open the IntellaDB Data Sources dialog", AllIcons.Actions.Properties);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        DbExplorerPanel explorer = project == null ? null : DbExplorerPanel.find(project);
        if (explorer != null) {
            explorer.tree().showDataSourcesDialog(explorer.selectedConfig(), null);
        }
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabled(e.getProject() != null
                && DbExplorerPanel.find(e.getProject()) != null);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.EDT;
    }
}
