package dev.phucngu.intelladb.ui;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.StoragePathMacros;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectCloseListener;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * What each connection's SQL console had at the last save — text, default schema and
 * whether its tab was open — so consoles survive an IDE restart. Kept in the workspace
 * file (per user, not shared through version control).
 */
@Service(Service.Level.PROJECT)
@State(name = "IntellaDbConsoles", storages = @Storage(StoragePathMacros.WORKSPACE_FILE))
public final class ConsoleStore implements PersistentStateComponent<ConsoleStore.State> {

    /** XML-serializable state (public fields). */
    public static final class State {
        public List<ConsoleState> consoles = new ArrayList<>();
    }

    public static final class ConsoleState {
        public String connectionId = "";
        public String sql = "";
        public @Nullable String schema;
        /** The schema's database when the connection browses every database; null for the one it starts on. */
        public @Nullable String database;
        public boolean open;
    }

    private State state = new State();
    /**
     * Set once the project starts closing: the platform then closes every tab, which must
     * not be remembered as the user closing the consoles.
     */
    private boolean closing;

    public ConsoleStore(@NotNull Project project) {
        project.getMessageBus().connect().subscribe(ProjectCloseListener.TOPIC, new ProjectCloseListener() {
            @Override
            public void projectClosingBeforeSave(@NotNull Project closed) {
                if (closed == project) {
                    closing = true;
                }
            }
        });
    }

    public static @NotNull ConsoleStore getInstance(@NotNull Project project) {
        return project.getService(ConsoleStore.class);
    }

    @Override
    public @NotNull State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State loaded) {
        state = loaded;
    }

    /** The saved console of the connection, or null if it never had one. */
    public @Nullable ConsoleState find(@NotNull String connectionId) {
        return state.consoles.stream().filter(c -> c.connectionId.equals(connectionId)).findFirst().orElse(null);
    }

    /** Connection ids whose console tab was open. */
    public @NotNull List<String> openConsoles() {
        return state.consoles.stream().filter(c -> c.open).map(c -> c.connectionId).toList();
    }

    public void setSql(@NotNull String connectionId, @NotNull String sql) {
        get(connectionId).sql = sql;
    }

    public void setSchema(@NotNull String connectionId, @Nullable String database, @Nullable String schema) {
        ConsoleState console = get(connectionId);
        console.database = database;
        console.schema = schema;
    }

    public void setOpen(@NotNull String connectionId, boolean open) {
        if (!closing) {
            get(connectionId).open = open;
        }
    }

    /** Forgets the console of a deleted connection. */
    public void remove(@NotNull String connectionId) {
        state.consoles.removeIf(c -> c.connectionId.equals(connectionId));
    }

    private @NotNull ConsoleState get(@NotNull String connectionId) {
        ConsoleState existing = find(connectionId);
        if (existing != null) {
            return existing;
        }
        ConsoleState created = new ConsoleState();
        created.connectionId = connectionId;
        state.consoles.add(created);
        return created;
    }
}
