package dev.phucngu.intelladb.connection;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Connections shared by every project in the IDE ("Global Data Sources" in IntelliJ's
 * database tools), stored at application level. Each project's {@link ConnectionManager}
 * lists them before its own and keeps its own sessions for them; the passwords are the
 * same PasswordSafe entries (keyed by connection id) whichever project uses them.
 */
@Service(Service.Level.APP)
@State(name = "IntellaDbGlobalConnections", storages = @Storage("intella-db-global.xml"))
public final class GlobalConnections implements PersistentStateComponent<GlobalConnections.State> {

    /** XML-serializable state (public fields, no passwords). */
    public static final class State {
        public List<DbConfig> connections = new ArrayList<>();
    }

    private final State state = new State();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    public static @NotNull GlobalConnections getInstance() {
        return ApplicationManager.getApplication().getService(GlobalConnections.class);
    }

    @Override
    public @NotNull State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State loaded) {
        state.connections.clear();
        state.connections.addAll(loaded.connections);
    }

    @NotNull List<DbConfig> configs() {
        return List.copyOf(state.connections);
    }

    @Nullable DbConfig find(@NotNull String id) {
        return state.connections.stream().filter(c -> c.id.equals(id)).findFirst().orElse(null);
    }

    /** Adds or replaces (by id) the connection. */
    void put(@NotNull DbConfig config) {
        int index = indexOf(config.id);
        if (index >= 0) {
            state.connections.set(index, config);
        } else {
            state.connections.add(config);
        }
        fireChanged();
    }

    boolean remove(@NotNull String id) {
        boolean removed = state.connections.removeIf(c -> c.id.equals(id));
        if (removed) {
            fireChanged();
        }
        return removed;
    }

    private int indexOf(@NotNull String id) {
        for (int i = 0; i < state.connections.size(); i++) {
            if (state.connections.get(i).id.equals(id)) {
                return i;
            }
        }
        return -1;
    }

    /** Runs on the EDT whenever a global connection is added, changed or removed (from any project). */
    void addListener(@NotNull Runnable listener) {
        listeners.add(listener);
    }

    void removeListener(@NotNull Runnable listener) {
        listeners.remove(listener);
    }

    private void fireChanged() {
        ApplicationManager.getApplication().invokeLater(() -> listeners.forEach(Runnable::run));
    }
}
