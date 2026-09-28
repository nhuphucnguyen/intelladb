package community.intelladb;

import community.intelladb.connection.DbConfig;
import community.intelladb.connection.SqlResult;
import community.intelladb.history.QueryHistory;
import community.intelladb.history.QueryHistoryStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryHistoryStoreTest {

    @TempDir
    Path dir;

    private static DbConfig config() {
        DbConfig config = new DbConfig();
        config.name = "localhost";
        return config;
    }

    @Test
    void roundTripsEntriesAndResults() throws IOException {
        QueryHistoryStore store = new QueryHistoryStore(dir.resolve("history.json.gz"));
        QueryHistory history = new QueryHistory(() -> 10);
        history.add(config(), "public", "select * from t", SqlResult.rows("select * from t",
                List.of("id", "name", "active", "at"), List.of("int8", "text", "bool", "timestamp"),
                List.<Object[]>of(new Object[]{42L, "a", true, java.sql.Timestamp.valueOf("2026-09-28 10:00:00")},
                        new Object[]{new BigDecimal("100.000000"), null, false, null}),
                true, 12, "public", "t"));
        history.add(config(), null, "update t set x = 1", SqlResult.update("update t set x = 1", 3, 4));
        history.add(config(), null, "bad", SqlResult.error("bad", "syntax error", 1));
        store.save(history.entries());

        List<QueryHistory.Entry> loaded = store.load();
        assertEquals(3, loaded.size());
        assertEquals("bad", loaded.get(0).sql());
        assertEquals("syntax error", loaded.get(0).result().text);
        assertEquals(3, loaded.get(1).result().updateCount);
        assertNull(loaded.get(1).schema());

        QueryHistory.Entry select = loaded.get(2);
        QueryHistory.Entry original = history.entries().get(2);
        assertEquals(original.id(), select.id());
        assertEquals(original.executedAt(), select.executedAt());
        assertEquals("public", select.schema());
        SqlResult result = select.result();
        assertEquals(List.of("int8", "text", "bool", "timestamp"), result.columnTypes);
        assertEquals("public.t", result.qualifiedSource());
        assertTrue(result.truncated);
        assertArrayEquals(new Object[]{new BigDecimal("42"), "a", true, "2026-09-28 10:00:00.0"}, result.rows.get(0));
        assertArrayEquals(new Object[]{new BigDecimal("100.000000"), null, false, null}, result.rows.get(1));
    }

    @Test
    void historyReloadsFromItsStore() throws Exception {
        QueryHistoryStore store = new QueryHistoryStore(dir.resolve("h.json.gz"));
        QueryHistory history = new QueryHistory(() -> 10, store);
        history.add(config(), null, "select 1", SqlResult.message("select 1", "ok", 1));
        history.awaitSaved();
        QueryHistory reopened = new QueryHistory(() -> 10, store);
        assertEquals(1, reopened.entries().size());
        assertEquals("select 1", reopened.entries().get(0).sql());
        assertTrue(reopened.add(config(), null, "select 2", SqlResult.message("select 2", "ok", 1)).id()
                > reopened.entries().get(1).id()); // ids continue after the loaded ones
        reopened.awaitSaved();
    }

    @Test
    void unreadableFileStartsEmpty() throws IOException {
        Path file = dir.resolve("broken.json.gz");
        Files.writeString(file, "not gzip");
        assertTrue(new QueryHistoryStore(file).load().isEmpty());
    }
}
