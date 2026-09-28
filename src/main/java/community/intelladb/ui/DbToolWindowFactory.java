package community.intelladb.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import community.intelladb.IntellaDbIcons;
import org.jetbrains.annotations.NotNull;

/**
 * Registers the "DB Explorer" tool window with two native header tabs — "Explorer"
 * (connection tree + DB actions) and "AI Assistant" (chat + AI actions) — so each gets
 * the full tool-window width and its own toolbar instead of sharing a split pane.
 */
public final class DbToolWindowFactory implements ToolWindowFactory {

    public static final String TOOL_WINDOW_ID = "DB Explorer";

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        toolWindow.setIcon(IntellaDbIcons.TOOL_WINDOW);
        toolWindow.setStripeTitle("DB Explorer");
        ContentFactory factory = toolWindow.getContentManager().getFactory();

        DbExplorerPanel explorer = new DbExplorerPanel(project);
        Content explorerContent = factory.createContent(explorer, "Explorer", false);
        explorerContent.setCloseable(false);
        explorerContent.setDisposer(explorer); // also disposes the chat it owns
        toolWindow.getContentManager().addContent(explorerContent);

        Content aiContent = factory.createContent(explorer.aiPanel(), "AI Assistant", false);
        aiContent.setCloseable(false);
        aiContent.setIcon(IntellaDbIcons.AI);
        aiContent.putUserData(com.intellij.openapi.wm.ToolWindow.SHOW_CONTENT_ICON, true);
        toolWindow.getContentManager().addContent(aiContent);
    }
}
