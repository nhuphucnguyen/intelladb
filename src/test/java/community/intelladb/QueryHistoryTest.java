package community.intelladb;

import community.intelladb.connection.DbConfig;
import community.intelladb.connection.SqlResult;
import community.intelladb.history.QueryHistory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class QueryHistoryTest {

    private static DbConfig config() {
        DbConfig config = new DbConfig();
        config.name = "localhost";
        return config;
    }

    private static SqlResult rows(String sql) {
        return SqlResult.rows(sql, List.of("id"), List.<Object[]>of(new Object[]{1}), false, 5);
    }

    @Test
    void keepsNewestFirstWithCachedResult() {
        QueryHistory history = new QueryHistory(() -> 10);
        SqlResult first = rows("select 1");
        history.add(config(), null, "  select 1  ", first);
        history.add(config(), "public", "select 2", rows("select 2"));

        List<QueryHistory.Entry> entries = history.entries();
        assertEquals(2, entries.size());
        assertEquals("select 2", entries.get(0).sql());
        assertEquals("public", entries.get(0).schema());
        assertEquals("select 1", entries.get(1).sql()); // input stored trimmed
        assertSame(first, entries.get(1).result());     // result cached as executed
        assertEquals("localhost", entries.get(1).connectionName());
    }

    @Test
    void dropsOldestBeyondLimit() {
        QueryHistory history = new QueryHistory(() -> 5); // MIN_LIMIT
        for (int i = 0; i < 8; i++) {
            history.add(config(), null, "select " + i, rows("select " + i));
        }
        List<QueryHistory.Entry> entries = history.entries();
        assertEquals(5, entries.size());
        assertEquals("select 7", entries.get(0).sql());
        assertEquals("select 3", entries.get(4).sql());
    }

    @Test
    void shrinkingTheLimitTrimsAndNotifies() {
        AtomicInteger limit = new AtomicInteger(10);
        QueryHistory history = new QueryHistory(limit::get);
        for (int i = 0; i < 8; i++) {
            history.add(config(), null, "select " + i, rows("select " + i));
        }
        limit.set(6);
        history.limitChanged();
        assertEquals(6, history.entries().size());
        assertEquals("select 7", history.entries().get(0).sql());
    }

    @Test
    void limitIsClamped() {
        assertEquals(QueryHistory.MIN_LIMIT, new QueryHistory(() -> 0).limit());
        assertEquals(QueryHistory.MAX_LIMIT, new QueryHistory(() -> 100_000).limit());
    }
}
