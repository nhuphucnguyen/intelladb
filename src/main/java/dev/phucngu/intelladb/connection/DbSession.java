package dev.phucngu.intelladb.connection;

import dev.phucngu.intelladb.schema.MetadataLoader;
import dev.phucngu.intelladb.schema.SchemaCatalog;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * A live JDBC session for one {@link DbConfig}. Single physical connection, serialized
 * with a monitor (a database tool window never issues truly concurrent statements).
 */
public final class DbSession implements AutoCloseable {

    private final DbConfig config;
    private final DbDialect dialect;
    private final String password;
    private Connection connection;
    private volatile SchemaCatalog catalog;
    private volatile String serverVersion = "";
    /** Statement currently executing, so {@link #cancel()} can interrupt it from another thread. */
    private volatile Statement running;
    /** Last user activity (statements, introspection) — keep-alive pings don't count. */
    private volatile long lastActivity = System.currentTimeMillis();
    private volatile long lastPing = System.currentTimeMillis();

    public DbSession(@NotNull DbConfig config, @Nullable String password) {
        this.config = config;
        this.dialect = config.dialect();
        this.password = password;
    }

    public @NotNull DbConfig config() {
        return config;
    }

    public @NotNull DbDialect dialect() {
        return dialect;
    }

    public boolean isOpen() {
        try {
            return connection != null && !connection.isClosed();
        } catch (SQLException e) {
            return false;
        }
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
        if (isOpen()) {
            return;
        }
        connection = open(config, password, 10);
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("select version()")) {
            if (rs.next()) {
                serverVersion = rs.getString(1);
            }
        } catch (SQLException ignored) {
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
        dialect.loadDriver();
        Properties props = new Properties();
        props.setProperty("loginTimeout", String.valueOf(timeoutSeconds));
        props.setProperty("connectTimeout", String.valueOf(timeoutSeconds));
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
        Connection connection = DriverManager.getConnection(dialect.jdbcUrl(config), props);
        try {
            if (config.readOnly) {
                connection.setReadOnly(true);
            }
            String timeZone = config.timeZone.isBlank() ? null : dialect.timeZoneStatement(config.timeZone.trim());
            try (Statement st = connection.createStatement()) {
                if (timeZone != null) {
                    st.execute(timeZone);
                }
                for (String sql : dev.phucngu.intelladb.util.SqlSplitter.split(config.startupScript)) {
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
        return running != null;
    }

    /** Keep-alive round trip; not user activity, so it doesn't hold off auto-disconnect. */
    public synchronized void ping() {
        lastPing = System.currentTimeMillis();
        if (!isOpen()) {
            return;
        }
        try (Statement st = connection.createStatement()) {
            st.execute("SELECT 1");
        } catch (SQLException ignored) {
            // A dead connection shows up on the next real statement, which reconnects.
        }
    }

    /**
     * Executes a single statement and materializes the outcome.
     * Must not be called on the EDT.
     */
    public synchronized @NotNull SqlResult execute(@NotNull String sql) {
        long start = System.currentTimeMillis();
        lastActivity = start;
        try {
            ensureOpen();
            try (Statement st = connection.createStatement()) {
                running = st;
                boolean hasResultSet = st.execute(sql);
                long duration = System.currentTimeMillis() - start;
                if (hasResultSet) {
                    try (ResultSet rs = st.getResultSet()) {
                        return materialize(sql, rs, duration);
                    }
                }
                long count = st.getLargeUpdateCount();
                return count >= 0
                        ? SqlResult.update(sql, count, duration)
                        : SqlResult.message(sql, "OK", duration);
            }
        } catch (SQLException e) {
            return SqlResult.error(sql, e.getMessage() == null ? e.toString() : e.getMessage(),
                    System.currentTimeMillis() - start);
        } finally {
            running = null;
            lastActivity = System.currentTimeMillis();
        }
    }

    /**
     * Asks the server to abort the statement currently running (if any). Deliberately not
     * synchronized: {@link #execute} holds the monitor for the whole statement.
     */
    public void cancel() {
        Statement statement = running;
        if (statement != null) {
            try {
                statement.cancel();
            } catch (SQLException ignored) {
            }
        }
    }

    /**
     * Switches between auto-commit ("Tx: Auto") and manual transactions ("Tx: Manual").
     * Per JDBC, turning auto-commit back on commits the pending transaction.
     */
    public synchronized void setAutoCommit(boolean autoCommit) throws SQLException {
        ensureOpen();
        if (connection.getAutoCommit() != autoCommit) {
            connection.setAutoCommit(autoCommit);
        }
    }

    public synchronized @NotNull SqlResult commit() {
        return endTransaction("commit", true);
    }

    public synchronized @NotNull SqlResult rollback() {
        return endTransaction("rollback", false);
    }

    private @NotNull SqlResult endTransaction(@NotNull String label, boolean commit) {
        long start = System.currentTimeMillis();
        try {
            ensureOpen();
            if (connection.getAutoCommit()) {
                return SqlResult.message(label, "Nothing to " + label + " (auto-commit is on)", 0);
            }
            if (commit) {
                connection.commit();
            } else {
                connection.rollback();
            }
            return SqlResult.message(label, label.substring(0, 1).toUpperCase() + label.substring(1) + " completed",
                    System.currentTimeMillis() - start);
        } catch (SQLException e) {
            return SqlResult.error(label, e.getMessage() == null ? e.toString() : e.getMessage(),
                    System.currentTimeMillis() - start);
        }
    }

    private static @NotNull SqlResult materialize(@NotNull String sql, @NotNull ResultSet rs,
                                                  long duration) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int columnCount = meta.getColumnCount();
        List<String> columns = new ArrayList<>(columnCount);
        List<String> types = new ArrayList<>(columnCount);
        for (int i = 1; i <= columnCount; i++) {
            columns.add(meta.getColumnLabel(i));
            String type = meta.getColumnTypeName(i);
            types.add(type == null ? "" : type);
        }
        List<Object[]> rows = new ArrayList<>();
        boolean truncated = false;
        while (rs.next()) {
            if (rows.size() >= SqlResult.MAX_ROWS) {
                truncated = true;
                break;
            }
            Object[] row = new Object[columnCount];
            for (int i = 1; i <= columnCount; i++) {
                Object value = rs.getObject(i);
                row[i - 1] = switch (value) {
                    case byte[] bytes -> "<binary " + bytes.length + "B>";
                    case org.postgresql.util.PGobject pg -> pg.getValue();
                    case null -> null;
                    default -> value;
                };
            }
            rows.add(row);
        }
        String[] source = singleSourceTable(meta, columnCount);
        return SqlResult.rows(sql, columns, types, rows, truncated, duration, source[0], source[1]);
    }

    /**
     * {schema, table} when every column maps to the same base table (pgjdbc reports the
     * origin of plain column references), else {null, null}.
     */
    private static @NotNull String[] singleSourceTable(@NotNull ResultSetMetaData meta, int columnCount) {
        String[] none = {null, null};
        try {
            if (columnCount == 0 || !meta.isWrapperFor(org.postgresql.PGResultSetMetaData.class)) {
                return none;
            }
            var pg = meta.unwrap(org.postgresql.PGResultSetMetaData.class);
            String schema = null;
            String table = null;
            for (int i = 1; i <= columnCount; i++) {
                String columnTable = pg.getBaseTableName(i);
                String columnSchema = pg.getBaseSchemaName(i);
                if (columnTable == null || columnTable.isEmpty()) {
                    return none; // computed column
                }
                if (table == null) {
                    table = columnTable;
                    schema = columnSchema == null || columnSchema.isEmpty() ? null : columnSchema;
                } else if (!table.equals(columnTable) || !java.util.Objects.equals(schema,
                        columnSchema == null || columnSchema.isEmpty() ? null : columnSchema)) {
                    return none; // join
                }
            }
            return new String[]{schema, table};
        } catch (SQLException e) {
            return none;
        }
    }

    /** Reloads the schema catalog in the background of the caller's thread. */
    public synchronized @NotNull SchemaCatalog loadCatalog() throws SQLException {
        ensureOpen();
        lastActivity = System.currentTimeMillis();
        SchemaCatalog fresh = MetadataLoader.load(connection, dialect, config.schemas, config.showSystemSchemas);
        catalog = fresh;
        return fresh;
    }

    @Override
    public synchronized void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
            }
            connection = null;
            catalog = null;
        }
    }
}
