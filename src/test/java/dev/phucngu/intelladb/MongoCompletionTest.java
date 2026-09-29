package dev.phucngu.intelladb;

import dev.phucngu.intelladb.mongo.MongoCompletion;
import dev.phucngu.intelladb.schema.ColumnMeta;
import dev.phucngu.intelladb.schema.SchemaCatalog;
import dev.phucngu.intelladb.schema.TableMeta;
import dev.phucngu.intelladb.sql.completion.Suggestion;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MongoCompletionTest {

    private static final TableMeta PETS = new TableMeta("pets", TableMeta.Kind.TABLE, List.of(
            new ColumnMeta("_id", "objectId", false, "", 1, true, ""),
            new ColumnMeta("name", "string", false, "", 2, false, ""),
            new ColumnMeta("age", "int", true, "", 3, false, ""),
            new ColumnMeta("first name", "string", true, "", 4, false, "")), "",
            List.of(new TableMeta.Key("_id_", List.of("_id"), true)), List.of(),
            List.of(new TableMeta.Index("name_1", List.of("name"), false)), List.of());
    private static final TableMeta ITEMS = new TableMeta("order-items", TableMeta.Kind.TABLE, List.of(
            new ColumnMeta("_id", "objectId", false, "", 1, true, ""),
            new ColumnMeta("sku", "string", false, "", 2, false, "")), "");
    private static final SchemaCatalog CATALOG = new SchemaCatalog(List.of(
            new SchemaCatalog.Schema("shop", List.of(ITEMS, PETS)),
            new SchemaCatalog.Schema("hr", List.of(new TableMeta("people", TableMeta.Kind.TABLE,
                    List.of(new ColumnMeta("_id", "int", false, "", 1, true, "")), "")))));

    /** Suggestions at the '|' in {@code text}. */
    private static MongoCompletion.Result at(String text) {
        int caret = text.indexOf('|');
        return MongoCompletion.suggest(text.replace("|", ""), caret, CATALOG, "shop");
    }

    private static List<String> lookups(String text) {
        return at(text).suggestions().stream().map(Suggestion::lookup).toList();
    }

    private static String insert(String text, String lookup) {
        return at(text).suggestions().stream().filter(s -> s.lookup().equals(lookup)).findFirst().orElseThrow().insertText();
    }

    @Test
    void statementStartUseAndShow() {
        assertEquals(List.of("db", "use", "show"), lookups("|"));
        assertEquals("d", at("d|").prefix());
        assertEquals(List.of("shop", "hr"), lookups("use |"));
        assertTrue(lookups("show c|").contains("collections"));
        assertEquals(List.of("db", "use", "show"), lookups("db.pets.find()\n|"), "a finished line starts a new statement");
    }

    @Test
    void afterDbCollectionsThenDatabaseMethods() {
        List<String> names = lookups("db.|");
        assertEquals(List.of("order-items", "pets"), names.subList(0, 2));
        assertTrue(names.contains("getCollection"));
        assertTrue(names.contains("runCommand"));
        assertEquals("getCollection(\"order-items\")", insert("db.|", "order-items"), "not an identifier");
        assertEquals("pe", at("db.pe|").prefix());
        assertEquals(List.of("people"), lookups("db.getSiblingDB('hr').|").subList(0, 1));
        assertEquals(List.of("shop", "hr"), lookups("db.getSiblingDB('|"));
        assertEquals(List.of("order-items", "pets"), lookups("db.getCollection(\"|"));
    }

    @Test
    void collectionAndCursorMethods() {
        List<String> methods = lookups("db.pets.|");
        assertEquals("find", methods.getFirst());
        assertTrue(methods.containsAll(List.of("aggregate", "updateOne", "deleteMany", "createIndex")));
        assertTrue(lookups("db.getCollection('order-items').|").contains("insertOne"));
        List<String> cursor = lookups("db.pets.find({}).|");
        assertEquals("sort", cursor.getFirst());
        assertTrue(cursor.contains("limit"));
        assertTrue(lookups("db.pets.find({})\n  .sort({name: 1})\n  .|").contains("skip"), "chained on a new line");
        assertEquals(List.of("toArray", "explain", "pretty"), lookups("db.pets.aggregate([]).|"));
        assertTrue(lookups("db.pets.countDocuments().|").isEmpty());
    }

    @Test
    void filterFieldsAndOperators() {
        List<String> keys = lookups("db.pets.find({|");
        assertEquals(List.of("_id", "name", "age", "first name"), keys.subList(0, 4));
        assertTrue(keys.contains("$or"));
        assertEquals("\"first name\"", insert("db.pets.find({|", "first name"));
        assertTrue(lookups("db.pets.find({name: 'x', |").contains("age"), "after a comma");
        assertTrue(lookups("db.pets.find({age: {|").contains("$gt"));
        assertFalse(lookups("db.pets.find({age: {|").contains("name"));
        assertTrue(lookups("db.pets.find({$or: [{|").contains("name"), "inside $or");
        assertTrue(lookups("db.pets.find({age: {$gt: 1}, $and: [{name: 'a'}, {|").contains("age"));
        assertEquals(List.of("first name"), lookups("db.pets.find({\"fi|").stream().filter(s -> s.startsWith("fi")).toList(),
                "quoted key");
        assertTrue(lookups("db.pets.find({name: |").contains("ObjectId"), "value position");
        assertTrue(lookups("db.pets.find({name: 'x'|").isEmpty(), "right after a value");
        assertTrue(lookups("db.pets.find({}, {|").contains("name"), "projection");
        assertTrue(lookups("db.pets.find().sort({|").contains("age"));
    }

    @Test
    void updatesAndDocuments() {
        assertEquals("$set", lookups("db.pets.updateOne({_id: 1}, {|").getFirst());
        assertTrue(lookups("db.pets.updateOne({_id: 1}, {$set: {|").contains("name"));
        assertTrue(lookups("db.pets.updateMany({}, {$inc: {age: 1}, $set: {|").contains("name"));
        assertTrue(lookups("db.pets.insertOne({|").contains("age"));
        assertTrue(lookups("db.pets.insertMany([{name: 'a'}, {|").contains("age"));
        assertTrue(lookups("db.pets.updateOne({}, {}, {|").contains("upsert"));
        assertTrue(lookups("db.pets.createIndex({name: 1}, {|").contains("unique"));
        assertEquals(List.of("_id_", "name_1"), lookups("db.pets.dropIndex('|"));
        assertEquals(List.of("_id", "name", "age", "first name"), lookups("db.pets.distinct('|"));
    }

    @Test
    void aggregationStagesAccumulatorsAndPaths() {
        assertEquals("$match", lookups("db.pets.aggregate([{|").getFirst());
        assertTrue(lookups("db.pets.aggregate([{$match: {age: 1}}, {|").contains("$group"));
        assertTrue(lookups("db.pets.aggregate([{$match: {|").contains("name"));
        List<String> group = lookups("db.pets.aggregate([{$group: {|");
        assertEquals("_id", group.getFirst());
        assertTrue(lookups("db.pets.aggregate([{$group: {_id: '$name', total: {|").contains("$sum"));
        assertTrue(lookups("db.pets.aggregate([{$group: {_id: \"$|").contains("$name"), "field path");
        assertTrue(lookups("db.pets.aggregate([{$group: {_id: null, n: {$sum: \"$a|").contains("$age"));
        assertTrue(lookups("db.pets.aggregate([{$lookup: {|").contains("foreignField"));
        assertTrue(lookups("db.pets.aggregate([{$project: {upper: {|").contains("$toUpper"));
        assertTrue(lookups("db.pets.aggregate([|").isEmpty(), "a stage starts with {");
    }

    @Test
    void offlineStillKnowsTheLanguage() {
        MongoCompletion.Result result = MongoCompletion.suggest("db.pets.find({age: {", 20, null, null);
        assertTrue(result.suggestions().stream().anyMatch(s -> s.lookup().equals("$gt")));
        assertTrue(MongoCompletion.suggest("db.", 3, null, null).suggestions().stream()
                .anyMatch(s -> s.lookup().equals("getCollection")));
        assertTrue(MongoCompletion.suggest("SELECT ", 7, CATALOG, "shop").suggestions().isEmpty());
    }
}
