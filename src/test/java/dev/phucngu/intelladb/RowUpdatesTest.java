package dev.phucngu.intelladb;

import dev.phucngu.intelladb.connection.MySqlDialect;
import dev.phucngu.intelladb.connection.PostgresDialect;
import dev.phucngu.intelladb.schema.ColumnMeta;
import dev.phucngu.intelladb.schema.TableMeta;
import dev.phucngu.intelladb.sql.RowUpdates;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RowUpdatesTest {

    private static ColumnMeta column(String name, boolean nullable, boolean primaryKey) {
        return new ColumnMeta(name, "int4", nullable, "", 1, primaryKey, "");
    }

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    @Test
    void keyIsThePrimaryKeyInKeyOrder() {
        TableMeta table = new TableMeta("film_actor", TableMeta.Kind.TABLE,
                List.of(column("actor_id", false, true), column("film_id", false, true), column("note", true, false)), "");
        assertEquals(List.of("actor_id", "film_id"), RowUpdates.rowKey(table));
    }

    @Test
    void keyFallsBackToAUniqueKeyOverNotNullColumns() {
        List<ColumnMeta> columns = List.of(column("email", true, false), column("code", false, false),
                column("region", false, false));
        TableMeta table = new TableMeta("owner", TableMeta.Kind.TABLE, columns, "", List.of(
                new TableMeta.Key("owner_email", List.of("email"), false),
                new TableMeta.Key("owner_code_region", List.of("code", "region"), false)),
                List.of(), List.of(), List.of());
        // The nullable unique key can match several NULL rows, so it is skipped.
        assertEquals(List.of("code", "region"), RowUpdates.rowKey(table));
    }

    @Test
    void noKeyForKeylessTablesAndViews() {
        assertNull(RowUpdates.rowKey(new TableMeta("log", TableMeta.Kind.TABLE,
                List.of(column("line", true, false)), "")));
        assertNull(RowUpdates.rowKey(new TableMeta("v", TableMeta.Kind.VIEW,
                List.of(column("id", false, true)), "")));
    }

    @Test
    void updateSetsQuotedValuesAndMatchesTheLoadedKey() {
        String sql = RowUpdates.update(new PostgresDialect(), "public.actor", new RowUpdates.Edit(
                map("first_name", "O'Neil", "Last Name", null), map("actor_id", 3)));
        assertEquals("UPDATE public.actor SET first_name = 'O''Neil', \"Last Name\" = NULL WHERE actor_id = 3", sql);
    }

    @Test
    void keyLiteralsFollowTheLoadedType() {
        String sql = RowUpdates.update(new PostgresDialect(), "t", new RowUpdates.Edit(
                map("n", "5"), map("a", new BigDecimal("1.50"), "b", true, "c", "x", "d", Double.NaN, "e", null)));
        assertEquals("UPDATE t SET n = '5' WHERE a = 1.50 AND b = TRUE AND c = 'x' AND d = 'NaN' AND e IS NULL", sql);
    }

    @Test
    void mysqlEscapesBackslashesAndQuotesWithBackticks() {
        String sql = RowUpdates.update(new MySqlDialect(), "shop.`order`", new RowUpdates.Edit(
                map("path", "C:\\tmp\\'x'"), map("id", 7L)));
        assertEquals("UPDATE shop.`order` SET path = 'C:\\\\tmp\\\\''x''' WHERE id = 7", sql);
    }

    @Test
    void deleteMatchesTheLoadedKey() {
        assertEquals("DELETE FROM public.film_actor WHERE actor_id = 1 AND film_id = 23",
                RowUpdates.delete(new PostgresDialect(), "public.film_actor", map("actor_id", 1, "film_id", 23)));
        assertThrows(IllegalArgumentException.class, () -> RowUpdates.delete(new PostgresDialect(), "t", Map.of()));
    }

    @Test
    void updateCanSetNull() {
        assertEquals("UPDATE t SET note = NULL WHERE id = 4",
                RowUpdates.update(new PostgresDialect(), "t", new RowUpdates.Edit(map("note", null), map("id", 4))));
    }

    @Test
    void updateNeedsChangesAndAKey() {
        assertThrows(IllegalArgumentException.class, () -> RowUpdates.update(new PostgresDialect(), "t",
                new RowUpdates.Edit(Map.of(), map("id", 1))));
        assertThrows(IllegalArgumentException.class, () -> RowUpdates.update(new PostgresDialect(), "t",
                new RowUpdates.Edit(map("a", "1"), Map.of())));
    }
}
