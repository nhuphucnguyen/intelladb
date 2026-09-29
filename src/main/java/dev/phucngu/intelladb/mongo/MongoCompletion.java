package dev.phucngu.intelladb.mongo;

import dev.phucngu.intelladb.schema.ColumnMeta;
import dev.phucngu.intelladb.schema.SchemaCatalog;
import dev.phucngu.intelladb.schema.TableMeta;
import dev.phucngu.intelladb.sql.completion.Suggestion;
import dev.phucngu.intelladb.util.SqlSplitter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;

/**
 * Completion for the MongoDB console, on the text like the SQL completion: it reads the
 * statement up to the caret, keeps track of which call, argument, object and key it is in,
 * and suggests what fits there —
 * <ul>
 *   <li>{@code db.} collections of the current database and database methods, {@code db.pets.}
 *       collection methods, {@code find().} cursor methods, {@code use } databases;</li>
 *   <li>in a filter the collection's fields and {@code $and / $or…}, after a field
 *       {@code {$gt / $in…}}, in an update {@code $set / $inc…} and then fields, in
 *       {@code aggregate([{} ])} stages, inside {@code $group} accumulators, and
 *       {@code "$field"} paths in expressions;</li>
 *   <li>shell constructors ({@code ObjectId}, {@code ISODate}…) where a value goes.</li>
 * </ul>
 */
public final class MongoCompletion {

    /** What the caret has typed of the name being completed, and the candidates. */
    public record Result(@NotNull String prefix, @NotNull List<Suggestion> suggestions) {
        static final Result NONE = new Result("", List.of());
    }

    // ------------------------------------------------------------------ vocabulary

    static final List<String> DATABASE_METHODS = List.of(
            "getCollection", "getSiblingDB", "runCommand", "adminCommand", "aggregate", "createCollection",
            "createView", "dropDatabase", "getCollectionNames", "getCollectionInfos", "getName", "stats", "version",
            "serverStatus", "currentOp", "killOp", "getUsers");
    static final List<String> COLLECTION_METHODS = List.of(
            "find", "findOne", "aggregate", "countDocuments", "estimatedDocumentCount", "distinct", "insertOne",
            "insertMany", "updateOne", "updateMany", "replaceOne", "deleteOne", "deleteMany", "findOneAndUpdate",
            "findOneAndReplace", "findOneAndDelete", "createIndex", "createIndexes", "dropIndex", "dropIndexes",
            "getIndexes", "drop", "renameCollection", "stats");
    static final List<String> FIND_CURSOR_METHODS = List.of(
            "sort", "limit", "skip", "projection", "count", "explain", "hint", "maxTimeMS", "batchSize", "collation",
            "allowDiskUse", "toArray", "pretty");
    static final List<String> AGGREGATE_CURSOR_METHODS = List.of("toArray", "explain", "pretty");
    static final List<String> TOP_LEVEL_QUERY = List.of("$and", "$or", "$nor", "$expr", "$text", "$where",
            "$jsonSchema", "$comment");
    static final List<String> FIELD_OPERATORS = List.of("$eq", "$ne", "$gt", "$gte", "$lt", "$lte", "$in", "$nin",
            "$exists", "$type", "$regex", "$options", "$elemMatch", "$size", "$all", "$not", "$mod");
    static final List<String> UPDATE_OPERATORS = List.of("$set", "$unset", "$inc", "$mul", "$min", "$max", "$rename",
            "$push", "$pull", "$addToSet", "$pop", "$pullAll", "$currentDate", "$setOnInsert");
    static final List<String> STAGES = List.of("$match", "$project", "$group", "$sort", "$limit", "$skip", "$unwind",
            "$lookup", "$addFields", "$set", "$unset", "$count", "$facet", "$bucket", "$bucketAuto", "$replaceRoot",
            "$replaceWith", "$sample", "$sortByCount", "$graphLookup", "$unionWith", "$out", "$merge", "$densify",
            "$fill", "$setWindowFields", "$geoNear", "$documents", "$redact", "$collStats", "$indexStats");
    static final List<String> ACCUMULATORS = List.of("$sum", "$avg", "$min", "$max", "$first", "$last", "$push",
            "$addToSet", "$count", "$stdDevPop", "$stdDevSamp", "$top", "$bottom", "$firstN", "$lastN", "$mergeObjects");
    static final List<String> EXPRESSIONS = List.of("$add", "$subtract", "$multiply", "$divide", "$mod", "$abs",
            "$round", "$concat", "$substr", "$toUpper", "$toLower", "$trim", "$split", "$strLenCP", "$cond", "$ifNull",
            "$switch", "$eq", "$ne", "$gt", "$gte", "$lt", "$lte", "$and", "$or", "$not", "$in", "$size", "$arrayElemAt",
            "$filter", "$map", "$reduce", "$first", "$last", "$dateToString", "$year", "$month", "$dayOfMonth",
            "$toString", "$toInt", "$toDouble", "$toDate", "$toObjectId", "$type", "$literal", "$sum", "$avg",
            "$min", "$max", "$mergeObjects", "$getField", "$let");
    static final List<String> LOOKUP_KEYS = List.of("from", "localField", "foreignField", "as", "let", "pipeline");
    static final List<String> CONSTRUCTORS = List.of("ObjectId", "ISODate", "NumberLong", "NumberInt",
            "NumberDecimal", "UUID", "Timestamp", "BinData", "RegExp", "MinKey", "MaxKey");
    static final List<String> LITERALS = List.of("true", "false", "null");
    private static final Set<String> FILTER_LISTS = Set.of("$and", "$or", "$nor");

    /** What an object (or array) at the caret holds, which decides what its keys can be. */
    enum Role {
        NONE, FILTER, FILTER_LIST, FIELD_OPERATORS, UPDATE, FIELDS, DOCUMENT, DOCUMENTS, PIPELINE, STAGE, GROUP,
        ACCUMULATOR, EXPRESSION, LOOKUP, FIND_OPTIONS, UPDATE_OPTIONS, INDEX_OPTIONS
    }

    private enum Receiver { NONE, DB, COLLECTION, FIND_CURSOR, AGGREGATE_CURSOR, DONE }

    /** A call, object or array the caret is inside. */
    private static final class Frame {
        final char open;
        final Role role;
        final String method;
        final Receiver receiver;
        int arg;
        String key;
        boolean value;
        String firstString;

        Frame(char open, @NotNull Role role, @Nullable String method, @NotNull Receiver receiver) {
            this.open = open;
            this.role = role;
            this.method = method;
            this.receiver = receiver;
        }
    }

    private final SchemaCatalog catalog;
    private final String currentDatabase;

    private MongoCompletion(@Nullable SchemaCatalog catalog, @Nullable String currentDatabase) {
        this.catalog = catalog;
        this.currentDatabase = currentDatabase;
    }

    /**
     * @param catalog         the connection's catalog; null when not connected (no names, just the language)
     * @param currentDatabase what {@code db} means in the console; null when unknown
     */
    public static @NotNull Result suggest(@NotNull String text, int offset, @Nullable SchemaCatalog catalog,
                                          @Nullable String currentDatabase) {
        return new MongoCompletion(catalog, currentDatabase).complete(statementBefore(text, offset));
    }

    /**
     * The statement the caret is in, up to the caret. A marker character stands in for the
     * next letter typed, so the splitter decides whether it continues the statement
     * (inside brackets, after a '.') or starts a new one (after a finished line).
     */
    static @NotNull String statementBefore(@NotNull String text, int offset) {
        String probe = text.substring(0, Math.min(offset, text.length())) + '';
        List<SqlSplitter.Statement> statements = SqlSplitter.ranges(probe, SqlSplitter.Options.MONGO);
        if (statements.isEmpty()) {
            return "";
        }
        String last = statements.getLast().text();
        return last.endsWith("") ? last.substring(0, last.length() - 1) : "";
    }

    // ------------------------------------------------------------------ analysis

    private @NotNull Result complete(@NotNull String statement) {
        List<Token> tokens = Token.scan(statement);
        Token last = tokens.isEmpty() ? null : tokens.getLast();
        // The token being typed (an identifier or an unfinished string) is the prefix, not context.
        boolean typingWord = last != null && statement.length() == last.end
                && (last.kind == Token.Kind.WORD || (last.kind == Token.Kind.STRING && !last.closed));
        List<Token> context = typingWord ? tokens.subList(0, tokens.size() - 1) : tokens;
        String prefix = typingWord ? last.text : "";
        boolean inString = typingWord && last.kind == Token.Kind.STRING;
        if (last != null && !typingWord && statement.length() == last.end
                && (last.kind == Token.Kind.NUMBER || last.kind == Token.Kind.STRING || last.kind == Token.Kind.REGEX)) {
            return Result.NONE; // right after a value
        }

        // Statement start: db / use / show.
        if (context.isEmpty()) {
            return inString ? Result.NONE : new Result(prefix, keywords(List.of("db", "use", "show")));
        }
        Token first = context.getFirst();
        if (first.kind == Token.Kind.WORD && context.size() == 1 && !inString) {
            if (first.text.equals("use")) {
                return new Result(prefix, databases());
            }
            if (first.text.equals("show")) {
                return new Result(prefix, keywords(List.of("dbs", "collections", "users", "roles")));
            }
        }
        if (first.kind != Token.Kind.WORD || !first.text.equals("db")) {
            return Result.NONE;
        }

        String database = currentDatabase;
        String collection = null;
        Receiver receiver = Receiver.DB;
        boolean dot = false;
        Deque<Frame> stack = new ArrayDeque<>();
        String lastWord = null;
        for (int i = 1; i < context.size(); i++) {
            Token t = context.get(i);
            Token next = i + 1 < context.size() ? context.get(i + 1) : null;
            Frame top = stack.peek();
            if (t.kind == Token.Kind.PUNCT) {
                switch (t.text.charAt(0)) {
                    case '.' -> dot = stack.isEmpty();
                    case '(' -> {
                        Role role = top == null ? Role.NONE : childRole(top);
                        stack.push(new Frame('(', role, lastWord, stack.isEmpty() ? receiver : Receiver.NONE));
                        lastWord = null;
                    }
                    case '{', '[' -> {
                        Role role = top == null ? Role.NONE : childRole(top);
                        stack.push(new Frame(t.text.charAt(0), role, null, Receiver.NONE));
                    }
                    case ')', '}', ']' -> {
                        Frame closed = stack.poll();
                        if (closed != null && closed.open == '(' && stack.isEmpty()) {
                            // A finished call on the receiver: what can follow it?
                            String method = closed.method == null ? "" : closed.method;
                            switch (closed.receiver) {
                                case DB -> {
                                    if (method.equals("getSiblingDB") || method.equals("getDB")) {
                                        database = closed.firstString;
                                        receiver = Receiver.DB;
                                    } else if (method.equals("getCollection")) {
                                        collection = closed.firstString;
                                        receiver = Receiver.COLLECTION;
                                    } else {
                                        receiver = Receiver.DONE;
                                    }
                                }
                                case COLLECTION -> receiver = method.equals("find") ? Receiver.FIND_CURSOR
                                        : method.equals("aggregate") ? Receiver.AGGREGATE_CURSOR : Receiver.DONE;
                                case FIND_CURSOR, AGGREGATE_CURSOR -> {
                                    if (method.equals("count") || method.equals("explain") || method.equals("toArray")) {
                                        receiver = Receiver.DONE;
                                    }
                                }
                                default -> receiver = Receiver.DONE;
                            }
                        }
                    }
                    case ',' -> {
                        if (top != null) {
                            top.arg++;
                            top.key = null;
                            top.value = false;
                        }
                    }
                    case ':' -> {
                        if (top != null && top.open == '{') {
                            top.value = true;
                        }
                    }
                    default -> {
                    }
                }
                continue;
            }
            if (t.kind == Token.Kind.WORD) {
                lastWord = t.text;
                if (stack.isEmpty() && dot) {
                    dot = false;
                    boolean call = next != null && next.kind == Token.Kind.PUNCT && next.text.equals("(");
                    if (receiver == Receiver.DB && !call) {
                        collection = t.text;
                        receiver = Receiver.COLLECTION;
                    } else if (receiver == Receiver.COLLECTION && !call) {
                        collection = collection + "." + t.text; // db.system.profile
                    }
                } else if (top != null && top.open == '{' && !top.value) {
                    top.key = t.text;
                }
                continue;
            }
            if (t.kind == Token.Kind.STRING && top != null) {
                if (top.open == '(' && top.arg == 0 && top.firstString == null) {
                    top.firstString = t.text;
                } else if (top.open == '{' && !top.value) {
                    top.key = t.text;
                }
            }
        }

        Frame top = stack.peek();
        if (top == null) {
            if (!dot || inString) {
                return Result.NONE;
            }
            return switch (receiver) {
                case DB -> new Result(prefix, concat(collections(database), functions(DATABASE_METHODS, "db")));
                case COLLECTION -> new Result(prefix, functions(COLLECTION_METHODS, collection));
                case FIND_CURSOR -> new Result(prefix, functions(FIND_CURSOR_METHODS, "cursor"));
                case AGGREGATE_CURSOR -> new Result(prefix, functions(AGGREGATE_CURSOR_METHODS, "cursor"));
                default -> Result.NONE;
            };
        }
        Fields fields = new Fields(database, collection);
        if (top.open == '(') {
            // Names in quotes (getCollection("…"), distinct("…")); a bare argument starts with { or [.
            return inString ? new Result(prefix, stringArgument(top, fields)) : Result.NONE;
        }
        if (top.open == '[') {
            if (inString) {
                return top.role == Role.EXPRESSION ? new Result(prefix, fieldPaths(fields, prefix)) : Result.NONE;
            }
            // Pipelines and document lists hold objects; other arrays ($in: […]) hold values.
            return Set.of(Role.PIPELINE, Role.DOCUMENTS, Role.FILTER_LIST).contains(top.role) ? Result.NONE
                    : new Result(prefix, values());
        }
        // Inside an object: a key, or the value of top.key.
        if (!top.value) {
            List<Suggestion> keys = keysFor(top.role, fields, inString);
            return keys.isEmpty() ? Result.NONE : new Result(prefix, keys);
        }
        if (inString) {
            return prefix.startsWith("$") && expressionContext(top) ? new Result(prefix, fieldPaths(fields, prefix)) : Result.NONE;
        }
        return new Result(prefix, values());
    }

    /** The role of an object or array opened inside {@code parent}. */
    private static @NotNull Role childRole(@NotNull Frame parent) {
        return switch (parent.open) {
            case '(' -> callRole(parent.receiver, parent.method, parent.arg);
            case '[' -> switch (parent.role) {
                case PIPELINE -> Role.STAGE;
                case FILTER_LIST -> Role.FILTER;
                case DOCUMENTS -> Role.DOCUMENT;
                case EXPRESSION -> Role.EXPRESSION;
                default -> Role.NONE;
            };
            default -> parent.value ? valueRole(parent.role, parent.key == null ? "" : parent.key) : Role.NONE;
        };
    }

    /** What argument {@code arg} of {@code method} is. */
    static @NotNull Role callRole(@NotNull Receiver receiver, @Nullable String method, int arg) {
        if (method == null) {
            return Role.NONE;
        }
        if (receiver == Receiver.DB) {
            return method.equals("aggregate") && arg == 0 ? Role.PIPELINE : Role.NONE;
        }
        if (receiver == Receiver.FIND_CURSOR) {
            return switch (method) {
                case "sort", "projection", "hint" -> Role.FIELDS;
                default -> Role.NONE;
            };
        }
        if (receiver != Receiver.COLLECTION) {
            return Role.NONE;
        }
        return switch (method) {
            case "find", "findOne" -> arg == 0 ? Role.FILTER : arg == 1 ? Role.FIELDS : Role.FIND_OPTIONS;
            case "countDocuments", "deleteOne", "deleteMany", "findOneAndDelete" -> arg == 0 ? Role.FILTER : Role.NONE;
            case "distinct" -> arg == 1 ? Role.FILTER : Role.NONE;
            case "updateOne", "updateMany", "findOneAndUpdate" -> arg == 0 ? Role.FILTER : arg == 1 ? Role.UPDATE
                    : Role.UPDATE_OPTIONS;
            case "replaceOne", "findOneAndReplace" -> arg == 0 ? Role.FILTER : arg == 1 ? Role.DOCUMENT : Role.UPDATE_OPTIONS;
            case "insertOne" -> arg == 0 ? Role.DOCUMENT : Role.NONE;
            case "insertMany" -> arg == 0 ? Role.DOCUMENTS : Role.NONE;
            case "aggregate" -> arg == 0 ? Role.PIPELINE : Role.NONE;
            case "createIndex" -> arg == 0 ? Role.FIELDS : Role.INDEX_OPTIONS;
            default -> Role.NONE;
        };
    }

    /** The role of the value of key {@code key} in an object of role {@code role}. */
    static @NotNull Role valueRole(@NotNull Role role, @NotNull String key) {
        return switch (role) {
            case FILTER -> FILTER_LISTS.contains(key) ? Role.FILTER_LIST
                    : key.equals("$expr") ? Role.EXPRESSION : key.startsWith("$") ? Role.NONE : Role.FIELD_OPERATORS;
            case FIELD_OPERATORS -> key.equals("$not") ? Role.FIELD_OPERATORS
                    : key.equals("$elemMatch") ? Role.FILTER : Role.NONE;
            case UPDATE -> key.startsWith("$") ? Role.FIELDS : Role.NONE;
            case STAGE -> switch (key) {
                case "$match" -> Role.FILTER;
                case "$project", "$addFields", "$set", "$sort", "$unset" -> Role.FIELDS;
                case "$group" -> Role.GROUP;
                case "$lookup", "$graphLookup", "$unionWith" -> Role.LOOKUP;
                case "$facet" -> Role.NONE;
                default -> Role.EXPRESSION;
            };
            case GROUP -> key.equals("_id") ? Role.EXPRESSION : Role.ACCUMULATOR;
            case ACCUMULATOR, EXPRESSION, FIELDS -> Role.EXPRESSION;
            case LOOKUP -> key.equals("pipeline") ? Role.PIPELINE : key.equals("let") ? Role.EXPRESSION : Role.NONE;
            default -> Role.NONE;
        };
    }

    /** Whether a {@code "$field"} path makes sense as a value here (aggregation expressions). */
    private static boolean expressionContext(@NotNull Frame frame) {
        return switch (frame.role) {
            case EXPRESSION, ACCUMULATOR, GROUP, FIELDS -> true;
            case STAGE -> true; // {$unwind: "$tags"}, {$sortByCount: "$name"}
            default -> false;
        };
    }

    // ------------------------------------------------------------------ candidates

    /** The collection whose fields a filter or update is about. */
    private record Fields(@Nullable String database, @Nullable String collection) {
    }

    private @NotNull List<Suggestion> keysFor(@NotNull Role role, @NotNull Fields fields, boolean inString) {
        List<Suggestion> keys = switch (role) {
            case FILTER -> concat(fieldKeys(fields, inString), operators(TOP_LEVEL_QUERY, "query"));
            case FIELD_OPERATORS -> operators(FIELD_OPERATORS, "query operator");
            case UPDATE -> operators(UPDATE_OPERATORS, "update operator");
            case FIELDS, DOCUMENT -> fieldKeys(fields, inString);
            case STAGE -> operators(STAGES, "stage");
            case GROUP -> concat(List.of(keyword("_id", "group key")), fieldKeys(fields, inString));
            case ACCUMULATOR -> operators(ACCUMULATORS, "accumulator");
            case EXPRESSION -> operators(EXPRESSIONS, "expression");
            case LOOKUP -> keywords(LOOKUP_KEYS);
            case FIND_OPTIONS -> keywords(List.of("projection", "sort", "limit", "skip"));
            case UPDATE_OPTIONS -> keywords(List.of("upsert", "arrayFilters", "hint", "returnDocument", "projection", "sort"));
            case INDEX_OPTIONS -> keywords(List.of("name", "unique", "sparse", "expireAfterSeconds",
                    "partialFilterExpression", "hidden", "collation"));
            default -> List.of();
        };
        if (inString) {
            // Inside quotes only names fit: no operators.
            keys = keys.stream().filter(s -> !s.lookup().startsWith("$")).toList();
        }
        return keys;
    }

    private @NotNull List<Suggestion> stringArgument(@NotNull Frame call, @NotNull Fields fields) {
        String method = call.method == null ? "" : call.method;
        if (call.arg != 0) {
            return List.of();
        }
        return switch (method) {
            case "getCollection", "createView" -> call.receiver == Receiver.DB ? collectionNames(fields.database()) : List.of();
            case "getSiblingDB", "getDB" -> call.receiver == Receiver.DB ? databases() : List.of();
            case "distinct" -> fieldNames(fields);
            case "dropIndex", "hint" -> indexNames(fields);
            default -> List.of();
        };
    }

    private @NotNull List<Suggestion> values() {
        List<Suggestion> values = new ArrayList<>(functions(CONSTRUCTORS, "shell"));
        values.addAll(keywords(LITERALS));
        return values;
    }

    private @Nullable SchemaCatalog.Schema schema(@Nullable String database) {
        if (catalog == null || database == null) {
            return null;
        }
        for (SchemaCatalog.Schema schema : catalog.schemas()) {
            if (schema.name().equals(database)) {
                return schema;
            }
        }
        return null;
    }

    private @Nullable TableMeta table(@NotNull Fields fields) {
        SchemaCatalog.Schema schema = schema(fields.database());
        if (schema == null || fields.collection() == null) {
            return null;
        }
        for (TableMeta table : schema.tables()) {
            if (table.name.equals(fields.collection())) {
                return table;
            }
        }
        return null;
    }

    /** After {@code db.}: names that are identifiers go in as is, others as {@code getCollection("…")}. */
    private @NotNull List<Suggestion> collections(@Nullable String database) {
        SchemaCatalog.Schema schema = schema(database);
        if (schema == null) {
            return List.of();
        }
        List<Suggestion> out = new ArrayList<>();
        for (TableMeta table : schema.tables()) {
            String insert = isIdentifier(table.name) && !DATABASE_METHODS.contains(table.name) ? table.name
                    : "getCollection(" + MongoValues.shell(table.name) + ")";
            out.add(new Suggestion(table.name, insert, table.isView() ? Suggestion.Kind.VIEW : Suggestion.Kind.TABLE,
                    table.isView() ? "view" : "collection", schema.name(), 90));
        }
        return out;
    }

    private @NotNull List<Suggestion> collectionNames(@Nullable String database) {
        SchemaCatalog.Schema schema = schema(database);
        if (schema == null) {
            return List.of();
        }
        return schema.tables().stream().map(t -> new Suggestion(t.name, t.name,
                t.isView() ? Suggestion.Kind.VIEW : Suggestion.Kind.TABLE, "collection", schema.name(), 90)).toList();
    }

    /** The databases in the catalog (system ones only when the connection shows them). */
    private @NotNull List<Suggestion> databases() {
        if (catalog == null) {
            return List.of();
        }
        return catalog.schemas().stream()
                .map(s -> new Suggestion(s.name(), s.name(), Suggestion.Kind.SCHEMA, "database", "", 90)).toList();
    }

    /** Field names as object keys, quoted where they aren't identifiers ({@code "first name"}). */
    private @NotNull List<Suggestion> fieldKeys(@NotNull Fields fields, boolean inString) {
        TableMeta table = table(fields);
        if (table == null) {
            return List.of();
        }
        List<Suggestion> out = new ArrayList<>();
        for (ColumnMeta column : table.columns) {
            String insert = inString || isIdentifier(column.name) ? column.name : MongoValues.shell(column.name);
            out.add(new Suggestion(column.name, insert, column.primaryKey ? Suggestion.Kind.KEY_COLUMN : Suggestion.Kind.COLUMN,
                    column.typeName, table.name, 100));
        }
        return out;
    }

    private @NotNull List<Suggestion> fieldNames(@NotNull Fields fields) {
        TableMeta table = table(fields);
        if (table == null) {
            return List.of();
        }
        return table.columns.stream().map(c -> new Suggestion(c.name, c.name,
                c.primaryKey ? Suggestion.Kind.KEY_COLUMN : Suggestion.Kind.COLUMN, c.typeName, table.name, 100)).toList();
    }

    /** {@code "$name"} paths for aggregation expressions; the typed text includes the {@code $}. */
    private @NotNull List<Suggestion> fieldPaths(@NotNull Fields fields, @NotNull String prefix) {
        if (!prefix.startsWith("$")) {
            return List.of();
        }
        TableMeta table = table(fields);
        if (table == null) {
            return List.of();
        }
        return table.columns.stream().map(c -> new Suggestion("$" + c.name, "$" + c.name, Suggestion.Kind.COLUMN,
                c.typeName, table.name, 100)).toList();
    }

    private @NotNull List<Suggestion> indexNames(@NotNull Fields fields) {
        TableMeta table = table(fields);
        if (table == null) {
            return List.of();
        }
        List<Suggestion> out = new ArrayList<>();
        table.keys.forEach(k -> out.add(new Suggestion(k.name(), k.name(), Suggestion.Kind.KEYWORD, "index", table.name, 80)));
        table.indexes.forEach(i -> {
            if (out.stream().noneMatch(s -> s.lookup().equals(i.name()))) {
                out.add(new Suggestion(i.name(), i.name(), Suggestion.Kind.KEYWORD, "index", table.name, 80));
            }
        });
        return out;
    }

    private static @NotNull List<Suggestion> functions(@NotNull List<String> names, @Nullable String location) {
        List<Suggestion> out = new ArrayList<>();
        int priority = 60 + names.size();
        for (String name : names) {
            // Listed most-used first; keep that order among equally good matches.
            out.add(new Suggestion(name, name, Suggestion.Kind.FUNCTION, "", location == null ? "" : location, priority--));
        }
        return out;
    }

    private static @NotNull List<Suggestion> operators(@NotNull List<String> names, @NotNull String detail) {
        List<Suggestion> out = new ArrayList<>();
        int priority = 50 + names.size();
        for (String name : names) {
            out.add(new Suggestion(name, name, Suggestion.Kind.KEYWORD, detail, "", priority--));
        }
        return out;
    }

    private static @NotNull List<Suggestion> keywords(@NotNull List<String> words) {
        return words.stream().map(w -> keyword(w, "")).toList();
    }

    private static @NotNull Suggestion keyword(@NotNull String word, @NotNull String detail) {
        return new Suggestion(word, word, Suggestion.Kind.KEYWORD, detail, "", 40);
    }

    private static @NotNull List<Suggestion> concat(@NotNull List<Suggestion> a, @NotNull List<Suggestion> b) {
        List<Suggestion> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    private static boolean isIdentifier(@NotNull String name) {
        if (name.isEmpty() || !MongoShellParser.isIdentifierStart(name.charAt(0))) {
            return false;
        }
        for (int i = 1; i < name.length(); i++) {
            if (!MongoShellParser.isIdentifierPart(name.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ tokens

    /** Just enough of a tokenizer to follow calls and objects: words, strings, numbers, punctuation. */
    record Token(@NotNull Kind kind, @NotNull String text, int start, int end, boolean closed) {
        enum Kind { WORD, STRING, NUMBER, REGEX, PUNCT }

        static @NotNull List<Token> scan(@NotNull String s) {
            List<Token> tokens = new ArrayList<>();
            int i = 0;
            int n = s.length();
            while (i < n) {
                char c = s.charAt(i);
                if (Character.isWhitespace(c)) {
                    i++;
                } else if (s.startsWith("//", i)) {
                    int end = s.indexOf('\n', i);
                    i = end < 0 ? n : end + 1;
                } else if (s.startsWith("/*", i)) {
                    int end = s.indexOf("*/", i + 2);
                    i = end < 0 ? n : end + 2;
                } else if (c == '\'' || c == '"' || c == '`') {
                    int j = i + 1;
                    StringBuilder value = new StringBuilder();
                    boolean closed = false;
                    while (j < n) {
                        char d = s.charAt(j);
                        if (d == '\\' && j + 1 < n) {
                            value.append(s.charAt(j + 1));
                            j += 2;
                            continue;
                        }
                        if (d == c) {
                            closed = true;
                            j++;
                            break;
                        }
                        value.append(d);
                        j++;
                    }
                    tokens.add(new Token(Kind.STRING, value.toString(), i, j, closed));
                    i = j;
                } else if (c == '/' && regexAllowed(tokens)) {
                    int j = i + 1;
                    while (j < n && s.charAt(j) != '/' && s.charAt(j) != '\n') {
                        j += s.charAt(j) == '\\' ? 2 : 1;
                    }
                    j = Math.min(j + 1, n);
                    while (j < n && Character.isLetter(s.charAt(j))) {
                        j++;
                    }
                    tokens.add(new Token(Kind.REGEX, s.substring(i, j), i, j, true));
                    i = j;
                } else if (Character.isDigit(c)) {
                    int j = i + 1;
                    while (j < n && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '.')) {
                        j++;
                    }
                    tokens.add(new Token(Kind.NUMBER, s.substring(i, j), i, j, true));
                    i = j;
                } else if (MongoShellParser.isIdentifierStart(c)) {
                    int j = i + 1;
                    while (j < n && MongoShellParser.isIdentifierPart(s.charAt(j))) {
                        j++;
                    }
                    tokens.add(new Token(Kind.WORD, s.substring(i, j), i, j, true));
                    i = j;
                } else {
                    tokens.add(new Token(Kind.PUNCT, String.valueOf(c), i, i + 1, true));
                    i++;
                }
            }
            return tokens;
        }

        private static boolean regexAllowed(@NotNull List<Token> tokens) {
            if (tokens.isEmpty()) {
                return true;
            }
            Token last = tokens.getLast();
            return last.kind == Kind.PUNCT && "([{,:".contains(last.text);
        }
    }
}
