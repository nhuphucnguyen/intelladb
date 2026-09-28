package community.intelladb.schema;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/** One table or view. */
public final class TableMeta {
    public enum Kind { TABLE, VIEW, MATERIALIZED_VIEW, FOREIGN_TABLE }

    public final String name;
    public final Kind kind;
    public final List<ColumnMeta> columns;
    public final String remarks;

    public TableMeta(@NotNull String name, @NotNull Kind kind,
                     @NotNull List<ColumnMeta> columns, @NotNull String remarks) {
        this.name = name;
        this.kind = kind;
        this.columns = columns;
        this.remarks = remarks;
    }

    public boolean isView() {
        return kind != Kind.TABLE;
    }

    /** Names of primary key columns, in key order. */
    public @NotNull List<String> primaryKeyColumns() {
        return columns.stream().filter(c -> c.primaryKey).map(c -> c.name).toList();
    }
}
