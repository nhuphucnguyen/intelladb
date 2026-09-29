package dev.phucngu.intelladb.schema;

import org.jetbrains.annotations.NotNull;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MySQL objects plain JDBC metadata can't see (or only sees one table at a time), read from
 * information_schema with one query per object kind for all databases at once. A MySQL
 * database is the "schema" here, so table keys are "database.table".
 */
public final class MySqlObjects implements ObjectsLoader {

    @Override
    public @NotNull CatalogObjects load(@NotNull Connection connection, @NotNull List<String> databases)
            throws SQLException {
        CatalogObjects objects = new CatalogObjects();
        if (databases.isEmpty()) {
            return objects; // nothing to introspect; "IN ()" would not parse
        }
        loadKeys(connection, databases, objects);
        loadIndexes(connection, databases, objects);
        loadChecks(connection, databases, objects);
        loadRoutines(connection, databases, objects);
        loadRoles(connection, objects);
        return objects;
    }

    private static void loadKeys(@NotNull Connection connection, @NotNull List<String> databases,
                                 @NotNull CatalogObjects out) throws SQLException {
        String sql = "SELECT tc.TABLE_SCHEMA, tc.TABLE_NAME, tc.CONSTRAINT_NAME, tc.CONSTRAINT_TYPE,"
                + " k.COLUMN_NAME, k.REFERENCED_TABLE_SCHEMA, k.REFERENCED_TABLE_NAME, k.REFERENCED_COLUMN_NAME"
                + " FROM information_schema.TABLE_CONSTRAINTS tc"
                + " JOIN information_schema.KEY_COLUMN_USAGE k ON k.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA"
                + " AND k.CONSTRAINT_NAME = tc.CONSTRAINT_NAME AND k.TABLE_SCHEMA = tc.TABLE_SCHEMA"
                + " AND k.TABLE_NAME = tc.TABLE_NAME"
                + " WHERE tc.CONSTRAINT_TYPE IN ('PRIMARY KEY', 'UNIQUE', 'FOREIGN KEY')"
                + " AND tc.TABLE_SCHEMA IN " + placeholders(databases.size())
                + " ORDER BY tc.TABLE_SCHEMA, tc.TABLE_NAME, tc.CONSTRAINT_TYPE, tc.CONSTRAINT_NAME, k.ORDINAL_POSITION";
        // One constraint spans several rows (one per column); group them in query order.
        Map<String, Constraint> constraints = new LinkedHashMap<>();
        try (PreparedStatement ps = prepare(connection, sql, databases); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String table = rs.getString(1) + "." + rs.getString(2);
                String name = rs.getString(3);
                String type = rs.getString(4);
                String refSchema = rs.getString(6);
                String refTable = rs.getString(7);
                Constraint c = constraints.computeIfAbsent(table + '\0' + type + '\0' + name,
                        k -> new Constraint(table, name, type, refSchema, refTable));
                c.columns.add(rs.getString(5));
                c.refColumns.add(rs.getString(8));
            }
        }
        for (Constraint c : constraints.values()) {
            switch (c.type) {
                case "FOREIGN KEY" -> out.foreignKeys.computeIfAbsent(c.table, k -> new ArrayList<>())
                        .add(new TableMeta.ForeignKey(c.name, c.columns, c.refSchema, c.refTable, c.refColumns));
                default -> out.keys.computeIfAbsent(c.table, k -> new ArrayList<>())
                        .add(new TableMeta.Key(c.name, c.columns, "PRIMARY KEY".equals(c.type)));
            }
        }
    }

    private static void loadIndexes(@NotNull Connection connection, @NotNull List<String> databases,
                                    @NotNull CatalogObjects out) throws SQLException {
        // A functional index part has no COLUMN_NAME (8.0.13+).
        String sql = "SELECT TABLE_SCHEMA, TABLE_NAME, INDEX_NAME, NON_UNIQUE, COALESCE(COLUMN_NAME, '(expression)')"
                + " FROM information_schema.STATISTICS WHERE INDEX_NAME <> 'PRIMARY' AND TABLE_SCHEMA IN "
                + placeholders(databases.size()) + " ORDER BY TABLE_SCHEMA, TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX";
        Map<String, Constraint> indexes = new LinkedHashMap<>();
        try (PreparedStatement ps = prepare(connection, sql, databases); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String table = rs.getString(1) + "." + rs.getString(2);
                String name = rs.getString(3);
                String type = rs.getInt(4) == 0 ? "UNIQUE" : "INDEX";
                Constraint index = indexes.computeIfAbsent(table + '\0' + name,
                        k -> new Constraint(table, name, type, null, null));
                index.columns.add(rs.getString(5));
            }
        }
        for (Constraint index : indexes.values()) {
            out.indexes.computeIfAbsent(index.table, k -> new ArrayList<>())
                    .add(new TableMeta.Index(index.name, index.columns, "UNIQUE".equals(index.type)));
        }
    }

    private static void loadChecks(@NotNull Connection connection, @NotNull List<String> databases,
                                   @NotNull CatalogObjects out) throws SQLException {
        String sql = "SELECT tc.TABLE_SCHEMA, tc.TABLE_NAME, cc.CONSTRAINT_NAME, cc.CHECK_CLAUSE"
                + " FROM information_schema.CHECK_CONSTRAINTS cc"
                + " JOIN information_schema.TABLE_CONSTRAINTS tc ON tc.CONSTRAINT_SCHEMA = cc.CONSTRAINT_SCHEMA"
                + " AND tc.CONSTRAINT_NAME = cc.CONSTRAINT_NAME AND tc.CONSTRAINT_TYPE = 'CHECK'"
                + " WHERE tc.TABLE_SCHEMA IN " + placeholders(databases.size())
                + " ORDER BY tc.TABLE_SCHEMA, tc.TABLE_NAME, cc.CONSTRAINT_NAME";
        try (PreparedStatement ps = prepare(connection, sql, databases); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.checks.computeIfAbsent(rs.getString(1) + "." + rs.getString(2), k -> new ArrayList<>())
                        .add(new TableMeta.Check(rs.getString(3), rs.getString(4)));
            }
        } catch (SQLException e) {
            // CHECK_CONSTRAINTS only exists from MySQL 8.0.16; older servers just have no checks to show.
        }
    }

    private static void loadRoutines(@NotNull Connection connection, @NotNull List<String> databases,
                                     @NotNull CatalogObjects out) throws SQLException {
        // PARAMETERS.ORDINAL_POSITION 0 is a function's return value, already in ROUTINES.DTD_IDENTIFIER.
        Map<String, List<String>> parameters = new LinkedHashMap<>();
        String parameterSql = "SELECT SPECIFIC_SCHEMA, SPECIFIC_NAME,"
                + " CASE WHEN ROUTINE_TYPE = 'PROCEDURE' THEN PARAMETER_MODE END, PARAMETER_NAME, DTD_IDENTIFIER"
                + " FROM information_schema.PARAMETERS WHERE ORDINAL_POSITION > 0 AND SPECIFIC_SCHEMA IN "
                + placeholders(databases.size()) + " ORDER BY SPECIFIC_SCHEMA, SPECIFIC_NAME, ORDINAL_POSITION";
        try (PreparedStatement ps = prepare(connection, parameterSql, databases); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String mode = rs.getString(3); // functions only have IN parameters, not worth showing
                String name = rs.getString(4);
                parameters.computeIfAbsent(rs.getString(1) + '\0' + rs.getString(2), k -> new ArrayList<>())
                        .add((mode == null ? "" : mode + " ") + (name == null ? "" : name + " ") + rs.getString(5));
            }
        }
        String sql = "SELECT ROUTINE_SCHEMA, ROUTINE_NAME, ROUTINE_TYPE, SPECIFIC_NAME, COALESCE(DTD_IDENTIFIER, '')"
                + " FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA IN " + placeholders(databases.size())
                + " ORDER BY ROUTINE_SCHEMA, ROUTINE_NAME";
        try (PreparedStatement ps = prepare(connection, sql, databases); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                boolean procedure = "PROCEDURE".equals(rs.getString(3));
                List<String> args = parameters.getOrDefault(rs.getString(1) + '\0' + rs.getString(4), List.of());
                out.routines.computeIfAbsent(rs.getString(1), k -> new ArrayList<>())
                        .add(new SchemaCatalog.Routine(rs.getString(2),
                                procedure ? SchemaCatalog.Routine.Kind.PROCEDURE : SchemaCatalog.Routine.Kind.FUNCTION,
                                String.join(", ", args), procedure ? "" : rs.getString(5)));
            }
        }
    }

    private static void loadRoles(@NotNull Connection connection, @NotNull CatalogObjects out) {
        // Reading the account table needs privileges most users don't have; then there is simply nothing to show.
        // The mysql.* accounts are internal, like PostgreSQL's pg_* roles.
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT DISTINCT User FROM mysql.user WHERE User <> '' AND User NOT LIKE 'mysql.%' ORDER BY 1")) {
            while (rs.next()) {
                out.roles.add(rs.getString(1));
            }
        } catch (SQLException ignored) {
        }
    }

    private static @NotNull String placeholders(int count) {
        return "(" + "?, ".repeat(count - 1) + "?)";
    }

    private static @NotNull PreparedStatement prepare(@NotNull Connection connection, @NotNull String sql,
                                                      @NotNull List<String> databases) throws SQLException {
        PreparedStatement ps = connection.prepareStatement(sql);
        for (int i = 0; i < databases.size(); i++) {
            ps.setString(i + 1, databases.get(i));
        }
        return ps;
    }

    /** A key, foreign key or index being assembled from its per-column rows. */
    private static final class Constraint {
        final String table;
        final String name;
        final String type;
        final String refSchema;
        final String refTable;
        final List<String> columns = new ArrayList<>();
        final List<String> refColumns = new ArrayList<>();

        Constraint(String table, String name, String type, String refSchema, String refTable) {
            this.table = table;
            this.name = name;
            this.type = type;
            this.refSchema = refSchema;
            this.refTable = refTable;
        }
    }
}
