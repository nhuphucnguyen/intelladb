package community.intelladb.connection;

import org.jetbrains.annotations.NotNull;

/**
 * A database dialect knows how to build JDBC URLs and which built-in schemas to hide.
 * PostgreSQL is the first supported dialect; the interface keeps the rest of the
 * plugin dialect-agnostic (MySQL etc. can be added without touching the UI).
 */
public interface DbDialect {

    @NotNull String id();

    @NotNull String displayName();

    int defaultPort();

    /** JDBC URL for the given connection settings. */
    @NotNull String jdbcUrl(@NotNull DbConfig config);

    /** Schemas that are part of the server itself and hidden from the tree by default. */
    @NotNull java.util.Set<String> systemSchemas();
}
