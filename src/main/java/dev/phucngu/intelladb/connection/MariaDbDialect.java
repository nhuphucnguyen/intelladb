package dev.phucngu.intelladb.connection;

import dev.phucngu.intelladb.sql.SqlVocabulary;
import org.jetbrains.annotations.NotNull;

import java.util.Properties;

/**
 * MariaDB on the same bundled Connector/J as {@link MySqlDialect}, which is MariaDB's own
 * driver; everything is shared except the URL scheme, the MySQL-8-only password setting and
 * the words MariaDB adds (sequences, RETURNING, its own types).
 */
public final class MariaDbDialect extends MySqlDialect {

    public static final String ID = "mariadb";

    private static final SqlVocabulary VOCABULARY = MYSQL_VOCABULARY.plus(
            SqlVocabulary.words("except", "intersect", "returning", "sequence", "system_time", "versioning"),
            SqlVocabulary.words(),
            SqlVocabulary.words("json_arrayagg", "json_detailed", "json_objectagg", "lastval", "nextval",
                    "regexp_replace", "regexp_substr", "setval", "sys_guid"),
            SqlVocabulary.words("inet4", "inet6", "uuid"));

    @Override
    public @NotNull String id() {
        return ID;
    }

    @Override
    public @NotNull String displayName() {
        return "MariaDB";
    }

    @Override
    public @NotNull String jdbcUrl(@NotNull DbConfig config) {
        if (!config.jdbcUrlOverride.isBlank()) {
            return config.jdbcUrlOverride.trim();
        }
        String hostPort = "jdbc:mariadb://" + config.host + ':' + config.port;
        return config.database.isEmpty() ? hostPort : hostPort + '/' + config.database;
    }

    /** MariaDB has no caching_sha2_password, so there is no RSA key to fetch over plain TCP. */
    @Override
    public @NotNull Properties connectionProperties(@NotNull DbConfig config) {
        Properties props = super.connectionProperties(config);
        props.remove("allowPublicKeyRetrieval");
        return props;
    }

    @Override
    public @NotNull SqlVocabulary vocabulary() {
        return VOCABULARY;
    }
}
