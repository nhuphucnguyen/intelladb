package community.intelladb.connection;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Connects to a saved connection on background threads, prompting for the password when
 * needed, and runs the caller's action with the live session on the EDT. Shared by the
 * DB Explorer and the AI Assistant tool windows. UI state (e.g. the tree's "connecting…")
 * subscribes via {@link Listener}.
 */
@Service(Service.Level.PROJECT)
public final class SessionOpener {

    /** UI hooks for connect lifecycle; all callbacks arrive on the EDT. */
    public interface Listener {
        default void connecting(@NotNull DbConfig config) {
        }

        default void connected(@NotNull DbConfig config, @NotNull DbSession session) {
        }

        default void failed(@NotNull DbConfig config, @NotNull String message) {
        }
    }

    private final Project project;
    private final ConnectionManager manager;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    public SessionOpener(@NotNull Project project) {
        this.project = project;
        this.manager = ConnectionManager.getInstance(project);
    }

    public static @NotNull SessionOpener getInstance(@NotNull Project project) {
        return project.getService(SessionOpener.class);
    }

    public void addListener(@NotNull Listener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public @NotNull ConnectionManager manager() {
        return manager;
    }

    public @NotNull List<DbConfig> configs() {
        return manager.configs();
    }

    public @Nullable DbSession sessionOf(@Nullable DbConfig config) {
        return config == null ? null : manager.session(config.id);
    }

    /**
     * Runs {@code action} on the EDT with a live session for {@code config}, connecting
     * (and prompting for a password) when needed. Credentials are reused in this order:
     * live session → in-memory password for this IDE run → PasswordSafe (when the
     * connection was saved with "save password").
     */
    public void withSession(@NotNull DbConfig config, @NotNull Consumer<DbSession> action) {
        DbSession existing = manager.session(config.id);
        if (existing != null) {
            action.accept(existing);
            return;
        }
        // PasswordSafe access must not run on the EDT.
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            String password = manager.readPassword(config);
            if (password != null) {
                connectInBackground(config, password, action);
                return;
            }
            ApplicationManager.getApplication().invokeLater(() -> {
                community.intelladb.ui.PasswordPromptDialog prompt =
                        new community.intelladb.ui.PasswordPromptDialog(project, config);
                if (!prompt.showAndGet()) {
                    return;
                }
                String typed = prompt.password();
                if (typed.isBlank()) {
                    return;
                }
                if (prompt.rememberPassword()) {
                    // Persists to the PasswordSafe and flips the connection to
                    // savePassword=true, so this prompt does not come back.
                    manager.rememberPassword(config, typed);
                } else {
                    manager.rememberPasswordInMemory(config, typed);
                }
                connectInBackground(config, typed, action);
            });
        });
    }

    private void connectInBackground(@NotNull DbConfig config, @NotNull String password,
                                     @NotNull Consumer<DbSession> action) {
        fire(listener -> listener.connecting(config));
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                DbSession session = manager.connect(config, password);
                ApplicationManager.getApplication().invokeLater(() -> {
                    fire(listener -> listener.connected(config, session));
                    action.accept(session);
                });
            } catch (Exception ex) {
                String message = ex.getMessage() == null ? ex.toString() : ex.getMessage();
                ApplicationManager.getApplication().invokeLater(() -> {
                    fire(listener -> listener.failed(config, message));
                    Messages.showErrorDialog(project,
                            "Could not connect to " + config.describe() + ":\n" + message,
                            "Intella DB");
                });
            }
        });
    }

    private void fire(@NotNull java.util.function.Consumer<Listener> call) {
        for (Listener listener : listeners) {
            call.accept(listener);
        }
    }
}
