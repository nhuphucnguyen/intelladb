package community.intelladb.history;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import community.intelladb.connection.DbConfig;
import community.intelladb.connection.SqlResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;

/**
 * Recently executed console queries with their results, newest first, so earlier results
 * can be reopened and compared without running the query again. Keeps the last N entries
 * (application setting, see {@link #limit()}), in memory for the IDE session; each entry
 * holds the fetched rows (at most {@link SqlResult#MAX_ROWS}). Accessed on the EDT.
 */
@Service(Service.Level.PROJECT)
public final class QueryHistory {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MIN_LIMIT = 5;
    public static final int MAX_LIMIT = 500;
    private static final String LIMIT_KEY = "intelladb.history.size";
    private static final AtomicLong IDS = new AtomicLong();

    /**
     * One executed statement.
     *
     * @param schema default schema the console had selected (null = connection default)
     */
    public record Entry(long id, @NotNull LocalDateTime executedAt, @NotNull String connectionId,
                        @NotNull String connectionName, @Nullable String schema, @NotNull String sql,
                        @NotNull SqlResult result) {
    }

    private final Deque<Entry> entries = new ArrayDeque<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final IntSupplier limit;

    public QueryHistory(@NotNull Project project) {
        this(QueryHistory::configuredLimit);
    }

    /** For tests: a history with a fixed or custom limit source. */
    public QueryHistory(@NotNull IntSupplier limit) {
        this.limit = limit;
    }

    public static @NotNull QueryHistory getInstance(@NotNull Project project) {
        return project.getService(QueryHistory.class);
    }

    public @NotNull Entry add(@NotNull DbConfig config, @Nullable String schema, @NotNull String sql,
                              @NotNull SqlResult result) {
        Entry entry = new Entry(IDS.incrementAndGet(), LocalDateTime.now(), config.id, config.name,
                schema, sql.strip(), result);
        entries.addFirst(entry);
        trim();
        fireChanged();
        return entry;
    }

    /** Newest first. */
    public @NotNull List<Entry> entries() {
        return new ArrayList<>(entries);
    }

    public void clear() {
        entries.clear();
        fireChanged();
    }

    public int limit() {
        return clamp(limit.getAsInt());
    }

    /** Applies a changed limit (drops the oldest entries beyond it). */
    public void limitChanged() {
        if (trim()) {
            fireChanged();
        }
    }

    public void addListener(@NotNull Runnable listener, @NotNull Disposable parent) {
        listeners.add(listener);
        Disposer.register(parent, () -> listeners.remove(listener));
    }

    private boolean trim() {
        boolean trimmed = false;
        int max = limit();
        while (entries.size() > max) {
            entries.removeLast();
            trimmed = true;
        }
        return trimmed;
    }

    private void fireChanged() {
        listeners.forEach(Runnable::run);
    }

    // ------------------------------------------------------------------ setting

    public static int configuredLimit() {
        return clamp(PropertiesComponent.getInstance().getInt(LIMIT_KEY, DEFAULT_LIMIT));
    }

    public static void setConfiguredLimit(int value) {
        PropertiesComponent.getInstance().setValue(LIMIT_KEY, clamp(value), DEFAULT_LIMIT);
    }

    private static int clamp(int value) {
        return Math.max(MIN_LIMIT, Math.min(MAX_LIMIT, value));
    }
}
