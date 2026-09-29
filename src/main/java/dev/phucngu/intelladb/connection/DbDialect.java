package dev.phucngu.intelladb.connection;

import dev.phucngu.intelladb.schema.IdentifierQuoting;
import dev.phucngu.intelladb.schema.ObjectsLoader;
import dev.phucngu.intelladb.sql.SqlVocabulary;
import dev.phucngu.intelladb.util.SqlSplitter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.ResultSetMetaData;

/**
 * A database dialect knows how to build JDBC URLs, which built-in schemas to hide, and
 * everything else that differs between database products (identifier quoting, LIMIT,
 * schema switching, driver-specific metadata…). The defaults are today's PostgreSQL/ANSI
 * behaviour; the rest of the plugin only talks to this interface.
 */
public interface DbDialect {

    @NotNull String id();

    @NotNull String displayName();

    int defaultPort();

    /** JDBC URL for the given connection settings. */
    @NotNull String jdbcUrl(@NotNull DbConfig config);

    /** The URL handed to the driver, when it differs from the one shown to the user. */
    default @NotNull String connectUrl(@NotNull String jdbcUrl) {
        return jdbcUrl;
    }

    /**
     * Driver properties derived from the connection settings (SSL, read-only…). The
     * user's own driver properties are applied on top and win.
     */
    default @NotNull java.util.Properties connectionProperties(@NotNull DbConfig config) {
        return new java.util.Properties();
    }

    /** Driver properties that bound how long connecting may take; the units differ between drivers. */
    default @NotNull java.util.Properties timeoutProperties(int seconds) {
        java.util.Properties props = new java.util.Properties();
        props.setProperty("loginTimeout", String.valueOf(seconds));
        props.setProperty("connectTimeout", String.valueOf(seconds));
        return props;
    }

    /**
     * Statement run after connecting when the connection is read-only, on top of
     * {@code Connection.setReadOnly(true)}; null when that alone is enough.
     */
    default @Nullable String readOnlyStatement() {
        return null;
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

    /** Whether {@code name} is a server-internal schema (hidden unless "show system schemas"). */
    default boolean isSystemSchema(@NotNull String name) {
        return systemSchemas().contains(name.toLowerCase());
    }

    /**
     * The driver instance to connect with. DriverManager's service discovery does not see
     * plugin-bundled drivers, so the dialect hands out its driver directly and
     * {@link DbSession#open} calls {@code driver().connect(...)}.
     */
    @NotNull java.sql.Driver driver() throws java.sql.SQLException;

    // ------------------------------------------------------------------ namespaces & metadata

    /** How databases and schemas nest under a connection. */
    default @NotNull NamespaceModel namespaces() {
        return NamespaceModel.DATABASES_AND_SCHEMAS;
    }

    /** Loader for the objects plain JDBC metadata misses; null means plain JDBC only. */
    default @Nullable ObjectsLoader objectsLoader() {
        return null;
    }

    // ------------------------------------------------------------------ SQL syntax

    /** Quotes an identifier if it needs it. */
    default @NotNull String quote(@NotNull String identifier) {
        return IdentifierQuoting.quote(identifier);
    }

    /** Limits a SELECT to {@code rows} rows. */
    default @NotNull String limit(@NotNull String selectSql, int rows) {
        return selectSql + " LIMIT " + rows;
    }

    /** Statement that makes {@code namespace} the default schema of the session, or null if unsupported. */
    default @Nullable String useNamespaceStatement(@NotNull String namespace) {
        return null;
    }

    /** Schema a server resolves unqualified names to when nothing was selected; null if there is none. */
    default @Nullable String defaultSchema() {
        return null;
    }

    /** Keywords, functions and types offered by completion in this dialect's consoles. */
    default @NotNull SqlVocabulary vocabulary() {
        return SqlVocabulary.ANSI;
    }

    /** Lexical rules for splitting scripts into statements. */
    default @NotNull SqlSplitter.Options splitterOptions() {
        return SqlSplitter.Options.POSTGRES;
    }

    // ------------------------------------------------------------------ connection UI

    /** SSL mode names offered in the connection dialog, in display order; the first is the default. */
    @NotNull java.util.List<String> sslModes();

    // ------------------------------------------------------------------ driver-specific values

    /** Converts a driver-specific value object (e.g. PostgreSQL's PGobject) to something displayable. */
    default @Nullable Object displayValue(@Nullable Object value) {
        return value;
    }

    /**
     * {schema, table} when every column of the result maps to the same base table, else
     * null. Only meaningful where the driver reports the origin of plain column references.
     */
    default String @Nullable [] sourceTable(@NotNull ResultSetMetaData meta, int columnCount) {
        return null;
    }

    /**
     * Name of result column {@code column} (1-based) in its base table, whatever it was
     * aliased to — what an UPDATE of an edited cell targets. Most drivers (MariaDB) report
     * it as the column name, with the alias as the label.
     */
    default @Nullable String baseColumnName(@NotNull ResultSetMetaData meta, int column) throws java.sql.SQLException {
        return meta.getColumnName(column);
    }
}
