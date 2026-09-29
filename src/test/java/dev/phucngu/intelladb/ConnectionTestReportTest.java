package dev.phucngu.intelladb;

import dev.phucngu.intelladb.connection.ConnectionTestReport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConnectionTestReportTest {

    @Test
    void caseSensitivityLikePostgres() {
        // pgjdbc: unquoted folded to lower case, quoted kept as written
        assertEquals("plain=lower, delimited=exact",
                ConnectionTestReport.caseSensitivity(true, false, false, false, false, true));
    }

    @Test
    void caseSensitivityMixedWhenNothingApplies() {
        assertEquals("plain=mixed, delimited=upper",
                ConnectionTestReport.caseSensitivity(false, false, false, false, true, false));
    }

    @Test
    void summaryAndCopiedText() {
        var report = new ConnectionTestReport("PostgreSQL", "14.22 (Postgres.app)", "14.22",
                "plain=lower, delimited=exact", "PostgreSQL JDBC Driver (ver. 42.7.4, JDBC4.2)", 14, "no");
        assertEquals("PostgreSQL 14.22", report.summary());
        assertEquals("""
                DBMS: PostgreSQL (ver. 14.22 (Postgres.app))
                Case sensitivity: plain=lower, delimited=exact
                Driver: PostgreSQL JDBC Driver (ver. 42.7.4, JDBC4.2)

                Ping: 14 ms
                SSL: no
                """, report.text());
    }
}
