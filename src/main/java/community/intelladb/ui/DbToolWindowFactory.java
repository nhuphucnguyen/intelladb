package community.intelladb.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import community.intelladb.IntellaDbIcons;
import org.jetbrains.annotations.NotNull;

/** Registers the "DB Explorer" tool window. */
public final class DbToolWindowFactory implements ToolWindowFactory {

    public static final String TOOL_WINDOW_ID = "DB Explorer";

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        toolWindow.setIcon(IntellaDbIcons.TOOL_WINDOW);
        toolWindow.setStripeTitle("DB Explorer");
        DbExplorerPanel panel = new DbExplorerPanel(project);
        var content = toolWindow.getContentManager().getFactory().createContent(panel, "", false);
        content.setDisposer(panel);
        toolWindow.getContentManager().addContent(content);
    }
}
