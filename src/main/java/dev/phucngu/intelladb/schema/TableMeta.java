package dev.phucngu.intelladb.schema;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/** One table or view. */
public final class TableMeta {
    public enum Kind { TABLE, VIEW, MATERIALIZED_VIEW, FOREIGN_TABLE }

    /** A primary key or unique constraint. */
    public record Key(String name, List<String> columns, boolean primary) {
    }

    public record ForeignKey(String name, List<String> columns,
                             String refSchema, String refTable, List<String> refColumns) {
    }

    public record Index(String name, List<String> columns, boolean unique) {
    }

    public record Check(String name, String definition) {
    }

    public final String name;
    public final Kind kind;
    public final List<ColumnMeta> columns;
    public final String remarks;
    public final List<Key> keys;
    public final List<ForeignKey> foreignKeys;
    public final List<Index> indexes;
    public final List<Check> checks;

    public TableMeta(@NotNull String name, @NotNull Kind kind,
                     @NotNull List<ColumnMeta> columns, @NotNull String remarks) {
        this(name, kind, columns, remarks, List.of(), List.of(), List.of(), List.of());
    }

    public TableMeta(@NotNull String name, @NotNull Kind kind,
                     @NotNull List<ColumnMeta> columns, @NotNull String remarks,
                     @NotNull List<Key> keys, @NotNull List<ForeignKey> foreignKeys,
                     @NotNull List<Index> indexes, @NotNull List<Check> checks) {
        this.name = name;
        this.kind = kind;
        this.columns = columns;
        this.remarks = remarks;
        this.keys = keys;
        this.foreignKeys = foreignKeys;
        this.indexes = indexes;
        this.checks = checks;
    }

    public boolean isView() {
        return kind != Kind.TABLE;
    }

    /** Names of primary key columns, in key order. */
    public @NotNull List<String> primaryKeyColumns() {
        return columns.stream().filter(c -> c.primaryKey).map(c -> c.name).toList();
    }
}
