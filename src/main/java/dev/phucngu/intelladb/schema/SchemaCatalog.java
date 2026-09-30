package dev.phucngu.intelladb.schema;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Full metadata snapshot of one connection: the current database's schemas → tables →
 * columns, plus the schema-level objects (routines, sequences, types) and the
 * database/server-level ones (extensions, roles) the dialect can see. A connection that
 * browses every database has one catalog per database ({@link #databaseCatalogs()}); its
 * own schemas are then those of the database the session starts on.
 */
public final class SchemaCatalog {

    public record Schema(String name, List<TableMeta> tables, List<Routine> routines,
                         List<String> sequences, List<ObjectType> objectTypes) {
        public Schema(String name, List<TableMeta> tables) {
            this(name, tables, List.of(), List.of(), List.of());
        }

        public @NotNull List<TableMeta> tablesOf(TableMeta.@NotNull Kind kind) {
            return tables.stream().filter(t -> t.kind == kind).toList();
        }

        public @NotNull List<Routine> routinesOf(boolean aggregates) {
            return routines.stream().filter(r -> (r.kind() == Routine.Kind.AGGREGATE) == aggregates).toList();
        }
    }

    /** A function, procedure or aggregate; {@code arguments} and {@code returns} are display text. */
    public record Routine(String name, Kind kind, String arguments, String returns) {
        public enum Kind { FUNCTION, PROCEDURE, AGGREGATE }
    }

    /** A user-defined type; {@code kind} is display text such as "enum" or "composite". */
    public record ObjectType(String name, String kind) {
    }

    /** An installed extension (a database-level object). */
    public record Extension(String name, String version) {
    }

    private final List<Schema> schemas;
    private final String database;
    private final List<String> databases;
    private final int totalSchemas;
    private final List<Extension> extensions;
    private final List<String> roles;
    private final List<SchemaCatalog> databaseCatalogs;

    public SchemaCatalog(@NotNull List<Schema> schemas) {
        this(schemas, "", List.of(), schemas.size(), List.of(), List.of());
    }

    /**
     * @param database     the connected database; its schemas are the ones in {@code schemas}
     * @param databases    every database on the server (the connected one included)
     * @param totalSchemas schema count of the connected database, system schemas included
     */
    public SchemaCatalog(@NotNull List<Schema> schemas, @NotNull String database, @NotNull List<String> databases,
                         int totalSchemas, @NotNull List<Extension> extensions, @NotNull List<String> roles) {
        this(schemas, database, databases, totalSchemas, extensions, roles, List.of());
    }

    private SchemaCatalog(@NotNull List<Schema> schemas, @NotNull String database, @NotNull List<String> databases,
                          int totalSchemas, @NotNull List<Extension> extensions, @NotNull List<String> roles,
                          @NotNull List<SchemaCatalog> databaseCatalogs) {
        this.databaseCatalogs = databaseCatalogs;
        this.schemas = schemas;
        this.database = database;
        this.databases = databases;
        this.totalSchemas = totalSchemas;
        this.extensions = extensions;
        this.roles = roles;
    }

    /**
     * The catalog of a connection that browses every database.
     *
     * @param home      the database the session starts on
     * @param loaded    one catalog per introspected database
     * @param databases every database on the server
     */
    public static @NotNull SchemaCatalog ofDatabases(@NotNull String home, @NotNull List<SchemaCatalog> loaded,
                                                     @NotNull List<String> databases) {
        SchemaCatalog own = loaded.stream().filter(c -> c.database.equals(home)).findFirst().orElse(null);
        List<String> roles = loaded.isEmpty() ? List.of() : loaded.get(0).roles; // server-wide, the same everywhere
        return own == null
                ? new SchemaCatalog(List.of(), home, databases, 0, List.of(), roles, List.copyOf(loaded))
                : new SchemaCatalog(own.schemas, home, databases, own.totalSchemas, own.extensions, roles,
                List.copyOf(loaded));
    }

    /** One catalog per introspected database when the connection browses every database; else empty. */
    public @NotNull List<SchemaCatalog> databaseCatalogs() {
        return databaseCatalogs;
    }

    /**
     * The catalog of {@code database} when the connection browses every database (empty
     * when it isn't introspected); this catalog for null or a connection bound to one.
     */
    public @NotNull SchemaCatalog forDatabase(@Nullable String database) {
        if (database == null || databaseCatalogs.isEmpty()) {
            return this;
        }
        String name = database.isEmpty() ? this.database : database;
        return databaseCatalogs.stream().filter(c -> c.database.equals(name)).findFirst()
                .orElseGet(() -> new SchemaCatalog(List.of(), name, databases, 0, List.of(), roles));
    }

    public @NotNull List<Schema> schemas() {
        return schemas;
    }

    public boolean isEmpty() {
        return schemas.isEmpty();
    }

    /** All tables/views across schemas, qualified as schema.name — used for AI context. */
    public @NotNull List<TableMeta> allTables() {
        return schemas.stream().flatMap(s -> s.tables().stream()).toList();
    }

    public @NotNull List<String> schemaNames() {
        return schemas.stream().map(Schema::name).toList();
    }

    public @NotNull String database() {
        return database;
    }

    public @NotNull List<String> databases() {
        return databases;
    }

    public int totalSchemas() {
        return totalSchemas;
    }

    public @NotNull List<Extension> extensions() {
        return extensions;
    }

    public @NotNull List<String> roles() {
        return roles;
    }
}
