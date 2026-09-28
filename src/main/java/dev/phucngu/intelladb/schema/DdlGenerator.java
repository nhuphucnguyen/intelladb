package dev.phucngu.intelladb.schema;

import org.jetbrains.annotations.NotNull;

/** Renders {@link SchemaCatalog} as CREATE TABLE DDL — used for the AI prompt and "Copy DDL". */
public final class DdlGenerator {

    public static @NotNull String generate(@NotNull SchemaCatalog catalog) {
        StringBuilder sb = new StringBuilder();
        for (SchemaCatalog.Schema schema : catalog.schemas()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            for (TableMeta table : schema.tables()) {
                appendTable(sb, schema.name(), table);
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    private static void appendTable(@NotNull StringBuilder sb, @NotNull String schema, @NotNull TableMeta table) {
        sb.append("CREATE ").append(table.isView() ? "VIEW " : "TABLE ")
          .append(schema).append('.').append(quote(table.name)).append(" (\n");
        for (int i = 0; i < table.columns.size(); i++) {
            ColumnMeta column = table.columns.get(i);
            sb.append("    ").append(quote(column.name)).append(' ').append(column.typeName);
            if (!column.nullable) {
                sb.append(" NOT NULL");
            }
            if (!column.defaultValue.isBlank()) {
                sb.append(" DEFAULT ").append(column.defaultValue);
            }
            if (column.primaryKey) {
                sb.append(" PRIMARY KEY");
            }
            if (i < table.columns.size() - 1) {
                sb.append(',');
            }
            if (!column.remarks.isBlank()) {
                sb.append("  -- ").append(column.remarks.replace("\n", " "));
            }
            sb.append('\n');
        }
        sb.append(");");
    }

    private static @NotNull String quote(@NotNull String identifier) {
        return IdentifierQuoting.quote(identifier);
    }

    private DdlGenerator() {
    }
}
