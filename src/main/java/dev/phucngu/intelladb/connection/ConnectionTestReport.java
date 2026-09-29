package dev.phucngu.intelladb.connection;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.List;

/** What Test Connection found out about the server, for the dialog's result balloon. */
public record ConnectionTestReport(@NotNull String productName, @NotNull String productVersion,
                                   @NotNull String shortVersion, @NotNull String caseSensitivity,
                                   @NotNull String driver, long pingMillis, @NotNull String ssl) {

    /** Reads the report from a freshly opened connection. */
    public static @NotNull ConnectionTestReport probe(@NotNull Connection connection, @NotNull DbDialect dialect)
            throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        long start = System.nanoTime();
        connection.isValid(5);
        long ping = (System.nanoTime() - start) / 1_000_000;
        String ssl;
        try {
            ssl = dialect.sslStatus(connection);
        } catch (SQLException e) {
            ssl = null;
        }
        return new ConnectionTestReport(
                meta.getDatabaseProductName(),
                meta.getDatabaseProductVersion(),
                meta.getDatabaseMajorVersion() + "." + meta.getDatabaseMinorVersion(),
                caseSensitivity(meta.storesLowerCaseIdentifiers(), meta.storesUpperCaseIdentifiers(),
                        meta.supportsMixedCaseIdentifiers(),
                        meta.storesLowerCaseQuotedIdentifiers(), meta.storesUpperCaseQuotedIdentifiers(),
                        meta.supportsMixedCaseQuotedIdentifiers()),
                meta.getDriverName() + " (ver. " + meta.getDriverVersion()
                        + ", JDBC" + meta.getJDBCMajorVersion() + "." + meta.getJDBCMinorVersion() + ")",
                ping,
                ssl == null ? "unknown" : ssl);
    }

    /** "plain=lower, delimited=exact": how unquoted and quoted identifiers are matched. */
    public static @NotNull String caseSensitivity(boolean plainLower, boolean plainUpper, boolean plainExact,
                                                  boolean quotedLower, boolean quotedUpper, boolean quotedExact) {
        return "plain=" + caseRule(plainLower, plainUpper, plainExact)
                + ", delimited=" + caseRule(quotedLower, quotedUpper, quotedExact);
    }

    private static String caseRule(boolean lower, boolean upper, boolean exact) {
        return exact ? "exact" : lower ? "lower" : upper ? "upper" : "mixed";
    }

    /** Label next to the Test Connection link, e.g. "PostgreSQL 14.22". */
    public @NotNull String summary() {
        return productName + " " + shortVersion;
    }

    /** The balloon's lines; a null entry is a blank separator line. */
    public @NotNull List<String> lines() {
        return java.util.Arrays.asList(
                "DBMS: " + productName + " (ver. " + productVersion + ")",
                "Case sensitivity: " + caseSensitivity,
                "Driver: " + driver,
                null,
                "Ping: " + pingMillis + " ms",
                "SSL: " + ssl);
    }

    /** What Copy puts on the clipboard. */
    public @NotNull String text() {
        StringBuilder text = new StringBuilder();
        for (String line : lines()) {
            text.append(line == null ? "" : line).append('\n');
        }
        return text.toString();
    }
}
