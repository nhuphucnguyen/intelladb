package dev.phucngu.intelladb.connection.capability;

import dev.phucngu.intelladb.connection.DbDialect;
import dev.phucngu.intelladb.sql.RowUpdates;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/** Plain SQL: INSERT / UPDATE / DELETE built by {@link RowUpdates}, qualified as the dialect quotes. */
public final class GenericRowMutator implements RowMutator {

    public static final GenericRowMutator INSTANCE = new GenericRowMutator();

    private GenericRowMutator() {
    }

    @Override
    public @NotNull String insert(@NotNull DbDialect dialect, @Nullable String schema, @NotNull String table,
                                  @NotNull Map<String, Object> values) {
        return RowUpdates.insert(dialect, dialect.qualified(schema, table), values);
    }

    @Override
    public @NotNull String update(@NotNull DbDialect dialect, @Nullable String schema, @NotNull String table,
                                  @NotNull RowUpdates.Edit edit) {
        return RowUpdates.update(dialect, dialect.qualified(schema, table), edit);
    }

    @Override
    public @NotNull String delete(@NotNull DbDialect dialect, @Nullable String schema, @NotNull String table,
                                  @NotNull Map<String, Object> key) {
        return RowUpdates.delete(dialect, dialect.qualified(schema, table), key);
    }
}
