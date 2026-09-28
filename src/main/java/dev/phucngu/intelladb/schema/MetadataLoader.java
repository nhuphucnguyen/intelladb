package dev.phucngu.intelladb.schema;

import dev.phucngu.intelladb.connection.DbDialect;
import dev.phucngu.intelladb.connection.PostgresDialect;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Loads a {@link SchemaCatalog} over plain JDBC metadata (works for any JDBC database);
 * on PostgreSQL, {@link PostgresObjects} adds what JDBC metadata doesn't cover.
 */
public final class MetadataLoader {

    public static @NotNull SchemaCatalog load(@NotNull Connection connection, @NotNull DbDialect dialect)
            throws SQLException {
        return load(connection, dialect, List.of(), false);
    }

    /**
     * @param onlySchemas the schemas to introspect; empty means every schema (system schemas
     *                    only with {@code showSystem})
     */
    public static @NotNull SchemaCatalog load(@NotNull Connection connection, @NotNull DbDialect dialect,
                                              @NotNull List<String> onlySchemas, boolean showSystem)
            throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        String catalog = connection.getCatalog();

        // schema → (table name → TableMeta under construction)
        Map<String, Map<String, TableBuilder>> builders = new TreeMap<>();

        int totalSchemas = 0;
        try (ResultSet rs = meta.getSchemas()) {
            while (rs.next()) {
                String schema = rs.getString("TABLE_SCHEM");
                if (schema == null) {
                    continue;
                }
                totalSchemas++;
                boolean included = onlySchemas.isEmpty()
                        ? showSystem || !dialect.systemSchemas().contains(schema.toLowerCase())
                        : onlySchemas.contains(schema);
                if (included) {
                    builders.put(schema, new TreeMap<>());
                }
            }
        }

        PostgresObjects pg = PostgresDialect.ID.equals(dialect.id())
                ? PostgresObjects.load(connection, List.copyOf(builders.keySet())) : null;

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
                    continue; // a schema that is filtered out
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
            if (pg != null) {
                // pgjdbc's getPrimaryKeys takes a table name, not a pattern.
                for (String table : tables.keySet()) {
                    for (TableMeta.Key key : pg.keys.getOrDefault(schema + "." + table, List.of())) {
                        if (key.primary()) {
                            pkByTable.put(table, key.columns());
                        }
                    }
                }
            } else {
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
            List<TableMeta> tableMetas = tables.values().stream().map(t -> t.build(schemaName, pg)).toList();
            schemas.add(pg == null
                    ? new SchemaCatalog.Schema(schemaName, tableMetas)
                    : new SchemaCatalog.Schema(schemaName, tableMetas,
                    pg.routines.getOrDefault(schemaName, List.of()),
                    pg.sequences.getOrDefault(schemaName, List.of()),
                    pg.objectTypes.getOrDefault(schemaName, List.of())));
        });
        String database = catalog == null ? "" : catalog;
        List<String> databases = pg != null ? pg.databases : database.isEmpty() ? List.of() : List.of(database);
        return new SchemaCatalog(schemas, database, databases, totalSchemas,
                pg == null ? List.of() : pg.extensions, pg == null ? List.of() : pg.roles);
    }

    /** Every schema of the connected database, system schemas included (for the connection dialog). */
    public static @NotNull List<String> schemaNames(@NotNull Connection connection) throws SQLException {
        List<String> names = new ArrayList<>();
        try (ResultSet rs = connection.getMetaData().getSchemas()) {
            while (rs.next()) {
                names.add(rs.getString("TABLE_SCHEM"));
            }
        }
        names.sort(null);
        return names;
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

        TableMeta build(@NotNull String schema, @Nullable PostgresObjects pg) {
            if (pg == null) {
                return new TableMeta(name, kind, List.copyOf(columns), remarks);
            }
            String key = schema + "." + name;
            return new TableMeta(name, kind, List.copyOf(columns), remarks,
                    pg.keys.getOrDefault(key, List.of()), pg.foreignKeys.getOrDefault(key, List.of()),
                    pg.indexes.getOrDefault(key, List.of()), pg.checks.getOrDefault(key, List.of()));
        }
    }
}
