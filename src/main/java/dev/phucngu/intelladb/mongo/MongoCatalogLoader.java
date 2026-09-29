package dev.phucngu.intelladb.mongo;

import com.mongodb.MongoException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import dev.phucngu.intelladb.schema.ColumnMeta;
import dev.phucngu.intelladb.schema.SchemaCatalog;
import dev.phucngu.intelladb.schema.TableMeta;
import org.bson.Document;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MongoDB as a {@link SchemaCatalog}, so the tree, completion, the AI prompt and grid
 * editing work as for SQL: each database is a schema (there is no level above), each
 * collection a table (views and their pipeline source as views), and the fields found in a
 * sample of its documents the columns, typed by their most common BSON type. {@code _id}
 * is the primary key; unique indexes are keys, every other index an index.
 */
public final class MongoCatalogLoader {

    /** Databases the server keeps for itself, hidden unless asked for. */
    public static final Set<String> SYSTEM_DATABASES = Set.of("admin", "config", "local");
    /** Documents sampled per collection to find its fields. */
    static final int SAMPLE_SIZE = 100;

    private MongoCatalogLoader() {
    }

    public static @NotNull SchemaCatalog load(@NotNull MongoClient client, @NotNull List<String> onlyDatabases,
                                              boolean showSystem, @NotNull String fallbackDatabase) {
        List<String> all = new ArrayList<>();
        try {
            client.listDatabaseNames().forEach(all::add);
        } catch (MongoException notAllowed) {
            // Users without listDatabases see just the databases they named.
        }
        if (all.isEmpty()) {
            all.addAll(onlyDatabases.isEmpty() ? List.of(fallbackDatabase) : onlyDatabases);
        }
        all.sort(null);
        List<SchemaCatalog.Schema> schemas = new ArrayList<>();
        for (String name : all) {
            boolean included = onlyDatabases.isEmpty()
                    ? showSystem || !SYSTEM_DATABASES.contains(name)
                    : onlyDatabases.contains(name);
            if (included) {
                schemas.add(new SchemaCatalog.Schema(name, collections(client.getDatabase(name))));
            }
        }
        return new SchemaCatalog(schemas, "", List.of(), all.size(), List.of(), users(client));
    }

    private static @NotNull List<TableMeta> collections(@NotNull MongoDatabase database) {
        List<TableMeta> tables = new ArrayList<>();
        List<Document> infos = new ArrayList<>();
        try {
            database.listCollections().forEach(infos::add);
        } catch (MongoException e) {
            return tables;
        }
        infos.sort((a, b) -> a.getString("name").compareTo(b.getString("name")));
        for (Document info : infos) {
            String name = info.getString("name");
            if (name.startsWith("system.")) {
                continue; // server bookkeeping (views, profile, js)
            }
            boolean view = "view".equals(info.getString("type"));
            Document options = info.get("options", Document.class);
            String remarks = view && options != null && options.getString("viewOn") != null
                    ? "view on " + options.getString("viewOn")
                    : "timeseries".equals(info.getString("type")) ? "time series" : "";
            List<ColumnMeta> columns = sampleFields(database, name);
            List<TableMeta.Key> keys = new ArrayList<>();
            List<TableMeta.Index> indexes = new ArrayList<>();
            if (!view) {
                indexes(database, name, keys, indexes);
            }
            tables.add(new TableMeta(name, view ? TableMeta.Kind.VIEW : TableMeta.Kind.TABLE, columns, remarks,
                    keys, List.of(), indexes, List.of()));
        }
        return tables;
    }

    /** Top-level fields of the first {@link #SAMPLE_SIZE} documents, {@code _id} first, then as first seen. */
    static @NotNull List<ColumnMeta> sampleFields(@NotNull MongoDatabase database, @NotNull String collection) {
        Map<String, Map<String, Integer>> types = new LinkedHashMap<>();
        Map<String, Integer> present = new LinkedHashMap<>();
        int sampled = 0;
        try {
            for (Document document : database.getCollection(collection).find().limit(SAMPLE_SIZE)) {
                sampled++;
                for (Map.Entry<String, Object> field : document.entrySet()) {
                    types.computeIfAbsent(field.getKey(), k -> new LinkedHashMap<>())
                            .merge(MongoValues.typeName(field.getValue()), 1, Integer::sum);
                    present.merge(field.getKey(), field.getValue() == null ? 0 : 1, Integer::sum);
                }
            }
        } catch (MongoException e) {
            // unreadable (e.g. no find privilege): no fields
        }
        return fields(types, present, sampled);
    }

    static @NotNull List<ColumnMeta> fields(@NotNull Map<String, Map<String, Integer>> types,
                                            @NotNull Map<String, Integer> present, int sampled) {
        List<String> names = new ArrayList<>(types.keySet());
        if (names.remove("_id")) {
            names.addFirst("_id");
        }
        List<ColumnMeta> columns = new ArrayList<>();
        int position = 1;
        for (String name : names) {
            boolean id = name.equals("_id");
            columns.add(new ColumnMeta(name, dominantType(types.get(name)),
                    !id && present.getOrDefault(name, 0) < sampled, "", position++, id, ""));
        }
        if (columns.isEmpty()) {
            columns.add(new ColumnMeta("_id", "objectId", false, "", 1, true, ""));
        }
        return columns;
    }

    /** "string", or "int|double" when the sample mixes types (most common first); null values don't count. */
    static @NotNull String dominantType(@NotNull Map<String, Integer> counts) {
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(counts.entrySet());
        entries.removeIf(e -> e.getKey().equals("null"));
        if (entries.isEmpty()) {
            return "null";
        }
        entries.sort((a, b) -> b.getValue() - a.getValue());
        return String.join("|", entries.stream().map(Map.Entry::getKey).toList());
    }

    private static void indexes(@NotNull MongoDatabase database, @NotNull String collection,
                                @NotNull List<TableMeta.Key> keys, @NotNull List<TableMeta.Index> indexes) {
        try {
            for (Document index : database.getCollection(collection).listIndexes()) {
                String name = index.getString("name");
                Document key = index.get("key", Document.class);
                List<String> fields = new ArrayList<>();
                // What the tree shows: "age desc", "bio (text)", "loc (2dsphere)".
                List<String> shown = new ArrayList<>();
                if (key != null) {
                    key.forEach((field, direction) -> {
                        fields.add(field);
                        shown.add(direction instanceof Number n
                                ? (n.doubleValue() < 0 ? field + " desc" : field)
                                : field + " (" + direction + ")");
                    });
                }
                boolean unique = Boolean.TRUE.equals(index.getBoolean("unique"));
                if ("_id_".equals(name)) {
                    keys.add(new TableMeta.Key(name, List.of("_id"), true));
                    continue;
                }
                if (unique) {
                    keys.add(new TableMeta.Key(name, fields, false));
                }
                indexes.add(new TableMeta.Index(name, shown, unique));
            }
        } catch (MongoException e) {
            // no listIndexes privilege
        }
    }

    /** The server's users (from admin), shown under Server Objects; empty without the privilege. */
    private static @NotNull List<String> users(@NotNull MongoClient client) {
        List<String> users = new ArrayList<>();
        try {
            Document info = client.getDatabase("admin").runCommand(new Document("usersInfo", new Document("forAllDBs", true)));
            for (Document user : info.getList("users", Document.class, List.of())) {
                users.add(user.getString("user") + "@" + user.getString("db"));
            }
        } catch (MongoException | ClassCastException e) {
            // not allowed: no users shown
        }
        users.sort(null);
        return users;
    }
}
