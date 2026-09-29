package dev.phucngu.intelladb.schema;

import dev.phucngu.intelladb.connection.DbDialect;
import dev.phucngu.intelladb.connection.NamespaceModel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Loads a {@link SchemaCatalog} over plain JDBC metadata (works for any JDBC database);
 * where the dialect has an {@link ObjectsLoader}, it adds what JDBC metadata doesn't cover.
 * A namespace is a schema, or — for {@link NamespaceModel#SCHEMAS_ONLY} dialects like
 * MySQL — a JDBC catalog, which the model shows as a schema.
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
        boolean catalogsAsSchemas = dialect.namespaces() == NamespaceModel.SCHEMAS_ONLY;
        String catalog = catalogsAsSchemas ? null : connection.getCatalog();

        // schema → (table name → TableMeta under construction)
        Map<String, Map<String, TableBuilder>> builders = new TreeMap<>();

        int totalSchemas = 0;
        try (ResultSet rs = catalogsAsSchemas ? meta.getCatalogs() : meta.getSchemas()) {
            while (rs.next()) {
                String schema = rs.getString(catalogsAsSchemas ? "TABLE_CAT" : "TABLE_SCHEM");
                if (schema == null) {
                    continue;
                }
                totalSchemas++;
                boolean included = onlySchemas.isEmpty()
                        ? showSystem || !dialect.isSystemSchema(schema)
                        : onlySchemas.contains(schema);
                if (included) {
                    builders.put(schema, new TreeMap<>());
                }
            }
        }

        ObjectsLoader objectsLoader = dialect.objectsLoader();
        CatalogObjects objects = objectsLoader == null
                ? null : objectsLoader.load(connection, List.copyOf(builders.keySet()));

        // One getTables call for the whole database, or one per catalog when catalogs are the namespaces.
        List<String> tableScopes = catalogsAsSchemas ? List.copyOf(builders.keySet()) : Collections.singletonList(null);
        for (String scope : tableScopes) {
            try (ResultSet rs = meta.getTables(catalogsAsSchemas ? scope : catalog, null, "%", new String[]{
                    "TABLE", "VIEW", "MATERIALIZED VIEW", "FOREIGN TABLE"})) {
                while (rs.next()) {
                    String schema = catalogsAsSchemas ? scope : rs.getString("TABLE_SCHEM");
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
        }

        // Columns + primary keys per table.
        for (Map.Entry<String, Map<String, TableBuilder>> schemaEntry : builders.entrySet()) {
            String schema = schemaEntry.getKey();
            Map<String, TableBuilder> tables = schemaEntry.getValue();
            if (tables.isEmpty()) {
                continue;
            }
            Map<String, List<String>> pkByTable = new LinkedHashMap<>();
            if (objects != null) {
                // The objects loader already has every key; JDBC's getPrimaryKeys takes one table name at a time.
                for (String table : tables.keySet()) {
                    for (TableMeta.Key key : objects.keys.getOrDefault(schema + "." + table, List.of())) {
                        if (key.primary()) {
                            pkByTable.put(table, key.columns());
                        }
                    }
                }
            } else {
                try (ResultSet rs = meta.getPrimaryKeys(catalogsAsSchemas ? schema : catalog,
                        catalogsAsSchemas ? null : schema, "%")) {
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
            try (ResultSet rs = meta.getColumns(catalogsAsSchemas ? schema : catalog,
                    catalogsAsSchemas ? null : schema, "%", "%")) {
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
            List<TableMeta> tableMetas = tables.values().stream().map(t -> t.build(schemaName, objects)).toList();
            schemas.add(objects == null
                    ? new SchemaCatalog.Schema(schemaName, tableMetas)
                    : new SchemaCatalog.Schema(schemaName, tableMetas,
                    objects.routines.getOrDefault(schemaName, List.of()),
                    objects.sequences.getOrDefault(schemaName, List.of()),
                    objects.objectTypes.getOrDefault(schemaName, List.of())));
        });
        // With catalogs as schemas there is no single connected database to name.
        String database = catalog == null ? "" : catalog;
        List<String> databases = objects != null && !catalogsAsSchemas ? objects.databases
                : database.isEmpty() ? List.of() : List.of(database);
        return new SchemaCatalog(schemas, database, databases, totalSchemas,
                objects == null ? List.of() : objects.extensions, objects == null ? List.of() : objects.roles);
    }

    /** Every schema of the connected database, system schemas included (for the connection dialog). */
    public static @NotNull List<String> schemaNames(@NotNull Connection connection, @NotNull DbDialect dialect)
            throws SQLException {
        boolean catalogsAsSchemas = dialect.namespaces() == NamespaceModel.SCHEMAS_ONLY;
        List<String> names = new ArrayList<>();
        try (ResultSet rs = catalogsAsSchemas ? connection.getMetaData().getCatalogs()
                : connection.getMetaData().getSchemas()) {
            while (rs.next()) {
                names.add(rs.getString(catalogsAsSchemas ? "TABLE_CAT" : "TABLE_SCHEM"));
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

        TableMeta build(@NotNull String schema, @Nullable CatalogObjects objects) {
            if (objects == null) {
                return new TableMeta(name, kind, List.copyOf(columns), remarks);
            }
            String key = schema + "." + name;
            return new TableMeta(name, kind, List.copyOf(columns), remarks,
                    objects.keys.getOrDefault(key, List.of()), objects.foreignKeys.getOrDefault(key, List.of()),
                    objects.indexes.getOrDefault(key, List.of()), objects.checks.getOrDefault(key, List.of()));
        }
    }
}
