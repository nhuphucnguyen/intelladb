package dev.phucngu.intelladb;

import dev.phucngu.intelladb.connection.ConnectionTestReport;
import dev.phucngu.intelladb.connection.DbConfig;
import dev.phucngu.intelladb.connection.DbSession;
import dev.phucngu.intelladb.connection.Dialects;
import dev.phucngu.intelladb.connection.SqlResult;
import dev.phucngu.intelladb.mongo.MongoDialect;
import dev.phucngu.intelladb.mongo.MongoValues;
import dev.phucngu.intelladb.schema.SchemaCatalog;
import dev.phucngu.intelladb.schema.TableMeta;
import dev.phucngu.intelladb.sql.RowUpdates;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs the MongoDB engine against a real server: a throwaway standalone one on
 * localhost:27017 by default ({@code docker run -p 27017:27017 mongo:8}), or
 * INTELLADB_MONGO_URL; skipped when none is reachable. Transactions need a replica set:
 * INTELLADB_MONGO_RS_URL, by default a single-node one on localhost:27018
 * ({@code docker run -p 27018:27017 mongo:8 --replSet rs0}, then {@code rs.initiate()}).
 * Works in a throwaway database that is dropped afterwards.
 */
class MongoEngineTest {

    private static final String DATABASE = "intelladb_engine_test";
    private static final MongoDialect DIALECT = new MongoDialect();
    private static DbSession session;

    @BeforeAll
    static void connect() {
        session = new DbSession(config(env("INTELLADB_MONGO_URL", "mongodb://localhost:27017/" + DATABASE)), null);
        try {
            session.ensureOpen();
        } catch (Exception e) {
            assumeTrue(false, "no MongoDB: " + e.getMessage());
        }
        run("db.dropDatabase()");
        run("""
                db.pets.insertMany([
                  {_id: 1, name: "Rex", age: 3, weight: 12.5, born: ISODate("2021-04-01T00:00:00Z"), tags: ["dog"]},
                  {_id: 2, name: "Tom", age: 5, owner: {name: "Ann"}},
                  {_id: 3, name: "Kit", age: NumberLong(1), weight: 3.0}
                ])""");
        run("db.pets.createIndex({name: 1}, {unique: true})");
        run("db.pets.createIndex({age: -1, weight: 1})");
        run("db.createView('adults', 'pets', [{$match: {age: {$gte: 3}}}])");
    }

    @AfterAll
    static void cleanUp() {
        if (session != null && session.isOpen()) {
            run("db.dropDatabase()");
            session.close();
        }
    }

    private static DbConfig config(String url) {
        DbConfig config = new DbConfig();
        config.dialectId = MongoDialect.ID;
        config.jdbcUrlOverride = url;
        config.database = DATABASE;
        config.noAuth = true;
        return config;
    }

    private static SqlResult run(String statement) {
        SqlResult result = session.execute(statement);
        assertTrue(result.isSuccessful(), statement + " → " + result.text);
        return result;
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    @Test
    void registeredAndReportsTheServer() throws Exception {
        assertEquals(MongoDialect.class, Dialects.byId("mongodb").getClass());
        assertTrue(session.serverVersion().startsWith("MongoDB "), session.serverVersion());
        ConnectionTestReport report = DIALECT.testConnection(config("mongodb://localhost:27017/"), null);
        assertEquals("MongoDB", report.productName());
        assertTrue(report.productVersion().contains("standalone"), report.productVersion());
        assertTrue(report.driver().startsWith("MongoDB Java Driver (ver. 5."), report.driver());
        assertTrue(DIALECT.probeDatabases(config("mongodb://localhost:27017/"), null).contains(DATABASE));
    }

    @Test
    void findShowsDocumentsAsEditableRows() {
        SqlResult result = run("db.pets.find({age: {$gte: 1}}).sort({_id: 1})");
        assertEquals(List.of("_id", "name", "age", "weight", "born", "tags", "owner"), result.columns);
        assertEquals(List.of("int", "string", "int", "double", "date", "array", "object"), result.columnTypes);
        assertEquals(3, result.rows.size());
        Object[] rex = result.rows.getFirst();
        assertEquals(1, rex[0]);
        assertEquals("Rex", rex[1]);
        assertEquals("ISODate(\"2021-04-01T00:00:00Z\")", rex[4].toString());
        assertEquals("[\"dog\"]", rex[5].toString());
        assertNull(rex[6], "missing field");
        assertEquals(1L, result.rows.get(2)[2], "long stays long");
        // The source collection makes the rows editable.
        assertEquals(DATABASE, result.sourceSchema);
        assertEquals("pets", result.sourceTable);
        assertEquals(result.columns, result.sourceColumns);

        assertEquals(List.of("Tom"), run("db.getCollection('pets').find({}, {name: 1, _id: 0}).skip(1).limit(1)")
                .rows.stream().map(r -> r[0]).toList());
        assertNull(run("db.pets.aggregate([{$group: {_id: null, n: {$sum: 1}}}])").sourceTable, "aggregations are read-only");
        assertNull(run("db.pets.find({}, {upper: {$toUpper: '$name'}})").sourceTable, "computed projections are read-only");
        assertEquals(1, run("db.pets.findOne({name: /^r/i})").rows.size());
    }

    @Test
    void countsDistinctAndCommands() {
        assertEquals(2L, run("db.pets.countDocuments({age: {$gt: 2}})").rows.getFirst()[0]);
        assertEquals(2L, run("db.pets.find({age: {$gt: 2}}).count()").rows.getFirst()[0]);
        assertEquals(List.of("Kit", "Rex", "Tom"), run("db.pets.distinct('name')").rows.stream().map(r -> r[0]).toList());
        assertEquals(1.0, run("db.runCommand({ping: 1})").rows.getFirst()[0]);
        assertTrue(run("show collections").rows.stream().anyMatch(r -> r[0].equals("pets")));
        assertTrue(run("show dbs").rows.stream().anyMatch(r -> r[0].equals(DATABASE)));
        assertTrue(run("db.pets.find({name: 'Rex'}).explain()").columns.contains("queryPlanner"));
        SqlResult indexes = run("db.pets.getIndexes()");
        assertEquals(3, indexes.rows.size());
        assertFalse(session.execute("db.pets.find({name: 'x')").isSuccessful(), "syntax error");
        assertFalse(session.execute("SELECT * FROM pets").isSuccessful());
    }

    @Test
    void writesReportWhatChanged() {
        assertTrue(run("db.scratch.insertOne({a: 1})").text.startsWith("Inserted 1 document (_id: ObjectId(\""));
        assertEquals("Matched 1 document, modified 1", run("db.scratch.updateOne({a: 1}, {$set: {a: 2}})").text);
        assertEquals("Matched 0 documents, modified 0, upserted _id BsonInt32{value=9}",
                run("db.scratch.updateOne({_id: 9}, {$set: {a: 3}}, {upsert: true})").text);
        assertEquals("Deleted 2 documents", run("db.scratch.deleteMany({})").text);
        assertEquals(1, run("db.scratch.findOneAndUpdate({_id: 1}, {$set: {b: 1}}, {upsert: true, returnDocument: 'after'})")
                .rows.size());
        run("db.scratch.drop()");
    }

    @Test
    void useSwitchesTheDatabase() {
        try {
            assertEquals("switched to db admin", run("use admin").text);
            assertTrue(run("db.getName()").rows.getFirst()[0].equals("admin"));
        } finally {
            run("use " + DATABASE);
        }
    }

    @Test
    void catalogHasCollectionsFieldsKeysAndViews() throws Exception {
        SchemaCatalog catalog = session.loadCatalog();
        SchemaCatalog.Schema schema = catalog.schemas().stream().filter(s -> s.name().equals(DATABASE)).findFirst().orElseThrow();
        TableMeta pets = schema.tables().stream().filter(t -> t.name.equals("pets")).findFirst().orElseThrow();
        assertEquals(List.of("_id", "name", "age", "weight", "born", "tags", "owner"), pets.columns.stream().map(c -> c.name).toList());
        assertEquals("int|long", pets.columns.get(2).typeName);
        assertTrue(pets.columns.get(3).nullable, "weight isn't in every document");
        assertFalse(pets.columns.get(1).nullable);
        assertEquals(List.of("_id"), pets.primaryKeyColumns());
        assertEquals(List.of("_id"), RowUpdates.rowKey(pets));
        assertTrue(pets.keys.contains(new TableMeta.Key("name_1", List.of("name"), false)));
        assertTrue(pets.indexes.contains(new TableMeta.Index("age_-1_weight_1", List.of("age desc", "weight"), false)),
                pets.indexes.toString());
        TableMeta adults = schema.tables().stream().filter(t -> t.name.equals("adults")).findFirst().orElseThrow();
        assertEquals(TableMeta.Kind.VIEW, adults.kind);
        assertEquals("view on pets", adults.remarks);
        assertFalse(catalog.schemaNames().contains("admin"), "system databases are hidden");

        String text = DIALECT.describeSchema(new SchemaCatalog(List.of(schema)));
        assertTrue(text.contains("// pets: {_id: int, name: string, age: int|long, weight?: double"), text);
        assertTrue(text.contains("db.getSiblingDB(\"" + DATABASE + "\").getCollection(\"pets\").createIndex({\"name\": 1}, "
                + "{\"name\": \"name_1\", \"unique\": true})"), text);
        assertTrue(text.contains(".createView(\"adults\", \"pets\", [])"), text);
    }

    @Test
    void editedRowsKeepTheirTypes() {
        SqlResult loaded = run("db.pets.find({_id: 3})");
        Object[] kit = loaded.rows.getFirst();
        String update = DIALECT.updateStatement(loaded.sourceSchema, loaded.sourceTable, new RowUpdates.Edit(
                Map.of("age", "2", "weight", "3.5"), Map.of("_id", kit[0]), Map.of("age", kit[2], "weight", kit[3])));
        assertTrue(update.startsWith("db.getSiblingDB(\"" + DATABASE + "\").getCollection(\"pets\").updateOne("
                + "{\"_id\": 3}, {\"$set\": {"), update);
        assertTrue(update.contains("\"age\": {\"$numberLong\": \"2\"}"), "the long stays a long: " + update);
        assertTrue(update.contains("\"weight\": 3.5"), update);
        SqlResult applied = session.applyRowUpdates(List.of(update));
        assertTrue(applied.isSuccessful(), applied.text);
        Object[] after = run("db.pets.find({_id: 3})").rows.getFirst();
        assertEquals(2L, after[2]);
        assertEquals(3.5, after[3]);

        // Nothing is written unless every change finds its document.
        String missing = DIALECT.deleteStatement(DATABASE, "pets", Map.of("_id", 99));
        String fine = DIALECT.updateStatement(DATABASE, "pets", new RowUpdates.Edit(Map.of("name", "Kat"), Map.of("_id", 3),
                Map.of("name", "Kit")));
        SqlResult failed = session.applyRowUpdates(List.of(fine, missing));
        assertFalse(failed.isSuccessful());
        assertTrue(failed.text.startsWith("No document matched"), failed.text);
        assertEquals("Kit", run("db.pets.find({_id: 3})").rows.getFirst()[1]);
    }

    @Test
    void objectIdKeysRoundTrip() {
        ObjectId id = new ObjectId();
        run("db.ids.insertOne({_id: ObjectId('" + id.toHexString() + "'), at: new Date(0)})");
        SqlResult loaded = run("db.ids.find()");
        Object cell = loaded.rows.getFirst()[0];
        assertInstanceOf(MongoValues.Cell.class, cell);
        String update = DIALECT.updateStatement(DATABASE, "ids", new RowUpdates.Edit(
                Map.of("at", "ISODate(\"2020-01-01T00:00:00Z\")"), Map.of("_id", cell), Map.of("at", loaded.rows.getFirst()[1])));
        assertTrue(session.applyRowUpdates(List.of(update)).isSuccessful(), update);
        Object at = MongoValues.value(run("db.ids.find()").rows.getFirst()[1]);
        assertEquals(new Date(1577836800000L), at);
        assertTrue(session.applyRowUpdates(List.of(DIALECT.deleteStatement(DATABASE, "ids", Map.of("_id", cell)))).isSuccessful());
        run("db.ids.drop()");
    }

    @Test
    void readOnlyConnectionsRefuseWrites() {
        DbConfig config = config(env("INTELLADB_MONGO_URL", "mongodb://localhost:27017/" + DATABASE));
        config.readOnly = true;
        try (DbSession readOnly = new DbSession(config, null)) {
            assertTrue(readOnly.execute("db.pets.find()").isSuccessful());
            for (String write : List.of("db.pets.insertOne({})", "db.pets.deleteMany({})", "db.pets.drop()",
                    "db.runCommand({drop: 'pets'})", "db.pets.aggregate([{$out: 'copy'}])", "db.dropDatabase()")) {
                SqlResult result = readOnly.execute(write);
                assertFalse(result.isSuccessful(), write);
                assertTrue(result.text.contains("read-only"), result.text);
            }
        }
        assertEquals(3L, run("db.pets.countDocuments()").rows.getFirst()[0]);
    }

    @Test
    void standaloneServersExplainThatTransactionsNeedAReplicaSet() throws Exception {
        try (DbSession tx = new DbSession(config(env("INTELLADB_MONGO_URL", "mongodb://localhost:27017/" + DATABASE)), null)) {
            tx.setAutoCommit(false);
            SqlResult result = tx.execute("db.pets.insertOne({_id: 50})");
            assertFalse(result.isSuccessful());
            assertTrue(result.text.contains("transactions need a replica set"), result.text);
        }
    }

    @Test
    void transactionsOnAReplicaSet() throws Exception {
        DbConfig config = config(env("INTELLADB_MONGO_RS_URL", "mongodb://localhost:27018/" + DATABASE + "?directConnection=true"));
        try (DbSession tx = new DbSession(config, null)) {
            try {
                tx.ensureOpen();
            } catch (Exception e) {
                assumeTrue(false, "no replica set: " + e.getMessage());
            }
            assertTrue(tx.execute("db.dropDatabase()").isSuccessful());
            assertTrue(tx.execute("db.createCollection('tx')").isSuccessful());
            tx.setAutoCommit(false);
            assertEquals("Inserted 1 document (_id: 1) (pending: commit the transaction to keep it)",
                    tx.execute("db.tx.insertOne({_id: 1})").text);
            assertEquals("Rollback completed", tx.rollback().text);
            assertEquals(0L, tx.execute("db.tx.countDocuments()").rows.getFirst()[0]);
            assertTrue(tx.execute("db.tx.insertOne({_id: 2})").isSuccessful());
            assertEquals("Commit completed", tx.commit().text);
            tx.setAutoCommit(true);
            assertEquals(1L, tx.execute("db.tx.countDocuments()").rows.getFirst()[0]);

            // Grid edits on a replica set run in a transaction of their own.
            String ok = DIALECT.updateStatement(DATABASE, "tx", new RowUpdates.Edit(Map.of("v", "1"), Map.of("_id", 2), Map.of()));
            assertTrue(tx.applyRowUpdates(List.of(ok)).isSuccessful());
            assertTrue(tx.execute("db.dropDatabase()").isSuccessful());
        }
    }

    @Test
    void theConnectionsDatabaseIsListedBeforeItExists() throws Exception {
        DbConfig config = config("mongodb://localhost:27017/");
        config.database = "intelladb_not_created_yet";
        assertTrue(DIALECT.probeSchemaNames(config, null).contains("intelladb_not_created_yet"));
        try (DbSession fresh = new DbSession(config, null)) {
            SchemaCatalog catalog = fresh.loadCatalog();
            assertTrue(catalog.schemaNames().contains("intelladb_not_created_yet"), catalog.schemaNames().toString());
            assertTrue(catalog.schemas().stream().filter(s -> s.name().equals("intelladb_not_created_yet"))
                    .findFirst().orElseThrow().tables().isEmpty());
        }
    }
}
