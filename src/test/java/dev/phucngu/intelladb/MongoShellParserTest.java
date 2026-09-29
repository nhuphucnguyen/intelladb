package dev.phucngu.intelladb;

import dev.phucngu.intelladb.mongo.MongoShellParser;
import dev.phucngu.intelladb.mongo.MongoShellParser.CollectionCall;
import dev.phucngu.intelladb.mongo.MongoShellParser.DatabaseCall;
import dev.phucngu.intelladb.mongo.MongoShellParser.Show;
import dev.phucngu.intelladb.mongo.MongoShellParser.Use;
import dev.phucngu.intelladb.mongo.MongoValues;
import org.bson.BsonRegularExpression;
import org.bson.BsonTimestamp;
import org.bson.Document;
import org.bson.types.Binary;
import org.bson.types.Decimal128;
import org.bson.types.MinKey;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MongoShellParserTest {

    @Test
    void findWithChainedCursorCalls() {
        CollectionCall find = assertInstanceOf(CollectionCall.class, MongoShellParser.parseCommand("""
                db.pets.find({age: {$gt: 2}, 'name': "Rex", tags: ['a', "b",],}, {name: 1, _id: 0})
                  .sort({name: -1}) // newest first
                  .limit(10);
                """));
        assertNull(find.database());
        assertEquals("pets", find.collection());
        assertEquals("find", find.call().name());
        assertEquals(new Document("age", new Document("$gt", 2)).append("name", "Rex").append("tags", List.of("a", "b")),
                find.call().arg(0));
        assertEquals(new Document("name", 1).append("_id", 0), find.call().arg(1));
        assertEquals(List.of("sort", "limit"), find.chain().stream().map(MongoShellParser.Call::name).toList());
        assertEquals(new Document("name", -1), find.chain().get(0).arg(0));
        assertEquals(10, find.chain().get(1).arg(0));
    }

    @Test
    void collectionForms() {
        assertEquals("system.profile", ((CollectionCall) MongoShellParser.parseCommand("db.system.profile.find()")).collection());
        CollectionCall named = (CollectionCall) MongoShellParser.parseCommand("db.getCollection('order-items').countDocuments()");
        assertEquals("order-items", named.collection());
        assertEquals("countDocuments", named.call().name());
        assertEquals("a b", ((CollectionCall) MongoShellParser.parseCommand("db['a b'].drop()")).collection());
        CollectionCall sibling = (CollectionCall) MongoShellParser.parseCommand("db.getSiblingDB(\"shop\").pets.findOne()");
        assertEquals("shop", sibling.database());
        assertEquals("pets", sibling.collection());
        // A collection that happens to share a database method's name.
        assertEquals("stats", ((CollectionCall) MongoShellParser.parseCommand("db.stats.find()")).collection());
    }

    @Test
    void databaseCallsUseAndShow() {
        DatabaseCall command = assertInstanceOf(DatabaseCall.class,
                MongoShellParser.parseCommand("db.runCommand({ping: 1})"));
        assertEquals("runCommand", command.call().name());
        assertEquals(new Document("ping", 1), command.call().arg(0));
        assertEquals("admin", ((DatabaseCall) MongoShellParser.parseCommand("db.getSiblingDB('admin').stats()")).database());
        assertEquals(new Use("shop"), MongoShellParser.parseCommand("use shop"));
        assertEquals(new Show("collections"), MongoShellParser.parseCommand("show collections;"));
    }

    @Test
    void shellValues() {
        String hex = "65f0c0ffee00000000000001";
        Document d = (Document) MongoShellParser.parseValue("""
                {id: ObjectId("%s"), at: ISODate("2024-05-01T10:00:00Z"), day: new Date("2024-05-01"),
                 n: NumberLong(5), big: 3000000000, i: 7, f: 1.5, e: 1e3, neg: -2, hex: 0x1F,
                 dec: NumberDecimal("9.99"), u: UUID("123e4567-e89b-12d3-a456-426614174000"),
                 re: /^r.x$/i, ts: Timestamp(10, 2), min: MinKey(), nil: null, t: true,
                 bin: BinData(0, "AQID"), s: 'it\\'s', inf: -Infinity}
                """.formatted(hex));
        assertEquals(new ObjectId(hex), d.get("id"));
        assertEquals(Date.from(Instant.parse("2024-05-01T10:00:00Z")), d.get("at"));
        assertEquals(Date.from(Instant.parse("2024-05-01T00:00:00Z")), d.get("day"));
        assertEquals(5L, d.get("n"));
        assertEquals(3000000000L, d.get("big"));
        assertEquals(7, d.get("i"));
        assertEquals(1.5, d.get("f"));
        assertEquals(1000.0, d.get("e"));
        assertEquals(-2, d.get("neg"));
        assertEquals(31, d.get("hex"));
        assertEquals(new Decimal128(new BigDecimal("9.99")), d.get("dec"));
        assertEquals(UUID.fromString("123e4567-e89b-12d3-a456-426614174000"), d.get("u"));
        assertEquals(new BsonRegularExpression("^r.x$", "i"), d.get("re"));
        assertEquals(new BsonTimestamp(10, 2), d.get("ts"));
        assertInstanceOf(MinKey.class, d.get("min"));
        assertNull(d.get("nil"));
        assertEquals(true, d.get("t"));
        assertEquals(new Binary((byte) 0, new byte[]{1, 2, 3}), d.get("bin"));
        assertEquals("it's", d.get("s"));
        assertEquals(Double.NEGATIVE_INFINITY, d.get("inf"));
    }

    @Test
    void extendedJsonWrappersAreUnwrapped() {
        Document d = (Document) MongoShellParser.parseValue("""
                {"_id": {"$oid": "65f0c0ffee00000000000001"}, "at": {"$date": "2024-05-01T10:00:00Z"},
                 "n": {"$numberLong": "5"}, "nested": {"$gt": 1}}
                """);
        assertEquals(new ObjectId("65f0c0ffee00000000000001"), d.get("_id"));
        assertInstanceOf(Date.class, d.get("at"));
        assertEquals(5L, d.get("n"));
        assertEquals(new Document("$gt", 1), d.get("nested"), "operators are not wrappers");
    }

    @Test
    void jsonRoundTripKeepsTypes() {
        Document original = new Document("_id", new ObjectId("65f0c0ffee00000000000001"))
                .append("at", Date.from(Instant.parse("2024-05-01T10:00:00Z")))
                .append("n", 5L).append("i", 5).append("f", 5.0).append("dec", new Decimal128(new BigDecimal("1.10")))
                .append("tags", List.of("a", new Document("deep", true)))
                .append("text", "line\n\"quoted\"");
        Object back = MongoShellParser.parseValue(MongoValues.json(original));
        assertEquals(original, back);
        assertEquals(original, MongoShellParser.parseValue(MongoValues.shell(original)));
    }

    @Test
    void gridCellsAndEdits() {
        ObjectId id = new ObjectId("65f0c0ffee00000000000001");
        Object cell = MongoValues.cell(id);
        assertEquals("ObjectId(\"65f0c0ffee00000000000001\")", cell.toString());
        assertEquals(id, MongoValues.value(cell));
        assertEquals(42, MongoValues.cell(42));

        // A string field stays a string, whatever is typed.
        assertEquals("42", MongoValues.edited("42", "old"));
        // Numbers keep their width.
        assertEquals(43L, MongoValues.edited("43", 42L));
        assertEquals(2.0, MongoValues.edited("2", 1.5));
        assertEquals(7, MongoValues.edited("7", 6));
        assertEquals(new Decimal128(new BigDecimal("2.50")), MongoValues.edited("2.50", new BigDecimal("1.1")));
        // Shell values keep their type; anything else becomes text.
        assertEquals(id, MongoValues.edited("ObjectId(\"65f0c0ffee00000000000001\")", MongoValues.value(cell)));
        assertEquals(new Document("a", 1), MongoValues.edited("{\"a\": 1}", new Document()));
        assertEquals("not a value", MongoValues.edited("not a value", 3));
        assertNull(MongoValues.edited(null, "x"));
    }

    @Test
    void syntaxErrorsSayWhere() {
        MongoShellParser.SyntaxError error = assertThrows(MongoShellParser.SyntaxError.class,
                () -> MongoShellParser.parseCommand("db.pets.find({name: 'x')"));
        assertTrue(error.getMessage().contains("line 1"), error.getMessage());
        assertThrows(MongoShellParser.SyntaxError.class, () -> MongoShellParser.parseCommand("SELECT * FROM pets"));
        assertThrows(MongoShellParser.SyntaxError.class, () -> MongoShellParser.parseCommand("db.nosuchmethod()"));
        assertThrows(MongoShellParser.SyntaxError.class, () -> MongoShellParser.parseValue("{a: Rex}"));
    }
}
