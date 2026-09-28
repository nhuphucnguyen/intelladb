package community.intelladb.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import community.intelladb.IntellaDbIcons;
import org.jetbrains.annotations.NotNull;

/**
 * "AI Assistant" — a tool window in its own right, separate from the DB Explorer, so the
 * connection tree and the chat can be used side by side (or switched from the stripe).
 */
public final class AiAssistantToolWindowFactory implements ToolWindowFactory {

    public static final String ID = "AI Assistant";

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        toolWindow.setIcon(IntellaDbIcons.AI);
        toolWindow.setStripeTitle(ID);
        AiChatPanel panel = new AiChatPanel(project);
        var content = toolWindow.getContentManager().getFactory().createContent(panel, "", false);
        content.setDisposer(panel);
        toolWindow.getContentManager().addContent(content);
    }
}
