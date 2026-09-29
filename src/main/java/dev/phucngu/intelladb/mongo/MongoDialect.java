package dev.phucngu.intelladb.mongo;

import dev.phucngu.intelladb.connection.ConnectionTestReport;
import dev.phucngu.intelladb.connection.ConsoleLanguage;
import dev.phucngu.intelladb.connection.DbConfig;
import dev.phucngu.intelladb.connection.DbDialect;
import dev.phucngu.intelladb.connection.NamespaceModel;
import dev.phucngu.intelladb.connection.SessionEngine;
import dev.phucngu.intelladb.schema.SchemaCatalog;
import dev.phucngu.intelladb.sql.RowUpdates;
import dev.phucngu.intelladb.util.SqlSplitter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.Driver;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MongoDB. It has no JDBC driver: sessions run on {@link MongoEngine} (the official Java
 * driver) and the console speaks the MongoDB shell. Databases show as schemas under the
 * connection, collections as tables, sampled fields as columns (see {@link MongoCatalogLoader}).
 */
public final class MongoDialect implements DbDialect {

    public static final String ID = "mongodb";

    @Override
    public @NotNull String id() {
        return ID;
    }

    @Override
    public @NotNull String displayName() {
        return "MongoDB";
    }

    @Override
    public int defaultPort() {
        return 27017;
    }

    @Override
    public @NotNull String jdbcUrl(@NotNull DbConfig config) {
        return MongoConnector.url(config);
    }

    @Override
    public @NotNull Driver driver() throws SQLException {
        throw new SQLException("MongoDB has no JDBC driver; its sessions use the MongoDB Java driver");
    }

    @Override
    public @NotNull SessionEngine newEngine(@NotNull DbConfig config, @Nullable String password) {
        return new MongoEngine(config, password);
    }

    @Override
    public @NotNull ConnectionTestReport testConnection(@NotNull DbConfig config, @Nullable String password)
            throws SQLException {
        return MongoConnector.testConnection(config, password);
    }

    @Override
    public @NotNull List<String> probeDatabases(@NotNull DbConfig config, @Nullable String password)
            throws SQLException {
        return MongoConnector.databaseNames(config, password);
    }

    @Override
    public @NotNull List<String> probeSchemaNames(@NotNull DbConfig config, @Nullable String password)
            throws SQLException {
        return MongoConnector.databaseNames(config, password);
    }

    @Override
    public @NotNull Set<String> systemSchemas() {
        return MongoCatalogLoader.SYSTEM_DATABASES;
    }

    @Override
    public @NotNull NamespaceModel namespaces() {
        return NamespaceModel.SCHEMAS_ONLY;
    }

    /** Names are used as they are: the shell text around them quotes them (see {@link #collection}). */
    @Override
    public @NotNull String quote(@NotNull String identifier) {
        return identifier;
    }

    @Override
    public @NotNull String useNamespaceStatement(@NotNull String namespace) {
        return "use " + namespace;
    }

    @Override
    public @NotNull SqlSplitter.Options splitterOptions() {
        return SqlSplitter.Options.MONGO;
    }

    /** "verify-full" checks the certificate and host name; "trust" accepts any certificate. */
    @Override
    public @NotNull List<String> sslModes() {
        return List.of("verify-full", "trust");
    }

    // ------------------------------------------------------------------ console

    @Override
    public @NotNull ConsoleLanguage consoleLanguage() {
        return ConsoleLanguage.MONGO_SHELL;
    }

    @Override
    public @NotNull String queryLanguage() {
        return "MongoDB shell (mongosh) commands";
    }

    @Override
    public @NotNull String codeFence() {
        return "javascript";
    }

    @Override
    public @NotNull String lineComment() {
        return "//";
    }

    @Override
    public @NotNull String previewStatement(@NotNull String schema, @NotNull String table, int rows) {
        return collection(schema, table) + ".find().limit(" + rows + ")";
    }

    /** {@code db.getSiblingDB("shop").getCollection("pets")}: works for any names. */
    static @NotNull String collection(@Nullable String database, @NotNull String collection) {
        String db = database == null ? "db" : "db.getSiblingDB(" + MongoValues.shell(database) + ")";
        return db + ".getCollection(" + MongoValues.shell(collection) + ")";
    }

    /**
     * Collections as the shell commands that make them — {@code createCollection},
     * {@code createIndex}, views with their pipeline source — each preceded by a comment
     * with its sampled fields and their types, which is what the AI assistant reads.
     */
    @Override
    public @NotNull String describeSchema(@NotNull SchemaCatalog catalog) {
        return MongoSchemaText.describe(catalog);
    }

    @Override
    public @NotNull String folderLabel(@NotNull String label) {
        return switch (label) {
            case "tables" -> "collections";
            case "columns" -> "fields";
            case "roles" -> "users";
            default -> label;
        };
    }

    // ------------------------------------------------------------------ edits

    @Override
    public boolean schemaless() {
        return true;
    }

    /**
     * {@code db.getSiblingDB("shop").getCollection("pets").updateOne({"_id": …}, {"$set": {…}})}:
     * edited text is typed like the value it replaces ({@link MongoValues#edited}); Set NULL sets null.
     */
    @Override
    public @NotNull String updateStatement(@Nullable String schema, @NotNull String table, @NotNull RowUpdates.Edit edit) {
        if (edit.changes().isEmpty() || edit.key().isEmpty()) {
            throw new IllegalArgumentException("An update needs changed fields and a key");
        }
        Map<String, Object> set = new LinkedHashMap<>();
        edit.changes().forEach((field, text) ->
                set.put(field, MongoValues.edited(text, MongoValues.value(edit.loaded().get(field)))));
        return collection(schema, table) + ".updateOne(" + filter(edit.key()) + ", "
                + MongoValues.shell(Map.of("$set", set)) + ")";
    }

    @Override
    public @NotNull String deleteStatement(@Nullable String schema, @NotNull String table, @NotNull Map<String, Object> key) {
        if (key.isEmpty()) {
            throw new IllegalArgumentException("A delete needs a key");
        }
        return collection(schema, table) + ".deleteOne(" + filter(key) + ")";
    }

    private static @NotNull String filter(@NotNull Map<String, Object> key) {
        Map<String, Object> filter = new LinkedHashMap<>();
        key.forEach((field, loaded) -> filter.put(field, MongoValues.value(loaded)));
        return MongoValues.shell(filter);
    }

    @Override
    public @NotNull String qualified(@Nullable String schema, @NotNull String table) {
        return schema == null ? table : schema + "." + table;
    }
}
