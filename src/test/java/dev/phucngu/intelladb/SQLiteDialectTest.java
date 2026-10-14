package dev.phucngu.intelladb;

import dev.phucngu.intelladb.connection.DbConfig;
import dev.phucngu.intelladb.connection.DbSession;
import dev.phucngu.intelladb.connection.SqlResult;
import dev.phucngu.intelladb.connection.SQLiteDialect;
import dev.phucngu.intelladb.schema.SchemaCatalog;
import dev.phucngu.intelladb.schema.TableMeta;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The SQLite dialect against a real in-memory database — the driver is bundled, so this
 * needs no server and never skips. Also pins down how xerial's JDBC metadata maps onto the
 * schema model (one catalog shown as one schema).
 */
class SQLiteDialectTest {

    private static final SQLiteDialect DIALECT = new SQLiteDialect();
    private static DbSession session;

    @BeforeAll
    static void open() throws Exception {
        DbConfig config = new DbConfig();
        config.dialectId = SQLiteDialect.ID;
        config.noAuth = true;
        session = new DbSession(config, null);
        // xerial runs only the first statement of a multi-statement script, so seed one by one.
        for (String ddl : new String[]{
                "CREATE TABLE owner (id INTEGER PRIMARY KEY AUTOINCREMENT, code TEXT NOT NULL UNIQUE, note TEXT)",
                "CREATE TABLE pet (id INTEGER PRIMARY KEY, owner_id INTEGER REFERENCES owner(id), name TEXT)",
                "CREATE VIEW pet_names AS SELECT name FROM pet",
                "INSERT INTO owner (code) VALUES ('a1'), ('b2')"}) {
            SqlResult result = session.execute(ddl);
            assertTrue(result.isSuccessful(), ddl + ": " + result.text);
        }
        session.loadCatalog();
    }

    @AfterAll
    static void close() {
        if (session != null) {
            session.close();
        }
    }

    @Test
    void urlsAreFilePathsOrMemory() throws Exception {
        DbConfig config = new DbConfig();
        config.dialectId = SQLiteDialect.ID;
        assertEquals("jdbc:sqlite::memory:", DIALECT.jdbcUrl(config));
        config.database = "~/db/app.sqlite3";
        assertEquals("jdbc:sqlite:~/db/app.sqlite3", DIALECT.jdbcUrl(config));
        config.jdbcUrlOverride = "jdbc:sqlite:/tmp/x.db?readonly=true";
        assertEquals("jdbc:sqlite:/tmp/x.db?readonly=true", DIALECT.jdbcUrl(config));
        assertTrue(DIALECT.driver().acceptsURL("jdbc:sqlite::memory:"));
        assertEquals(List.of("off"), DIALECT.sslModes());
        assertTrue(DIALECT.noAuthByDefault());
        assertFalse(DIALECT.needsPassword(config));
    }

    @Test
    void catalogShowsTablesColumnsAndView() throws Exception {
        SchemaCatalog catalog = session.loadCatalog();
        SchemaCatalog.Schema schema = schemaOf(catalog, "owner");
        assertNotNull(schema, "a schema holding owner; got " + catalog.schemas());
        TableMeta owner = table(schema, "owner");
        assertNotNull(owner);
        List<String> ownerColumns = owner.columns.stream().map(c -> c.name).toList();
        assertTrue(ownerColumns.containsAll(List.of("id", "code", "note")), ownerColumns.toString());
        TableMeta pet = table(schema, "pet");
        assertNotNull(pet);
        assertTrue(pet.columns.stream().anyMatch(c -> c.name.equals("owner_id")), "foreign key column");
        assertNotNull(table(schema, "pet_names"), "views are listed");
    }

    @Test
    void insertUpdateDeleteRoundTrip() {
        LinkedHashMap<String, Object> insert = new LinkedHashMap<>();
        insert.put("code", "c3");
        insert.put("note", "third");
        assertTrue(session.applyRowUpdates(List.of(DIALECT.insertStatement(schema(), "owner", insert))).isSuccessful());
        assertEquals("3", scalar("SELECT count(*) FROM owner"));

        assertTrue(session.applyRowUpdates(List.of(DIALECT.updateStatement(schema(), "owner",
                new dev.phucngu.intelladb.sql.RowUpdates.Edit(Map.of("note", "3rd"), Map.of("code", "c3"))))).isSuccessful());
        assertEquals("3rd", scalar("SELECT note FROM owner WHERE code = 'c3'"));

        assertTrue(session.applyRowUpdates(List.of(DIALECT.deleteStatement(schema(), "owner", Map.of("code", "c3"))))
                .isSuccessful());
        assertEquals("2", scalar("SELECT count(*) FROM owner"));
    }

    @Test
    void updateThatMatchesNothingFailsAsAWhole() {
        String update = DIALECT.updateStatement(schema(), "owner",
                new dev.phucngu.intelladb.sql.RowUpdates.Edit(Map.of("note", "x"), Map.of("code", "missing")));
        SqlResult result = session.applyRowUpdates(List.of(update));
        assertFalse(result.isSuccessful());
        assertEquals("2", scalar("SELECT count(*) FROM owner"), "nothing was written");
    }

    private static String schema() {
        return schemaOf(session.catalog(), "owner").name();
    }

    private static String scalar(String sql) {
        SqlResult result = session.execute(sql);
        assertTrue(result.isSuccessful(), result.text);
        return String.valueOf(result.rows.get(0)[0]);
    }

    private static SchemaCatalog.Schema schemaOf(SchemaCatalog catalog, String table) {
        for (SchemaCatalog.Schema schema : catalog.schemas()) {
            if (table(schema, table) != null) {
                return schema;
            }
        }
        return null;
    }

    private static TableMeta table(SchemaCatalog.Schema schema, String name) {
        for (TableMeta table : schema.tables()) {
            if (table.name.equals(name)) {
                return table;
            }
        }
        return null;
    }
}
