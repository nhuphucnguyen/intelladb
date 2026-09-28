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
 * (application setting, see {@link #limit()}); each entry holds the fetched rows (at most
 * {@link SqlResult#MAX_ROWS}). Persisted per project in the IDE system directory — not
 * under the project, so cached result rows never end up in version control — and saved
 * in the background after every change. Accessed on the EDT.
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
    private final @Nullable QueryHistoryStore store;
    /** Saves one at a time, in order; a newer snapshot simply overwrites the file again. */
    private final java.util.concurrent.ExecutorService saver;

    public QueryHistory(@NotNull Project project) {
        this(QueryHistory::configuredLimit, new QueryHistoryStore(storeFile(project)));
    }

    /** For tests: a history with a fixed or custom limit source, kept in memory only. */
    public QueryHistory(@NotNull IntSupplier limit) {
        this(limit, null);
    }

    /** For tests: as {@link #QueryHistory(IntSupplier)}, persisted through {@code store}. */
    public QueryHistory(@NotNull IntSupplier limit, @Nullable QueryHistoryStore store) {
        this.limit = limit;
        this.store = store;
        this.saver = store == null ? null : com.intellij.util.concurrency.SequentialTaskExecutor
                .createSequentialApplicationPoolExecutor("IntellaDB query history");
        if (store != null) {
            List<Entry> loaded = store.load();
            entries.addAll(loaded);
            loaded.forEach(entry -> IDS.accumulateAndGet(entry.id(), Math::max));
            trim();
        }
    }

    private static @NotNull java.nio.file.Path storeFile(@NotNull Project project) {
        return java.nio.file.Path.of(com.intellij.openapi.application.PathManager.getSystemPath(),
                "intelladb", "history", project.getLocationHash() + ".json.gz");
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
        save();
        listeners.forEach(Runnable::run);
    }

    /** For tests: blocks until every save queued so far has been written. */
    @org.jetbrains.annotations.TestOnly
    public void awaitSaved() throws Exception {
        if (saver != null) {
            saver.submit(() -> { }).get();
        }
    }

    private void save() {
        if (store == null) {
            return;
        }
        List<Entry> snapshot = entries(); // entries are immutable: safe to write off the EDT
        saver.execute(() -> {
            try {
                store.save(snapshot);
            } catch (java.io.IOException e) {
                com.intellij.openapi.diagnostic.Logger.getInstance(QueryHistory.class)
                        .warn("Could not save the query history", e);
            }
        });
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
