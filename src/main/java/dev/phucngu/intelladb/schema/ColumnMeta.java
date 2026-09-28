package dev.phucngu.intelladb.schema;

import org.jetbrains.annotations.NotNull;

/** One column of a table or view. */
public final class ColumnMeta {
    public final String name;
    public final String typeName;
    public final boolean nullable;
    public final String defaultValue;
    public final int position;
    public final boolean primaryKey;
    public final String remarks;

    public ColumnMeta(@NotNull String name, @NotNull String typeName, boolean nullable,
                      @NotNull String defaultValue, int position, boolean primaryKey, @NotNull String remarks) {
        this.name = name;
        this.typeName = typeName;
        this.nullable = nullable;
        this.defaultValue = defaultValue;
        this.position = position;
        this.primaryKey = primaryKey;
        this.remarks = remarks;
    }
}
