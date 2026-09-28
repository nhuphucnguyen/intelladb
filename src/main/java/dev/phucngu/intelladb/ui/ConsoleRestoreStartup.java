package dev.phucngu.intelladb.ui;

import com.intellij.openapi.application.WriteIntentReadAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import com.intellij.openapi.wm.ToolWindowManager;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Reopens the SQL console tabs that were open when the project was last closed. */
public final class ConsoleRestoreStartup implements ProjectActivity {

    @Override
    public @Nullable Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        if (ConsoleStore.getInstance(project).openConsoles().isEmpty()) {
            return Unit.INSTANCE;
        }
        // Once tool windows are registered; find() creates the explorer that owns the consoles.
        // That callback holds no lock, and creating a console touches its document (and
        // writes the restored text), so take the write-intent lock like a normal EDT event.
        ToolWindowManager.getInstance(project).invokeLater(() -> WriteIntentReadAction.run((Runnable) () -> {
            DbExplorerPanel explorer = project.isDisposed() ? null : DbExplorerPanel.find(project);
            if (explorer != null) {
                explorer.restoreConsoles();
            }
        }));
        return Unit.INSTANCE;
    }
}
