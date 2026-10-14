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
import java.util.UUID;

/**
 * What each SQL console had at the last save — text, name, default schema and whether its
 * tab was open — so consoles survive an IDE restart. A connection can own several named
 * consoles. Kept in the workspace file (per user, not shared through version control).
 */
@Service(Service.Level.PROJECT)
@State(name = "IntellaDbConsoles", storages = @Storage(StoragePathMacros.WORKSPACE_FILE))
public final class ConsoleStore implements PersistentStateComponent<ConsoleStore.State> {

    /** XML-serializable state (public fields). */
    public static final class State {
        public List<ConsoleState> consoles = new ArrayList<>();
    }

    public static final class ConsoleState {
        public String id = "";
        public String connectionId = "";
        public String name = "console";
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
        // One anonymous console per connection before named consoles: it becomes that
        // connection's "console", keyed by the connection id itself.
        for (ConsoleState console : state.consoles) {
            if (console.id.isBlank()) {
                console.id = console.connectionId;
            }
            if (console.name == null || console.name.isBlank()) {
                console.name = "console";
            }
        }
    }

    /** The saved console with this id, or null. */
    public @Nullable ConsoleState find(@NotNull String consoleId) {
        return state.consoles.stream().filter(c -> c.id.equals(consoleId)).findFirst().orElse(null);
    }

    /** The connection's consoles, oldest first. */
    public @NotNull List<ConsoleState> ofConnection(@NotNull String connectionId) {
        return state.consoles.stream().filter(c -> c.connectionId.equals(connectionId)).toList();
    }

    /** Console ids whose tab was open. */
    public @NotNull List<String> openConsoles() {
        return state.consoles.stream().filter(c -> c.open).map(c -> c.id).toList();
    }

    /** A fresh named console for the connection ("console", then "console 2", …). */
    public @NotNull ConsoleState create(@NotNull String connectionId) {
        ConsoleState created = new ConsoleState();
        created.id = UUID.randomUUID().toString();
        created.connectionId = connectionId;
        int existing = ofConnection(connectionId).size();
        created.name = existing == 0 ? "console" : "console " + (existing + 1);
        state.consoles.add(created);
        return created;
    }

    public void setSql(@NotNull String consoleId, @NotNull String sql) {
        ConsoleState console = find(consoleId);
        if (console != null) {
            console.sql = sql;
        }
    }

    public void setSchema(@NotNull String consoleId, @Nullable String database, @Nullable String schema) {
        ConsoleState console = find(consoleId);
        if (console != null) {
            console.database = database;
            console.schema = schema;
        }
    }

    public void setName(@NotNull String consoleId, @Nullable String name) {
        ConsoleState console = find(consoleId);
        if (console != null && name != null && !name.isBlank()) {
            console.name = name.strip();
        }
    }

    public void setOpen(@NotNull String consoleId, boolean open) {
        if (!closing) {
            ConsoleState console = find(consoleId);
            if (console != null) {
                console.open = open;
            }
        }
    }

    /** Points a console at another connection (the schema selector's neighbour). */
    public void rebind(@NotNull String consoleId, @NotNull String connectionId) {
        ConsoleState console = find(consoleId);
        if (console != null) {
            console.connectionId = connectionId;
            console.schema = null;
            console.database = null;
        }
    }

    /** Forgets every console of a deleted connection. */
    public void remove(@NotNull String connectionId) {
        state.consoles.removeIf(c -> c.connectionId.equals(connectionId));
    }
}
