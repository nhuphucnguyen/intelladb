package dev.phucngu.intelladb.schema;

import org.jetbrains.annotations.NotNull;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * PostgreSQL objects plain JDBC metadata can't see (or only sees one table at a time):
 * constraints, indexes, routines, aggregates, sequences, user types, databases,
 * extensions and roles — each loaded with one catalog query for the whole database.
 */
public final class PostgresObjects implements ObjectsLoader {

    /** Every per-schema query is limited to the schemas being introspected (bound as a text[]). */
    private static final String IN_SCHEMAS = "n.nspname = ANY(?)";

    @Override
    public @NotNull CatalogObjects load(@NotNull Connection connection, @NotNull List<String> schemas)
            throws SQLException {
        CatalogObjects objects = new CatalogObjects();
        Array schemaArray = connection.createArrayOf("text", schemas.toArray());
        Query query = sql -> {
            PreparedStatement ps = connection.prepareStatement(sql);
            ps.setArray(1, schemaArray);
            return ps;
        };
        loadConstraints(objects, query);
        loadIndexes(objects, query);
        loadRoutines(objects, query);
        try (PreparedStatement ps = query.prepare("SELECT n.nspname, c.relname FROM pg_class c"
                + " JOIN pg_namespace n ON n.oid = c.relnamespace"
                + " WHERE c.relkind = 'S' AND " + IN_SCHEMAS + " ORDER BY 1, 2");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                objects.sequences.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(rs.getString(2));
            }
        }
        loadTypes(objects, query);
        try (Statement st = connection.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT datname FROM pg_database WHERE NOT datistemplate ORDER BY 1")) {
                while (rs.next()) {
                    objects.databases.add(rs.getString(1));
                }
            }
            try (ResultSet rs = st.executeQuery("SELECT extname, extversion FROM pg_extension ORDER BY 1")) {
                while (rs.next()) {
                    objects.extensions.add(new SchemaCatalog.Extension(rs.getString(1), rs.getString(2)));
                }
            }
            try (ResultSet rs = st.executeQuery(
                    "SELECT rolname FROM pg_roles WHERE rolname NOT LIKE 'pg\\_%' ORDER BY 1")) {
                while (rs.next()) {
                    objects.roles.add(rs.getString(1));
                }
            }
        }
        return objects;
    }

    private static void loadConstraints(@NotNull CatalogObjects out, @NotNull Query query) throws SQLException {
        String columnsOf = "ARRAY(SELECT a.attname::text FROM unnest(%s) WITH ORDINALITY k(n, i)"
                + " JOIN pg_attribute a ON a.attrelid = %s AND a.attnum = k.n ORDER BY k.i)";
        String sql = "SELECT n.nspname, t.relname, c.conname, c.contype, "
                + columnsOf.formatted("c.conkey", "c.conrelid") + ", rn.nspname, rt.relname, "
                + columnsOf.formatted("c.confkey", "c.confrelid") + ", pg_get_constraintdef(c.oid)"
                + " FROM pg_constraint c"
                + " JOIN pg_class t ON t.oid = c.conrelid JOIN pg_namespace n ON n.oid = t.relnamespace"
                + " LEFT JOIN pg_class rt ON rt.oid = c.confrelid LEFT JOIN pg_namespace rn ON rn.oid = rt.relnamespace"
                + " WHERE c.contype IN ('p', 'u', 'f', 'c') AND " + IN_SCHEMAS + " ORDER BY 1, 2, c.contype, 3";
        try (PreparedStatement ps = query.prepare(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String table = rs.getString(1) + "." + rs.getString(2);
                String name = rs.getString(3);
                List<String> columns = strings(rs.getArray(5));
                switch (rs.getString(4)) {
                    case "p", "u" -> out.keys.computeIfAbsent(table, k -> new ArrayList<>())
                            .add(new TableMeta.Key(name, columns, "p".equals(rs.getString(4))));
                    case "f" -> out.foreignKeys.computeIfAbsent(table, k -> new ArrayList<>())
                            .add(new TableMeta.ForeignKey(name, columns, rs.getString(6), rs.getString(7),
                                    strings(rs.getArray(8))));
                    default -> out.checks.computeIfAbsent(table, k -> new ArrayList<>())
                            .add(new TableMeta.Check(name, rs.getString(9)));
                }
            }
        }
    }

    private static void loadIndexes(@NotNull CatalogObjects out, @NotNull Query query) throws SQLException {
        String sql = "SELECT n.nspname, t.relname, i.relname, x.indisunique,"
                + " ARRAY(SELECT pg_get_indexdef(x.indexrelid, k, true) FROM generate_series(1, x.indnkeyatts) k)"
                + " FROM pg_index x JOIN pg_class i ON i.oid = x.indexrelid JOIN pg_class t ON t.oid = x.indrelid"
                + " JOIN pg_namespace n ON n.oid = t.relnamespace WHERE " + IN_SCHEMAS + " ORDER BY 1, 2, 3";
        try (PreparedStatement ps = query.prepare(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.indexes.computeIfAbsent(rs.getString(1) + "." + rs.getString(2), k -> new ArrayList<>())
                        .add(new TableMeta.Index(rs.getString(3), strings(rs.getArray(5)), rs.getBoolean(4)));
            }
        }
    }

    private static void loadRoutines(@NotNull CatalogObjects out, @NotNull Query query) throws SQLException {
        // Extension members (e.g. pgcrypto's functions) are listed under the extension, not here.
        String sql = "SELECT n.nspname, p.proname, p.prokind, pg_get_function_identity_arguments(p.oid),"
                + " coalesce(pg_get_function_result(p.oid), '')"
                + " FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace"
                + " WHERE p.prokind IN ('f', 'p', 'a') AND " + IN_SCHEMAS
                + " AND NOT EXISTS (SELECT 1 FROM pg_depend d WHERE d.classid = 'pg_proc'::regclass"
                + " AND d.objid = p.oid AND d.deptype = 'e') ORDER BY 1, 2, 4";
        try (PreparedStatement ps = query.prepare(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                SchemaCatalog.Routine.Kind kind = switch (rs.getString(3)) {
                    case "p" -> SchemaCatalog.Routine.Kind.PROCEDURE;
                    case "a" -> SchemaCatalog.Routine.Kind.AGGREGATE;
                    default -> SchemaCatalog.Routine.Kind.FUNCTION;
                };
                out.routines.computeIfAbsent(rs.getString(1), k -> new ArrayList<>())
                        .add(new SchemaCatalog.Routine(rs.getString(2), kind, rs.getString(4), rs.getString(5)));
            }
        }
    }

    private static void loadTypes(@NotNull CatalogObjects out, @NotNull Query query) throws SQLException {
        // Only free-standing types: a table's row type belongs to the table, and a range's
        // multirange is created implicitly with it.
        String sql = "SELECT n.nspname, t.typname, t.typtype FROM pg_type t"
                + " JOIN pg_namespace n ON n.oid = t.typnamespace WHERE " + IN_SCHEMAS
                + " AND (t.typtype IN ('e', 'd', 'r')"
                + " OR (t.typtype = 'c' AND (SELECT relkind FROM pg_class WHERE oid = t.typrelid) = 'c'))"
                + " AND NOT EXISTS (SELECT 1 FROM pg_depend d WHERE d.classid = 'pg_type'::regclass"
                + " AND d.objid = t.oid AND d.deptype = 'e') ORDER BY 1, 2";
        try (PreparedStatement ps = query.prepare(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String kind = switch (rs.getString(3)) {
                    case "e" -> "enum";
                    case "d" -> "domain";
                    case "r" -> "range";
                    default -> "composite";
                };
                out.objectTypes.computeIfAbsent(rs.getString(1), k -> new ArrayList<>())
                        .add(new SchemaCatalog.ObjectType(rs.getString(2), kind));
            }
        }
    }

    /** Prepares a statement with the schema list bound to its single parameter. */
    private interface Query {
        @NotNull PreparedStatement prepare(@NotNull String sql) throws SQLException;
    }

    private static @NotNull List<String> strings(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        return Arrays.stream((Object[]) array.getArray()).map(String::valueOf).toList();
    }
}
