package dev.phucngu.intelladb.schema;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** What an {@link ObjectsLoader} found, in a form that doesn't depend on the dialect. */
public final class CatalogObjects {

    /** Per-table objects, keyed by "namespace.table". */
    public final Map<String, List<TableMeta.Key>> keys = new HashMap<>();
    public final Map<String, List<TableMeta.ForeignKey>> foreignKeys = new HashMap<>();
    public final Map<String, List<TableMeta.Index>> indexes = new HashMap<>();
    public final Map<String, List<TableMeta.Check>> checks = new HashMap<>();
    /** Per-namespace objects, keyed by namespace name. */
    public final Map<String, List<SchemaCatalog.Routine>> routines = new HashMap<>();
    public final Map<String, List<String>> sequences = new HashMap<>();
    public final Map<String, List<SchemaCatalog.ObjectType>> objectTypes = new HashMap<>();
    public final List<String> databases = new ArrayList<>();
    public final List<SchemaCatalog.Extension> extensions = new ArrayList<>();
    public final List<String> roles = new ArrayList<>();
}
