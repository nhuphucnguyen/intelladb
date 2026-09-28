package community.intelladb.schema;

import community.intelladb.connection.DbDialect;
import org.jetbrains.annotations.NotNull;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Loads a {@link SchemaCatalog} over plain JDBC metadata (works for any JDBC database). */
public final class MetadataLoader {

    public static @NotNull SchemaCatalog load(@NotNull Connection connection, @NotNull DbDialect dialect)
            throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        String catalog = connection.getCatalog();

        // schema → (table name → TableMeta under construction)
        Map<String, Map<String, TableBuilder>> builders = new TreeMap<>();

        try (ResultSet rs = meta.getSchemas()) {
            while (rs.next()) {
                String schema = rs.getString("TABLE_SCHEM");
                if (schema != null && !dialect.systemSchemas().contains(schema.toLowerCase())) {
                    builders.put(schema, new TreeMap<>());
                }
            }
        }

        try (ResultSet rs = meta.getTables(catalog, null, "%", new String[]{
                "TABLE", "VIEW", "MATERIALIZED VIEW", "FOREIGN TABLE"})) {
            while (rs.next()) {
                String schema = rs.getString("TABLE_SCHEM");
                String name = rs.getString("TABLE_NAME");
                String type = rs.getString("TABLE_TYPE");
                String remarks = rs.getString("REMARKS");
                if (schema == null || name == null) {
                    continue;
                }
                Map<String, TableBuilder> tables = builders.get(schema);
                if (tables == null) {
                    tables = new TreeMap<>();
                    builders.put(schema, tables);
                }
                tables.put(name, new TableBuilder(name, kindOf(type), remarks == null ? "" : remarks));
            }
        }

        // Columns + primary keys per table.
        for (Map.Entry<String, Map<String, TableBuilder>> schemaEntry : builders.entrySet()) {
            String schema = schemaEntry.getKey();
            Map<String, TableBuilder> tables = schemaEntry.getValue();
            if (tables.isEmpty()) {
                continue;
            }
            Map<String, List<String>> pkByTable = new LinkedHashMap<>();
            try (ResultSet rs = meta.getPrimaryKeys(catalog, schema, "%")) {
                while (rs.next()) {
                    String table = rs.getString("TABLE_NAME");
                    String column = rs.getString("COLUMN_NAME");
                    if (table != null && column != null) {
                        pkByTable.computeIfAbsent(table, k -> new ArrayList<>()).add(column);
                    }
                }
            } catch (SQLException ignored) {
                // Some drivers don't support "%" for primary keys; fall back below.
            }
            try (ResultSet rs = meta.getColumns(catalog, schema, "%", "%")) {
                while (rs.next()) {
                    String table = rs.getString("TABLE_NAME");
                    String column = rs.getString("COLUMN_NAME");
                    TableBuilder builder = table != null ? tables.get(table) : null;
                    if (builder == null || column == null) {
                        continue;
                    }
                    String typeName = rs.getString("TYPE_NAME");
                    int nullable = rs.getInt("NULLABLE");
                    String defaultValue = rs.getString("COLUMN_DEF");
                    int position = rs.getInt("ORDINAL_POSITION");
                    String remarks = rs.getString("REMARKS");
                    boolean pk = pkByTable.getOrDefault(table, List.of()).contains(column);
                    builder.columns.add(new ColumnMeta(
                            column, typeName == null ? "?" : typeName,
                            nullable != DatabaseMetaData.columnNoNulls,
                            defaultValue == null ? "" : defaultValue,
                            position, pk, remarks == null ? "" : remarks));
                }
            }
        }

        List<SchemaCatalog.Schema> schemas = new ArrayList<>();
        builders.forEach((schemaName, tables) -> {
            List<TableMeta> tableMetas = tables.values().stream().map(TableBuilder::build).toList();
            schemas.add(new SchemaCatalog.Schema(schemaName, tableMetas));
        });
        return new SchemaCatalog(schemas);
    }

    private static TableMeta.@NotNull Kind kindOf(@NotNull String jdbcType) {
        return switch (jdbcType.toUpperCase()) {
            case "VIEW" -> TableMeta.Kind.VIEW;
            case "MATERIALIZED VIEW" -> TableMeta.Kind.MATERIALIZED_VIEW;
            case "FOREIGN TABLE" -> TableMeta.Kind.FOREIGN_TABLE;
            default -> TableMeta.Kind.TABLE;
        };
    }

    private static final class TableBuilder {
        final String name;
        final TableMeta.Kind kind;
        final String remarks;
        final List<ColumnMeta> columns = new ArrayList<>();

        TableBuilder(String name, TableMeta.Kind kind, String remarks) {
            this.name = name;
            this.kind = kind;
            this.remarks = remarks;
        }

        TableMeta build() {
            return new TableMeta(name, kind, List.copyOf(columns), remarks);
        }
    }
}
