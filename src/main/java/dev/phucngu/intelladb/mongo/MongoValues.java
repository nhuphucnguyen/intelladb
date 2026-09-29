package dev.phucngu.intelladb.mongo;

import org.bson.BsonRegularExpression;
import org.bson.BsonTimestamp;
import org.bson.BsonUndefined;
import org.bson.Document;
import org.bson.types.Binary;
import org.bson.types.Code;
import org.bson.types.Decimal128;
import org.bson.types.MaxKey;
import org.bson.types.MinKey;
import org.bson.types.ObjectId;
import org.bson.types.Symbol;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * BSON values in the grid and back. A cell holds what the grid can show and sort as is —
 * strings, numbers, booleans, null — or a {@link Cell} whose text is the shell form
 * ({@code ObjectId("…")}, {@code ISODate("…")}, documents and arrays as JSON with Extended
 * JSON wrappers such as {@code {"$oid": "…"}}). Both forms parse back with
 * {@link MongoShellParser}, so an edited cell keeps its type.
 */
public final class MongoValues {

    /** A cell for a value the grid has no native form for; shows (and edits) as its shell text. */
    public record Cell(@NotNull Object value, @NotNull String text) {
        @Override
        public @NotNull String toString() {
            return text;
        }
    }

    private MongoValues() {
    }

    /** What the grid shows for a document field. */
    public static @Nullable Object cell(@Nullable Object value) {
        return switch (value) {
            case null -> null;
            case String s -> s;
            case Integer i -> i;
            case Long l -> l;
            case Double d -> d;
            case Boolean b -> b;
            case Decimal128 d -> d.isNaN() || d.isInfinite() ? new Cell(d, shell(d)) : d.bigDecimalValue();
            case BsonUndefined u -> null;
            default -> new Cell(value, shell(value));
        };
    }

    /** The BSON value behind a grid cell (a loaded one, not an edited text). */
    public static @Nullable Object value(@Nullable Object cell) {
        return switch (cell) {
            case Cell c -> c.value();
            case BigDecimal d -> new Decimal128(d);
            case null, default -> cell;
        };
    }

    /**
     * The value an edited cell text stands for, typed like the value it replaces: text stays
     * text where the field held a string, otherwise it is read as a shell value
     * ({@code 42}, {@code true}, {@code ISODate("…")}, {@code {"a": 1}}) — falling back to
     * the text itself when it isn't one.
     */
    public static @Nullable Object edited(@Nullable Object newText, @Nullable Object loaded) {
        if (newText == null) {
            return null; // Set NULL
        }
        String text = String.valueOf(newText);
        if (loaded instanceof String) {
            return text;
        }
        Object parsed;
        try {
            parsed = MongoShellParser.parseValue(text);
        } catch (RuntimeException notAValue) {
            return text;
        }
        // Keep a number's type: 5 typed over a long stays a long, 2 over a double a double,
        // 2.50 over a decimal the exact decimal.
        if (parsed instanceof Number n) {
            if (loaded instanceof BigDecimal) {
                try {
                    return new Decimal128(new BigDecimal(text.trim()));
                } catch (NumberFormatException hex) {
                    return new Decimal128(new BigDecimal(n.toString()));
                }
            }
            boolean whole = !(parsed instanceof Double d) || isWhole(d);
            if (loaded instanceof Long && whole) {
                return n.longValue();
            }
            if (loaded instanceof Double) {
                return n.doubleValue();
            }
        }
        return parsed;
    }

    private static boolean isWhole(double d) {
        return d == Math.rint(d) && !Double.isInfinite(d);
    }

    /** The BSON type name of a value, as {@code $type} reports it ("objectId", "string", "int"…). */
    public static @NotNull String typeName(@Nullable Object value) {
        return switch (value) {
            case null -> "null";
            case String s -> "string";
            case Integer i -> "int";
            case Long l -> "long";
            case Double d -> "double";
            case Decimal128 d -> "decimal";
            case Boolean b -> "bool";
            case Date d -> "date";
            case ObjectId o -> "objectId";
            case Document d -> "object";
            case Map<?, ?> m -> "object";
            case List<?> l -> "array";
            case Binary b -> "binData";
            case UUID u -> "binData";
            case BsonRegularExpression r -> "regex";
            case BsonTimestamp t -> "timestamp";
            case MinKey m -> "minKey";
            case MaxKey m -> "maxKey";
            case Code c -> "javascript";
            case Symbol s -> "symbol";
            default -> value.getClass().getSimpleName();
        };
    }

    /** Whether the grid shows a value only as a size (like a SQL blob) and can't edit it. */
    public static boolean isBinary(@Nullable Object value) {
        return value instanceof Binary b && b.getType() != 4 && b.getType() != 3;
    }

    // ------------------------------------------------------------------ shell text

    /** The value as mongosh would type it: {@code ObjectId("…")}, {@code NumberLong("5")}, JSON for documents. */
    public static @NotNull String shell(@Nullable Object value) {
        StringBuilder sb = new StringBuilder();
        writeShell(sb, value);
        return sb.toString();
    }

    private static void writeShell(@NotNull StringBuilder sb, @Nullable Object value) {
        switch (value) {
            case null -> sb.append("null");
            case ObjectId o -> sb.append("ObjectId(\"").append(o.toHexString()).append("\")");
            case Date d -> sb.append("ISODate(\"").append(d.toInstant()).append("\")");
            case Long l -> sb.append("NumberLong(\"").append(l).append("\")");
            case Decimal128 d -> sb.append("NumberDecimal(\"").append(d).append("\")");
            case UUID u -> sb.append("UUID(\"").append(u).append("\")");
            case Binary b -> {
                if (b.getType() == 4 && b.getData().length == 16) {
                    sb.append("UUID(\"").append(uuid(b.getData())).append("\")");
                } else if (isBinary(b) && b.getData().length > 1024) {
                    sb.append("<binary ").append(b.getData().length).append("B>");
                } else {
                    sb.append("BinData(").append(b.getType()).append(", \"")
                            .append(Base64.getEncoder().encodeToString(b.getData())).append("\")");
                }
            }
            case BsonRegularExpression r -> {
                sb.append("RegExp(");
                writeString(sb, r.getPattern());
                sb.append(", ");
                writeString(sb, r.getOptions());
                sb.append(')');
            }
            case BsonTimestamp t -> sb.append("Timestamp(").append(t.getTime()).append(", ").append(t.getInc()).append(')');
            case MinKey m -> sb.append("MinKey()");
            case MaxKey m -> sb.append("MaxKey()");
            case Map<?, ?> map -> writeJson(sb, map);
            case List<?> list -> writeJson(sb, list);
            default -> writeJson(sb, value);
        }
    }

    // ------------------------------------------------------------------ JSON

    /**
     * Documents and arrays as JSON a JSON viewer accepts, with Extended JSON wrappers for the
     * values JSON has no type for ({@code {"$oid": "…"}}, {@code {"$date": "…"}},
     * {@code {"$numberLong": "5"}}…), so reading it back gives the same types.
     */
    public static @NotNull String json(@Nullable Object value) {
        StringBuilder sb = new StringBuilder();
        writeJson(sb, value);
        return sb.toString();
    }

    private static void writeJson(@NotNull StringBuilder sb, @Nullable Object value) {
        switch (value) {
            case null -> sb.append("null");
            case String s -> writeString(sb, s);
            case Boolean b -> sb.append(b);
            case Integer i -> sb.append(i);
            case Double d -> {
                if (d.isNaN() || d.isInfinite()) {
                    sb.append("{\"$numberDouble\": \"").append(d.isNaN() ? "NaN" : d > 0 ? "Infinity" : "-Infinity").append("\"}");
                } else if (isWhole(d) && Math.abs(d) < 1e15) {
                    sb.append(d.longValue()).append(".0"); // stays a double when read back
                } else {
                    sb.append(d);
                }
            }
            case Long l -> sb.append("{\"$numberLong\": \"").append(l).append("\"}");
            case Decimal128 d -> sb.append("{\"$numberDecimal\": \"").append(d).append("\"}");
            case ObjectId o -> sb.append("{\"$oid\": \"").append(o.toHexString()).append("\"}");
            case Date d -> sb.append("{\"$date\": \"").append(d.toInstant()).append("\"}");
            case UUID u -> sb.append("{\"$uuid\": \"").append(u).append("\"}");
            case Binary b -> {
                if (b.getType() == 4 && b.getData().length == 16) {
                    sb.append("{\"$uuid\": \"").append(uuid(b.getData())).append("\"}");
                } else {
                    sb.append("{\"$binary\": {\"base64\": \"").append(Base64.getEncoder().encodeToString(b.getData()))
                            .append("\", \"subType\": \"").append(String.format("%02x", b.getType())).append("\"}}");
                }
            }
            case BsonRegularExpression r -> {
                sb.append("{\"$regularExpression\": {\"pattern\": ");
                writeString(sb, r.getPattern());
                sb.append(", \"options\": ");
                writeString(sb, r.getOptions());
                sb.append("}}");
            }
            case BsonTimestamp t -> sb.append("{\"$timestamp\": {\"t\": ").append(t.getTime())
                    .append(", \"i\": ").append(t.getInc()).append("}}");
            case MinKey m -> sb.append("{\"$minKey\": 1}");
            case MaxKey m -> sb.append("{\"$maxKey\": 1}");
            case Map<?, ?> map -> {
                sb.append('{');
                String separator = "";
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    sb.append(separator);
                    writeString(sb, String.valueOf(entry.getKey()));
                    sb.append(": ");
                    writeJson(sb, entry.getValue());
                    separator = ", ";
                }
                sb.append('}');
            }
            case List<?> list -> {
                sb.append('[');
                String separator = "";
                for (Object item : list) {
                    sb.append(separator);
                    writeJson(sb, item);
                    separator = ", ";
                }
                sb.append(']');
            }
            case Code c -> writeString(sb, c.getCode());
            case Symbol s -> writeString(sb, s.getSymbol());
            case BsonUndefined u -> sb.append("null");
            default -> writeString(sb, String.valueOf(value));
        }
    }

    private static void writeString(@NotNull StringBuilder sb, @NotNull String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    private static @NotNull UUID uuid(byte @NotNull [] bytes) {
        long msb = 0;
        long lsb = 0;
        for (int i = 0; i < 8; i++) {
            msb = (msb << 8) | (bytes[i] & 0xff);
            lsb = (lsb << 8) | (bytes[i + 8] & 0xff);
        }
        return new UUID(msb, lsb);
    }
}
