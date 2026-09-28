package community.intelladb.ui;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Owns the per-console results views (Output log + result tabs) shown in the "DB Services"
 * tool window. Views live here rather than in the tool window so a console can run before
 * the tool window was ever opened; the tool window panel only displays them.
 */
@Service(Service.Level.PROJECT)
public final class ResultsHub implements Disposable {

    public static final String TOOL_WINDOW_ID = "DB Services";

    private final Project project;
    private final Map<SqlConsole, ConsoleResultsView> views = new LinkedHashMap<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private @Nullable Consumer<SqlConsole> selector;

    public ResultsHub(@NotNull Project project) {
        this.project = project;
    }

    public static @NotNull ResultsHub getInstance(@NotNull Project project) {
        return project.getService(ResultsHub.class);
    }

    @NotNull ConsoleResultsView viewFor(@NotNull SqlConsole console) {
        ConsoleResultsView view = views.get(console);
        if (view == null) {
            view = new ConsoleResultsView(project, console);
            Disposer.register(this, view);
            views.put(console, view);
            listeners.forEach(Runnable::run);
        }
        return view;
    }

    @NotNull List<ConsoleResultsView> views() {
        return new ArrayList<>(views.values());
    }

    void addListener(@NotNull Runnable listener, @NotNull Disposable parent) {
        listeners.add(listener);
        Disposer.register(parent, () -> listeners.remove(listener));
    }

    /** Set by the tool window panel so {@link #reveal} can select the console's node. */
    void setSelector(@Nullable Consumer<SqlConsole> selector) {
        this.selector = selector;
    }

    /** Shows the DB Services tool window on this console's results, keeping focus in the editor. */
    void reveal(@NotNull SqlConsole console) {
        ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID);
        if (toolWindow == null) {
            return;
        }
        toolWindow.show(() -> {
            if (selector != null) {
                selector.accept(console);
            }
        });
    }

    @Override
    public void dispose() {
        views.clear();
        listeners.clear();
    }
}
