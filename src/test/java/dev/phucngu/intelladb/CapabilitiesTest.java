package dev.phucngu.intelladb;

import dev.phucngu.intelladb.connection.DbDialect;
import dev.phucngu.intelladb.connection.PostgresDialect;
import dev.phucngu.intelladb.connection.capability.Capabilities;
import dev.phucngu.intelladb.connection.capability.RowMutator;
import dev.phucngu.intelladb.mongo.MongoDialect;
import dev.phucngu.intelladb.mongo.MongoRowMutator;
import dev.phucngu.intelladb.schema.PostgresObjects;
import dev.phucngu.intelladb.sql.RowUpdates;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The dialect capability chain: a dialect binds what it changes, everything else falls
 * through to the generic behaviour (the pattern IntelliJ's database plugin uses for its
 * per-DBMS extensions).
 */
class CapabilitiesTest {

    private interface Marker { }

    @Test
    void unboundCapabilitiesFallThroughToNull() {
        assertNull(Capabilities.GENERIC.find(Marker.class));
        assertNull(Capabilities.GENERIC.extend().find(Marker.class));
        Marker fallback = new Marker() {
        };
        assertSame(fallback, Capabilities.GENERIC.get(Marker.class, fallback));
    }

    @Test
    void ownBindingShadowsTheFallbackChain() {
        Marker generic = new Marker() {
        };
        Marker mysql = new Marker() {
        };
        Capabilities base = Capabilities.GENERIC.extend().bind(Marker.class, generic);
        Capabilities chain = base.extend().bind(Marker.class, mysql);
        assertSame(mysql, chain.find(Marker.class));
        assertSame(generic, base.find(Marker.class));
        assertNull(Capabilities.GENERIC.find(Marker.class));
    }

    @Test
    void genericRowMutatorIsTheBaseBinding() {
        assertInstanceOf(RowMutator.class, Capabilities.GENERIC.find(RowMutator.class));
    }

    @Test
    void postgresBindsItsObjectsLoader() {
        DbDialect postgres = new PostgresDialect();
        assertInstanceOf(PostgresObjects.class, postgres.objectsLoader());
    }

    @Test
    void insertStatementUsesTheRowMutator() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("code", "n1");
        assertEquals("INSERT INTO public.owner (code) VALUES ('n1')",
                new PostgresDialect().insertStatement("public", "owner", values));
        // MongoDB speaks mongosh instead of SQL (typed by MongoValues, keys quoted)
        assertEquals("db.getCollection(\"pets\").insertOne({\"name\": \"Rex\"})",
                new MongoDialect().insertStatement(null, "pets", Map.of("name", "Rex")));
    }

    @Test
    void mongoUpdateAndDeleteStillSpeakMongosh() {
        RowUpdates.Edit edit = new RowUpdates.Edit(Map.of("age", "4"), Map.of("_id", 1), Map.of("age", 3));
        assertEquals("db.getCollection(\"pets\").updateOne({\"_id\": 1}, {\"$set\": {\"age\": 4}})",
                new MongoDialect().updateStatement(null, "pets", edit));
        assertEquals("db.getCollection(\"pets\").deleteOne({\"_id\": 1})",
                new MongoDialect().deleteStatement(null, "pets", Map.of("_id", 1)));
        assertSame(MongoRowMutator.INSTANCE, new MongoDialect().capabilities().find(RowMutator.class));
    }
}
