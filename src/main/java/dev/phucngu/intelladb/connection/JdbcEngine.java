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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The SQL dialects' engine: one JDBC connection, opened with {@link DbSession#open}. A
 * connection that browses every database ({@link DbConfig#allDatabases()}) starts on the
 * dialect's maintenance database and opens one more connection per database it is asked
 * to run on, keeping each (and its transaction) until the session closes.
 */
final class JdbcEngine implements SessionEngine {

    private final DbConfig config;
    private final DbDialect dialect;
    private final String password;
    private final boolean allDatabases;
    /** Under allDatabases: the database the session starts on. */
    private final String home;
    /** The connection opened first; under allDatabases, the one to {@link #home}. */
    private Connection homeConnection;
    /** Under allDatabases: the other databases' connections, by database. */
    private final Map<String, Connection> others = new LinkedHashMap<>();
    /** The connection calls run on. */
    private Connection connection;
    private String current;
    /** Statement currently executing, so {@link #cancel()} can interrupt it from another thread. */
    private volatile Statement running;

    JdbcEngine(@NotNull DbConfig config, @Nullable String password) {
        this.config = config;
        this.dialect = config.dialect();
        this.password = password;
        this.allDatabases = config.allDatabases();
        this.home = allDatabases ? dialect.maintenanceDatabase() : config.database;
    }

    @Override
    public boolean isOpen() {
        try {
            return homeConnection != null && !homeConnection.isClosed();
        } catch (SQLException e) {
            return false;
        }
    }

    @Override
    public @NotNull String open() throws SQLException {
        close();
        homeConnection = connection = DbSession.open(config, password, 10);
        current = home;
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
        List<Connection> all = new ArrayList<>(others.values());
        all.add(0, homeConnection);
        for (Connection each : all) {
            try (Statement st = each.createStatement()) {
                st.execute("SELECT 1");
            } catch (SQLException ignored) {
                // A dead connection shows up on the next real statement, which reconnects.
            }
        }
    }

    @Override
    public void useDatabase(@Nullable String database) throws SQLException {
        if (!allDatabases || database == null) {
            return;
        }
        String target = database.isEmpty() ? home : database;
        if (target.equals(home)) {
            connection = homeConnection;
        } else {
            Connection other = others.get(target);
            if (other == null || other.isClosed()) {
                other = DbSession.open(config.withDatabase(target), password, 10);
                others.put(target, other);
            }
            connection = other;
        }
        current = target;
    }

    @Override
    public @Nullable String database() {
        return allDatabases ? current : null;
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
        if (!allDatabases) {
            return MetadataLoader.load(connection, dialect, config.schemas, config.showSystemSchemas);
        }
        List<String> names = dialect.listDatabases(homeConnection);
        List<SchemaCatalog> loaded = new ArrayList<>();
        for (String name : names) {
            if (!config.showsDatabase(name, names)) {
                continue;
            }
            Connection open = name.equals(home) ? homeConnection : others.get(name);
            boolean temporary = open == null || open.isClosed();
            try {
                Connection used = temporary ? DbSession.open(config.withDatabase(name), password, 10) : open;
                try {
                    loaded.add(MetadataLoader.load(used, dialect, config.schemasOf(name, names), config.showSystemSchemas));
                } finally {
                    if (temporary) {
                        used.close();
                    }
                }
            } catch (SQLException ignored) {
                // A database the user may not connect to: left out, like a filtered one.
            }
        }
        return SchemaCatalog.ofDatabases(home, loaded, names);
    }

    @Override
    public void close() {
        List<Connection> all = new ArrayList<>(others.values());
        if (homeConnection != null) {
            all.add(homeConnection);
        }
        for (Connection each : all) {
            try {
                each.close();
            } catch (SQLException ignored) {
            }
        }
        others.clear();
        homeConnection = connection = null;
    }
}
