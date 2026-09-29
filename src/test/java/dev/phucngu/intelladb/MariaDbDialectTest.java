package dev.phucngu.intelladb;

import dev.phucngu.intelladb.connection.DbConfig;
import dev.phucngu.intelladb.connection.Dialects;
import dev.phucngu.intelladb.connection.MariaDbDialect;
import dev.phucngu.intelladb.connection.NamespaceModel;
import dev.phucngu.intelladb.util.SqlSplitter;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MariaDbDialectTest {

    private final MariaDbDialect dialect = new MariaDbDialect();

    @Test
    void isRegisteredAfterMySql() {
        assertEquals("mysql", Dialects.all().get(1).id());
        assertSame(MariaDbDialect.class, Dialects.byId("mariadb").getClass());
        assertEquals("MariaDB", dialect.displayName());
        assertEquals(3306, dialect.defaultPort());
        assertEquals(NamespaceModel.SCHEMAS_ONLY, dialect.namespaces());
        assertEquals(SqlSplitter.Options.MYSQL, dialect.splitterOptions());
    }

    @Test
    void urlUsesTheMariaDbScheme() throws Exception {
        DbConfig config = new DbConfig();
        config.dialectId = MariaDbDialect.ID;
        config.host = "db.example.com";
        config.port = 3307;
        assertEquals("jdbc:mariadb://db.example.com:3307", dialect.jdbcUrl(config));
        config.database = "shop";
        assertEquals("jdbc:mariadb://db.example.com:3307/shop", dialect.jdbcUrl(config));
        assertEquals("jdbc:mariadb://db.example.com:3307/shop", dialect.connectUrl(dialect.jdbcUrl(config)));
        // A pasted MySQL-style URL still reaches the driver.
        assertEquals("jdbc:mariadb://h:1/db", dialect.connectUrl("jdbc:mysql://h:1/db"));
        assertTrue(dialect.driver().acceptsURL(dialect.jdbcUrl(config)));
    }

    @Test
    void noPublicKeyRetrievalButSslLikeMySql() {
        DbConfig config = new DbConfig();
        assertNull(dialect.connectionProperties(config).getProperty("allowPublicKeyRetrieval"));
        assertEquals("false", dialect.connectionProperties(config).getProperty("yearIsDateType"));
        config.sslMode = true;
        config.sslModeName = "verify-full";
        Properties ssl = dialect.connectionProperties(config);
        assertEquals("verify-full", ssl.getProperty("sslMode"));
    }

    @Test
    void vocabularyAddsMariaDbWords() {
        assertTrue(dialect.vocabulary().keywords().contains("returning"));
        assertTrue(dialect.vocabulary().functions().contains("nextval"));
        assertTrue(dialect.vocabulary().dataTypes().contains("inet6"));
        assertTrue(dialect.vocabulary().functions().contains("group_concat"), "MySQL words are kept");
        assertEquals("`order`", dialect.quote("order"));
        assertEquals("USE shop", dialect.useNamespaceStatement("shop"));
    }
}
