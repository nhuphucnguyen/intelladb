package dev.phucngu.intelladb.connection;

import org.jetbrains.annotations.NotNull;

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
        StringBuilder url = new StringBuilder("jdbc:postgresql://")
                .append(config.host).append(':').append(config.port).append('/').append(config.database);
        if (config.sslMode) {
            url.append("?sslmode=require");
        }
        return url.toString();
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
