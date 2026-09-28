package community.intelladb.ui;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import org.jetbrains.annotations.NotNull;

/**
 * Registers the bottom "DB Services" tool window: "Consoles" (per-console output and result
 * tabs) and "History" (recent queries with their cached results).
 */
public final class DbServicesToolWindowFactory implements ToolWindowFactory, DumbAware {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        DbServicesPanel panel = new DbServicesPanel(project);
        Content consoles = toolWindow.getContentManager().getFactory()
                .createContent(panel, ResultsHub.CONSOLES_TAB, false);
        consoles.setCloseable(false);
        consoles.setDisposer(panel);
        toolWindow.getContentManager().addContent(consoles);

        QueryHistoryPanel history = new QueryHistoryPanel(project);
        Content historyContent = toolWindow.getContentManager().getFactory()
                .createContent(history, ResultsHub.HISTORY_TAB, false);
        historyContent.setCloseable(false);
        historyContent.setDisposer(history);
        toolWindow.getContentManager().addContent(historyContent);
    }
}
