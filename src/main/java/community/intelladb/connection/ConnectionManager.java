package community.intelladb.connection;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.Credentials;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Project-level store of saved connections plus the live {@link DbSession}s.
 * Passwords live in the IDE PasswordSafe (or in memory for the session when the
 * user chose not to save them).
 */
@Service(Service.Level.PROJECT)
@State(name = "IntellaDbConnections", storages = @Storage("intella-db.xml"))
public final class ConnectionManager implements PersistentStateComponent<ConnectionManager.State> {

    /** XML-serializable state (public fields, no passwords). */
    public static final class State {
        public List<DbConfig> connections = new ArrayList<>();
    }

    private final Project project;
    private final State state = new State();
    private final Map<String, DbSession> sessions = new HashMap<>();
    /** Session-only passwords for configs with savePassword=false. */
    private final Map<String, String> memoryPasswords = new HashMap<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    public ConnectionManager(@NotNull Project project) {
        this.project = project;
    }

    public static @NotNull ConnectionManager getInstance(@NotNull Project project) {
        return project.getService(ConnectionManager.class);
    }

    @Override
    public @Nullable State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State loaded) {
        state.connections.clear();
        state.connections.addAll(loaded.connections);
    }

    // ------------------------------------------------------------------ configs

    public @NotNull List<DbConfig> configs() {
        return List.copyOf(state.connections);
    }

    public @Nullable DbConfig findConfig(@NotNull String id) {
        return state.connections.stream().filter(c -> c.id.equals(id)).findFirst().orElse(null);
    }

    public void saveConfig(@NotNull DbConfig config, @Nullable String password, boolean passwordChanged) {
        boolean isNew = findConfig(config.id) == null;
        state.connections.removeIf(c -> c.id.equals(config.id));
        state.connections.add(config);
        if (isNew || passwordChanged) {
            if (config.savePassword && password != null && !password.isBlank()) {
                storePassword(config.id, password);
                memoryPasswords.remove(config.id);
            } else {
                clearPassword(config.id);
                if (password != null && !password.isBlank()) {
                    memoryPasswords.put(config.id, password);
                }
            }
        }
        fireChanged();
    }

    public void deleteConfig(@NotNull String id) {
        state.connections.removeIf(c -> c.id.equals(id));
        memoryPasswords.remove(id);
        clearPassword(id);
        DbSession session = sessions.remove(id);
        if (session != null) {
            session.close();
        }
        fireChanged();
    }

    // ------------------------------------------------------------------ passwords

    private static @NotNull CredentialAttributes attributes(@NotNull String configId) {
        return new CredentialAttributes("Intella DB", configId, ConnectionManager.class, false);
    }

    private static void storePassword(@NotNull String configId, @NotNull String password) {
        try {
            PasswordSafe.getInstance().set(attributes(configId), new Credentials(configId, password));
        } catch (Exception ignored) {
            // PasswordSafe can be unavailable (e.g. no keychain access); fail soft.
        }
    }

    private static void clearPassword(@NotNull String configId) {
        try {
            PasswordSafe.getInstance().set(attributes(configId), null);
        } catch (Exception ignored) {
        }
    }

    public @Nullable String readPassword(@NotNull DbConfig config) {
        String memory = memoryPasswords.get(config.id);
        if (memory != null) {
            return memory;
        }
        if (!config.savePassword) {
            return null;
        }
        try {
            Credentials credentials = PasswordSafe.getInstance().get(attributes(config.id));
            return credentials != null ? credentials.getPasswordAsString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Remembers a password typed interactively (in memory only, for this IDE run). */
    public void rememberPasswordInMemory(@NotNull DbConfig config, @NotNull String password) {
        memoryPasswords.put(config.id, password);
    }

    /**
     * Stores the password in the PasswordSafe and marks the connection to save passwords,
     * so interactive prompts stay a one-time event for this connection.
     */
    public void rememberPassword(@NotNull DbConfig config, @NotNull String password) {
        config.savePassword = true;
        saveConfig(config, password, true);
    }

    // ------------------------------------------------------------------ sessions

    /** Returns the live session, or null when not connected. */
    public @Nullable DbSession session(@NotNull String configId) {
        DbSession session = sessions.get(configId);
        return session != null && session.isOpen() ? session : null;
    }

    /** Opens (or reuses) a session on the current thread. Must not be the EDT. */
    public @NotNull DbSession connect(@NotNull DbConfig config, @Nullable String password) throws Exception {
        DbSession existing = sessions.get(config.id);
        if (existing != null && existing.isOpen()) {
            return existing;
        }
        if (existing != null) {
            existing.close();
        }
        DbSession session = new DbSession(config, password);
        session.ensureOpen();
        session.loadCatalog();
        sessions.put(config.id, session);
        if (password != null && !config.savePassword) {
            memoryPasswords.put(config.id, password);
        }
        fireChanged();
        return session;
    }

    public void disconnect(@NotNull String configId) {
        DbSession session = sessions.remove(configId);
        if (session != null) {
            session.close();
        }
        fireChanged();
    }

    public void disconnectAll() {
        sessions.values().forEach(DbSession::close);
        sessions.clear();
        fireChanged();
    }

    // ------------------------------------------------------------------ change notification

    /** Listener runs on the EDT. */
    public void addListener(@NotNull Runnable listener) {
        listeners.add(listener);
    }

    private void fireChanged() {
        ApplicationManager.getApplication().invokeLater(() -> listeners.forEach(Runnable::run));
    }
}
