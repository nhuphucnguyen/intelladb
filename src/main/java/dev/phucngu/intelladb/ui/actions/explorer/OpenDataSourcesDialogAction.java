package dev.phucngu.intelladb.ui.actions.explorer;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import dev.phucngu.intelladb.ui.ConnectionTreePanel;
import org.jetbrains.annotations.NotNull;

/**
 * "Data Sources…" in the toolbar (IntelliJ shows it as "Properties" in the tree's
 * context menu): opens the Data Sources dialog with the selected connection's entry.
 */
public final class OpenDataSourcesDialogAction extends ConnectionDialogAction {

    public OpenDataSourcesDialogAction(@NotNull Project project, @NotNull ConnectionTreePanel treePanel) {
        super(project, treePanel, "Data Sources…", AllIcons.Actions.Properties);
        getTemplatePresentation().setDescription("Open the Data Sources dialog");
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.EDT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        // In a context menu IntelliJ calls this "Properties" because it edits the
        // selection; in the toolbar it opens the whole dialog.
        e.getPresentation().setText(e.getInputEvent() instanceof java.awt.event.MouseEvent
                ? "Properties…" : "Data Sources…");
    }
}
