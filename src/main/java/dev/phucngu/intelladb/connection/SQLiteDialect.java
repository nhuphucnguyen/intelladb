package dev.phucngu.intelladb.connection;

import dev.phucngu.intelladb.sql.SqlVocabulary;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * SQLite over the xerial driver (Apache-2.0, bundled): a database is a file — or
 * {@code :memory:} — so there is no host, port or login. The file path goes in the
 * connection's Database field (blank means a private in-memory database); URL-only mode
 * takes a full {@code jdbc:sqlite:} URL. One database file is shown as one schema, as
 * IntelliJ does for file databases.
 */
public final class SQLiteDialect implements DbDialect {

    public static final String ID = "sqlite";

    private static final SqlVocabulary VOCABULARY = SqlVocabulary.ANSI.plus(
            SqlVocabulary.words("autoincrement", "conflict", "deferred", "glob", "ignore", "immediate",
                    "indexed", "instead", "notindexed", "nothing", "pragma", "query", "recursive", "reindex",
                    "temp", "vacuum", "virtual", "without"),
            SqlVocabulary.words("analyze", "attach", "detach", "pragma", "reindex", "replace", "vacuum"),
            SqlVocabulary.words("abs", "changes", "char", "coalesce", "glob", "hex", "ifnull", "iif", "instr",
                    "json", "json_array", "json_extract", "json_group_array", "json_group_object", "json_object",
                    "last_insert_rowid", "length", "lower", "ltrim", "nullif", "printf", "quote", "random",
                    "round", "rtrim", "sign", "sqlite_version", "substr", "substring", "total_changes", "trim",
                    "typeof", "unicode", "upper", "zeroblob", "date", "time", "datetime", "julianday",
                    "strftime", "unixepoch"),
            SqlVocabulary.words("any", "blob", "int", "int2", "int8", "numeric", "real", "row", "rowid", "text"));

    private volatile org.sqlite.JDBC driver;

    @Override
    public @NotNull String id() {
        return ID;
    }

    @Override
    public @NotNull String displayName() {
        return "SQLite";
    }

    /** No server; the field exists only because the dialog always shows it. */
    @Override
    public int defaultPort() {
        return 0;
    }

    @Override
    public @NotNull String jdbcUrl(@NotNull DbConfig config) {
        if (!config.jdbcUrlOverride.isBlank()) {
            return config.jdbcUrlOverride.trim();
        }
        String path = config.database.isBlank() ? ":memory:" : config.database.strip();
        return "jdbc:sqlite:" + path;
    }

    /** xerial has no login-timeout properties, and unknown ones would be read as pragmas. */
    @Override
    public @NotNull Properties timeoutProperties(int seconds) {
        return new Properties();
    }

    @Override
    public boolean noAuthByDefault() {
        return true;
    }

    @Override
    public boolean needsPassword(@NotNull DbConfig config) {
        return false;
    }

    /** One file is one database; there is nothing for the dialog's dropdown to list. */
    @Override
    public @NotNull List<String> listDatabases(@NotNull java.sql.Connection connection) {
        return List.of();
    }

    @Override
    public @NotNull Set<String> systemSchemas() {
        return Set.of("sqlite_schema", "sqlite_master", "sqlite_temp_master", "sqlite_sequence");
    }

    @Override
    public @NotNull java.sql.Driver driver() {
        org.sqlite.JDBC d = driver;
        if (d == null) {
            driver = d = new org.sqlite.JDBC();
        }
        return d;
    }

    /** The database file's single namespace, shown as one schema under the connection. */
    @Override
    public @NotNull NamespaceModel namespaces() {
        return NamespaceModel.SCHEMAS_ONLY;
    }

    /** xerial reports neither schemas nor catalogs; the file's one namespace is {@code main}. */
    @Override
    public @NotNull List<String> namespaceNames(@NotNull java.sql.Connection connection) {
        return List.of("main");
    }

    /** The dialog's combo needs at least one entry; a file database has no transport to secure. */
    @Override
    public @NotNull List<String> sslModes() {
        return List.of("off");
    }
}
