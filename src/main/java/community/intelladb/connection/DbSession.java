package community.intelladb.connection;

import community.intelladb.schema.MetadataLoader;
import community.intelladb.schema.SchemaCatalog;
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
        try {
            Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException e) {
            throw new SQLException("PostgreSQL JDBC driver not found", e);
        }
        Properties props = new Properties();
        if (!config.user.isBlank()) {
            props.setProperty("user", config.user);
        }
        if (password != null && !password.isBlank()) {
            props.setProperty("password", password);
        }
        props.setProperty("loginTimeout", "10");
        props.setProperty("connectTimeout", "10");
        connection = DriverManager.getConnection(dialect.jdbcUrl(config), props);
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("select version()")) {
            if (rs.next()) {
                serverVersion = rs.getString(1);
            }
        } catch (SQLException ignored) {
        }
    }

    /**
     * Executes a single statement and materializes the outcome.
     * Must not be called on the EDT.
     */
    public synchronized @NotNull SqlResult execute(@NotNull String sql) {
        long start = System.currentTimeMillis();
        try {
            ensureOpen();
            try (Statement st = connection.createStatement()) {
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
        }
    }

    private static @NotNull SqlResult materialize(@NotNull String sql, @NotNull ResultSet rs,
                                                  long duration) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int columnCount = meta.getColumnCount();
        List<String> columns = new ArrayList<>(columnCount);
        for (int i = 1; i <= columnCount; i++) {
            columns.add(meta.getColumnLabel(i));
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
                row[i - 1] = value instanceof byte[] bytes ? "<binary " + bytes.length + "B>" : value;
            }
            rows.add(row);
        }
        return SqlResult.rows(sql, columns, rows, truncated, duration);
    }

    /** Reloads the schema catalog in the background of the caller's thread. */
    public synchronized @NotNull SchemaCatalog loadCatalog() throws SQLException {
        ensureOpen();
        SchemaCatalog fresh = MetadataLoader.load(connection, dialect);
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
