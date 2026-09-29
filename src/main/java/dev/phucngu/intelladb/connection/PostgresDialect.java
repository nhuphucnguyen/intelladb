package dev.phucngu.intelladb.connection;

import dev.phucngu.intelladb.schema.ObjectsLoader;
import dev.phucngu.intelladb.schema.PostgresObjects;
import dev.phucngu.intelladb.sql.SqlVocabulary;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;

public final class PostgresDialect implements DbDialect {

    public static final String ID = "postgres";

    private static final List<String> SSL_MODES =
            List.of("require", "verify-ca", "verify-full", "prefer", "allow", "disable");

    private static final SqlVocabulary VOCABULARY = SqlVocabulary.ANSI.plus(
            SqlVocabulary.words("concurrently", "conflict", "do", "filter", "ilike", "lateral", "materialized",
                    "nothing", "nulls", "returning", "similar", "tablesample", "within"),
            SqlVocabulary.words("analyze", "call", "cluster", "comment", "copy", "deallocate", "discard", "do",
                    "execute", "listen", "lock", "notify", "prepare", "refresh", "reindex", "reset", "set",
                    "show", "vacuum"),
            SqlVocabulary.words("age", "array_agg", "array_length", "concat", "concat_ws", "date_part", "date_trunc",
                    "dense_rank", "extract", "first_value", "gen_random_uuid", "generate_series", "greatest",
                    "json_agg", "json_build_object", "jsonb_agg", "jsonb_array_elements", "jsonb_build_object",
                    "jsonb_each", "jsonb_set", "lag", "last_value", "lead", "least", "left", "lpad", "now",
                    "position", "rank", "regexp_matches", "regexp_replace", "replace", "right", "row_number",
                    "rpad", "split_part", "string_agg", "to_char", "to_date", "to_timestamp", "unnest"),
            SqlVocabulary.words("bigserial", "bytea", "cidr", "double precision", "float4", "float8", "inet", "int2",
                    "int4", "int8", "interval", "json", "jsonb", "money", "serial", "smallserial", "text",
                    "timestamptz", "timetz", "tsvector", "uuid", "xml"));

    private final PostgresObjects objects = new PostgresObjects();
    private volatile org.postgresql.Driver driver;

    @Override
    public @NotNull String id() {
        return ID;
    }

    @Override
    public @NotNull String displayName() {
        return "PostgreSQL";
    }

    @Override
    public int defaultPort() {
        return 5432;
    }

    @Override
    public @NotNull String jdbcUrl(@NotNull DbConfig config) {
        if (!config.jdbcUrlOverride.isBlank()) {
            return config.jdbcUrlOverride.trim();
        }
        return "jdbc:postgresql://" + config.host + ':' + config.port + '/' + config.database;
    }

    @Override
    public @NotNull Properties connectionProperties(@NotNull DbConfig config) {
        Properties props = new Properties();
        if (config.sslMode) {
            props.setProperty("sslmode", config.sslModeName.isBlank() ? "require" : config.sslModeName);
            putIfSet(props, "sslrootcert", config.sslRootCert);
            putIfSet(props, "sslcert", config.sslCert);
            putIfSet(props, "sslkey", config.sslKey);
        }
        if (config.readOnly) {
            // By default pgjdbc only honours read-only inside explicit transactions.
            props.setProperty("readOnly", "true");
            props.setProperty("readOnlyMode", "always");
        }
        return props;
    }

    @Override
    public @NotNull String timeZoneStatement(@NotNull String zone) {
        return "SET TIME ZONE '" + zone.replace("'", "''") + "'";
    }

    @Override
    public @NotNull String maintenanceDatabase() {
        return "postgres";
    }

    @Override
    public @NotNull List<String> listDatabases(@NotNull Connection connection) throws SQLException {
        List<String> names = new ArrayList<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT datname FROM pg_database WHERE NOT datistemplate AND datallowconn ORDER BY 1")) {
            while (rs.next()) {
                names.add(rs.getString(1));
            }
        }
        return names;
    }

    private static void putIfSet(@NotNull Properties props, @NotNull String key, @NotNull String value) {
        if (!value.isBlank()) {
            props.setProperty(key, value.trim());
        }
    }

    @Override
    public @NotNull Set<String> systemSchemas() {
        return Set.of("pg_catalog", "information_schema", "pg_toast");
    }

    @Override
    public boolean isSystemSchema(@NotNull String name) {
        String lower = name.toLowerCase();
        return systemSchemas().contains(lower) || lower.startsWith("pg_temp") || lower.startsWith("pg_toast_temp");
    }

    @Override
    public @NotNull java.sql.Driver driver() {
        org.postgresql.Driver d = driver;
        if (d == null) {
            driver = d = new org.postgresql.Driver();
        }
        return d;
    }

    @Override
    public @Nullable ObjectsLoader objectsLoader() {
        return objects;
    }

    @Override
    public @Nullable String useNamespaceStatement(@NotNull String namespace) {
        return "SET search_path TO " + quote(namespace);
    }

    @Override
    public @NotNull SqlVocabulary vocabulary() {
        return VOCABULARY;
    }

    @Override
    public @Nullable String defaultSchema() {
        return "public";
    }

    @Override
    public @NotNull List<String> sslModes() {
        return SSL_MODES;
    }

    @Override
    public @Nullable Object displayValue(@Nullable Object value) {
        return value instanceof org.postgresql.util.PGobject pg ? pg.getValue() : value;
    }

    /** pgjdbc's column name is the label; the base name comes from its own metadata interface. */
    @Override
    public @Nullable String baseColumnName(@NotNull ResultSetMetaData meta, int column) throws SQLException {
        return meta.isWrapperFor(org.postgresql.PGResultSetMetaData.class)
                ? meta.unwrap(org.postgresql.PGResultSetMetaData.class).getBaseColumnName(column)
                : meta.getColumnName(column);
    }

    /** pgjdbc reports the origin of plain column references, so a join or expression yields null. */
    @Override
    public String @Nullable [] sourceTable(@NotNull ResultSetMetaData meta, int columnCount) {
        try {
            if (columnCount == 0 || !meta.isWrapperFor(org.postgresql.PGResultSetMetaData.class)) {
                return null;
            }
            var pg = meta.unwrap(org.postgresql.PGResultSetMetaData.class);
            String schema = null;
            String table = null;
            for (int i = 1; i <= columnCount; i++) {
                String columnTable = pg.getBaseTableName(i);
                String columnSchema = pg.getBaseSchemaName(i);
                if (columnTable == null || columnTable.isEmpty()) {
                    return null; // computed column
                }
                if (table == null) {
                    table = columnTable;
                    schema = columnSchema == null || columnSchema.isEmpty() ? null : columnSchema;
                } else if (!table.equals(columnTable) || !java.util.Objects.equals(schema,
                        columnSchema == null || columnSchema.isEmpty() ? null : columnSchema)) {
                    return null; // join
                }
            }
            return new String[]{schema, table};
        } catch (SQLException e) {
            return null;
        }
    }

    /** pg_stat_ssl (9.5+) describes this backend's own connection. */
    @Override
    public @Nullable String sslStatus(@NotNull java.sql.Connection connection) throws SQLException {
        try (var st = connection.createStatement();
             var rs = st.executeQuery("SELECT ssl, version FROM pg_stat_ssl WHERE pid = pg_backend_pid()")) {
            if (!rs.next()) {
                return null;
            }
            return rs.getBoolean(1) ? "yes (" + rs.getString(2) + ")" : "no";
        }
    }
}
