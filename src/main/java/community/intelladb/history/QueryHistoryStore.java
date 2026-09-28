package community.intelladb.history;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import community.intelladb.connection.SqlResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Reads and writes the query history (entries with their cached results) as gzipped JSON.
 * Cell values come back as {@link BigDecimal} (numbers), {@link Boolean}, null, or their
 * String form (dates, UUIDs, …) — what the grid needs to show and sort them.
 */
public final class QueryHistoryStore {

    private static final int VERSION = 1;

    private final Path file;

    public QueryHistoryStore(@NotNull Path file) {
        this.file = file;
    }

    /** Newest first; empty when there is no (readable) file. */
    public @NotNull List<QueryHistory.Entry> load() {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try (Reader reader = new InputStreamReader(new GZIPInputStream(Files.newInputStream(file)),
                StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            List<QueryHistory.Entry> entries = new ArrayList<>();
            for (JsonElement element : root.getAsJsonArray("entries")) {
                entries.add(readEntry(element.getAsJsonObject()));
            }
            return entries;
        } catch (Exception e) {
            return List.of(); // corrupt or from an incompatible version: start over
        }
    }

    /** Replaces the file atomically (written to a temp file first). */
    public void save(@NotNull List<QueryHistory.Entry> entries) throws IOException {
        JsonArray array = new JsonArray();
        entries.forEach(entry -> array.add(writeEntry(entry)));
        JsonObject root = new JsonObject();
        root.addProperty("version", VERSION);
        root.add("entries", array);

        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try (Writer writer = new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(temp)),
                StandardCharsets.UTF_8)) {
            writer.write(root.toString());
        }
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    // ------------------------------------------------------------------ entries

    private static @NotNull JsonObject writeEntry(@NotNull QueryHistory.Entry entry) {
        JsonObject json = new JsonObject();
        json.addProperty("id", entry.id());
        json.addProperty("executedAt", entry.executedAt().toString());
        json.addProperty("connectionId", entry.connectionId());
        json.addProperty("connectionName", entry.connectionName());
        json.addProperty("schema", entry.schema());
        json.addProperty("sql", entry.sql());
        json.add("result", writeResult(entry.result()));
        return json;
    }

    private static @NotNull QueryHistory.Entry readEntry(@NotNull JsonObject json) {
        return new QueryHistory.Entry(json.get("id").getAsLong(),
                LocalDateTime.parse(json.get("executedAt").getAsString()),
                json.get("connectionId").getAsString(), json.get("connectionName").getAsString(),
                string(json, "schema"), json.get("sql").getAsString(),
                readResult(json.getAsJsonObject("result")));
    }

    private static @NotNull JsonObject writeResult(@NotNull SqlResult result) {
        JsonObject json = new JsonObject();
        json.addProperty("kind", result.kind.name());
        json.addProperty("sql", result.sql);
        json.addProperty("durationMs", result.durationMs);
        switch (result.kind) {
            case ROWS -> {
                json.add("columns", strings(result.columns));
                json.add("columnTypes", strings(result.columnTypes));
                json.addProperty("sourceSchema", result.sourceSchema);
                json.addProperty("sourceTable", result.sourceTable);
                json.addProperty("truncated", result.truncated);
                JsonArray rows = new JsonArray();
                for (Object[] row : result.rows) {
                    JsonArray cells = new JsonArray();
                    for (Object value : row) {
                        cells.add(writeValue(value));
                    }
                    rows.add(cells);
                }
                json.add("rows", rows);
            }
            case UPDATE_COUNT -> json.addProperty("updateCount", result.updateCount);
            case MESSAGE, ERROR -> json.addProperty("text", result.text);
        }
        return json;
    }

    private static @NotNull SqlResult readResult(@NotNull JsonObject json) {
        String sql = json.get("sql").getAsString();
        long duration = json.get("durationMs").getAsLong();
        return switch (SqlResult.Kind.valueOf(json.get("kind").getAsString())) {
            case ROWS -> {
                List<Object[]> rows = new ArrayList<>();
                for (JsonElement row : json.getAsJsonArray("rows")) {
                    JsonArray cells = row.getAsJsonArray();
                    Object[] values = new Object[cells.size()];
                    for (int i = 0; i < values.length; i++) {
                        values[i] = readValue(cells.get(i));
                    }
                    rows.add(values);
                }
                yield SqlResult.rows(sql, readStrings(json.getAsJsonArray("columns")),
                        readStrings(json.getAsJsonArray("columnTypes")), rows,
                        json.get("truncated").getAsBoolean(), duration,
                        string(json, "sourceSchema"), string(json, "sourceTable"));
            }
            case UPDATE_COUNT -> SqlResult.update(sql, json.get("updateCount").getAsLong(), duration);
            case MESSAGE -> SqlResult.message(sql, json.get("text").getAsString(), duration);
            case ERROR -> SqlResult.error(sql, json.get("text").getAsString(), duration);
        };
    }

    // ------------------------------------------------------------------ values

    private static @NotNull JsonElement writeValue(@Nullable Object value) {
        return switch (value) {
            case null -> JsonNull.INSTANCE;
            case Boolean bool -> new JsonPrimitive(bool);
            case Double d when !Double.isFinite(d) -> new JsonPrimitive(d.toString());
            case Float f when !Float.isFinite(f) -> new JsonPrimitive(f.toString());
            case Number number -> new JsonPrimitive(new BigDecimal(number.toString()));
            default -> new JsonPrimitive(String.valueOf(value));
        };
    }

    private static @Nullable Object readValue(@NotNull JsonElement element) {
        if (element.isJsonNull()) {
            return null;
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isBoolean()) {
            return primitive.getAsBoolean();
        }
        return primitive.isNumber() ? primitive.getAsBigDecimal() : primitive.getAsString();
    }

    private static @NotNull JsonArray strings(@NotNull List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }

    private static @NotNull List<String> readStrings(@NotNull JsonArray array) {
        List<String> values = new ArrayList<>(array.size());
        array.forEach(element -> values.add(element.getAsString()));
        return values;
    }

    private static @Nullable String string(@NotNull JsonObject json, @NotNull String name) {
        JsonElement element = json.get(name);
        return element == null || element.isJsonNull() ? null : element.getAsString();
    }
}
