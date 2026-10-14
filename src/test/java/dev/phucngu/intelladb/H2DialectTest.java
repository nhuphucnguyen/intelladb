package dev.phucngu.intelladb;

import dev.phucngu.intelladb.connection.DbConfig;
import dev.phucngu.intelladb.connection.DbSession;
import dev.phucngu.intelladb.connection.H2Dialect;
import dev.phucngu.intelladb.connection.SqlResult;
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
 * The H2 dialect against a real in-memory database — the driver is bundled, so this needs
 * no server and never skips. H2 has real schemas, so PUBLIC shows as the schema and
 * INFORMATION_SCHEMA stays hidden.
 */
class H2DialectTest {

    private static final H2Dialect DIALECT = new H2Dialect();
    private static DbSession session;

    @BeforeAll
    static void open() throws Exception {
        DbConfig config = new DbConfig();
        config.dialectId = H2Dialect.ID;
        config.noAuth = true;
        session = new DbSession(config, null);
        session.execute("""
                CREATE TABLE owner (id INT AUTO_INCREMENT PRIMARY KEY, code VARCHAR(40) NOT NULL UNIQUE, note VARCHAR(200));
                CREATE TABLE pet (id INT PRIMARY KEY, owner_id INT REFERENCES owner(id), name VARCHAR(40));
                CREATE VIEW pet_names AS SELECT name FROM pet;
                INSERT INTO owner (code) VALUES ('a1'), ('b2');
                """);
        session.loadCatalog();
    }

    @AfterAll
    static void close() {
        if (session != null) {
            session.close();
        }
    }

    @Test
    void urlsCoverMemoryFileAndTcp() throws Exception {
        DbConfig config = new DbConfig();
        config.dialectId = H2Dialect.ID;
        assertEquals("jdbc:h2:mem:intelladb;DB_CLOSE_DELAY=-1", DIALECT.jdbcUrl(config));
        config.database = "~/db/app";
        assertEquals("jdbc:h2:~/db/app", DIALECT.jdbcUrl(config));
        config.database = "tcp://db.example.com/~/app";
        assertEquals("jdbc:h2:tcp://db.example.com/~/app", DIALECT.jdbcUrl(config));
        config.jdbcUrlOverride = "jdbc:h2:mem:x";
        assertEquals("jdbc:h2:mem:x", DIALECT.jdbcUrl(config));
        assertTrue(DIALECT.driver().acceptsURL("jdbc:h2:mem:intelladb"));
        assertEquals(List.of("off"), DIALECT.sslModes());
        assertEquals("PUBLIC", DIALECT.defaultSchema());
        assertEquals("SET SCHEMA \"PUBLIC\"", DIALECT.useNamespaceStatement("PUBLIC"));
        assertTrue(DIALECT.isSystemSchema("INFORMATION_SCHEMA"));
    }

    @Test
    void catalogShowsPublicSchemaAndHidesInformationSchema() throws Exception {
        SchemaCatalog catalog = session.loadCatalog();
        SchemaCatalog.Schema schema = schemaOf(catalog, "owner");
        assertNotNull(schema, "a schema holding owner; got " + catalog.schemas());
        assertTrue(schema.name().equalsIgnoreCase("PUBLIC"), schema.name());
        assertFalse(catalog.schemas().stream().anyMatch(s -> s.name().equalsIgnoreCase("information_schema")),
                "the system schema is hidden by default");
        TableMeta owner = table(schema, "owner");
        assertNotNull(owner);
        List<String> ownerColumns = owner.columns.stream().map(c -> c.name).toList();
        assertTrue(ownerColumns.stream().map(String::toUpperCase).toList().containsAll(List.of("ID", "CODE", "NOTE")),
                ownerColumns.toString());
        assertNotNull(table(schema, "pet_names"), "views are listed");
    }

    @Test
    void insertUpdateDeleteRoundTrip() {
        LinkedHashMap<String, Object> insert = new LinkedHashMap<>();
        insert.put("CODE", "c3");
        insert.put("NOTE", "third");
        assertTrue(session.applyRowUpdates(List.of(DIALECT.insertStatement(schema(), "OWNER", insert))).isSuccessful());
        assertEquals("3", scalar("SELECT count(*) FROM owner"));

        assertTrue(session.applyRowUpdates(List.of(DIALECT.updateStatement(schema(), "OWNER",
                new dev.phucngu.intelladb.sql.RowUpdates.Edit(Map.of("NOTE", "3rd"), Map.of("CODE", "c3"))))).isSuccessful());
        assertEquals("3rd", scalar("SELECT note FROM owner WHERE code = 'c3'"));

        assertTrue(session.applyRowUpdates(List.of(DIALECT.deleteStatement(schema(), "OWNER", Map.of("CODE", "c3"))))
                .isSuccessful());
        assertEquals("2", scalar("SELECT count(*) FROM owner"));
    }

    @Test
    void updateThatMatchesNothingFailsAsAWhole() {
        String update = DIALECT.updateStatement(schema(), "OWNER",
                new dev.phucngu.intelladb.sql.RowUpdates.Edit(Map.of("NOTE", "x"), Map.of("CODE", "missing")));
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
            if (table.name.equalsIgnoreCase(name)) {
                return table;
            }
        }
        return null;
    }
}
