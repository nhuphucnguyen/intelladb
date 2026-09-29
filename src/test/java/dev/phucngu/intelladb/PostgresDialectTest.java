package dev.phucngu.intelladb;

import dev.phucngu.intelladb.connection.NamespaceModel;
import dev.phucngu.intelladb.connection.PostgresDialect;
import dev.phucngu.intelladb.util.SqlSplitter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The dialect seams keep PostgreSQL's SQL syntax exactly as the plugin always produced it. */
class PostgresDialectTest {

    private final PostgresDialect dialect = new PostgresDialect();

    @Test
    void sqlSyntax() {
        assertEquals("customers", dialect.quote("customers"));
        assertEquals("\"CamelCase\"", dialect.quote("CamelCase"));
        assertEquals("SELECT * FROM t LIMIT 200", dialect.limit("SELECT * FROM t", 200));
        assertEquals("SET search_path TO \"My Schema\"", dialect.useNamespaceStatement("My Schema"));
        assertEquals("public", dialect.defaultSchema());
        assertEquals(SqlSplitter.Options.POSTGRES, dialect.splitterOptions());
        assertEquals(NamespaceModel.DATABASES_AND_SCHEMAS, dialect.namespaces());
        assertNotNull(dialect.objectsLoader());
    }

    @Test
    void systemSchemas() {
        assertTrue(dialect.isSystemSchema("pg_catalog"));
        assertTrue(dialect.isSystemSchema("Information_Schema"));
        assertTrue(dialect.isSystemSchema("pg_temp_3"));
        assertTrue(dialect.isSystemSchema("pg_toast_temp_3"));
        assertFalse(dialect.isSystemSchema("public"));
    }

    @Test
    void sslModes() {
        assertEquals(List.of("require", "verify-ca", "verify-full", "prefer", "allow", "disable"), dialect.sslModes());
    }

    @Test
    void driverAcceptsItsOwnUrlsOnly() throws Exception {
        assertTrue(dialect.driver().acceptsURL("jdbc:postgresql://localhost/db"));
        assertFalse(dialect.driver().acceptsURL("jdbc:mariadb://localhost/db"));
        assertEquals(dialect.driver(), dialect.driver());
    }
}
