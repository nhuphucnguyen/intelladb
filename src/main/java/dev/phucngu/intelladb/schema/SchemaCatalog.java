package dev.phucngu.intelladb.schema;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Full metadata snapshot of one connection: the current database's schemas → tables →
 * columns, plus the schema-level objects (routines, sequences, types) and the
 * database/server-level ones (extensions, roles) the dialect can see.
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
        this.schemas = schemas;
        this.database = database;
        this.databases = databases;
        this.totalSchemas = totalSchemas;
        this.extensions = extensions;
        this.roles = roles;
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
