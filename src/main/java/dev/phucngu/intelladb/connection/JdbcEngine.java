package dev.phucngu.intelladb.connection;

import dev.phucngu.intelladb.schema.MetadataLoader;
import dev.phucngu.intelladb.schema.SchemaCatalog;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** The SQL dialects' engine: one JDBC connection, opened with {@link DbSession#open}. */
final class JdbcEngine implements SessionEngine {

    private final DbConfig config;
    private final DbDialect dialect;
    private final String password;
    private Connection connection;
    /** Statement currently executing, so {@link #cancel()} can interrupt it from another thread. */
    private volatile Statement running;

    JdbcEngine(@NotNull DbConfig config, @Nullable String password) {
        this.config = config;
        this.dialect = config.dialect();
        this.password = password;
    }

    @Override
    public boolean isOpen() {
        try {
            return connection != null && !connection.isClosed();
        } catch (SQLException e) {
            return false;
        }
    }

    @Override
    public @NotNull String open() throws SQLException {
        connection = DbSession.open(config, password, 10);
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("select version()")) {
            if (rs.next()) {
                String version = rs.getString(1);
                return version == null ? "" : version;
            }
        } catch (SQLException ignored) {
        }
        return "";
    }

    @Override
    public void ping() {
        if (!isOpen()) {
            return;
        }
        try (Statement st = connection.createStatement()) {
            st.execute("SELECT 1");
        } catch (SQLException ignored) {
            // A dead connection shows up on the next real statement, which reconnects.
        }
    }

    @Override
    public @NotNull SqlResult execute(@NotNull String sql) {
        long start = System.currentTimeMillis();
        try (Statement st = connection.createStatement()) {
            running = st;
            boolean hasResultSet = st.execute(sql);
            long duration = System.currentTimeMillis() - start;
            if (hasResultSet) {
                try (ResultSet rs = st.getResultSet()) {
                    return materialize(sql, rs, duration, dialect);
                }
            }
            long count = st.getLargeUpdateCount();
            return count >= 0
                    ? SqlResult.update(sql, count, duration)
                    : SqlResult.message(sql, "OK", duration);
        } catch (SQLException e) {
            return SqlResult.error(sql, e.getMessage() == null ? e.toString() : e.getMessage(),
                    System.currentTimeMillis() - start);
        } finally {
            running = null;
        }
    }

    @Override
    public void cancel() {
        Statement statement = running;
        if (statement != null) {
            try {
                statement.cancel();
            } catch (SQLException ignored) {
            }
        }
    }

    /** Per JDBC, turning auto-commit back on commits the pending transaction. */
    @Override
    public void setAutoCommit(boolean autoCommit) throws SQLException {
        if (connection.getAutoCommit() != autoCommit) {
            connection.setAutoCommit(autoCommit);
        }
    }

    @Override
    public @NotNull SqlResult commit() {
        return endTransaction("commit", true);
    }

    @Override
    public @NotNull SqlResult rollback() {
        return endTransaction("rollback", false);
    }

    private @NotNull SqlResult endTransaction(@NotNull String label, boolean commit) {
        long start = System.currentTimeMillis();
        try {
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

    /**
     * Each UPDATE / DELETE must change exactly one row, or none of them stick. With
     * auto-commit on they get their own transaction; inside a manual transaction they are
     * undone to a savepoint on failure and otherwise stay pending until the user commits.
     */
    @Override
    public @NotNull SqlResult applyRowUpdates(@NotNull List<String> statements) {
        long start = System.currentTimeMillis();
        String sql = String.join(";\n", statements);
        try {
            boolean autoCommit = connection.getAutoCommit();
            java.sql.Savepoint savepoint = autoCommit ? null : connection.setSavepoint();
            if (autoCommit) {
                connection.setAutoCommit(false);
            }
            try {
                try (Statement st = connection.createStatement()) {
                    running = st;
                    for (String update : statements) {
                        long count = st.executeLargeUpdate(update);
                        if (count != 1) {
                            throw new SQLException((count == 0 ? "No row matched (changed or deleted meanwhile?)"
                                    : count + " rows matched instead of one") + ": " + update);
                        }
                    }
                }
                if (autoCommit) {
                    connection.commit();
                } else {
                    try {
                        connection.releaseSavepoint(savepoint);
                    } catch (SQLException unsupported) {
                        // Harmless: the savepoint ends with the transaction anyway.
                    }
                }
            } catch (SQLException e) {
                try {
                    if (autoCommit) {
                        connection.rollback();
                    } else {
                        connection.rollback(savepoint);
                    }
                } catch (SQLException ignored) {
                }
                throw e;
            } finally {
                if (autoCommit) {
                    connection.setAutoCommit(true);
                }
            }
            int n = statements.size();
            return SqlResult.message(sql, n + " row" + (n == 1 ? "" : "s") + " changed"
                    + (autoCommit ? "" : " (pending: commit the transaction to keep the changes)"),
                    System.currentTimeMillis() - start);
        } catch (SQLException e) {
            return SqlResult.error(sql, e.getMessage() == null ? e.toString() : e.getMessage(),
                    System.currentTimeMillis() - start);
        } finally {
            running = null;
        }
    }

    private static @NotNull SqlResult materialize(@NotNull String sql, @NotNull ResultSet rs,
                                                  long duration, @NotNull DbDialect dialect) throws SQLException {
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
        boolean[] binary = new boolean[columnCount];
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
                    case byte[] bytes -> {
                        binary[i - 1] = true;
                        yield "<binary " + bytes.length + "B>";
                    }
                    case null -> null;
                    default -> dialect.displayValue(value);
                };
            }
            rows.add(row);
        }
        String[] source = dialect.sourceTable(meta, columnCount);
        if (source == null) {
            return SqlResult.rows(sql, columns, types, rows, truncated, duration);
        }
        List<String> sourceColumns = new ArrayList<>(columnCount);
        for (int i = 1; i <= columnCount; i++) {
            String base = binary[i - 1] ? null : dialect.baseColumnName(meta, i);
            sourceColumns.add(base == null || base.isEmpty() ? null : base);
        }
        return SqlResult.rows(sql, columns, types, rows, truncated, duration, source[0], source[1], sourceColumns);
    }

    @Override
    public @NotNull SchemaCatalog loadCatalog() throws SQLException {
        return MetadataLoader.load(connection, dialect, config.schemas, config.showSystemSchemas);
    }

    @Override
    public void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
            }
            connection = null;
        }
    }
}
