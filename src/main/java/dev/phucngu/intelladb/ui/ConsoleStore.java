package dev.phucngu.intelladb.ui;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.StoragePathMacros;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectCloseListener;
import com.intellij.openapi.util.Disposer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

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
        /** Epoch millis of the last edit or run; 0 for consoles saved before this was tracked. */
        public long lastUsed;
    }

    private State state = new State();
    /**
     * Set once the project starts closing: the platform then closes every tab, which must
     * not be remembered as the user closing the consoles.
     */
    private boolean closing;
    private final Project project;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    public ConsoleStore(@NotNull Project project) {
        this.project = project;
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

    /** Every saved console, oldest first. */
    public @NotNull List<ConsoleState> all() {
        return List.copyOf(state.consoles);
    }

    /** Whether the project is closing, when closed tabs are not the user's doing. */
    boolean isClosing() {
        return closing;
    }

    /**
     * A fresh named console for the connection: "console", then "console 2", … — the first
     * name the connection's consoles don't use, so deleting one doesn't produce duplicates.
     */
    public @NotNull ConsoleState create(@NotNull String connectionId) {
        ConsoleState created = new ConsoleState();
        created.id = UUID.randomUUID().toString();
        created.connectionId = connectionId;
        List<String> taken = ofConnection(connectionId).stream().map(c -> c.name).toList();
        String name = "console";
        for (int n = 2; taken.contains(name); n++) {
            name = "console " + n;
        }
        created.name = name;
        created.lastUsed = System.currentTimeMillis();
        state.consoles.add(created);
        fireChanged();
        return created;
    }

    /** Forgets the consoles, text and all. */
    public void delete(@NotNull Collection<String> consoleIds) {
        if (state.consoles.removeIf(c -> consoleIds.contains(c.id))) {
            fireChanged();
        }
    }

    /** An edit; it counts as use. */
    public void setSql(@NotNull String consoleId, @NotNull String sql) {
        ConsoleState console = find(consoleId);
        if (console != null) {
            console.sql = sql;
            console.lastUsed = System.currentTimeMillis();
        }
    }

    /** Marks the console used now (a run). */
    public void touch(@NotNull String consoleId) {
        ConsoleState console = find(consoleId);
        if (console != null) {
            console.lastUsed = System.currentTimeMillis();
        }
    }

    public void setSchema(@NotNull String consoleId, @Nullable String database, @Nullable String schema) {
        ConsoleState console = find(consoleId);
        if (console != null) {
            console.database = database;
            console.schema = schema;
            fireChanged();
        }
    }

    public void setName(@NotNull String consoleId, @Nullable String name) {
        ConsoleState console = find(consoleId);
        if (console != null && name != null && !name.isBlank()) {
            console.name = name.strip();
            fireChanged();
        }
    }

    public void setOpen(@NotNull String consoleId, boolean open) {
        if (!closing) {
            ConsoleState console = find(consoleId);
            if (console != null && console.open != open) {
                console.open = open;
                fireChanged();
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
            fireChanged();
        }
    }

    /** Forgets every console of a deleted connection. */
    public void remove(@NotNull String connectionId) {
        if (state.consoles.removeIf(c -> c.connectionId.equals(connectionId))) {
            fireChanged();
        }
    }

    /**
     * Whether the console holds nothing worth keeping: no text, or only the header line a
     * new console starts with. A console with notes or SQL of the user's is never empty.
     */
    public static boolean isEmpty(@Nullable String sql) {
        String text = sql == null ? "" : sql.strip();
        return text.isEmpty() || !text.contains("\n")
                && (text.startsWith(SqlConsole.SQL_HEADER) || text.startsWith(SqlConsole.MONGO_HEADER));
    }

    /** "database.schema", "schema" or "" — what the console runs against besides the connection default. */
    public static @NotNull String scope(@NotNull ConsoleState console) {
        String database = console.database == null || console.database.isBlank() ? null : console.database;
        String schema = console.schema == null || console.schema.isBlank() ? null : console.schema;
        if (database != null && schema != null) {
            return database + "." + schema;
        }
        return database != null ? database : schema != null ? schema : "";
    }

    /** The first line that isn't blank or the header — a console's one-line preview. */
    public static @NotNull String preview(@Nullable String sql) {
        if (sql != null) {
            for (String line : sql.split("\n")) {
                String stripped = line.strip();
                if (!stripped.isEmpty() && !stripped.startsWith(SqlConsole.SQL_HEADER)
                        && !stripped.startsWith(SqlConsole.MONGO_HEADER)) {
                    return stripped;
                }
            }
        }
        return "";
    }

    /** Runs on the EDT after consoles are added, removed, renamed, re-scoped, opened or closed. */
    public void addListener(@NotNull Runnable listener, @NotNull Disposable parent) {
        listeners.add(listener);
        Disposer.register(parent, () -> listeners.remove(listener));
    }

    private void fireChanged() {
        ApplicationManager.getApplication().invokeLater(() -> listeners.forEach(Runnable::run), project.getDisposed());
    }
}
