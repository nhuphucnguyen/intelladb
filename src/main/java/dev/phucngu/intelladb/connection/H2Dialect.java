package dev.phucngu.intelladb.connection;

import dev.phucngu.intelladb.sql.SqlVocabulary;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * H2 (EPL-2.0/MPL-2.0 dual, bundled): embedded in-memory or file databases via
 * {@code jdbc:h2:…} — the Database field holds the H2 URL tail ({@code mem:test},
 * {@code ~/app}, {@code tcp://host/~/app}; blank means a shared in-memory database that
 * outlives single connections). H2 has real schemas ({@code PUBLIC}, the hidden
 * {@code INFORMATION_SCHEMA}), so it nests like PostgreSQL without the database level.
 */
public final class H2Dialect implements DbDialect {

    public static final String ID = "h2";

    private static final List<String> NO_SSL = List.of("off");

    private static final SqlVocabulary VOCABULARY = SqlVocabulary.ANSI.plus(
            SqlVocabulary.words("cross", "dam", "div", "except", "fast", "filter", "identity", "if", "ilike",
                    "ignore", "intersect", "interval", "key", "limit", "minus", "offset", "parent", "percent",
                    "pivot", "qualify", "row", "rownum", "sample", "top", "unlink", "until", "uuid", "value"),
            SqlVocabulary.words("alter", "analyze", "backup", "call", "checkpoint", "comment", "create", "drop",
                    "grant", "merge", "revoke", "script", "set", "show", "shutdown", "summary"),
            SqlVocabulary.words("array_append", "array_cat", "array_contains", "array_get", "array_length",
                    "cardinality", "concat", "concat_ws", "convert", "currval", "current_catalog", "current_schema",
                    "datediff", "dayname", "decode", "greatest", "hash", "ifnull", "last_day", "least", "listagg",
                    "locate", "lpad", "monthname", "nextval", "nullif", "nvl", "regexp_like", "regexp_replace",
                    "regexp_substr", "rownum", "rpad", "session_user", "stringdecode", "stringencode",
                    "stringtodate", "totimestamp", "truncate", "unnest"),
            SqlVocabulary.words("array", "bigint identity", "binary varying", "character large object", "decat",
                    "double precision", "geometry", "identity", "int", "interval", "json", "money", "name",
                    "nclob", "number", "smallint identity", "timestamp with time zone", "tinyint", "uuid",
                    "varchar_ignorecase", "year"));

    private volatile org.h2.Driver driver;

    @Override
    public @NotNull String id() {
        return ID;
    }

    @Override
    public @NotNull String displayName() {
        return "H2";
    }

    /** For {@code tcp://} URLs; embedded URLs don't use it. */
    @Override
    public int defaultPort() {
        return 9092;
    }

    @Override
    public @NotNull String jdbcUrl(@NotNull DbConfig config) {
        if (!config.jdbcUrlOverride.isBlank()) {
            return config.jdbcUrlOverride.trim();
        }
        if (config.database.isBlank()) {
            return "jdbc:h2:mem:intelladb;DB_CLOSE_DELAY=-1";
        }
        return "jdbc:h2:" + config.database.strip();
    }

    /** H2 rejects unknown connection settings, and has no login-timeout ones. */
    @Override
    public @NotNull Properties timeoutProperties(int seconds) {
        return new Properties();
    }

    @Override
    public boolean noAuthByDefault() {
        return true;
    }

    @Override
    public @NotNull Set<String> systemSchemas() {
        return Set.of("information_schema");
    }

    @Override
    public @NotNull java.sql.Driver driver() {
        org.h2.Driver d = driver;
        if (d == null) {
            driver = d = new org.h2.Driver();
        }
        return d;
    }

    @Override
    public @Nullable String useNamespaceStatement(@NotNull String namespace) {
        return "SET SCHEMA " + quote(namespace);
    }

    @Override
    public @Nullable String defaultSchema() {
        return "PUBLIC";
    }

    @Override
    public @NotNull List<String> sslModes() {
        return NO_SSL;
    }
}
