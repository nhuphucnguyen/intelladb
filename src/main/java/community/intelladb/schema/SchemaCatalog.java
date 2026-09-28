package community.intelladb.schema;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/** Full metadata snapshot of one connection: schemas → tables → columns. */
public final class SchemaCatalog {

    public record Schema(String name, List<TableMeta> tables) {
    }

    private final List<Schema> schemas;

    public SchemaCatalog(@NotNull List<Schema> schemas) {
        this.schemas = schemas;
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
}
