package dev.phucngu.intelladb;

import dev.phucngu.intelladb.connection.DbDialect;
import dev.phucngu.intelladb.connection.H2Dialect;
import dev.phucngu.intelladb.connection.MariaDbDialect;
import dev.phucngu.intelladb.connection.MySqlDialect;
import dev.phucngu.intelladb.connection.PostgresDialect;
import dev.phucngu.intelladb.connection.SQLiteDialect;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Page and count SQL every dialect ships — one LIMIT grammar serves all bundled SQL databases. */
class PaginationSqlTest {

    private static final List<DbDialect> SQL_DIALECTS = List.of(new PostgresDialect(), new MySqlDialect(),
            new MariaDbDialect(), new SQLiteDialect(), new H2Dialect());

    @Test
    void pagesAppendLimitOffset() {
        PostgresDialect postgres = new PostgresDialect();
        assertEquals("SELECT 1 LIMIT 50", postgres.limitedQuery("SELECT 1", 0, 50));
        assertEquals("SELECT 1 LIMIT 50 OFFSET 100", postgres.limitedQuery("SELECT 1", 100, 50));
        assertEquals("SELECT 1", postgres.limitedQuery("SELECT 1", 100, 0), "no limit means no rewrite");
        for (DbDialect dialect : SQL_DIALECTS) {
            assertEquals("SELECT 1 LIMIT 5 OFFSET 10", dialect.limitedQuery("SELECT 1", 10, 5), dialect.id());
        }
    }

    @Test
    void countWrapsTheQueryWithAnAlias() {
        assertEquals("SELECT COUNT(*) FROM (\nSELECT 1\n) AS intelladb_count",
                new PostgresDialect().countQuery("SELECT 1"));
        for (DbDialect dialect : SQL_DIALECTS) {
            String sql = dialect.countQuery("SELECT 1");
            assertTrue(sql.startsWith("SELECT COUNT(*)"), dialect.id());
            assertTrue(sql.endsWith(") AS intelladb_count"), dialect.id() + ": " + sql);
        }
    }
}
