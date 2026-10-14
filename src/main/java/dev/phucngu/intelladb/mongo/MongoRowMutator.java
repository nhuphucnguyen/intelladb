package dev.phucngu.intelladb.mongo;

import dev.phucngu.intelladb.connection.DbDialect;
import dev.phucngu.intelladb.connection.capability.RowMutator;
import dev.phucngu.intelladb.sql.RowUpdates;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Writes rows back as mongosh: {@code db.getSiblingDB("shop").getCollection("pets")
 * .insertOne({…})} and friends, run by the engine's row-update unit. Inserted and edited text
 * is typed like the value it lands beside ({@link MongoValues#edited}); a deleted or updated
 * document is found by its loaded {@code _id}.
 */
public final class MongoRowMutator implements RowMutator {

    public static final MongoRowMutator INSTANCE = new MongoRowMutator();

    private MongoRowMutator() {
    }

    @Override
    public @NotNull String insert(@NotNull DbDialect dialect, @Nullable String schema, @NotNull String table,
                                  @NotNull Map<String, Object> values) {
        if (values.isEmpty()) {
            throw new IllegalArgumentException("An insert needs at least one field");
        }
        return MongoDialect.collection(schema, table) + ".insertOne(" + document(values) + ")";
    }

    @Override
    public @NotNull String update(@NotNull DbDialect dialect, @Nullable String schema, @NotNull String table,
                                  @NotNull RowUpdates.Edit edit) {
        if (edit.changes().isEmpty() || edit.key().isEmpty()) {
            throw new IllegalArgumentException("An update needs changed fields and a key");
        }
        Map<String, Object> set = new LinkedHashMap<>();
        edit.changes().forEach((field, text) ->
                set.put(field, MongoValues.edited(text, MongoValues.value(edit.loaded().get(field)))));
        return MongoDialect.collection(schema, table) + ".updateOne(" + filter(edit.key()) + ", "
                + MongoValues.shell(Map.of("$set", set)) + ")";
    }

    @Override
    public @NotNull String delete(@NotNull DbDialect dialect, @Nullable String schema, @NotNull String table,
                                  @NotNull Map<String, Object> key) {
        if (key.isEmpty()) {
            throw new IllegalArgumentException("A delete needs a key");
        }
        return MongoDialect.collection(schema, table) + ".deleteOne(" + filter(key) + ")";
    }

    private static @NotNull String filter(@NotNull Map<String, Object> key) {
        Map<String, Object> filter = new LinkedHashMap<>();
        key.forEach((field, loaded) -> filter.put(field, MongoValues.value(loaded)));
        return MongoValues.shell(filter);
    }

    /** The typed cells of a new document: field text parsed like shell input, in column order. */
    private static @NotNull String document(@NotNull Map<String, Object> values) {
        Map<String, Object> document = new LinkedHashMap<>();
        values.forEach((field, text) -> document.put(field, MongoValues.edited(text, null)));
        return MongoValues.shell(document);
    }
}
