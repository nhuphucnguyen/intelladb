package dev.phucngu.intelladb.connection;

import dev.phucngu.intelladb.schema.MySqlObjects;
import dev.phucngu.intelladb.schema.ObjectsLoader;
import dev.phucngu.intelladb.sql.SqlVocabulary;
import dev.phucngu.intelladb.util.SqlSplitter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

/**
 * MySQL through MariaDB Connector/J (LGPL, so it can be bundled; MySQL's own driver is GPL).
 * A MySQL "database" is a JDBC catalog and is shown as a schema under the connection.
 */
public class MySqlDialect implements DbDialect {

    public static final String ID = "mysql";

    private static final String MYSQL_SCHEME = "jdbc:mysql:";
    private static final List<String> SSL_MODES = List.of("trust", "verify-ca", "verify-full");

    /** Words that must be quoted to be used as identifiers; not the full MySQL list, the common ones. */
    private static final Set<String> RESERVED = Set.of(
            "add", "all", "alter", "and", "as", "asc", "between", "by", "case", "check", "column", "constraint",
            "create", "cross", "database", "default", "delete", "desc", "distinct", "drop", "else", "exists",
            "for", "foreign", "from", "group", "having", "if", "in", "index", "inner", "insert", "interval",
            "into", "is", "join", "key", "keys", "left", "like", "limit", "not", "null", "on", "or", "order",
            "outer", "primary", "range", "references", "rename", "right", "select", "set", "show", "table",
            "then", "to", "union", "unique", "update", "use", "using", "values", "when", "where", "with");

    static final SqlVocabulary MYSQL_VOCABULARY = SqlVocabulary.ANSI.plus(
            SqlVocabulary.words("auto_increment", "charset", "collate", "div", "duplicate", "engine", "high_priority",
                    "ignore", "interval", "low_priority", "regexp", "rlike", "straight_join", "unsigned", "xor",
                    "zerofill"),
            SqlVocabulary.words("analyze", "call", "describe", "do", "handler", "load", "lock", "optimize", "rename",
                    "repair", "replace", "set", "show", "start", "unlock", "use"),
            SqlVocabulary.words("concat", "concat_ws", "curdate", "curtime", "database", "date_add", "date_format",
                    "date_sub", "datediff", "dense_rank", "found_rows", "from_unixtime", "greatest", "group_concat",
                    "if", "ifnull", "instr", "json_array", "json_contains", "json_extract", "json_object",
                    "json_unquote", "lag", "last_insert_id", "lead", "least", "left", "locate", "lpad", "md5", "now",
                    "rank", "replace", "right", "row_count", "row_number", "rpad", "sha2", "str_to_date",
                    "substring_index", "sysdate", "timestampdiff", "unix_timestamp", "user", "uuid", "version"),
            SqlVocabulary.words("binary", "bit", "blob", "datetime", "double", "enum", "json", "longblob", "longtext",
                    "mediumint", "mediumtext", "text", "tinyint", "tinytext", "varbinary", "year"));

    private final MySqlObjects objects = new MySqlObjects();
    private volatile org.mariadb.jdbc.Driver driver;

    @Override
    public @NotNull String id() {
        return ID;
    }

    @Override
    public @NotNull String displayName() {
        return "MySQL";
    }

    @Override
    public int defaultPort() {
        return 3306;
    }

    /** The familiar {@code jdbc:mysql://} form is what the user sees; see {@link #connectUrl}. */
    @Override
    public @NotNull String jdbcUrl(@NotNull DbConfig config) {
        if (!config.jdbcUrlOverride.isBlank()) {
            return config.jdbcUrlOverride.trim();
        }
        String hostPort = "jdbc:mysql://" + config.host + ':' + config.port;
        return config.database.isEmpty() ? hostPort : hostPort + '/' + config.database;
    }

    /** The MariaDB driver only accepts its own scheme, so MySQL URLs are rewritten just before connecting. */
    @Override
    public @NotNull String connectUrl(@NotNull String jdbcUrl) {
        return jdbcUrl.regionMatches(true, 0, MYSQL_SCHEME, 0, MYSQL_SCHEME.length())
                ? "jdbc:mariadb:" + jdbcUrl.substring(MYSQL_SCHEME.length()) : jdbcUrl;
    }

    @Override
    public @NotNull Properties connectionProperties(@NotNull DbConfig config) {
        Properties props = new Properties();
        // The driver's default would turn YEAR columns into dates.
        props.setProperty("yearIsDateType", "false");
        if (config.sslMode) {
            props.setProperty("sslMode", config.sslModeName.isBlank() ? SSL_MODES.get(0) : config.sslModeName);
            if (!config.sslRootCert.isBlank()) {
                props.setProperty("serverSslCert", config.sslRootCert.trim());
            }
            // Client certificate and key: the MariaDB driver wants a keystore, not the PEM files
            // the dialog collects, so they are ignored for MySQL.
        } else {
            // MySQL 8's default caching_sha2_password sends the password RSA-encrypted, which
            // needs the server's public key; without TLS the driver refuses to fetch it unless
            // told to. That trusts whoever answers on the network (a man in the middle could
            // serve their own key), so use SSL on untrusted networks. User driver properties win.
            props.setProperty("allowPublicKeyRetrieval", "true");
        }
        return props;
    }

    /** MariaDB's connectTimeout is in milliseconds (pgjdbc's is seconds); it has no loginTimeout. */
    @Override
    public @NotNull Properties timeoutProperties(int seconds) {
        Properties props = new Properties();
        props.setProperty("connectTimeout", String.valueOf(seconds * 1000));
        return props;
    }

    /**
     * MariaDB's setReadOnly(true) already makes the session read-only (verified: INSERT,
     * DDL and TRUNCATE are refused); this makes that explicit and independent of the driver.
     * Like any session setting it can be undone by the user with SET SESSION TRANSACTION READ WRITE.
     */
    @Override
    public @NotNull String readOnlyStatement() {
        return "SET SESSION TRANSACTION READ ONLY";
    }

    @Override
    public @NotNull String timeZoneStatement(@NotNull String zone) {
        return "SET time_zone = '" + zone.replace("\\", "\\\\").replace("'", "''") + "'";
    }

    @Override
    public @NotNull List<String> listDatabases(@NotNull Connection connection) throws SQLException {
        List<String> names = new ArrayList<>();
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery("SHOW DATABASES")) {
            while (rs.next()) {
                names.add(rs.getString(1));
            }
        }
        return names;
    }

    @Override
    public @NotNull Set<String> systemSchemas() {
        return Set.of("mysql", "information_schema", "performance_schema", "sys");
    }

    @Override
    public @NotNull java.sql.Driver driver() {
        org.mariadb.jdbc.Driver d = driver;
        if (d == null) {
            driver = d = new org.mariadb.jdbc.Driver();
        }
        return d;
    }

    @Override
    public @NotNull NamespaceModel namespaces() {
        return NamespaceModel.SCHEMAS_ONLY;
    }

    @Override
    public @Nullable ObjectsLoader objectsLoader() {
        return objects;
    }

    @Override
    public @NotNull String quote(@NotNull String identifier) {
        if (identifier.matches("[A-Za-z_][A-Za-z0-9_]*") && !RESERVED.contains(identifier.toLowerCase(Locale.ROOT))) {
            return identifier;
        }
        return '`' + identifier.replace("`", "``") + '`';
    }

    @Override
    public @Nullable String useNamespaceStatement(@NotNull String namespace) {
        return "USE " + quote(namespace);
    }

    @Override
    public @NotNull SqlVocabulary vocabulary() {
        return MYSQL_VOCABULARY;
    }

    @Override
    public @NotNull SqlSplitter.Options splitterOptions() {
        return SqlSplitter.Options.MYSQL;
    }

    @Override
    public @NotNull List<String> sslModes() {
        return SSL_MODES;
    }

    /**
     * The driver reports the original table of a plain column reference (not its alias), and
     * nothing for expressions and unions; the catalog name is the database, i.e. our schema.
     */
    @Override
    public String @Nullable [] sourceTable(@NotNull ResultSetMetaData meta, int columnCount) {
        try {
            if (columnCount == 0) {
                return null;
            }
            String schema = null;
            String table = null;
            for (int i = 1; i <= columnCount; i++) {
                String columnTable = meta.getTableName(i);
                String columnSchema = meta.getCatalogName(i);
                if (columnTable == null || columnTable.isEmpty()) {
                    return null; // computed column
                }
                if (columnSchema != null && columnSchema.isEmpty()) {
                    columnSchema = null;
                }
                if (table == null) {
                    table = columnTable;
                    schema = columnSchema;
                } else if (!table.equals(columnTable) || !java.util.Objects.equals(schema, columnSchema)) {
                    return null; // join
                }
            }
            return new String[]{schema, table};
        } catch (SQLException e) {
            return null;
        }
    }

    /** Ssl_version is empty for an unencrypted session. */
    @Override
    public @Nullable String sslStatus(@NotNull java.sql.Connection connection) throws SQLException {
        try (var st = connection.createStatement();
             var rs = st.executeQuery("SHOW SESSION STATUS LIKE 'Ssl_version'")) {
            if (!rs.next()) {
                return null;
            }
            String version = rs.getString(2);
            return version == null || version.isEmpty() ? "no" : "yes (" + version + ")";
        }
    }
}
