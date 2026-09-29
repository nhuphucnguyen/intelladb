package dev.phucngu.intelladb;

import dev.phucngu.intelladb.connection.DbConfig;
import dev.phucngu.intelladb.connection.DbSession;
import dev.phucngu.intelladb.connection.PostgresDialect;
import dev.phucngu.intelladb.connection.SqlResult;
import dev.phucngu.intelladb.schema.MetadataLoader;
import dev.phucngu.intelladb.schema.SchemaCatalog;
import dev.phucngu.intelladb.schema.TableMeta;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Loads a real PostgreSQL catalog. Needs a reachable server — the demo database from
 * tools/sample-data.sql by default, or INTELLADB_TEST_URL/USER/PASSWORD — and is skipped
 * otherwise. Works in a throwaway schema that is dropped afterwards.
 */
class MetadataLoaderPostgresTest {

    private static final String SCHEMA = "intelladb_loader_test";
    private static Connection connection;

    @BeforeAll
    static void connect() throws SQLException {
        String url = env("INTELLADB_TEST_URL", "jdbc:postgresql://localhost:5432/intelladb");
        try {
            connection = DriverManager.getConnection(url,
                    env("INTELLADB_TEST_USER", "intella"), env("INTELLADB_TEST_PASSWORD", "intella123"));
        } catch (SQLException e) {
            assumeTrue(false, "no PostgreSQL at " + url + ": " + e.getMessage());
        }
        try (Statement st = connection.createStatement()) {
            st.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
            st.execute("CREATE SCHEMA " + SCHEMA);
            st.execute("SET search_path = " + SCHEMA);
            st.execute("CREATE TYPE mood AS ENUM ('ok', 'meh')");
            st.execute("CREATE TABLE owner (id SERIAL PRIMARY KEY, code TEXT NOT NULL UNIQUE)");
            st.execute("CREATE TABLE pet (id INT, owner_id INT REFERENCES owner(id), name TEXT,"
                    + " age INT CHECK (age >= 0), PRIMARY KEY (id))");
            st.execute("CREATE INDEX pet_name_lower ON pet (lower(name), coalesce(age, 0))");
            st.execute("CREATE VIEW adult_pet AS SELECT * FROM pet WHERE age >= 18");
            st.execute("CREATE FUNCTION pet_count(p_owner INT) RETURNS BIGINT LANGUAGE sql"
                    + " AS $$ SELECT count(*) FROM pet WHERE owner_id = p_owner $$");
            st.execute("CREATE PROCEDURE noop() LANGUAGE sql AS $$ SELECT 1 $$");
            st.execute("CREATE AGGREGATE total(INT) (SFUNC = int4pl, STYPE = INT, INITCOND = '0')");
        }
    }

    @AfterAll
    static void cleanUp() throws SQLException {
        if (connection != null) {
            try (Statement st = connection.createStatement()) {
                st.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
            }
            connection.close();
        }
    }

    @Test
    void loadsDatabasesAndSchemaCounts() throws SQLException {
        SchemaCatalog catalog = MetadataLoader.load(connection, new PostgresDialect());

        assertEquals("intelladb", catalog.database());
        assertTrue(catalog.databases().contains("intelladb"));
        assertTrue(catalog.totalSchemas() > catalog.schemas().size(), "system schemas are counted but hidden");
        assertTrue(catalog.extensions().stream().anyMatch(e -> e.name().equals("plpgsql")));
        assertTrue(catalog.roles().contains("intella"));
    }

    @Test
    void loadsTableKeysForeignKeysIndexesAndChecks() throws SQLException {
        SchemaCatalog.Schema schema = schema();
        TableMeta pet = schema.tables().stream().filter(t -> t.name.equals("pet")).findFirst().orElseThrow();

        assertEquals(List.of("id"), pet.primaryKeyColumns());
        assertEquals(List.of(new TableMeta.Key("pet_pkey", List.of("id"), true)), pet.keys);
        assertEquals(List.of(new TableMeta.ForeignKey("pet_owner_id_fkey", List.of("owner_id"),
                SCHEMA, "owner", List.of("id"))), pet.foreignKeys);
        assertTrue(pet.indexes.contains(new TableMeta.Index("pet_name_lower",
                List.of("lower(name)", "COALESCE(age, 0)"), false)), pet.indexes.toString());
        assertEquals(1, pet.checks.size());
        assertTrue(pet.checks.getFirst().definition().contains("age >= 0"));

        TableMeta owner = schema.tables().stream().filter(t -> t.name.equals("owner")).findFirst().orElseThrow();
        assertTrue(owner.keys.contains(new TableMeta.Key("owner_code_key", List.of("code"), false)));
        assertEquals(List.of("adult_pet"), schema.tablesOf(TableMeta.Kind.VIEW).stream().map(t -> t.name).toList());
    }

    @Test
    void loadsRoutinesSequencesAndTypes() throws SQLException {
        SchemaCatalog.Schema schema = schema();

        assertEquals(List.of(
                new SchemaCatalog.Routine("noop", SchemaCatalog.Routine.Kind.PROCEDURE, "", ""),
                new SchemaCatalog.Routine("pet_count", SchemaCatalog.Routine.Kind.FUNCTION, "p_owner integer", "bigint")),
                schema.routinesOf(false));
        assertEquals(List.of(new SchemaCatalog.Routine("total", SchemaCatalog.Routine.Kind.AGGREGATE,
                "integer", "integer")), schema.routinesOf(true));
        assertEquals(List.of("owner_id_seq"), schema.sequences());
        assertEquals(List.of(new SchemaCatalog.ObjectType("mood", "enum")), schema.objectTypes());
    }

    @Test
    void loadsOnlyTheChosenSchemas() throws SQLException {
        SchemaCatalog only = MetadataLoader.load(connection, new PostgresDialect(), List.of(SCHEMA), false);
        assertEquals(List.of(SCHEMA), only.schemaNames());

        SchemaCatalog withSystem = MetadataLoader.load(connection, new PostgresDialect(), List.of(), true);
        assertTrue(withSystem.schemaNames().contains("pg_catalog"));
        assertTrue(withSystem.schemaNames().contains(SCHEMA));
    }

    @Test
    void openAppliesSessionOptions() throws SQLException {
        DbConfig config = new DbConfig();
        config.jdbcUrlOverride = env("INTELLADB_TEST_URL", "jdbc:postgresql://localhost:5432/intelladb");
        config.user = env("INTELLADB_TEST_USER", "intella");
        config.readOnly = true;
        config.timeZone = "Asia/Singapore";
        config.startupScript = "SET application_name = 'from-startup';\nSET statement_timeout = '7s';";
        config.driverProperties.put("ApplicationName", "from-driver");
        try (Connection c = DbSession.open(config, env("INTELLADB_TEST_PASSWORD", "intella123"), 5);
             Statement st = c.createStatement()) {
            assertEquals("Asia/Singapore", scalar(st, "SHOW TimeZone"));
            assertEquals("7s", scalar(st, "SHOW statement_timeout"));
            // The startup script runs after the driver properties are applied.
            assertEquals("from-startup", scalar(st, "SHOW application_name"));
            SQLException denied = assertThrows(SQLException.class,
                    () -> st.execute("CREATE TABLE " + SCHEMA + ".nope (id INT)"));
            assertTrue(denied.getMessage().contains("read-only"), denied.getMessage());
        }
    }

    @Test
    void editedRowsAreWrittenBackAllOrNothing() throws SQLException {
        DbConfig config = new DbConfig();
        config.jdbcUrlOverride = env("INTELLADB_TEST_URL", "jdbc:postgresql://localhost:5432/intelladb");
        config.user = env("INTELLADB_TEST_USER", "intella");
        String owner = SCHEMA + ".owner";
        try (DbSession session = new DbSession(config, env("INTELLADB_TEST_PASSWORD", "intella123"))) {
            session.execute("INSERT INTO " + owner + " (id, code) VALUES (101, 'a'), (102, 'b')");
            // Aliases don't hide the base columns an edit writes to.
            SqlResult rows = session.execute("SELECT id AS ident, code, length(code) FROM " + owner);
            assertEquals(List.of(), rows.sourceColumns); // computed column: not one table
            rows = session.execute("SELECT id AS ident, code FROM " + owner);
            assertEquals(java.util.Arrays.asList("id", "code"), rows.sourceColumns);

            SqlResult ok = session.applyRowUpdates(List.of(
                    "UPDATE " + owner + " SET code = 'aa' WHERE id = 101"));
            assertTrue(ok.isSuccessful(), ok.text);
            assertEquals("1 row changed", ok.text);

            // The second row is gone: nothing sticks, and auto-commit is back on.
            SqlResult failed = session.applyRowUpdates(List.of(
                    "UPDATE " + owner + " SET code = 'x' WHERE id = 102",
                    "UPDATE " + owner + " SET code = 'y' WHERE id = 999"));
            assertFalse(failed.isSuccessful());
            assertTrue(failed.text.startsWith("No row matched"), failed.text);
            assertEquals("aa,b", codes(session, owner));

            // In a manual transaction a failed submit only undoes itself (savepoint) …
            session.setAutoCommit(false);
            session.execute("UPDATE " + owner + " SET code = 'bb' WHERE id = 102");
            assertFalse(session.applyRowUpdates(List.of(
                    "UPDATE " + owner + " SET code = 'z' WHERE id = 101",
                    "UPDATE " + owner + " SET code = 'z' WHERE id = 999")).isSuccessful());
            assertEquals("aa,bb", codes(session, owner));
            // … and a successful one stays pending until the user commits or rolls back.
            SqlResult pending = session.applyRowUpdates(List.of(
                    "UPDATE " + owner + " SET code = 'c' WHERE id = 101"));
            assertTrue(pending.text.contains("pending"), pending.text);
            session.rollback();
            session.setAutoCommit(true);
            assertEquals("aa,b", codes(session, owner));

            // Updates and deletes go together; a delete that matches nothing undoes the update too.
            assertFalse(session.applyRowUpdates(List.of(
                    "UPDATE " + owner + " SET code = 'q' WHERE id = 101",
                    "DELETE FROM " + owner + " WHERE id = 999")).isSuccessful());
            assertEquals("aa,b", codes(session, owner));
            SqlResult deleted = session.applyRowUpdates(List.of("DELETE FROM " + owner + " WHERE id = 102"));
            assertTrue(deleted.isSuccessful(), deleted.text);
            assertEquals("aa", codes(session, owner));
        }
    }

    private static String codes(DbSession session, String owner) {
        SqlResult result = session.execute("SELECT code FROM " + owner + " WHERE id > 100 ORDER BY id");
        return result.rows.stream().map(row -> String.valueOf(row[0])).collect(java.util.stream.Collectors.joining(","));
    }

    @Test
    void startupScriptErrorsNameTheStatement() {
        DbConfig config = new DbConfig();
        config.jdbcUrlOverride = env("INTELLADB_TEST_URL", "jdbc:postgresql://localhost:5432/intelladb");
        config.user = env("INTELLADB_TEST_USER", "intella");
        config.startupScript = "SET no_such_setting = 1";
        SQLException error = assertThrows(SQLException.class,
                () -> DbSession.open(config, env("INTELLADB_TEST_PASSWORD", "intella123"), 5));
        assertTrue(error.getMessage().startsWith("Startup script failed at \"SET no_such_setting = 1\""),
                error.getMessage());
    }

    private static String scalar(Statement st, String sql) throws SQLException {
        try (var rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static SchemaCatalog.Schema schema() throws SQLException {
        return MetadataLoader.load(connection, new PostgresDialect()).schemas().stream()
                .filter(s -> s.name().equals(SCHEMA)).findFirst().orElseThrow();
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
