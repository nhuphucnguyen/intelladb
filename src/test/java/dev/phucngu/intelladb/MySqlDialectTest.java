package dev.phucngu.intelladb;

import dev.phucngu.intelladb.connection.DbConfig;
import dev.phucngu.intelladb.connection.Dialects;
import dev.phucngu.intelladb.connection.MySqlDialect;
import dev.phucngu.intelladb.connection.NamespaceModel;
import dev.phucngu.intelladb.schema.ColumnMeta;
import dev.phucngu.intelladb.schema.DdlGenerator;
import dev.phucngu.intelladb.schema.SchemaCatalog;
import dev.phucngu.intelladb.schema.TableMeta;
import dev.phucngu.intelladb.util.SqlSplitter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MySqlDialectTest {

    private final MySqlDialect dialect = new MySqlDialect();

    private static DbConfig config() {
        DbConfig config = new DbConfig();
        config.dialectId = MySqlDialect.ID;
        config.host = "db.example.com";
        config.port = 3307;
        return config;
    }

    @Test
    void isRegisteredAfterPostgres() {
        assertEquals("postgres", Dialects.all().get(0).id());
        assertSame(Dialects.byId("mysql").getClass(), MySqlDialect.class);
        assertEquals(3306, dialect.defaultPort());
        assertEquals(NamespaceModel.SCHEMAS_ONLY, dialect.namespaces());
    }

    @Test
    void urlIsShownInMySqlFormAndRewrittenForTheDriver() {
        DbConfig config = config();
        assertEquals("jdbc:mysql://db.example.com:3307", dialect.jdbcUrl(config));
        config.database = "shop";
        assertEquals("jdbc:mysql://db.example.com:3307/shop", dialect.jdbcUrl(config));

        assertEquals("jdbc:mariadb://db.example.com:3307/shop", dialect.connectUrl(dialect.jdbcUrl(config)));
        assertEquals("jdbc:mariadb://h:1/db?a=b", dialect.connectUrl("JDBC:MYSQL://h:1/db?a=b"));
        // Pasted MariaDB URLs already fit the driver.
        assertEquals("jdbc:mariadb://h:1/", dialect.connectUrl("jdbc:mariadb://h:1/"));

        config.jdbcUrlOverride = " jdbc:mysql://other/db?opt=1 ";
        assertEquals("jdbc:mysql://other/db?opt=1", dialect.jdbcUrl(config));
    }

    @Test
    void driverAcceptsTheRewrittenUrl() throws Exception {
        assertTrue(dialect.driver().acceptsURL(dialect.connectUrl("jdbc:mysql://localhost:3306")));
        assertFalse(dialect.driver().acceptsURL("jdbc:postgresql://localhost/db"));
    }

    @Test
    void connectionProperties() {
        DbConfig config = config();
        Properties plain = dialect.connectionProperties(config);
        assertEquals("true", plain.getProperty("allowPublicKeyRetrieval"));
        assertNull(plain.getProperty("sslMode"));

        config.sslMode = true;
        config.sslModeName = "verify-ca";
        config.sslRootCert = " /certs/ca.pem ";
        config.sslCert = "/certs/client.pem"; // not supported, ignored
        Properties ssl = dialect.connectionProperties(config);
        assertEquals("verify-ca", ssl.getProperty("sslMode"));
        assertEquals("/certs/ca.pem", ssl.getProperty("serverSslCert"));
        assertNull(ssl.getProperty("allowPublicKeyRetrieval"), "TLS protects the password exchange");
        assertNull(ssl.getProperty("sslcert"));

        assertEquals("30000", dialect.timeoutProperties(30).getProperty("connectTimeout"), "milliseconds");
        assertEquals(List.of("trust", "verify-ca", "verify-full"), dialect.sslModes());
    }

    @Test
    void sessionStatements() {
        assertEquals("SET time_zone = 'Asia/Singapore'", dialect.timeZoneStatement("Asia/Singapore"));
        assertEquals("SET time_zone = 'a\\\\b''c'", dialect.timeZoneStatement("a\\b'c"));
        assertEquals("SET SESSION TRANSACTION READ ONLY", dialect.readOnlyStatement());
        assertEquals("USE shop", dialect.useNamespaceStatement("shop"));
        assertEquals("USE `my-shop`", dialect.useNamespaceStatement("my-shop"));
        assertNull(dialect.defaultSchema());
    }

    @Test
    void quotingAndLimit() {
        assertEquals("customers", dialect.quote("customers"));
        assertEquals("CamelCase", dialect.quote("CamelCase"));
        assertEquals("`order-items`", dialect.quote("order-items"));
        assertEquals("`order`", dialect.quote("order"));
        assertEquals("`we``ird`", dialect.quote("we`ird"));
        assertEquals("`1st`", dialect.quote("1st"));
        assertEquals("SELECT * FROM t LIMIT 200", dialect.limit("SELECT * FROM t", 200));
    }

    @Test
    void systemSchemas() {
        assertTrue(dialect.isSystemSchema("mysql"));
        assertTrue(dialect.isSystemSchema("INFORMATION_SCHEMA"));
        assertTrue(dialect.isSystemSchema("performance_schema"));
        assertTrue(dialect.isSystemSchema("sys"));
        assertFalse(dialect.isSystemSchema("shop"));
        assertFalse(dialect.isSystemSchema("pg_temp_1"));
    }

    @Test
    void ddlUsesBackticksWhereNeeded() {
        ColumnMeta id = new ColumnMeta("id", "INT", false, "", 1, true, "");
        ColumnMeta group = new ColumnMeta("group", "VARCHAR", true, "", 2, false, "");
        TableMeta table = new TableMeta("order-items", TableMeta.Kind.TABLE, List.of(id, group), "");
        String ddl = DdlGenerator.generate(new SchemaCatalog(List.of(new SchemaCatalog.Schema("my-shop", List.of(table)))),
                dialect);
        assertTrue(ddl.contains("CREATE TABLE `my-shop`.`order-items` ("), ddl);
        assertTrue(ddl.contains("    id INT NOT NULL PRIMARY KEY,"), ddl);
        assertTrue(ddl.contains("    `group` VARCHAR"), ddl);
    }

    @Test
    void splitterOptions() {
        assertEquals(SqlSplitter.Options.MYSQL, dialect.splitterOptions());
    }
}
