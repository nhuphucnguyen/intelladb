package dev.phucngu.intelladb;

import dev.phucngu.intelladb.connection.DbConfig;
import dev.phucngu.intelladb.connection.DbSession;
import dev.phucngu.intelladb.connection.MySqlDialect;
import dev.phucngu.intelladb.connection.SqlResult;
import dev.phucngu.intelladb.schema.MetadataLoader;
import dev.phucngu.intelladb.schema.SchemaCatalog;
import dev.phucngu.intelladb.schema.TableMeta;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Loads a real MySQL catalog. Needs a reachable server — a throwaway local one by default
 * (root/root on localhost:3306), or INTELLADB_MYSQL_URL/USER/PASSWORD — and is skipped
 * otherwise. Works in a throwaway database that is dropped afterwards.
 */
class MetadataLoaderMySqlTest {

    private static final String DATABASE = "intelladb_loader_test";
    private static final MySqlDialect DIALECT = new MySqlDialect();
    private static Connection connection;

    @BeforeAll
    static void connect() throws SQLException {
        try {
            connection = DbSession.open(config(), password(), 5);
        } catch (SQLException e) {
            assumeTrue(false, "no MySQL at " + env("INTELLADB_MYSQL_URL", "jdbc:mariadb://localhost:3306/")
                    + ": " + e.getMessage());
        }
        try (Statement st = connection.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + DATABASE);
            st.execute("CREATE DATABASE " + DATABASE);
            st.execute("USE " + DATABASE);
            st.execute("CREATE TABLE owner (id INT AUTO_INCREMENT PRIMARY KEY, code VARCHAR(20) NOT NULL,"
                    + " region VARCHAR(10) NOT NULL, UNIQUE KEY owner_code_region (code, region))");
            st.execute("CREATE TABLE pet (id INT NOT NULL, owner_id INT, name VARCHAR(50), age INT,"
                    + " amount DECIMAL(10,2) UNSIGNED, PRIMARY KEY (id),"
                    + " CONSTRAINT pet_owner_fk FOREIGN KEY (owner_id) REFERENCES owner(id),"
                    + " CONSTRAINT pet_age_ck CHECK (age >= 0), INDEX pet_name_idx (name, age))");
            st.execute("CREATE VIEW adult_pet AS SELECT * FROM pet WHERE age >= 18");
            st.execute("CREATE FUNCTION pet_count(p_owner INT) RETURNS BIGINT READS SQL DATA"
                    + " RETURN (SELECT COUNT(*) FROM pet WHERE owner_id = p_owner)");
            st.execute("CREATE PROCEDURE bump_age(IN p_id INT, OUT p_age INT)"
                    + " BEGIN UPDATE pet SET age = age + 1 WHERE id = p_id; SELECT age INTO p_age FROM pet WHERE id = p_id; END");
            st.execute("INSERT INTO owner (code, region) VALUES ('a', 'eu')");
            st.execute("INSERT INTO pet (id, owner_id, name, age) VALUES (1, 1, 'Rex', 3)");
        }
    }

    @AfterAll
    static void cleanUp() throws SQLException {
        if (connection != null) {
            try (Statement st = connection.createStatement()) {
                st.execute("DROP DATABASE IF EXISTS " + DATABASE);
            }
            connection.close();
        }
    }

    @Test
    void databasesAreSchemasUnderTheConnection() throws SQLException {
        SchemaCatalog catalog = MetadataLoader.load(connection, DIALECT);

        assertTrue(catalog.schemaNames().contains(DATABASE));
        assertFalse(catalog.schemaNames().contains("information_schema"), "system schemas are hidden");
        assertTrue(catalog.totalSchemas() > catalog.schemas().size(), "system schemas are counted but hidden");
        assertEquals("", catalog.database(), "no database level in the model");
        assertTrue(catalog.extensions().isEmpty());
        assertTrue(catalog.roles().contains("root"));
        assertTrue(MetadataLoader.schemaNames(connection, DIALECT).containsAll(List.of(DATABASE, "mysql", "sys")));
    }

    @Test
    void loadsTablesColumnsKeysForeignKeysIndexesAndChecks() throws SQLException {
        SchemaCatalog.Schema schema = schema();
        TableMeta pet = table(schema, "pet");

        assertEquals(List.of("id", "owner_id", "name", "age", "amount"), pet.columns.stream().map(c -> c.name).toList());
        assertEquals("DECIMAL UNSIGNED", pet.columns.get(4).typeName);
        assertEquals(List.of("id"), pet.primaryKeyColumns());
        assertEquals(List.of(new TableMeta.Key("PRIMARY", List.of("id"), true)), pet.keys);
        assertEquals(List.of(new TableMeta.ForeignKey("pet_owner_fk", List.of("owner_id"),
                DATABASE, "owner", List.of("id"))), pet.foreignKeys);
        assertTrue(pet.indexes.contains(new TableMeta.Index("pet_name_idx", List.of("name", "age"), false)),
                pet.indexes.toString());
        assertEquals(1, pet.checks.size());
        assertEquals("pet_age_ck", pet.checks.getFirst().name());
        assertTrue(pet.checks.getFirst().definition().contains("age"), pet.checks.getFirst().definition());

        TableMeta owner = table(schema, "owner");
        assertTrue(owner.keys.contains(new TableMeta.Key("owner_code_region", List.of("code", "region"), false)));
        assertTrue(owner.indexes.contains(new TableMeta.Index("owner_code_region", List.of("code", "region"), true)));
        assertEquals(List.of("adult_pet"), schema.tablesOf(TableMeta.Kind.VIEW).stream().map(t -> t.name).toList());
        assertEquals(List.of("owner", "pet"), schema.tablesOf(TableMeta.Kind.TABLE).stream().map(t -> t.name).toList());
    }

    @Test
    void loadsRoutines() throws SQLException {
        assertEquals(List.of(
                new SchemaCatalog.Routine("bump_age", SchemaCatalog.Routine.Kind.PROCEDURE, "IN p_id int, OUT p_age int", ""),
                new SchemaCatalog.Routine("pet_count", SchemaCatalog.Routine.Kind.FUNCTION, "p_owner int", "bigint")),
                schema().routinesOf(false));
        assertTrue(schema().sequences().isEmpty());
        assertTrue(schema().objectTypes().isEmpty());
    }

    @Test
    void loadsOnlyTheChosenSchemasIncludingSystemOnes() throws SQLException {
        SchemaCatalog only = MetadataLoader.load(connection, DIALECT, List.of(DATABASE), false);
        assertEquals(List.of(DATABASE), only.schemaNames());

        // A system schema the user ticked explicitly is introspected.
        SchemaCatalog mysql = MetadataLoader.load(connection, DIALECT, List.of("mysql"), false);
        assertEquals(List.of("mysql"), mysql.schemaNames());
        assertFalse(mysql.schemas().getFirst().tables().isEmpty());

        SchemaCatalog withSystem = MetadataLoader.load(connection, DIALECT, List.of(), true);
        assertTrue(withSystem.schemaNames().contains("mysql"));
        assertTrue(withSystem.schemaNames().contains(DATABASE));
    }

    @Test
    void resultMetadataNamesTheSourceTable() {
        try (DbSession session = new DbSession(config(), password())) {
            SqlResult plain = session.execute("SELECT id, name FROM " + DATABASE + ".pet");
            assertEquals(DATABASE, plain.sourceSchema);
            assertEquals("pet", plain.sourceTable);
            assertEquals(DATABASE + ".pet", plain.qualifiedSource(DIALECT));

            // The driver reports the table behind an alias, not the alias.
            assertEquals("pet", session.execute("SELECT p.id AS ident FROM " + DATABASE + ".pet p").sourceTable);

            assertNull(session.execute("SELECT id + 1 FROM " + DATABASE + ".pet").sourceTable);
            assertNull(session.execute("SELECT p.id, o.code FROM " + DATABASE + ".pet p JOIN "
                    + DATABASE + ".owner o ON o.id = p.owner_id").sourceTable);
            assertNull(session.execute("SELECT 1").sourceTable);

            SqlResult typed = session.execute("SELECT amount FROM " + DATABASE + ".pet");
            assertEquals("DECIMAL UNSIGNED", typed.columnType(0));

            // Edits write to the base column behind an alias.
            assertEquals(List.of("id"), session.execute("SELECT p.id AS ident FROM " + DATABASE + ".pet p").sourceColumns);
        }
    }

    @Test
    void editedRowsAreWrittenBackAllOrNothing() {
        String owner = DATABASE + ".owner";
        try (DbSession session = new DbSession(config(), password())) {
            SqlResult inserted = session.execute("INSERT INTO " + owner + " (id, code, region) VALUES (101, 'edit-a', 'eu'), (102, 'edit-b', 'eu')");
            assertTrue(inserted.isSuccessful(), inserted.text);
            SqlResult ok = session.applyRowUpdates(List.of("UPDATE " + owner + " SET code = 'aa' WHERE id = 101"));
            assertTrue(ok.isSuccessful(), ok.text);
            SqlResult failed = session.applyRowUpdates(List.of(
                    "UPDATE " + owner + " SET code = 'x' WHERE id = 102",
                    "UPDATE " + owner + " SET code = 'y' WHERE id = 999"));
            assertFalse(failed.isSuccessful());
            SqlResult codes = session.execute("SELECT code FROM " + owner + " WHERE id > 100 ORDER BY id");
            assertEquals("aa", codes.rows.get(0)[0]);
            assertEquals("edit-b", codes.rows.get(1)[0]);
            session.execute("DELETE FROM " + owner + " WHERE id > 100");
        }
    }

    @Test
    void openAppliesSessionOptions() throws SQLException {
        DbConfig config = config();
        config.jdbcUrlOverride = "jdbc:mysql://localhost:3306"; // MySQL-style URL, rewritten for the driver
        config.readOnly = true;
        config.timeZone = "Asia/Singapore";
        config.startupScript = "SET @from_startup = 'yes';\nSET SESSION max_execution_time = 7000;";
        try (Connection c = DbSession.open(config, password(), 5); Statement st = c.createStatement()) {
            assertEquals("Asia/Singapore", scalar(st, "SELECT @@session.time_zone"));
            assertEquals("yes", scalar(st, "SELECT @from_startup"));
            assertEquals("7000", scalar(st, "SELECT @@session.max_execution_time"));
            assertEquals("1", scalar(st, "SELECT @@session.transaction_read_only"));
            // Read-only covers writes and DDL alike.
            SQLException insert = assertThrows(SQLException.class,
                    () -> st.execute("INSERT INTO " + DATABASE + ".owner (code, region) VALUES ('b', 'us')"));
            assertTrue(insert.getMessage().contains("READ ONLY"), insert.getMessage());
            SQLException ddl = assertThrows(SQLException.class,
                    () -> st.execute("CREATE TABLE " + DATABASE + ".nope (id INT)"));
            assertTrue(ddl.getMessage().contains("READ ONLY"), ddl.getMessage());
        }
    }

    @Test
    void consoleSchemaSwitchUsesUse() throws SQLException {
        try (Connection c = DbSession.open(config(), password(), 5); Statement st = c.createStatement()) {
            st.execute(DIALECT.useNamespaceStatement(DATABASE));
            assertEquals(DATABASE, scalar(st, "SELECT DATABASE()"));
        }
    }

    @Test
    void connectsWithCachingSha2AfterTheServerForgotTheCachedPassword() throws SQLException {
        // caching_sha2_password (MySQL 8's default) needs the RSA key for the first full
        // authentication over plain TCP; FLUSH PRIVILEGES empties the server's cache to force it.
        try (Statement st = connection.createStatement()) {
            st.execute("FLUSH PRIVILEGES");
        }
        try (Connection c = DbSession.open(config(), password(), 5); Statement st = c.createStatement()) {
            assertEquals("1", scalar(st, "SELECT 1"));
        }
    }

    @Test
    void connectsOverSsl() throws SQLException {
        DbConfig config = config();
        config.sslMode = true;
        config.sslModeName = "trust";
        try (Connection c = DbSession.open(config, password(), 5); Statement st = c.createStatement()) {
            assertFalse(scalar(st, "SELECT VARIABLE_VALUE FROM performance_schema.session_status"
                    + " WHERE VARIABLE_NAME = 'Ssl_cipher'").isEmpty());
        }
    }

    @Test
    void startupScriptErrorsNameTheStatement() {
        DbConfig config = config();
        config.startupScript = "SET no_such_setting = 1";
        SQLException error = assertThrows(SQLException.class, () -> DbSession.open(config, password(), 5));
        assertTrue(error.getMessage().startsWith("Startup script failed at \"SET no_such_setting = 1\""),
                error.getMessage());
    }

    private static DbConfig config() {
        DbConfig config = new DbConfig();
        config.dialectId = MySqlDialect.ID;
        config.jdbcUrlOverride = env("INTELLADB_MYSQL_URL", "jdbc:mariadb://localhost:3306/");
        config.user = env("INTELLADB_MYSQL_USER", "root");
        return config;
    }

    private static String password() {
        return env("INTELLADB_MYSQL_PASSWORD", "root");
    }

    private static String scalar(Statement st, String sql) throws SQLException {
        try (var rs = st.executeQuery(sql)) {
            rs.next();
            String value = rs.getString(1);
            return value == null ? "" : value;
        }
    }

    private static SchemaCatalog.Schema schema() throws SQLException {
        return MetadataLoader.load(connection, DIALECT).schemas().stream()
                .filter(s -> s.name().equals(DATABASE)).findFirst().orElseThrow();
    }

    private static TableMeta table(SchemaCatalog.Schema schema, String name) {
        return schema.tables().stream().filter(t -> t.name.equals(name)).findFirst().orElseThrow();
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
