package dev.phucngu.intelladb.connection.capability;

import dev.phucngu.intelladb.connection.DbDialect;
import dev.phucngu.intelladb.sql.RowUpdates;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * How rows added, edited or deleted in the results grid are written back: one statement per
 * row, run by {@code DbSession.applyRowUpdates} in a single all-or-nothing unit. SQL dialects
 * use the generic {@link GenericRowMutator}; document databases bind their own (the MongoDB
 * one speaks {@code insertOne}/{@code updateOne}/{@code deleteOne}).
 */
public interface RowMutator {

    /**
     * The statement that inserts one new row. {@code values} holds the typed cell values by
     * base column, in column order; columns the user left empty are absent, so the table's
     * defaults apply.
     */
    @NotNull String insert(@NotNull DbDialect dialect, @Nullable String schema, @NotNull String table,
                           @NotNull Map<String, Object> values);

    /** The statement that writes one edited row back (an UPDATE for SQL). */
    @NotNull String update(@NotNull DbDialect dialect, @Nullable String schema, @NotNull String table,
                           @NotNull RowUpdates.Edit edit);

    /** The statement that deletes one row, found by its loaded key (a DELETE for SQL). */
    @NotNull String delete(@NotNull DbDialect dialect, @Nullable String schema, @NotNull String table,
                           @NotNull Map<String, Object> key);
}
