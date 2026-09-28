package community.intelladb.ui;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import org.jetbrains.annotations.NotNull;

/** Registers the bottom "DB Services" tool window that shows console output and result sets. */
public final class DbServicesToolWindowFactory implements ToolWindowFactory, DumbAware {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        DbServicesPanel panel = new DbServicesPanel(project);
        Content content = toolWindow.getContentManager().getFactory().createContent(panel, "", false);
        content.setCloseable(false);
        content.setDisposer(panel);
        toolWindow.getContentManager().addContent(content);
    }
}
