package dev.phucngu.intelladb.connection;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Outcome of executing one SQL statement. */
public final class SqlResult {

    public enum Kind { ROWS, UPDATE_COUNT, MESSAGE, ERROR }

    public static final int MAX_ROWS = 1000;

    public final Kind kind;
    /** Column labels for {@link Kind#ROWS}. */
    public final List<String> columns;
    /** Database type name per column (e.g. {@code int8}, {@code varchar}); empty when unknown. */
    public final List<String> columnTypes;
    /**
     * Schema and table every column of a {@link Kind#ROWS} result comes from, as reported
     * by the driver; both null when the rows are not from exactly one table (joins,
     * expressions, VALUES…).
     */
    public final @Nullable String sourceSchema;
    public final @Nullable String sourceTable;
    /**
     * Base-table column of each result column when {@link #sourceTable} is known — what an
     * edit of that cell updates; an entry is null when the column can't be written back
     * (binary values are only shown as a size). Empty when there is no source table.
     */
    public final List<String> sourceColumns;
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
    /**
     * Database the statement ran on, set by the session when the connection browses every
     * database ({@link DbConfig#allDatabases()}); null otherwise.
     */
    public volatile @Nullable String database;

    private SqlResult(Kind kind, List<String> columns, List<String> columnTypes, List<Object[]> rows,
                      boolean truncated, long updateCount, String text, long durationMs, String sql,
                      @Nullable String sourceSchema, @Nullable String sourceTable,
                      @NotNull List<String> sourceColumns) {
        this.kind = kind;
        this.columns = columns;
        this.columnTypes = columnTypes;
        this.sourceSchema = sourceSchema;
        this.sourceTable = sourceTable;
        this.sourceColumns = sourceColumns;
        this.rows = rows;
        this.truncated = truncated;
        this.updateCount = updateCount;
        this.text = text;
        this.durationMs = durationMs;
        this.sql = sql;
    }

    public static SqlResult rows(@NotNull String sql, @NotNull List<String> columns,
                                 @NotNull List<Object[]> rows, boolean truncated, long durationMs) {
        return rows(sql, columns, List.of(), rows, truncated, durationMs);
    }

    public static SqlResult rows(@NotNull String sql, @NotNull List<String> columns,
                                 @NotNull List<String> columnTypes, @NotNull List<Object[]> rows,
                                 boolean truncated, long durationMs) {
        return rows(sql, columns, columnTypes, rows, truncated, durationMs, null, null);
    }

    public static SqlResult rows(@NotNull String sql, @NotNull List<String> columns,
                                 @NotNull List<String> columnTypes, @NotNull List<Object[]> rows,
                                 boolean truncated, long durationMs,
                                 @Nullable String sourceSchema, @Nullable String sourceTable) {
        return rows(sql, columns, columnTypes, rows, truncated, durationMs, sourceSchema, sourceTable, List.of());
    }

    public static SqlResult rows(@NotNull String sql, @NotNull List<String> columns,
                                 @NotNull List<String> columnTypes, @NotNull List<Object[]> rows,
                                 boolean truncated, long durationMs,
                                 @Nullable String sourceSchema, @Nullable String sourceTable,
                                 @NotNull List<String> sourceColumns) {
        return new SqlResult(Kind.ROWS, columns, columnTypes, rows, truncated, -1, null, durationMs, sql,
                sourceSchema, sourceTable, sourceColumns);
    }

    public static SqlResult update(@NotNull String sql, long updateCount, long durationMs) {
        return new SqlResult(Kind.UPDATE_COUNT, List.of(), List.of(), List.of(), false, updateCount, null, durationMs, sql, null, null, List.of());
    }

    public static SqlResult message(@NotNull String sql, @NotNull String text, long durationMs) {
        return new SqlResult(Kind.MESSAGE, List.of(), List.of(), List.of(), false, -1, text, durationMs, sql, null, null, List.of());
    }

    public static SqlResult error(@NotNull String sql, @NotNull String text, long durationMs) {
        return new SqlResult(Kind.ERROR, List.of(), List.of(), List.of(), false, -1, text, durationMs, sql, null, null, List.of());
    }

    /** Type name of column {@code index}, or "" when the driver did not report one. */
    public @NotNull String columnType(int index) {
        return index < columnTypes.size() ? columnTypes.get(index) : "";
    }

    /** {@code schema.table} (identifiers quoted as needed) when the rows come from one table. */
    public @Nullable String qualifiedSource(@NotNull DbDialect dialect) {
        if (sourceTable == null) {
            return null;
        }
        String table = dialect.quote(sourceTable);
        return sourceSchema == null ? table : dialect.quote(sourceSchema) + "." + table;
    }

    /** Base column of result column {@code index}, or null when it is unknown or not writable. */
    public @Nullable String sourceColumn(int index) {
        return index < sourceColumns.size() ? sourceColumns.get(index) : null;
    }

    public boolean isSuccessful() {
        return kind != Kind.ERROR;
    }
}
