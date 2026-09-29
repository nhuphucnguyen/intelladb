package dev.phucngu.intelladb.mongo;

import org.bson.BsonRegularExpression;
import org.bson.BsonTimestamp;
import org.bson.Document;
import org.bson.types.Binary;
import org.bson.types.Decimal128;
import org.bson.types.MaxKey;
import org.bson.types.MinKey;
import org.bson.types.ObjectId;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Parses the MongoDB shell (mongosh) commands the console runs — {@code use db},
 * {@code show collections}, {@code db.runCommand(…)}, {@code db.pets.find({…}).sort({…})…}
 * — and the JavaScript-ish values in them: relaxed object literals (unquoted keys, single
 * quotes, trailing commas, comments), shell constructors ({@code ObjectId("…")},
 * {@code ISODate("…")}, {@code NumberLong(…)}…), regex literals and Extended JSON wrappers
 * ({@code {"$oid": "…"}}), all turned into the values the Java driver writes.
 * <p>
 * Numbers follow the shell: a whole number that fits an int is an int (as mongosh stores
 * it), a bigger one a long, anything with a fraction or exponent a double.
 */
public final class MongoShellParser {

    /** A call in the chain: {@code find({…})}, {@code sort({…})}. */
    public record Call(@NotNull String name, @NotNull List<Object> args) {
        public @Nullable Object arg(int index) {
            return index < args.size() ? args.get(index) : null;
        }
    }

    /** One console statement. */
    public sealed interface Command permits Use, Show, DatabaseCall, CollectionCall {
    }

    /** {@code use shop}. */
    public record Use(@NotNull String database) implements Command {
    }

    /** {@code show dbs}, {@code show collections}… */
    public record Show(@NotNull String what) implements Command {
    }

    /** {@code db.runCommand({…})}; {@code database} is null for the current one. */
    public record DatabaseCall(@Nullable String database, @NotNull Call call) implements Command {
    }

    /**
     * {@code db.pets.find({…}).sort({…}).limit(5)}: the first call on the collection and the
     * cursor calls chained after it; {@code database} is null for the current one.
     */
    public record CollectionCall(@Nullable String database, @NotNull String collection, @NotNull Call call,
                                 @NotNull List<Call> chain) implements Command {
    }

    /** What went wrong and where (0-based offset into the statement). */
    public static final class SyntaxError extends RuntimeException {
        public final int offset;

        SyntaxError(@NotNull String message, int offset) {
            super(message);
            this.offset = offset;
        }
    }

    /** Methods called on {@code db} itself; any other name after {@code db.} is a collection. */
    private static final java.util.Set<String> DATABASE_METHODS = java.util.Set.of(
            "adminCommand", "aggregate", "createCollection", "createView", "currentOp", "dropDatabase",
            "getCollectionInfos", "getCollectionNames", "getName", "getUsers", "hostInfo", "killOp",
            "listCommands", "runCommand", "serverStatus", "stats", "version");

    private final String text;
    private int pos;

    private MongoShellParser(@NotNull String text) {
        this.text = text;
    }

    /** Parses one statement; a trailing {@code ;} is allowed. */
    public static @NotNull Command parseCommand(@NotNull String statement) {
        MongoShellParser parser = new MongoShellParser(statement);
        Command command = parser.command();
        parser.skipSpace();
        if (parser.peek() == ';') {
            parser.pos++;
            parser.skipSpace();
        }
        if (!parser.atEnd()) {
            throw parser.error("Unexpected text after the command");
        }
        return command;
    }

    /** Parses a single value, e.g. what the user typed into a grid cell. */
    public static @Nullable Object parseValue(@NotNull String value) {
        MongoShellParser parser = new MongoShellParser(value);
        parser.skipSpace();
        Object result = parser.value();
        parser.skipSpace();
        if (!parser.atEnd()) {
            throw parser.error("Unexpected text after the value");
        }
        return result;
    }

    // ------------------------------------------------------------------ commands

    private @NotNull Command command() {
        skipSpace();
        int start = pos;
        String word = identifier();
        if (word == null) {
            throw error("Expected a command such as db.collection.find()");
        }
        switch (word) {
            case "use" -> {
                skipSpace();
                String database = wordUntilEnd();
                if (database.isEmpty()) {
                    throw error("use needs a database name");
                }
                return new Use(database);
            }
            case "show" -> {
                skipSpace();
                String what = wordUntilEnd();
                if (what.isEmpty()) {
                    throw error("show needs what to show: dbs, collections, users");
                }
                return new Show(what);
            }
            case "db" -> {
                return afterDb();
            }
            default -> {
                pos = start;
                throw error("Expected a command starting with db, use or show");
            }
        }
    }

    /** The rest after {@code db}: a database method, or a collection and its calls. */
    private @NotNull Command afterDb() {
        String database = null;
        while (true) {
            String name;
            skipSpace();
            if (peek() == '[') {
                pos++;
                skipSpace();
                name = string();
                skipSpace();
                expect(']');
                return collectionCalls(database, name);
            }
            expect('.');
            skipSpace();
            int nameStart = pos;
            name = identifier();
            if (name == null) {
                throw error("Expected a collection or method name after db.");
            }
            skipSpace();
            if (peek() != '(') {
                // A collection: db.pets.find(), or db.system.profile.find() — dotted names.
                StringBuilder collection = new StringBuilder(name);
                while (true) {
                    int mark = pos;
                    skipSpace();
                    if (peek() != '.') {
                        pos = mark;
                        throw error("Expected a method call on " + collection + ", e.g. " + collection + ".find()");
                    }
                    pos++;
                    skipSpace();
                    int partStart = pos;
                    String part = identifier();
                    if (part == null) {
                        throw error("Expected a method name");
                    }
                    skipSpace();
                    if (peek() == '(') {
                        pos = partStart;
                        return collectionMethods(database, collection.toString());
                    }
                    collection.append('.').append(part);
                }
            }
            List<Object> args = arguments();
            switch (name) {
                case "getSiblingDB", "getDB" -> {
                    database = stringArg(name, args);
                    continue;
                }
                case "getCollection" -> {
                    return collectionCalls(database, stringArg(name, args));
                }
                default -> {
                    if (!DATABASE_METHODS.contains(name)) {
                        pos = nameStart;
                        throw error("Unknown database method db." + name + "()");
                    }
                    skipSpace();
                    return new DatabaseCall(database, new Call(name, args));
                }
            }
        }
    }

    /** After {@code db.getCollection("x")} or {@code db["x"]}: {@code .method(…)} and the chain. */
    private @NotNull Command collectionCalls(@Nullable String database, @NotNull String collection) {
        skipSpace();
        expect('.');
        skipSpace();
        return collectionMethods(database, collection);
    }

    private @NotNull Command collectionMethods(@Nullable String database, @NotNull String collection) {
        Call first = call();
        List<Call> chain = new ArrayList<>();
        while (true) {
            int mark = pos;
            skipSpace();
            if (peek() != '.') {
                pos = mark;
                break;
            }
            pos++;
            skipSpace();
            chain.add(call());
        }
        return new CollectionCall(database, collection, first, chain);
    }

    private @NotNull Call call() {
        String name = identifier();
        if (name == null) {
            throw error("Expected a method name");
        }
        skipSpace();
        if (peek() != '(') {
            throw error("Expected ( after " + name);
        }
        return new Call(name, arguments());
    }

    private @NotNull List<Object> arguments() {
        expect('(');
        List<Object> args = new ArrayList<>();
        skipSpace();
        if (peek() == ')') {
            pos++;
            return args;
        }
        while (true) {
            skipSpace();
            args.add(value());
            skipSpace();
            char c = peek();
            if (c == ',') {
                pos++;
                skipSpace();
                if (peek() == ')') { // trailing comma
                    pos++;
                    return args;
                }
            } else if (c == ')') {
                pos++;
                return args;
            } else {
                throw error("Expected , or ) in the argument list");
            }
        }
    }

    private @NotNull String stringArg(@NotNull String method, @NotNull List<Object> args) {
        if (args.size() != 1 || !(args.getFirst() instanceof String s)) {
            throw error(method + "() takes one name in quotes");
        }
        return s;
    }

    /** The rest of a {@code use} / {@code show} line, up to a ';' or the end. */
    private @NotNull String wordUntilEnd() {
        int start = pos;
        while (!atEnd() && peek() != ';' && !Character.isWhitespace(peek())) {
            pos++;
        }
        return text.substring(start, pos);
    }

    // ------------------------------------------------------------------ values

    private @Nullable Object value() {
        skipSpace();
        if (atEnd()) {
            throw error("Expected a value");
        }
        char c = peek();
        return switch (c) {
            case '{' -> document();
            case '[' -> array();
            case '\'', '"', '`' -> string();
            case '/' -> regex();
            default -> {
                if (c == '-' || c == '+' || c == '.' || Character.isDigit(c)) {
                    yield number();
                }
                String word = identifier();
                if (word == null) {
                    throw error("Unexpected character '" + c + "'");
                }
                yield word(word);
            }
        };
    }

    private @NotNull Object document() {
        expect('{');
        Document document = new Document();
        while (true) {
            skipSpace();
            if (peek() == '}') {
                pos++;
                return ExtendedJson.unwrap(document);
            }
            String key = key();
            skipSpace();
            expect(':');
            document.put(key, value());
            skipSpace();
            char c = peek();
            if (c == ',') {
                pos++;
            } else if (c != '}') {
                throw error("Expected , or } in the object");
            }
        }
    }

    private @NotNull String key() {
        char c = peek();
        if (c == '\'' || c == '"' || c == '`') {
            return string();
        }
        if (Character.isDigit(c)) {
            int start = pos;
            while (!atEnd() && Character.isDigit(peek())) {
                pos++;
            }
            return text.substring(start, pos);
        }
        String key = identifier();
        if (key == null) {
            throw error("Expected a field name");
        }
        return key;
    }

    private @NotNull List<Object> array() {
        expect('[');
        List<Object> list = new ArrayList<>();
        while (true) {
            skipSpace();
            if (peek() == ']') {
                pos++;
                return list;
            }
            list.add(value());
            skipSpace();
            char c = peek();
            if (c == ',') {
                pos++;
            } else if (c != ']') {
                throw error("Expected , or ] in the array");
            }
        }
    }

    private @NotNull String string() {
        char quote = peek();
        if (quote != '\'' && quote != '"' && quote != '`') {
            throw error("Expected a string in quotes");
        }
        int start = pos;
        pos++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (atEnd()) {
                pos = start;
                throw error("Unterminated string");
            }
            char c = text.charAt(pos++);
            if (c == quote) {
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (atEnd()) {
                continue;
            }
            char e = text.charAt(pos++);
            switch (e) {
                case 'n' -> sb.append('\n');
                case 't' -> sb.append('\t');
                case 'r' -> sb.append('\r');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'v' -> sb.append('\u000B');
                case '0' -> sb.append('\0');
                case 'x' -> sb.append((char) hex(2));
                case 'u' -> {
                    if (peek() == '{') {
                        pos++;
                        int close = text.indexOf('}', pos);
                        if (close < 0) {
                            throw error("Unterminated \\u{…} escape");
                        }
                        sb.appendCodePoint(Integer.parseInt(text.substring(pos, close), 16));
                        pos = close + 1;
                    } else {
                        sb.append((char) hex(4));
                    }
                }
                case '\n' -> {
                    // line continuation
                }
                default -> sb.append(e);
            }
        }
    }

    private int hex(int digits) {
        if (pos + digits > text.length()) {
            throw error("Bad escape");
        }
        try {
            int value = Integer.parseInt(text.substring(pos, pos + digits), 16);
            pos += digits;
            return value;
        } catch (NumberFormatException e) {
            throw error("Bad escape");
        }
    }

    private @NotNull Object number() {
        int start = pos;
        boolean negative = false;
        if (peek() == '-' || peek() == '+') {
            negative = peek() == '-';
            pos++;
            skipSpace();
            String word = peekIdentifier();
            if ("Infinity".equals(word)) {
                pos += word.length();
                return negative ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
            }
        }
        if (text.startsWith("0x", pos) || text.startsWith("0X", pos)) {
            pos += 2;
            int digits = pos;
            while (!atEnd() && Character.digit(peek(), 16) >= 0) {
                pos++;
            }
            long value = Long.parseLong(text.substring(digits, pos), 16);
            return narrow(negative ? -value : value);
        }
        int digitsStart = pos;
        boolean fraction = false;
        while (!atEnd()) {
            char c = peek();
            if (Character.isDigit(c) || c == '_') {
                pos++;
            } else if (c == '.' && !fraction) {
                fraction = true;
                pos++;
            } else if ((c == 'e' || c == 'E') && pos > digitsStart) {
                fraction = true;
                pos++;
                if (peek() == '+' || peek() == '-') {
                    pos++;
                }
            } else {
                break;
            }
        }
        String literal = text.substring(digitsStart, pos).replace("_", "");
        if (literal.isEmpty() || literal.equals(".")) {
            pos = start;
            throw error("Expected a number");
        }
        if (fraction) {
            double value = Double.parseDouble(literal);
            return negative ? -value : value;
        }
        try {
            long value = Long.parseLong(literal);
            return narrow(negative ? -value : value);
        } catch (NumberFormatException tooBig) {
            double value = Double.parseDouble(literal);
            return negative ? -value : value;
        }
    }

    private static @NotNull Object narrow(long value) {
        return value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE ? (Object) (int) value : (Object) value;
    }

    private @NotNull Object regex() {
        int start = pos;
        pos++; // opening '/'
        StringBuilder pattern = new StringBuilder();
        boolean inClass = false;
        while (true) {
            if (atEnd() || peek() == '\n') {
                pos = start;
                throw error("Unterminated regular expression");
            }
            char c = text.charAt(pos++);
            if (c == '\\' && !atEnd()) {
                pattern.append(c).append(text.charAt(pos++));
                continue;
            }
            if (c == '[') {
                inClass = true;
            } else if (c == ']') {
                inClass = false;
            } else if (c == '/' && !inClass) {
                break;
            }
            pattern.append(c);
        }
        int flagsStart = pos;
        while (!atEnd() && Character.isLetter(peek())) {
            pos++;
        }
        return new BsonRegularExpression(pattern.toString(), text.substring(flagsStart, pos));
    }

    /** A bare word: a literal, or a shell constructor such as {@code ObjectId("…")}. */
    private @Nullable Object word(@NotNull String word) {
        switch (word) {
            case "true":
                return true;
            case "false":
                return false;
            case "null", "undefined":
                return null;
            case "NaN":
                return Double.NaN;
            case "Infinity":
                return Double.POSITIVE_INFINITY;
            case "MinKey":
                optionalEmptyCall();
                return new MinKey();
            case "MaxKey":
                optionalEmptyCall();
                return new MaxKey();
            case "new": {
                skipSpace();
                String type = identifier();
                if (type == null) {
                    throw error("Expected a type after new");
                }
                return constructor(type);
            }
            default:
                return constructor(word);
        }
    }

    private void optionalEmptyCall() {
        int mark = pos;
        skipSpace();
        if (peek() == '(') {
            List<Object> args = arguments();
            if (!args.isEmpty()) {
                throw error("MinKey / MaxKey take no arguments");
            }
        } else {
            pos = mark;
        }
    }

    private @NotNull Object constructor(@NotNull String type) {
        int start = pos;
        skipSpace();
        if (peek() != '(') {
            pos = start;
            throw error("Unknown name " + type + " (strings need quotes)");
        }
        List<Object> args = arguments();
        Object first = args.isEmpty() ? null : args.getFirst();
        try {
            return switch (type) {
                case "ObjectId" -> first == null ? new ObjectId() : new ObjectId(String.valueOf(first));
                case "ISODate", "Date" -> first == null ? new Date() : date(first);
                case "NumberLong", "Long" -> Long.parseLong(numberText(first));
                case "NumberInt", "Int32" -> Integer.parseInt(numberText(first));
                case "Double" -> Double.parseDouble(numberText(first));
                case "NumberDecimal", "Decimal128" -> new Decimal128(new BigDecimal(numberText(first)));
                case "UUID" -> first == null ? UUID.randomUUID() : UUID.fromString(String.valueOf(first));
                case "BinData" -> new Binary(((Number) first).byteValue(),
                        Base64.getDecoder().decode(String.valueOf(args.get(1))));
                case "HexData" -> new Binary(((Number) first).byteValue(),
                        HexFormat.of().parseHex(String.valueOf(args.get(1))));
                case "Timestamp" -> timestamp(args);
                case "RegExp" -> new BsonRegularExpression(String.valueOf(first),
                        args.size() > 1 ? String.valueOf(args.get(1)) : "");
                default -> throw new IllegalArgumentException("Unknown constructor " + type + "()");
            };
        } catch (SyntaxError e) {
            throw e;
        } catch (RuntimeException e) {
            pos = start;
            throw error(type + "(): " + (e.getMessage() == null ? "bad arguments" : e.getMessage()));
        }
    }

    private static @NotNull String numberText(@Nullable Object value) {
        if (value == null) {
            return "0";
        }
        if (value instanceof Double d && d == Math.rint(d) && !Double.isInfinite(d)) {
            return String.valueOf(d.longValue());
        }
        return String.valueOf(value).trim();
    }

    private static @NotNull Date date(@NotNull Object value) {
        if (value instanceof Number n) {
            return new Date(n.longValue());
        }
        return new Date(parseInstant(String.valueOf(value)).toEpochMilli());
    }

    /** ISO-8601 in the forms mongosh accepts: full, without zone (UTC), or just a date. */
    static @NotNull Instant parseInstant(@NotNull String text) {
        String s = text.trim();
        try {
            return OffsetDateTime.parse(s).toInstant();
        } catch (DateTimeParseException ignored) {
        }
        try {
            return Instant.parse(s);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return java.time.LocalDateTime.parse(s).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDate.parse(s).atStartOfDay().toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("not an ISO date: " + text);
        }
    }

    private static @NotNull BsonTimestamp timestamp(@NotNull List<Object> args) {
        if (args.size() == 1 && args.getFirst() instanceof Document d) {
            return new BsonTimestamp(((Number) d.get("t")).intValue(), ((Number) d.get("i")).intValue());
        }
        if (args.size() == 2) {
            return new BsonTimestamp(((Number) args.get(0)).intValue(), ((Number) args.get(1)).intValue());
        }
        return new BsonTimestamp();
    }

    // ------------------------------------------------------------------ lexing helpers

    private @Nullable String identifier() {
        String word = peekIdentifier();
        if (word != null) {
            pos += word.length();
        }
        return word;
    }

    private @Nullable String peekIdentifier() {
        if (atEnd() || !isIdentifierStart(peek())) {
            return null;
        }
        int end = pos + 1;
        while (end < text.length() && isIdentifierPart(text.charAt(end))) {
            end++;
        }
        return text.substring(pos, end);
    }

    static boolean isIdentifierStart(char c) {
        return Character.isLetter(c) || c == '_' || c == '$';
    }

    static boolean isIdentifierPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    /** Skips whitespace and // and /* comments. */
    private void skipSpace() {
        while (!atEnd()) {
            char c = peek();
            if (Character.isWhitespace(c)) {
                pos++;
            } else if (text.startsWith("//", pos)) {
                int end = text.indexOf('\n', pos);
                pos = end < 0 ? text.length() : end + 1;
            } else if (text.startsWith("/*", pos)) {
                int end = text.indexOf("*/", pos + 2);
                pos = end < 0 ? text.length() : end + 2;
            } else {
                return;
            }
        }
    }

    private void expect(char c) {
        if (peek() != c) {
            throw error("Expected '" + c + "'");
        }
        pos++;
    }

    private char peek() {
        return atEnd() ? '\0' : text.charAt(pos);
    }

    private boolean atEnd() {
        return pos >= text.length();
    }

    private @NotNull SyntaxError error(@NotNull String message) {
        int line = 1;
        int column = 1;
        for (int i = 0; i < Math.min(pos, text.length()); i++) {
            if (text.charAt(i) == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }
        }
        String near = atEnd() ? "end of input" : "'" + text.substring(pos, Math.min(text.length(), pos + 20)).strip() + "'";
        return new SyntaxError(message + " (line " + line + ", column " + column + ", at " + near + ")", pos);
    }

    /** Extended JSON wrappers — {@code {"$oid": "…"}} and friends — as the values they stand for. */
    static final class ExtendedJson {
        static @NotNull Object unwrap(@NotNull Document d) {
            if (d.size() == 1) {
                String key = d.keySet().iterator().next();
                Object v = d.get(key);
                try {
                    switch (key) {
                        case "$oid":
                            if (v instanceof String s) {
                                return new ObjectId(s);
                            }
                            break;
                        case "$date":
                            if (v instanceof String s) {
                                return new Date(parseInstant(s).toEpochMilli());
                            }
                            if (v instanceof Number n) {
                                return new Date(n.longValue());
                            }
                            if (v instanceof Long l) {
                                return new Date(l);
                            }
                            break;
                        case "$numberLong":
                            return Long.parseLong(String.valueOf(v));
                        case "$numberInt":
                            return Integer.parseInt(String.valueOf(v));
                        case "$numberDouble":
                            return Double.parseDouble(String.valueOf(v));
                        case "$numberDecimal":
                            return new Decimal128(new BigDecimal(String.valueOf(v)));
                        case "$uuid":
                            return UUID.fromString(String.valueOf(v));
                        case "$minKey":
                            return new MinKey();
                        case "$maxKey":
                            return new MaxKey();
                        case "$binary":
                            if (v instanceof Document b && b.get("base64") instanceof String data) {
                                return new Binary((byte) Integer.parseInt(String.valueOf(b.get("subType")), 16),
                                        Base64.getDecoder().decode(data));
                            }
                            break;
                        case "$regularExpression":
                            if (v instanceof Document r) {
                                return new BsonRegularExpression(String.valueOf(r.get("pattern")),
                                        String.valueOf(r.getOrDefault("options", "")));
                            }
                            break;
                        case "$timestamp":
                            if (v instanceof Document t) {
                                return new BsonTimestamp(((Number) t.get("t")).intValue(), ((Number) t.get("i")).intValue());
                            }
                            break;
                        default:
                            break;
                    }
                } catch (RuntimeException notAWrapper) {
                    // e.g. {$oid: "not hex"}: keep it as the document it is
                }
            }
            return d;
        }

        private ExtendedJson() {
        }
    }
}
