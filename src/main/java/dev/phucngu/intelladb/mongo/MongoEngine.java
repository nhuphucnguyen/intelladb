package dev.phucngu.intelladb.mongo;

import com.mongodb.MongoException;
import com.mongodb.client.AggregateIterable;
import com.mongodb.client.ClientSession;
import com.mongodb.client.DistinctIterable;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Collation;
import com.mongodb.client.model.CollationStrength;
import com.mongodb.client.model.CountOptions;
import com.mongodb.client.model.DeleteOptions;
import com.mongodb.client.model.FindOneAndDeleteOptions;
import com.mongodb.client.model.FindOneAndReplaceOptions;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.IndexModel;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.InsertManyOptions;
import com.mongodb.client.model.RenameCollectionOptions;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.InsertManyResult;
import com.mongodb.client.result.UpdateResult;
import dev.phucngu.intelladb.connection.DbConfig;
import dev.phucngu.intelladb.connection.SessionEngine;
import dev.phucngu.intelladb.connection.SqlResult;
import dev.phucngu.intelladb.mongo.MongoShellParser.Call;
import dev.phucngu.intelladb.mongo.MongoShellParser.CollectionCall;
import dev.phucngu.intelladb.mongo.MongoShellParser.Command;
import dev.phucngu.intelladb.mongo.MongoShellParser.DatabaseCall;
import dev.phucngu.intelladb.mongo.MongoShellParser.Show;
import dev.phucngu.intelladb.mongo.MongoShellParser.Use;
import dev.phucngu.intelladb.schema.SchemaCatalog;
import dev.phucngu.intelladb.util.SqlSplitter;
import org.bson.BsonValue;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Runs the console's MongoDB shell commands on the Java driver and turns what comes back
 * into {@link SqlResult}s: documents as rows (one column per top-level field), counts and
 * writes as messages. A {@code find} on a collection names it as the rows' source, so the
 * grid can write edits back ({@code updateOne} / {@code deleteOne} by {@code _id}).
 * <p>
 * Tx: Manual runs data commands in a multi-document transaction (replica sets and sharded
 * clusters only; a standalone server says so). Read-only connections refuse the commands
 * that write. Cancel kills the running operation on the server, found by the comment every
 * read and write is tagged with.
 */
public final class MongoEngine implements SessionEngine {

    /** Commands (first key of a runCommand document) that change data or the server. */
    private static final Set<String> WRITE_COMMANDS = Set.of(
            "insert", "update", "delete", "findAndModify", "create", "drop", "dropDatabase", "createIndexes",
            "dropIndexes", "renameCollection", "collMod", "convertToCapped", "cloneCollectionAsCapped", "compact",
            "reIndex", "mapReduce", "applyOps", "createUser", "updateUser", "dropUser", "dropAllUsersFromDatabase",
            "grantRolesToUser", "revokeRolesFromUser", "createRole", "updateRole", "dropRole", "killOp",
            "killCursors", "shutdown", "setParameter", "fsync", "bulkWrite");

    private final DbConfig config;
    private final String password;
    private MongoClient client;
    private ClientSession session;
    /** The database {@code db} means: {@code use x} switches it. */
    private String database;
    private boolean manual;
    private @Nullable Boolean supportsTransactions;
    /** Comment tag of the operation running now, so {@link #cancel()} can find and kill it. */
    private volatile @Nullable String running;

    // Per statement (execute runs under the session's monitor, one at a time).
    private String sql = "";
    private long start;

    public MongoEngine(@NotNull DbConfig config, @Nullable String password) {
        this.config = config;
        this.password = password;
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public boolean isOpen() {
        return client != null;
    }

    @Override
    public @NotNull String open() throws SQLException {
        client = MongoConnector.open(config, password, 10);
        session = client.startSession();
        database = config.database.isBlank() ? "test" : config.database.trim();
        manual = false;
        supportsTransactions = null;
        for (SqlSplitter.Statement statement : SqlSplitter.ranges(config.startupScript, SqlSplitter.Options.MONGO)) {
            SqlResult result = execute(statement.text());
            if (!result.isSuccessful()) {
                close();
                throw new SQLException("Startup script failed at \"" + statement.text() + "\": " + result.text);
            }
        }
        try {
            Document build = client.getDatabase("admin").runCommand(new Document("buildInfo", 1));
            return "MongoDB " + build.get("version");
        } catch (MongoException e) {
            return "MongoDB";
        }
    }

    @Override
    public void ping() {
        if (client == null) {
            return;
        }
        try {
            client.getDatabase("admin").runCommand(new Document("ping", 1));
        } catch (MongoException ignored) {
            // the next statement reports it
        }
    }

    @Override
    public void close() {
        if (session != null) {
            try {
                session.close();
            } catch (RuntimeException ignored) {
            }
            session = null;
        }
        if (client != null) {
            client.close();
            client = null;
        }
    }

    @Override
    public @NotNull SchemaCatalog loadCatalog() {
        return MongoCatalogLoader.load(client, config.schemas, config.showSystemSchemas, database);
    }

    /** The database {@code db} currently stands for. */
    public @NotNull String database() {
        return database;
    }

    // ------------------------------------------------------------------ statements

    @Override
    public @NotNull SqlResult execute(@NotNull String statement) {
        sql = statement;
        start = System.currentTimeMillis();
        try {
            Command command = MongoShellParser.parseCommand(statement);
            return switch (command) {
                case Use use -> use(use.database());
                case Show show -> show(show.what());
                case DatabaseCall call -> databaseCall(db(call.database()), call.call());
                case CollectionCall call -> collectionCall(call);
            };
        } catch (MongoShellParser.SyntaxError e) {
            return error(e.getMessage());
        } catch (MongoException e) {
            return error(transactionHint(e, MongoConnector.message(e)));
        } catch (IllegalArgumentException | IllegalStateException | ClassCastException | IndexOutOfBoundsException e) {
            return error(e.getMessage() == null ? e.toString() : e.getMessage());
        } finally {
            running = null;
        }
    }

    private @NotNull SqlResult use(@NotNull String name) {
        database = name;
        return message("switched to db " + name);
    }

    private @NotNull SqlResult show(@NotNull String what) {
        return switch (what) {
            case "dbs", "databases" -> {
                List<Document> rows = new ArrayList<>();
                client.listDatabases().forEach(d -> rows.add(new Document("name", d.get("name"))
                        .append("sizeOnDisk", d.get("sizeOnDisk")).append("empty", d.get("empty"))));
                yield documents(rows, false, null, null, false);
            }
            case "collections", "tables" -> {
                List<Document> rows = new ArrayList<>();
                db(null).listCollections().forEach(c -> rows.add(new Document("name", c.get("name")).append("type", c.get("type"))));
                rows.sort((a, b) -> a.getString("name").compareTo(b.getString("name")));
                yield documents(rows, false, null, null, false);
            }
            case "users" -> documents(db(null).runCommand(new Document("usersInfo", 1))
                    .getList("users", Document.class, List.of()), false, null, null, false);
            case "roles" -> documents(db(null).runCommand(new Document("rolesInfo", 1))
                    .getList("roles", Document.class, List.of()), false, null, null, false);
            default -> error("show " + what + " is not supported; try show dbs, show collections, show users or show roles");
        };
    }

    private @NotNull MongoDatabase db(@Nullable String name) {
        return client.getDatabase(name == null ? database : name);
    }

    // ------------------------------------------------------------------ db.method()

    private @NotNull SqlResult databaseCall(@NotNull MongoDatabase db, @NotNull Call call) {
        switch (call.name()) {
            case "runCommand", "adminCommand" -> {
                Document command = command(call.arg(0));
                guardCommand(command);
                MongoDatabase target = call.name().equals("adminCommand") ? client.getDatabase("admin") : db;
                ClientSession s = transactionFor(command);
                return commandResult(s == null ? target.runCommand(command) : target.runCommand(s, command));
            }
            case "aggregate" -> {
                List<Document> pipeline = pipeline(call.arg(0));
                guardPipeline(pipeline);
                ClientSession s = transaction();
                AggregateIterable<Document> result = s == null ? db.aggregate(pipeline) : db.aggregate(s, pipeline);
                return cursorRows(result.comment(tag()).iterator(), null, null, false);
            }
            case "createCollection" -> {
                requireWritable("createCollection");
                Document command = new Document("create", string(call.arg(0), "collection name"));
                command.putAll(document(call.arg(1), "options"));
                db.runCommand(command);
                return message("Created collection " + command.get("create"));
            }
            case "createView" -> {
                requireWritable("createView");
                Document command = new Document("create", string(call.arg(0), "view name"))
                        .append("viewOn", string(call.arg(1), "source collection"))
                        .append("pipeline", pipeline(call.arg(2)));
                command.putAll(document(call.arg(3), "options"));
                db.runCommand(command);
                return message("Created view " + command.get("create"));
            }
            case "dropDatabase" -> {
                requireWritable("dropDatabase");
                db.drop();
                return message("Dropped database " + db.getName());
            }
            case "getCollectionNames" -> {
                List<Document> rows = new ArrayList<>();
                db.listCollectionNames().forEach(name -> rows.add(new Document("name", name)));
                rows.sort((a, b) -> a.getString("name").compareTo(b.getString("name")));
                return documents(rows, false, null, null, false);
            }
            case "getCollectionInfos" -> {
                List<Document> rows = new ArrayList<>();
                db.listCollections().filter(document(call.arg(0), "filter")).forEach(rows::add);
                return documents(rows, false, null, null, false);
            }
            case "getName" -> {
                return documents(List.of(new Document("name", db.getName())), false, null, null, false);
            }
            case "stats" -> {
                Document command = new Document("dbStats", 1);
                if (call.arg(0) instanceof Number scale) {
                    command.append("scale", scale);
                }
                return commandResult(db.runCommand(command));
            }
            case "version" -> {
                Document build = client.getDatabase("admin").runCommand(new Document("buildInfo", 1));
                return documents(List.of(new Document("version", build.get("version"))), false, null, null, false);
            }
            case "serverStatus", "hostInfo", "listCommands" -> {
                return commandResult(client.getDatabase("admin").runCommand(new Document(call.name(), 1)));
            }
            case "currentOp" -> {
                Document command = new Document("currentOp", 1);
                command.putAll(document(call.arg(0), "filter"));
                return commandResult(client.getDatabase("admin").runCommand(command));
            }
            case "killOp" -> {
                requireWritable("killOp");
                client.getDatabase("admin").runCommand(new Document("killOp", 1).append("op", call.arg(0)));
                return message("Killed operation " + call.arg(0));
            }
            case "getUsers" -> {
                return documents(db.runCommand(new Document("usersInfo", 1))
                        .getList("users", Document.class, List.of()), false, null, null, false);
            }
            default -> throw new IllegalArgumentException("db." + call.name() + "() is not supported");
        }
    }

    // ------------------------------------------------------------------ db.collection.method()

    private @NotNull SqlResult collectionCall(@NotNull CollectionCall command) {
        MongoDatabase db = db(command.database());
        MongoCollection<Document> collection = db.getCollection(command.collection());
        Call call = command.call();
        List<Call> chain = command.chain();
        String name = call.name();
        if (!chain.isEmpty() && !name.equals("find") && !name.equals("aggregate")) {
            throw new IllegalArgumentException("." + chain.getFirst().name() + "() can only follow find() or aggregate()");
        }
        return switch (name) {
            case "find", "findOne" -> find(db, collection, call, chain, name.equals("findOne"));
            case "aggregate" -> aggregate(db, collection, call, chain);
            case "countDocuments", "count" -> {
                Document options = document(call.arg(1), "options");
                CountOptions count = new CountOptions().comment(tag());
                if (options.get("limit") instanceof Number n) {
                    count.limit(n.intValue());
                }
                if (options.get("skip") instanceof Number n) {
                    count.skip(n.intValue());
                }
                ClientSession s = transaction();
                Document filter = document(call.arg(0), "filter");
                long n = s == null ? collection.countDocuments(filter, count) : collection.countDocuments(s, filter, count);
                yield scalar("count", n);
            }
            case "estimatedDocumentCount" -> scalar("count", collection.estimatedDocumentCount());
            case "distinct" -> {
                String field = string(call.arg(0), "field name");
                ClientSession s = transaction();
                Document filter = document(call.arg(1), "filter");
                DistinctIterable<BsonValue> values = s == null
                        ? collection.distinct(field, filter, BsonValue.class)
                        : collection.distinct(s, field, filter, BsonValue.class);
                List<Document> rows = new ArrayList<>();
                boolean truncated = false;
                // Values of any type: read as BSON, then decoded like document fields.
                org.bson.codecs.Codec<Document> codec = collection.getCodecRegistry().get(Document.class);
                for (BsonValue value : values.comment(tag())) {
                    if (rows.size() >= SqlResult.MAX_ROWS) {
                        truncated = true;
                        break;
                    }
                    rows.add(codec.decode(new org.bson.BsonDocument(field, value).asBsonReader(),
                            org.bson.codecs.DecoderContext.builder().build()));
                }
                yield documents(rows, truncated, null, null, false);
            }
            case "insertOne" -> {
                requireWritable(name);
                ClientSession s = transaction();
                Document document = document(call.arg(0), "document");
                if (s == null) {
                    collection.insertOne(document);
                } else {
                    collection.insertOne(s, document);
                }
                // The driver puts the generated _id into the document.
                yield message("Inserted 1 document (_id: " + MongoValues.shell(document.get("_id")) + ")" + pending());
            }
            case "insertMany" -> {
                requireWritable(name);
                List<Document> documents = documents(call.arg(0), "documents");
                InsertManyOptions options = new InsertManyOptions();
                if (document(call.arg(1), "options").get("ordered") instanceof Boolean ordered) {
                    options.ordered(ordered);
                }
                ClientSession s = transaction();
                InsertManyResult result = s == null ? collection.insertMany(documents, options)
                        : collection.insertMany(s, documents, options);
                yield message("Inserted " + plural(result.getInsertedIds().size(), "document") + pending());
            }
            case "updateOne", "updateMany" -> {
                requireWritable(name);
                Document filter = document(call.arg(0), "filter");
                Document optionsDoc = document(call.arg(2), "options");
                UpdateOptions options = updateOptions(optionsDoc);
                ClientSession s = transaction();
                boolean one = name.equals("updateOne");
                UpdateResult result;
                if (call.arg(1) instanceof List<?>) {
                    List<Document> pipeline = pipeline(call.arg(1));
                    result = s == null
                            ? (one ? collection.updateOne(filter, pipeline, options) : collection.updateMany(filter, pipeline, options))
                            : (one ? collection.updateOne(s, filter, pipeline, options) : collection.updateMany(s, filter, pipeline, options));
                } else {
                    Document update = document(call.arg(1), "update");
                    result = s == null
                            ? (one ? collection.updateOne(filter, update, options) : collection.updateMany(filter, update, options))
                            : (one ? collection.updateOne(s, filter, update, options) : collection.updateMany(s, filter, update, options));
                }
                yield message(describe(result) + pending());
            }
            case "replaceOne" -> {
                requireWritable(name);
                Document filter = document(call.arg(0), "filter");
                Document replacement = document(call.arg(1), "replacement");
                ReplaceOptions options = new ReplaceOptions().comment(tag());
                if (document(call.arg(2), "options").get("upsert") instanceof Boolean upsert) {
                    options.upsert(upsert);
                }
                ClientSession s = transaction();
                UpdateResult result = s == null ? collection.replaceOne(filter, replacement, options)
                        : collection.replaceOne(s, filter, replacement, options);
                yield message(describe(result) + pending());
            }
            case "deleteOne", "deleteMany" -> {
                requireWritable(name);
                Document filter = document(call.arg(0), "filter");
                DeleteOptions options = new DeleteOptions().comment(tag());
                ClientSession s = transaction();
                boolean one = name.equals("deleteOne");
                DeleteResult result = s == null
                        ? (one ? collection.deleteOne(filter, options) : collection.deleteMany(filter, options))
                        : (one ? collection.deleteOne(s, filter, options) : collection.deleteMany(s, filter, options));
                yield message("Deleted " + plural(result.getDeletedCount(), "document") + pending());
            }
            case "findOneAndUpdate", "findOneAndReplace", "findOneAndDelete" -> findOneAnd(collection, call);
            case "createIndex" -> {
                requireWritable(name);
                String index = collection.createIndex(document(call.arg(0), "keys"), indexOptions(document(call.arg(1), "options")));
                yield message("Created index " + index);
            }
            case "createIndexes" -> {
                requireWritable(name);
                IndexOptions options = indexOptions(document(call.arg(1), "options"));
                List<IndexModel> models = new ArrayList<>();
                for (Document keys : documents(call.arg(0), "index keys")) {
                    models.add(new IndexModel(keys, options));
                }
                yield message("Created indexes " + String.join(", ", collection.createIndexes(models)));
            }
            case "dropIndex" -> {
                requireWritable(name);
                Object index = call.arg(0);
                if (index instanceof String indexName) {
                    collection.dropIndex(indexName);
                } else {
                    collection.dropIndex(document(index, "index"));
                }
                yield message("Dropped index " + MongoValues.shell(index));
            }
            case "dropIndexes" -> {
                requireWritable(name);
                collection.dropIndexes();
                yield message("Dropped all indexes except _id_ on " + command.collection());
            }
            case "getIndexes" -> {
                List<Document> rows = new ArrayList<>();
                collection.listIndexes().forEach(rows::add);
                yield documents(rows, false, null, null, false);
            }
            case "getIndexKeys" -> {
                List<Document> rows = new ArrayList<>();
                collection.listIndexes().forEach(index -> rows.add(index.get("key", Document.class)));
                yield documents(rows, false, null, null, false);
            }
            case "drop" -> {
                requireWritable(name);
                collection.drop();
                yield message("Dropped collection " + command.collection());
            }
            case "renameCollection" -> {
                requireWritable(name);
                String target = string(call.arg(0), "new name");
                boolean dropTarget = Boolean.TRUE.equals(call.arg(1));
                collection.renameCollection(new com.mongodb.MongoNamespace(db.getName(), target),
                        new RenameCollectionOptions().dropTarget(dropTarget));
                yield message("Renamed " + command.collection() + " to " + target);
            }
            case "stats" -> {
                List<Document> rows = new ArrayList<>();
                collection.aggregate(List.of(new Document("$collStats", new Document("storageStats", new Document())
                        .append("count", new Document())))).forEach(rows::add);
                yield documents(rows, false, null, null, false);
            }
            default -> throw new IllegalArgumentException("db.collection." + name + "() is not supported");
        };
    }

    private @NotNull SqlResult find(@NotNull MongoDatabase db, @NotNull MongoCollection<Document> collection,
                                    @NotNull Call call, @NotNull List<Call> chain, boolean one) {
        Document filter = document(call.arg(0), "filter");
        Document projection = call.arg(1) == null ? null : document(call.arg(1), "projection");
        Document options = document(call.arg(2), "options");
        Document sort = options.get("sort", Document.class);
        Integer limit = options.get("limit") instanceof Number n ? n.intValue() : null;
        Integer skip = options.get("skip") instanceof Number n ? n.intValue() : null;
        if (options.get("projection") instanceof Document p) {
            projection = p;
        }
        Object hint = null;
        Long maxTime = null;
        Integer batchSize = null;
        Collation collation = null;
        Boolean allowDiskUse = null;
        String explain = null;
        boolean count = false;
        for (Call link : chain) {
            switch (link.name()) {
                case "sort" -> sort = document(link.arg(0), "sort");
                case "limit" -> limit = number(link.arg(0), "limit").intValue();
                case "skip" -> skip = number(link.arg(0), "skip").intValue();
                case "projection" -> projection = document(link.arg(0), "projection");
                case "hint" -> hint = link.arg(0);
                case "maxTimeMS" -> maxTime = number(link.arg(0), "maxTimeMS").longValue();
                case "batchSize" -> batchSize = number(link.arg(0), "batchSize").intValue();
                case "collation" -> collation = collation(document(link.arg(0), "collation"));
                case "allowDiskUse" -> allowDiskUse = link.arg(0) == null || Boolean.TRUE.equals(link.arg(0));
                case "count", "size", "itcount" -> count = true;
                case "explain" -> explain = link.arg(0) instanceof String v ? v : "queryPlanner";
                case "toArray", "pretty", "comment" -> {
                }
                default -> throw new IllegalArgumentException("." + link.name() + "() is not supported after find()");
            }
        }
        if (one) {
            limit = 1;
        }
        if (explain != null) {
            Document find = new Document("find", collection.getNamespace().getCollectionName()).append("filter", filter);
            if (projection != null) {
                find.append("projection", projection);
            }
            if (sort != null) {
                find.append("sort", sort);
            }
            if (limit != null) {
                find.append("limit", limit);
            }
            if (skip != null) {
                find.append("skip", skip);
            }
            return commandResult(db.runCommand(new Document("explain", find).append("verbosity", explain)));
        }
        ClientSession s = transaction();
        if (count) {
            CountOptions countOptions = new CountOptions().comment(tag());
            if (limit != null && limit > 0) {
                countOptions.limit(limit);
            }
            if (skip != null) {
                countOptions.skip(skip);
            }
            long n = s == null ? collection.countDocuments(filter, countOptions) : collection.countDocuments(s, filter, countOptions);
            return scalar("count", n);
        }
        FindIterable<Document> iterable = s == null ? collection.find(filter) : collection.find(s, filter);
        if (projection != null) {
            iterable.projection(projection);
        }
        if (sort != null) {
            iterable.sort(sort);
        }
        if (limit != null) {
            iterable.limit(limit);
        }
        if (skip != null) {
            iterable.skip(skip);
        }
        if (hint instanceof String indexName) {
            iterable.hintString(indexName);
        } else if (hint instanceof Document keys) {
            iterable.hint(keys);
        }
        if (maxTime != null) {
            iterable.maxTime(maxTime, TimeUnit.MILLISECONDS);
        }
        if (batchSize != null) {
            iterable.batchSize(batchSize);
        }
        if (collation != null) {
            iterable.collation(collation);
        }
        if (allowDiskUse != null) {
            iterable.allowDiskUse(allowDiskUse);
        }
        // Edits go back by _id; computed projections ({x: {$toUpper: "$name"}}) aren't fields to write.
        boolean editable = projection == null || projection.values().stream()
                .allMatch(v -> v instanceof Number || v instanceof Boolean);
        return cursorRows(iterable.comment(tag()).iterator(), db.getName(),
                collection.getNamespace().getCollectionName(), editable);
    }

    private @NotNull SqlResult aggregate(@NotNull MongoDatabase db, @NotNull MongoCollection<Document> collection,
                                         @NotNull Call call, @NotNull List<Call> chain) {
        List<Document> pipeline = pipeline(call.arg(0));
        guardPipeline(pipeline);
        Document options = document(call.arg(1), "options");
        String explain = null;
        for (Call link : chain) {
            switch (link.name()) {
                case "explain" -> explain = link.arg(0) instanceof String v ? v : "queryPlanner";
                case "toArray", "pretty" -> {
                }
                default -> throw new IllegalArgumentException("." + link.name() + "() is not supported after aggregate()");
            }
        }
        if (explain != null) {
            Document command = new Document("aggregate", collection.getNamespace().getCollectionName())
                    .append("pipeline", pipeline).append("cursor", new Document());
            return commandResult(db.runCommand(new Document("explain", command).append("verbosity", explain)));
        }
        ClientSession s = transaction();
        AggregateIterable<Document> iterable = s == null ? collection.aggregate(pipeline) : collection.aggregate(s, pipeline);
        if (options.get("allowDiskUse") instanceof Boolean allow) {
            iterable.allowDiskUse(allow);
        }
        if (options.get("maxTimeMS") instanceof Number n) {
            iterable.maxTime(n.longValue(), TimeUnit.MILLISECONDS);
        }
        if (options.get("collation") instanceof Document c) {
            iterable.collation(collation(c));
        }
        if (options.get("hint") instanceof Document hint) {
            iterable.hint(hint);
        } else if (options.get("hint") instanceof String hint) {
            iterable.hintString(hint);
        }
        if (options.get("let") instanceof Document let) {
            iterable.let(let);
        }
        return cursorRows(iterable.comment(tag()).iterator(), null, null, false);
    }

    private @NotNull SqlResult findOneAnd(@NotNull MongoCollection<Document> collection, @NotNull Call call) {
        requireWritable(call.name());
        Document filter = document(call.arg(0), "filter");
        boolean delete = call.name().equals("findOneAndDelete");
        Document options = document(call.arg(delete ? 1 : 2), "options");
        boolean after = "after".equals(options.getString("returnDocument")) || Boolean.TRUE.equals(options.get("returnNewDocument"));
        ReturnDocument returned = after ? ReturnDocument.AFTER : ReturnDocument.BEFORE;
        boolean upsert = Boolean.TRUE.equals(options.get("upsert"));
        Document sort = options.get("sort", Document.class);
        Document projection = options.get("projection", Document.class);
        ClientSession s = transaction();
        Document result;
        switch (call.name()) {
            case "findOneAndUpdate" -> {
                FindOneAndUpdateOptions o = new FindOneAndUpdateOptions().returnDocument(returned).upsert(upsert)
                        .sort(sort).projection(projection).comment(tag());
                if (call.arg(1) instanceof List<?>) {
                    List<Document> pipeline = pipeline(call.arg(1));
                    result = s == null ? collection.findOneAndUpdate(filter, pipeline, o)
                            : collection.findOneAndUpdate(s, filter, pipeline, o);
                } else {
                    Document update = document(call.arg(1), "update");
                    result = s == null ? collection.findOneAndUpdate(filter, update, o)
                            : collection.findOneAndUpdate(s, filter, update, o);
                }
            }
            case "findOneAndReplace" -> {
                FindOneAndReplaceOptions o = new FindOneAndReplaceOptions().returnDocument(returned).upsert(upsert)
                        .sort(sort).projection(projection).comment(tag());
                Document replacement = document(call.arg(1), "replacement");
                result = s == null ? collection.findOneAndReplace(filter, replacement, o)
                        : collection.findOneAndReplace(s, filter, replacement, o);
            }
            default -> {
                FindOneAndDeleteOptions o = new FindOneAndDeleteOptions().sort(sort).projection(projection).comment(tag());
                result = s == null ? collection.findOneAndDelete(filter, o) : collection.findOneAndDelete(s, filter, o);
            }
        }
        return documents(result == null ? List.of() : List.of(result), false, null, null, false);
    }

    // ------------------------------------------------------------------ transactions

    /**
     * The session data commands run in: the multi-document transaction under Tx: Manual
     * (started on first use), else none — each command commits on its own.
     */
    private @Nullable ClientSession transaction() {
        if (!manual) {
            return null;
        }
        if (!session.hasActiveTransaction()) {
            session.startTransaction();
        }
        return session;
    }

    /** runCommand joins the transaction only for data commands; admin commands can't run in one. */
    private @Nullable ClientSession transactionFor(@NotNull Document command) {
        String name = command.isEmpty() ? "" : command.keySet().iterator().next();
        return Set.of("find", "aggregate", "count", "distinct", "insert", "update", "delete", "findAndModify")
                .contains(name) ? transaction() : null;
    }

    private @NotNull String pending() {
        return manual && session.hasActiveTransaction() ? " (pending: commit the transaction to keep it)" : "";
    }

    /** Transactions need a replica set: say so instead of "Transaction numbers are only allowed…". */
    private @NotNull String transactionHint(@NotNull MongoException e, @NotNull String message) {
        if (manual && session != null && session.hasActiveTransaction()) {
            try {
                session.abortTransaction();
            } catch (RuntimeException ignored) {
            }
            if (e.getCode() == 20 || message.contains("Transaction numbers") || message.contains("retryable writes")) {
                return message + " — transactions need a replica set or a sharded cluster; use Tx: Auto on a standalone server";
            }
            return message + " (the transaction was rolled back)";
        }
        return message;
    }

    @Override
    public void setAutoCommit(boolean autoCommit) throws SQLException {
        if (autoCommit && manual && session.hasActiveTransaction()) {
            try {
                session.commitTransaction(); // like JDBC: turning auto-commit on commits
            } catch (MongoException e) {
                throw new SQLException(MongoConnector.message(e), e);
            }
        }
        manual = !autoCommit;
    }

    @Override
    public @NotNull SqlResult commit() {
        return endTransaction(true);
    }

    @Override
    public @NotNull SqlResult rollback() {
        return endTransaction(false);
    }

    private @NotNull SqlResult endTransaction(boolean commit) {
        String label = commit ? "commit" : "rollback";
        long begin = System.currentTimeMillis();
        if (!manual) {
            return SqlResult.message(label, "Nothing to " + label + " (auto-commit is on)", 0);
        }
        if (!session.hasActiveTransaction()) {
            return SqlResult.message(label, "Nothing to " + label + " (no statement ran in the transaction yet)", 0);
        }
        try {
            if (commit) {
                session.commitTransaction();
            } else {
                session.abortTransaction();
            }
            return SqlResult.message(label, (commit ? "Commit" : "Rollback") + " completed", System.currentTimeMillis() - begin);
        } catch (MongoException e) {
            return SqlResult.error(label, MongoConnector.message(e), System.currentTimeMillis() - begin);
        }
    }

    // ------------------------------------------------------------------ edited rows

    /**
     * The grid's {@code updateOne} / {@code deleteOne} commands, each of which must match
     * exactly one document. With transactions (replica sets) they run in one — the user's
     * under Tx: Manual — and fail together; a standalone server has none, so every filter is
     * checked first and nothing is written unless all of them match exactly one document.
     */
    @Override
    public @NotNull SqlResult applyRowUpdates(@NotNull List<String> statements) {
        sql = String.join("\n", statements);
        start = System.currentTimeMillis();
        try {
            List<CollectionCall> calls = new ArrayList<>();
            for (String statement : statements) {
                if (!(MongoShellParser.parseCommand(statement) instanceof CollectionCall call)
                        || !Set.of("updateOne", "deleteOne").contains(call.call().name())) {
                    throw new IllegalArgumentException("Not a row update: " + statement);
                }
                calls.add(call);
            }
            requireWritable("Submit");
            // Check first: nothing is written unless every change finds exactly its one document.
            for (int i = 0; i < calls.size(); i++) {
                CollectionCall call = calls.get(i);
                long matches = db(call.database()).getCollection(call.collection())
                        .countDocuments(document(call.call().arg(0), "filter"), new CountOptions().limit(2));
                if (matches != 1) {
                    throw new IllegalStateException((matches == 0 ? "No document matched (changed or deleted meanwhile?)"
                            : "More than one document matched") + ": " + statements.get(i));
                }
            }
            boolean inUserTransaction = manual;
            boolean ownTransaction = !manual && supportsTransactions();
            if (ownTransaction) {
                session.startTransaction();
            }
            ClientSession s = inUserTransaction ? transaction() : ownTransaction ? session : null;
            int applied = 0;
            try {
                for (int i = 0; i < calls.size(); i++) {
                    CollectionCall call = calls.get(i);
                    MongoCollection<Document> collection = db(call.database()).getCollection(call.collection());
                    Document filter = document(call.call().arg(0), "filter");
                    long changed;
                    if (call.call().name().equals("updateOne")) {
                        Document update = document(call.call().arg(1), "update");
                        UpdateResult result = s == null ? collection.updateOne(filter, update) : collection.updateOne(s, filter, update);
                        changed = result.getMatchedCount();
                    } else {
                        DeleteResult result = s == null ? collection.deleteOne(filter) : collection.deleteOne(s, filter);
                        changed = result.getDeletedCount();
                    }
                    if (changed != 1) {
                        throw new IllegalStateException("No document matched (changed or deleted meanwhile?): " + statements.get(i));
                    }
                    applied++;
                }
                if (ownTransaction) {
                    session.commitTransaction();
                }
            } catch (RuntimeException e) {
                if (ownTransaction || inUserTransaction) {
                    try {
                        session.abortTransaction();
                    } catch (RuntimeException ignored) {
                    }
                }
                String undone = ownTransaction ? "" : inUserTransaction ? " (the transaction was rolled back)"
                        : applied > 0 ? " (" + applied + " of " + calls.size()
                        + " changes were already written: a standalone server has no transactions to undo them)" : "";
                String text = e instanceof MongoException m ? MongoConnector.message(m) : e.getMessage();
                return error(text + undone);
            }
            return message(plural(calls.size(), "document") + " changed"
                    + (inUserTransaction ? " (pending: commit the transaction to keep the changes)" : ""));
        } catch (MongoShellParser.SyntaxError | IllegalArgumentException | IllegalStateException e) {
            return error(e.getMessage());
        } catch (MongoException e) {
            return error(MongoConnector.message(e));
        }
    }

    private boolean supportsTransactions() {
        if (supportsTransactions == null) {
            try {
                supportsTransactions = !MongoConnector.topology(client).equals("standalone");
            } catch (MongoException e) {
                supportsTransactions = false;
            }
        }
        return supportsTransactions;
    }

    // ------------------------------------------------------------------ cancel

    /** Kills the running operation, found by its comment among this user's operations. */
    @Override
    public void cancel() {
        String tag = running;
        MongoClient c = client;
        if (tag == null || c == null) {
            return;
        }
        try {
            MongoDatabase admin = c.getDatabase("admin");
            List<Document> operations = new ArrayList<>();
            admin.aggregate(List.of(
                    new Document("$currentOp", new Document()),
                    new Document("$match", new Document("$or", List.of(
                            new Document("command.comment", tag),
                            new Document("cursor.originatingCommand.comment", tag)))))).forEach(operations::add);
            for (Document operation : operations) {
                admin.runCommand(new Document("killOp", 1).append("op", operation.get("opid")));
            }
        } catch (MongoException ignored) {
            // finished meanwhile, or no privilege to see it
        }
    }

    /** A fresh comment tag for the next operation; {@link #cancel()} looks for it. */
    private @NotNull String tag() {
        String tag = "intelladb:" + UUID.randomUUID();
        running = tag;
        return tag;
    }

    // ------------------------------------------------------------------ read-only

    private void requireWritable(@NotNull String what) {
        if (config.readOnly) {
            throw new IllegalStateException("The connection is read-only: " + what + " is not allowed");
        }
    }

    private void guardCommand(@NotNull Document command) {
        String name = command.isEmpty() ? "" : command.keySet().iterator().next();
        if (WRITE_COMMANDS.contains(name)) {
            requireWritable(name);
        }
        if (name.equals("aggregate") && command.get("pipeline") instanceof List<?> pipeline) {
            guardPipeline(pipeline);
        }
    }

    private void guardPipeline(@NotNull List<?> pipeline) {
        for (Object stage : pipeline) {
            if (stage instanceof Document d && (d.containsKey("$out") || d.containsKey("$merge"))) {
                requireWritable(d.containsKey("$out") ? "$out" : "$merge");
            }
        }
    }

    // ------------------------------------------------------------------ results

    private @NotNull SqlResult cursorRows(@NotNull MongoCursor<Document> cursor, @Nullable String sourceDatabase,
                                          @Nullable String sourceCollection, boolean editable) {
        List<Document> rows = new ArrayList<>();
        boolean truncated = false;
        try (cursor) {
            while (cursor.hasNext()) {
                if (rows.size() >= SqlResult.MAX_ROWS) {
                    truncated = true;
                    break;
                }
                rows.add(cursor.next());
            }
        }
        return documents(rows, truncated, sourceDatabase, sourceCollection, editable);
    }

    /** A command's reply as one row — or its cursor's first batch as rows (listCollections, aggregate…). */
    private @NotNull SqlResult commandResult(@NotNull Document reply) {
        if (reply.get("cursor") instanceof Document cursor && cursor.get("firstBatch") instanceof List<?> batch) {
            List<Document> rows = new ArrayList<>();
            for (Object item : batch) {
                rows.add(item instanceof Document d ? d : new Document("value", item));
            }
            return documents(rows, false, null, null, false);
        }
        if (reply.get("inprog") instanceof List<?> operations) {
            List<Document> rows = new ArrayList<>();
            operations.forEach(op -> rows.add((Document) op));
            return documents(rows, false, null, null, false);
        }
        return documents(List.of(reply), false, null, null, false);
    }

    /**
     * Documents as rows: one column per top-level field ({@code _id} first, then in the
     * order they first appear), typed by the field's first non-null value. With a source
     * collection the result is editable: each column writes back to its field, except
     * binary ones (shown as a size) and names $set can't address.
     */
    @NotNull SqlResult documents(@NotNull List<Document> documents, boolean truncated, @Nullable String sourceDatabase,
                                 @Nullable String sourceCollection, boolean editable) {
        Map<String, String> types = new LinkedHashMap<>();
        Map<String, Boolean> binary = new LinkedHashMap<>();
        for (Document document : documents) {
            for (Map.Entry<String, Object> field : document.entrySet()) {
                Object value = field.getValue();
                if (!types.containsKey(field.getKey()) || types.get(field.getKey()).equals("null")) {
                    types.put(field.getKey(), MongoValues.typeName(value));
                }
                if (MongoValues.isBinary(value)) {
                    binary.put(field.getKey(), true);
                }
            }
        }
        List<String> columns = new ArrayList<>(types.keySet());
        if (columns.remove("_id")) {
            columns.addFirst("_id");
        }
        List<Object[]> rows = new ArrayList<>(documents.size());
        for (Document document : documents) {
            Object[] row = new Object[columns.size()];
            for (int c = 0; c < columns.size(); c++) {
                row[c] = MongoValues.cell(document.get(columns.get(c)));
            }
            rows.add(row);
        }
        List<String> columnTypes = columns.stream().map(types::get).toList();
        long duration = System.currentTimeMillis() - start;
        if (!editable || sourceCollection == null) {
            return SqlResult.rows(sql, columns, columnTypes, rows, truncated, duration);
        }
        List<String> sourceColumns = columns.stream()
                .map(c -> binary.containsKey(c) || c.contains(".") || c.startsWith("$") ? null : c)
                .toList();
        return SqlResult.rows(sql, columns, columnTypes, rows, truncated, duration,
                sourceDatabase, sourceCollection, sourceColumns);
    }

    private @NotNull SqlResult scalar(@NotNull String column, @Nullable Object value) {
        return documents(List.of(new Document(column, value)), false, null, null, false);
    }

    private @NotNull SqlResult message(@NotNull String text) {
        return SqlResult.message(sql, text, System.currentTimeMillis() - start);
    }

    private @NotNull SqlResult error(@NotNull String text) {
        return SqlResult.error(sql, text, System.currentTimeMillis() - start);
    }

    private static @NotNull String describe(@NotNull UpdateResult result) {
        String text = "Matched " + plural(result.getMatchedCount(), "document") + ", modified " + result.getModifiedCount();
        if (result.getUpsertedId() != null) {
            text += ", upserted _id " + result.getUpsertedId();
        }
        return text;
    }

    static @NotNull String plural(long n, @NotNull String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }

    // ------------------------------------------------------------------ arguments

    private static @NotNull Document document(@Nullable Object value, @NotNull String what) {
        if (value == null) {
            return new Document();
        }
        if (value instanceof Document d) {
            return d;
        }
        throw new IllegalArgumentException("Expected a document for the " + what + ", got " + MongoValues.shell(value));
    }

    private static @NotNull Document command(@Nullable Object value) {
        if (value instanceof String name) {
            return new Document(name, 1);
        }
        Document command = document(value, "command");
        if (command.isEmpty()) {
            throw new IllegalArgumentException("runCommand needs a command, e.g. {ping: 1}");
        }
        return command;
    }

    private static @NotNull List<Document> documents(@Nullable Object value, @NotNull String what) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("Expected an array of " + what);
        }
        List<Document> documents = new ArrayList<>();
        for (Object item : list) {
            documents.add(document(item, what));
        }
        return documents;
    }

    private static @NotNull List<Document> pipeline(@Nullable Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof Document stage) {
            return List.of(stage); // aggregate({$match: …}) — one stage without the array
        }
        return documents(value, "pipeline stages");
    }

    private static @NotNull String string(@Nullable Object value, @NotNull String what) {
        if (value instanceof String s) {
            return s;
        }
        throw new IllegalArgumentException("Expected a " + what + " in quotes");
    }

    private static @NotNull Number number(@Nullable Object value, @NotNull String what) {
        if (value instanceof Number n) {
            return n;
        }
        throw new IllegalArgumentException(what + "() needs a number");
    }

    private @NotNull UpdateOptions updateOptions(@NotNull Document options) {
        UpdateOptions update = new UpdateOptions().comment(tag());
        if (options.get("upsert") instanceof Boolean upsert) {
            update.upsert(upsert);
        }
        if (options.get("arrayFilters") instanceof List<?> filters) {
            List<Bson> bson = new ArrayList<>();
            filters.forEach(f -> bson.add((Document) f));
            update.arrayFilters(bson);
        }
        if (options.get("hint") instanceof Document hint) {
            update.hint(hint);
        } else if (options.get("hint") instanceof String hint) {
            update.hintString(hint);
        }
        return update;
    }

    private static @NotNull IndexOptions indexOptions(@NotNull Document options) {
        IndexOptions index = new IndexOptions();
        if (options.get("name") instanceof String name) {
            index.name(name);
        }
        if (options.get("unique") instanceof Boolean unique) {
            index.unique(unique);
        }
        if (options.get("sparse") instanceof Boolean sparse) {
            index.sparse(sparse);
        }
        if (options.get("expireAfterSeconds") instanceof Number seconds) {
            index.expireAfter(seconds.longValue(), TimeUnit.SECONDS);
        }
        if (options.get("partialFilterExpression") instanceof Document partial) {
            index.partialFilterExpression(partial);
        }
        if (options.get("hidden") instanceof Boolean hidden) {
            index.hidden(hidden);
        }
        if (options.get("collation") instanceof Document collation) {
            index.collation(collation(collation));
        }
        return index;
    }

    private static @NotNull Collation collation(@NotNull Document spec) {
        Collation.Builder builder = Collation.builder();
        if (spec.get("locale") instanceof String locale) {
            builder.locale(locale);
        }
        if (spec.get("strength") instanceof Number strength) {
            builder.collationStrength(CollationStrength.fromInt(strength.intValue()));
        }
        if (spec.get("caseLevel") instanceof Boolean caseLevel) {
            builder.caseLevel(caseLevel);
        }
        if (spec.get("numericOrdering") instanceof Boolean numeric) {
            builder.numericOrdering(numeric);
        }
        return builder.build();
    }
}
