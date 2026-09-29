package dev.phucngu.intelladb;

import dev.phucngu.intelladb.connection.DbConfig;
import dev.phucngu.intelladb.connection.PostgresDialect;
import dev.phucngu.intelladb.mongo.MongoDialect;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MongoDialectTest {

    private final MongoDialect dialect = new MongoDialect();

    @Test
    void asksForAPasswordOnlyWithAUser() {
        DbConfig config = new DbConfig();
        config.dialectId = MongoDialect.ID;
        config.host = "localhost";
        config.port = 27017;
        assertFalse(dialect.needsPassword(config), "no user: the driver sends no credentials");
        config.user = "app";
        assertTrue(dialect.needsPassword(config));
        config.jdbcUrlOverride = "mongodb://app:secret@localhost/";
        assertFalse(dialect.needsPassword(config), "the URL carries the password");
        config.jdbcUrlOverride = "";
        config.noAuth = true;
        assertFalse(dialect.needsPassword(config));
    }

    @Test
    void newMongoConnectionsStartWithoutAuth() {
        assertTrue(dialect.noAuthByDefault());
        assertFalse(new PostgresDialect().noAuthByDefault());
        DbConfig sql = new DbConfig();
        assertTrue(new PostgresDialect().needsPassword(sql), "SQL dialects keep asking");
    }
}
