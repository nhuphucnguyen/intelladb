package dev.phucngu.intelladb.connection;

import dev.phucngu.intelladb.schema.SchemaCatalog;
import org.jetbrains.annotations.NotNull;

import java.sql.SQLException;
import java.util.List;

/**
 * What a {@link DbSession} runs on: JDBC for the SQL dialects ({@link JdbcEngine}), the
 * MongoDB driver for MongoDB. {@link DbSession} serializes the calls and tracks activity;
 * an engine only talks to its database. Failures are {@link SQLException}s whatever the
 * database, so callers handle one kind of error.
 */
public interface SessionEngine {

    boolean isOpen();

    /** Connects and returns the server's version text (empty when unknown). */
    @NotNull String open() throws SQLException;

    /** Keep-alive round trip; failures are ignored (the next statement reconnects). */
    void ping();

    /** Runs one statement of the console's language and materializes the outcome. */
    @NotNull SqlResult execute(@NotNull String statement);

    /** Asks the server to abort the running statement; called from another thread. */
    void cancel();

    void setAutoCommit(boolean autoCommit) throws SQLException;

    @NotNull SqlResult commit();

    @NotNull SqlResult rollback();

    /** Writes edited grid rows back, all or nothing where the database allows it. */
    @NotNull SqlResult applyRowUpdates(@NotNull List<String> statements);

    @NotNull SchemaCatalog loadCatalog() throws SQLException;

    void close();
}
