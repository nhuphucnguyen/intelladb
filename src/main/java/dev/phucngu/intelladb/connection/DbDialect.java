package dev.phucngu.intelladb.connection;

import org.jetbrains.annotations.NotNull;

/**
 * A database dialect knows how to build JDBC URLs and which built-in schemas to hide.
 * PostgreSQL is the first supported dialect; the interface keeps the rest of the
 * plugin dialect-agnostic (MySQL etc. can be added without touching the UI).
 */
public interface DbDialect {

    @NotNull String id();

    @NotNull String displayName();

    int defaultPort();

    /** JDBC URL for the given connection settings. */
    @NotNull String jdbcUrl(@NotNull DbConfig config);

    /**
     * Driver properties derived from the connection settings (SSL, read-only…). The
     * user's own driver properties are applied on top and win.
     */
    default @NotNull java.util.Properties connectionProperties(@NotNull DbConfig config) {
        return new java.util.Properties();
    }

    /** Statement that sets the session time zone, or null when the dialect has none. */
    default @org.jetbrains.annotations.Nullable String timeZoneStatement(@NotNull String zone) {
        return null;
    }

    /** Database to connect to when only listing what the server has (the dialog's dropdown). */
    default @NotNull String maintenanceDatabase() {
        return "";
    }

    /** Databases on the server, for the connection dialog's dropdown. */
    default @NotNull java.util.List<String> listDatabases(@NotNull java.sql.Connection connection)
            throws java.sql.SQLException {
        java.util.List<String> names = new java.util.ArrayList<>();
        try (var rs = connection.getMetaData().getCatalogs()) {
            while (rs.next()) {
                names.add(rs.getString(1));
            }
        }
        return names;
    }

    /** Schemas that are part of the server itself and hidden from the tree by default. */
    @NotNull java.util.Set<String> systemSchemas();

    /**
     * Loads the JDBC driver class. DriverManager's service discovery does not see
     * plugin-bundled drivers, so an explicit load is required before connecting.
     */
    void loadDriver() throws java.sql.SQLException;
}
