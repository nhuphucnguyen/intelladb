package community.intelladb.connection;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/** Outcome of executing one SQL statement. */
public final class SqlResult {

    public enum Kind { ROWS, UPDATE_COUNT, MESSAGE, ERROR }

    public static final int MAX_ROWS = 1000;

    public final Kind kind;
    /** Column labels for {@link Kind#ROWS}. */
    public final List<String> columns;
    /** Row values for {@link Kind#ROWS} (already String.valueOf'd). */
    public final List<Object[]> rows;
    /** True when the result set was truncated to {@link #MAX_ROWS}. */
    public final boolean truncated;
    /** For {@link Kind#UPDATE_COUNT}. */
    public final long updateCount;
    /** For {@link Kind#MESSAGE} / {@link Kind#ERROR}. */
    public final String text;
    /** Wall time of the execution, milliseconds. */
    public final long durationMs;
    /** SQL that produced this result. */
    public final String sql;

    private SqlResult(Kind kind, List<String> columns, List<Object[]> rows, boolean truncated,
                      long updateCount, String text, long durationMs, String sql) {
        this.kind = kind;
        this.columns = columns;
        this.rows = rows;
        this.truncated = truncated;
        this.updateCount = updateCount;
        this.text = text;
        this.durationMs = durationMs;
        this.sql = sql;
    }

    public static SqlResult rows(@NotNull String sql, @NotNull List<String> columns,
                                 @NotNull List<Object[]> rows, boolean truncated, long durationMs) {
        return new SqlResult(Kind.ROWS, columns, rows, truncated, -1, null, durationMs, sql);
    }

    public static SqlResult update(@NotNull String sql, long updateCount, long durationMs) {
        return new SqlResult(Kind.UPDATE_COUNT, List.of(), List.of(), false, updateCount, null, durationMs, sql);
    }

    public static SqlResult message(@NotNull String sql, @NotNull String text, long durationMs) {
        return new SqlResult(Kind.MESSAGE, List.of(), List.of(), false, -1, text, durationMs, sql);
    }

    public static SqlResult error(@NotNull String sql, @NotNull String text, long durationMs) {
        return new SqlResult(Kind.ERROR, List.of(), List.of(), false, -1, text, durationMs, sql);
    }

    public boolean isSuccessful() {
        return kind != Kind.ERROR;
    }
}
