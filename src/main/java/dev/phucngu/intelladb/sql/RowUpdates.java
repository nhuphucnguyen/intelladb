package dev.phucngu.intelladb.sql;

import dev.phucngu.intelladb.connection.DbDialect;
import dev.phucngu.intelladb.schema.ColumnMeta;
import dev.phucngu.intelladb.schema.TableMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * UPDATE and DELETE statements for rows edited or deleted in the results grid. A row is found again by its key
 * — the primary key, else a unique key over NOT NULL columns — using the values it was
 * loaded with, so editing a key column itself works too.
 */
public final class RowUpdates {

    /**
     * One edited row: the new values by base column (in the order to SET them), the row's
     * key columns with their loaded values, and the loaded values of the changed columns
     * (so a document database can keep each field's type).
     */
    public record Edit(@NotNull Map<String, Object> changes, @NotNull Map<String, Object> key,
                       @NotNull Map<String, Object> loaded) {
        public Edit(@NotNull Map<String, Object> changes, @NotNull Map<String, Object> key) {
            this(changes, key, Map.of());
        }
    }

    private RowUpdates() {
    }

    /**
     * Columns that identify one row of {@code table}: the primary key, else the first
     * unique key whose columns are all NOT NULL; null when there is none (or it is a view).
     */
    public static @Nullable List<String> rowKey(@NotNull TableMeta table) {
        if (table.isView()) {
            return null;
        }
        List<String> primary = table.primaryKeyColumns();
        if (!primary.isEmpty()) {
            return primary;
        }
        for (TableMeta.Key key : table.keys) {
            if (key.primary()) {
                return key.columns();
            }
        }
        for (TableMeta.Key key : table.keys) {
            if (!key.columns().isEmpty() && key.columns().stream().allMatch(name -> isNotNull(table, name))) {
                return key.columns();
            }
        }
        return null;
    }

    private static boolean isNotNull(@NotNull TableMeta table, @NotNull String column) {
        for (ColumnMeta meta : table.columns) {
            if (meta.name.equals(column)) {
                return !meta.nullable;
            }
        }
        return false;
    }

    /** {@code UPDATE table SET a = 'x' WHERE id = 1} for one edited row. */
    public static @NotNull String update(@NotNull DbDialect dialect, @NotNull String qualifiedTable, @NotNull Edit edit) {
        if (edit.changes().isEmpty() || edit.key().isEmpty()) {
            throw new IllegalArgumentException("An update needs changed columns and a key");
        }
        StringBuilder sql = new StringBuilder("UPDATE ").append(qualifiedTable).append(" SET ");
        String separator = "";
        for (Map.Entry<String, Object> change : edit.changes().entrySet()) {
            sql.append(separator).append(dialect.quote(change.getKey())).append(" = ")
                    .append(literal(dialect, change.getValue()));
            separator = ", ";
        }
        return where(sql, dialect, edit.key()).toString();
    }

    /** {@code DELETE FROM table WHERE id = 1} for one deleted row, found by its loaded key values. */
    public static @NotNull String delete(@NotNull DbDialect dialect, @NotNull String qualifiedTable,
                                         @NotNull Map<String, Object> key) {
        if (key.isEmpty()) {
            throw new IllegalArgumentException("A delete needs a key");
        }
        return where(new StringBuilder("DELETE FROM ").append(qualifiedTable), dialect, key).toString();
    }

    private static @NotNull StringBuilder where(@NotNull StringBuilder sql, @NotNull DbDialect dialect,
                                                @NotNull Map<String, Object> key) {
        sql.append(" WHERE ");
        String separator = "";
        for (Map.Entry<String, Object> column : key.entrySet()) {
            sql.append(separator).append(dialect.quote(column.getKey()));
            sql.append(column.getValue() == null ? " IS NULL" : " = " + literal(dialect, column.getValue()));
            separator = " AND ";
        }
        return sql;
    }

    /**
     * A value as SQL: numbers and booleans bare, everything else a string literal — which
     * PostgreSQL and MySQL both convert to the column's type (dates, uuid, json, enums…).
     */
    static @NotNull String literal(@NotNull DbDialect dialect, @Nullable Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof Boolean flag) {
            return flag ? "TRUE" : "FALSE";
        }
        if (value instanceof Number number && isFinite(number)) {
            return number.toString();
        }
        String text = String.valueOf(value).replace("'", "''");
        if (dialect.splitterOptions().backslashEscapes()) {
            text = text.replace("\\", "\\\\");
        }
        return "'" + text + "'";
    }

    private static boolean isFinite(@NotNull Number number) {
        return switch (number) {
            case Double d -> Double.isFinite(d);
            case Float f -> Float.isFinite(f);
            default -> true;
        };
    }
}
