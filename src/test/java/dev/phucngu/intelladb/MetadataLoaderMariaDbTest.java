package dev.phucngu.intelladb;

import dev.phucngu.intelladb.connection.ConnectionTestReport;
import dev.phucngu.intelladb.connection.DbConfig;
import dev.phucngu.intelladb.connection.DbSession;
import dev.phucngu.intelladb.connection.MariaDbDialect;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Loads a real MariaDB catalog. Needs a reachable server — a throwaway local one by default
 * (root/root on localhost:3307, e.g. {@code docker run -p 3307:3306 -e MARIADB_ROOT_PASSWORD=root
 * mariadb:11}), or INTELLADB_MARIADB_URL/USER/PASSWORD — and is skipped otherwise. Works in a
 * throwaway database that is dropped afterwards.
 */
class MetadataLoaderMariaDbTest {

    private static final String DATABASE = "intelladb_loader_test";
    private static final MariaDbDialect DIALECT = new MariaDbDialect();
    private static Connection connection;

    @BeforeAll
    static void connect() throws SQLException {
        try {
            connection = DbSession.open(config(), password(), 5);
        } catch (SQLException e) {
            assumeTrue(false, "no MariaDB at " + config().jdbcUrlOverride + ": " + e.getMessage());
        }
        try (Statement st = connection.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + DATABASE);
            st.execute("CREATE DATABASE " + DATABASE);
            st.execute("USE " + DATABASE);
            st.execute("CREATE TABLE owner (id INT AUTO_INCREMENT PRIMARY KEY, code VARCHAR(20) NOT NULL,"
                    + " region VARCHAR(10) NOT NULL, UNIQUE KEY owner_code_region (code, region))");
            st.execute("CREATE TABLE pet (id INT NOT NULL, owner_id INT, name VARCHAR(50), age INT,"
                    + " addr INET6, PRIMARY KEY (id),"
                    + " CONSTRAINT pet_owner_fk FOREIGN KEY (owner_id) REFERENCES owner(id),"
                    + " CONSTRAINT pet_age_ck CHECK (age >= 0), INDEX pet_name_idx (name, age))");
            st.execute("CREATE VIEW adult_pet AS SELECT * FROM pet WHERE age >= 18");
            st.execute("CREATE SEQUENCE ticket_seq");
            st.execute("CREATE FUNCTION pet_count(p_owner INT) RETURNS BIGINT READS SQL DATA"
                    + " RETURN (SELECT COUNT(*) FROM pet WHERE owner_id = p_owner)");
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
        assertTrue(catalog.roles().contains("root"));
    }

    @Test
    void loadsTablesKeysForeignKeysIndexesChecksAndRoutines() throws SQLException {
        SchemaCatalog.Schema schema = MetadataLoader.load(connection, DIALECT, List.of(DATABASE), false)
                .schemas().getFirst();
        TableMeta pet = schema.tables().stream().filter(t -> t.name.equals("pet")).findFirst().orElseThrow();
        assertEquals(List.of("id", "owner_id", "name", "age", "addr"), pet.columns.stream().map(c -> c.name).toList());
        assertEquals(List.of("id"), pet.primaryKeyColumns());
        assertEquals(List.of(new TableMeta.ForeignKey("pet_owner_fk", List.of("owner_id"),
                DATABASE, "owner", List.of("id"))), pet.foreignKeys);
        assertTrue(pet.indexes.contains(new TableMeta.Index("pet_name_idx", List.of("name", "age"), false)),
                pet.indexes.toString());
        assertTrue(pet.checks.stream().anyMatch(c -> c.name().equals("pet_age_ck")), pet.checks.toString());
        assertEquals(List.of("adult_pet"), schema.tablesOf(TableMeta.Kind.VIEW).stream().map(t -> t.name).toList());
        assertTrue(schema.routinesOf(false).stream().anyMatch(r -> r.name().equals("pet_count")));
    }

    @Test
    void resultsNameTheSourceTableSoTheyCanBeEdited() {
        try (DbSession session = new DbSession(config(), password())) {
            SqlResult plain = session.execute("SELECT id, name FROM " + DATABASE + ".pet");
            assertEquals(DATABASE, plain.sourceSchema);
            assertEquals("pet", plain.sourceTable);
            SqlResult returning = session.execute("DELETE FROM " + DATABASE + ".pet WHERE id = -1 RETURNING id");
            assertTrue(returning.isSuccessful(), returning.text);
            SqlResult next = session.execute("SELECT NEXTVAL(" + DATABASE + ".ticket_seq)");
            assertEquals("1", String.valueOf(next.rows.getFirst()[0]));
        }
    }

    @Test
    void openAppliesSessionOptions() throws SQLException {
        DbConfig config = config();
        config.readOnly = true;
        config.timeZone = "+08:00";
        config.startupScript = "SET @from_startup = 'yes';";
        try (Connection c = DbSession.open(config, password(), 5); Statement st = c.createStatement()) {
            assertEquals("+08:00", scalar(st, "SELECT @@session.time_zone"));
            assertEquals("yes", scalar(st, "SELECT @from_startup"));
            SQLException insert = assertThrows(SQLException.class,
                    () -> st.execute("INSERT INTO " + DATABASE + ".owner (code, region) VALUES ('b', 'us')"));
            assertTrue(insert.getMessage().toLowerCase().contains("read only"), insert.getMessage());
        }
    }

    @Test
    void testConnectionReport() throws SQLException {
        ConnectionTestReport report = ConnectionTestReport.probe(connection, DIALECT);
        assertTrue(report.productVersion().contains("MariaDB"), report.productVersion());
        assertTrue(report.driver().startsWith("MariaDB Connector/J"), report.driver());
        assertFalse(report.ssl().equals("unknown"), report.ssl());
    }

    @Test
    void connectsOverSslWhenTheServerOffersIt() throws SQLException {
        DbConfig config = config();
        config.sslMode = true;
        config.sslModeName = "trust";
        Connection c;
        try {
            c = DbSession.open(config, password(), 5);
        } catch (SQLException e) {
            assumeTrue(false, "server has no TLS: " + e.getMessage());
            return;
        }
        try (c) {
            assertTrue(DIALECT.sslStatus(c).startsWith("yes ("), DIALECT.sslStatus(c));
        }
    }

    private static DbConfig config() {
        DbConfig config = new DbConfig();
        config.dialectId = MariaDbDialect.ID;
        config.jdbcUrlOverride = env("INTELLADB_MARIADB_URL", "jdbc:mariadb://localhost:3307/");
        config.user = env("INTELLADB_MARIADB_USER", "root");
        return config;
    }

    private static String password() {
        return env("INTELLADB_MARIADB_PASSWORD", "root");
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String scalar(Statement st, String sql) throws SQLException {
        try (var rs = st.executeQuery(sql)) {
            rs.next();
            String value = rs.getString(1);
            return value == null ? "" : value;
        }
    }
}
