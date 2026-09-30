package dev.phucngu.intelladb.connection;

import dev.phucngu.intelladb.schema.SchemaCatalog;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Properties;

/**
 * A live session for one {@link DbConfig}: a single physical connection (JDBC, or the
 * MongoDB driver — see {@link SessionEngine}), serialized with a monitor (a database tool
 * window never issues truly concurrent statements).
 */
public final class DbSession implements AutoCloseable {

    private final DbConfig config;
    private final DbDialect dialect;
    private final SessionEngine engine;
    private volatile SchemaCatalog catalog;
    private volatile String serverVersion = "";
    /** Whether a statement is executing (keep-alive and auto-disconnect wait for it). */
    private volatile boolean running;
    /** Last user activity (statements, introspection) — keep-alive pings don't count. */
    private volatile long lastActivity = System.currentTimeMillis();
    private volatile long lastPing = System.currentTimeMillis();

    public DbSession(@NotNull DbConfig config, @Nullable String password) {
        this.config = config;
        this.dialect = config.dialect();
        this.engine = dialect.newEngine(config, password);
    }

    public @NotNull DbConfig config() {
        return config;
    }

    public @NotNull DbDialect dialect() {
        return dialect;
    }

    public boolean isOpen() {
        return engine.isOpen();
    }

    public @NotNull String serverVersion() {
        return serverVersion;
    }

    public @Nullable SchemaCatalog catalog() {
        return catalog;
    }

    public void setCatalog(@Nullable SchemaCatalog catalog) {
        this.catalog = catalog;
    }

    /** Opens the connection if needed. Throws SQLException on failure. */
    public void ensureOpen() throws SQLException {
        if (!isOpen()) {
            serverVersion = engine.open();
        }
    }

    /**
     * Opens a JDBC connection with every connection setting applied: credentials, SSL and
     * the user's driver properties, then read-only, time zone and the startup script.
     * Shared by sessions and the dialog's Test Connection.
     */
    public static @NotNull Connection open(@NotNull DbConfig config, @Nullable String password, int timeoutSeconds)
            throws SQLException {
        DbDialect dialect = config.dialect();
        if (config.allDatabases()) {
            config = config.withDatabase(dialect.maintenanceDatabase());
        }
        Properties props = new Properties();
        props.putAll(dialect.timeoutProperties(timeoutSeconds));
        props.putAll(dialect.connectionProperties(config));
        if (!config.noAuth) {
            if (!config.user.isBlank()) {
                props.setProperty("user", config.user);
            }
            if (password != null && !password.isBlank()) {
                props.setProperty("password", password);
            }
        }
        config.driverProperties.forEach((key, value) -> {
            if (!key.isBlank()) {
                props.setProperty(key.trim(), value);
            }
        });
        String url = dialect.connectUrl(dialect.jdbcUrl(config));
        Connection connection = dialect.driver().connect(url, props);
        if (connection == null) {
            // Driver.connect returns null (instead of throwing) for a URL it doesn't handle.
            throw new SQLException(dialect.displayName() + " driver does not accept URL " + url);
        }
        try {
            if (config.readOnly) {
                connection.setReadOnly(true);
                String readOnly = dialect.readOnlyStatement();
                if (readOnly != null) {
                    try (Statement st = connection.createStatement()) {
                        st.execute(readOnly);
                    }
                }
            }
            String timeZone = config.timeZone.isBlank() ? null : dialect.timeZoneStatement(config.timeZone.trim());
            try (Statement st = connection.createStatement()) {
                if (timeZone != null) {
                    st.execute(timeZone);
                }
                for (String sql : dev.phucngu.intelladb.util.SqlSplitter.split(config.startupScript, dialect.splitterOptions())) {
                    try {
                        st.execute(sql);
                    } catch (SQLException e) {
                        throw new SQLException("Startup script failed at \"" + sql.strip() + "\": " + e.getMessage(), e);
                    }
                }
            }
            return connection;
        } catch (SQLException e) {
            connection.close();
            throw e;
        }
    }

    /** Milliseconds since the last statement or introspection. */
    public long idleMillis() {
        return System.currentTimeMillis() - lastActivity;
    }

    public long millisSincePing() {
        return System.currentTimeMillis() - Math.max(lastPing, lastActivity);
    }

    public boolean isBusy() {
        return running;
    }

    /** Keep-alive round trip; not user activity, so it doesn't hold off auto-disconnect. */
    public synchronized void ping() {
        lastPing = System.currentTimeMillis();
        engine.ping();
    }

    /**
     * Executes a single statement (SQL, or a MongoDB shell command) and materializes the
     * outcome. Must not be called on the EDT.
     */
    public @NotNull SqlResult execute(@NotNull String sql) {
        return execute(null, sql);
    }

    /**
     * As {@link #execute(String)}, on {@code database} when the connection browses every
     * database ({@link DbConfig#allDatabases()}; "" is the one it starts on), else on the
     * connection's own. The result names the database it ran on.
     */
    public synchronized @NotNull SqlResult execute(@Nullable String database, @NotNull String sql) {
        long start = System.currentTimeMillis();
        lastActivity = start;
        try {
            ensureOpen();
            engine.useDatabase(database);
            running = true;
            SqlResult result = engine.execute(sql);
            result.database = engine.database();
            return result;
        } catch (SQLException e) {
            return SqlResult.error(sql, e.getMessage() == null ? e.toString() : e.getMessage(),
                    System.currentTimeMillis() - start);
        } finally {
            running = false;
            lastActivity = System.currentTimeMillis();
        }
    }

    /**
     * Asks the server to abort the statement currently running (if any). Deliberately not
     * synchronized: {@link #execute} holds the monitor for the whole statement.
     */
    public void cancel() {
        if (running) {
            engine.cancel();
        }
    }

    /**
     * Switches between auto-commit ("Tx: Auto") and manual transactions ("Tx: Manual").
     * Turning auto-commit back on commits the pending transaction.
     */
    public void setAutoCommit(boolean autoCommit) throws SQLException {
        setAutoCommit(null, autoCommit);
    }

    /** As {@link #setAutoCommit(boolean)}, for {@code database} (see {@link #execute(String, String)}). */
    public synchronized void setAutoCommit(@Nullable String database, boolean autoCommit) throws SQLException {
        ensureOpen();
        engine.useDatabase(database);
        engine.setAutoCommit(autoCommit);
    }

    public @NotNull SqlResult commit() {
        return commit(null);
    }

    public @NotNull SqlResult rollback() {
        return rollback(null);
    }

    /** Commits the transaction on {@code database} (see {@link #execute(String, String)}). */
    public synchronized @NotNull SqlResult commit(@Nullable String database) {
        return endTransaction(database, "commit", true);
    }

    /** Rolls back the transaction on {@code database} (see {@link #execute(String, String)}). */
    public synchronized @NotNull SqlResult rollback(@Nullable String database) {
        return endTransaction(database, "rollback", false);
    }

    private @NotNull SqlResult endTransaction(@Nullable String database, @NotNull String label, boolean commit) {
        try {
            ensureOpen();
            engine.useDatabase(database);
        } catch (SQLException e) {
            return SqlResult.error(label, e.getMessage() == null ? e.toString() : e.getMessage(), 0);
        }
        return commit ? engine.commit() : engine.rollback();
    }

    /**
     * Runs the UPDATEs / DELETEs of edited grid rows as one unit: each must change exactly
     * one row, or none of them stick (see the engine for how). Must not be called on the EDT.
     */
    public @NotNull SqlResult applyRowUpdates(@NotNull List<String> statements) {
        return applyRowUpdates(null, statements);
    }

    /** As {@link #applyRowUpdates(List)}, on {@code database} (see {@link #execute(String, String)}). */
    public synchronized @NotNull SqlResult applyRowUpdates(@Nullable String database,
                                                           @NotNull List<String> statements) {
        long start = System.currentTimeMillis();
        lastActivity = start;
        try {
            ensureOpen();
            engine.useDatabase(database);
            running = true;
            return engine.applyRowUpdates(statements);
        } catch (SQLException e) {
            return SqlResult.error(String.join(";\n", statements),
                    e.getMessage() == null ? e.toString() : e.getMessage(), System.currentTimeMillis() - start);
        } finally {
            running = false;
            lastActivity = System.currentTimeMillis();
        }
    }

    /** Reloads the schema catalog in the background of the caller's thread. */
    public synchronized @NotNull SchemaCatalog loadCatalog() throws SQLException {
        ensureOpen();
        lastActivity = System.currentTimeMillis();
        SchemaCatalog fresh = engine.loadCatalog();
        catalog = fresh;
        return fresh;
    }

    @Override
    public synchronized void close() {
        engine.close();
        catalog = null;
    }
}
