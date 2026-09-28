package dev.phucngu.intelladb.connection;

import org.jetbrains.annotations.NotNull;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;

public final class PostgresDialect implements DbDialect {

    public static final String ID = "postgres";

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
    public void loadDriver() throws java.sql.SQLException {
        try {
            Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException e) {
            throw new java.sql.SQLException("PostgreSQL JDBC driver not found", e);
        }
    }
}
