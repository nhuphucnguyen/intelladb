package dev.phucngu.intelladb.schema;

import org.jetbrains.annotations.NotNull;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * Loads the objects plain JDBC metadata can't see (or only sees one table at a time) with
 * a few dialect-specific catalog queries for all namespaces at once.
 */
public interface ObjectsLoader {

    /** @param namespaces the schemas (or, for {@link dev.phucngu.intelladb.connection.NamespaceModel#SCHEMAS_ONLY}, databases) being introspected */
    @NotNull CatalogObjects load(@NotNull Connection connection, @NotNull List<String> namespaces)
            throws SQLException;
}
