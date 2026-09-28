package dev.phucngu.intelladb;

import dev.phucngu.intelladb.util.SqlSplitter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SqlSplitterTest {

    @Test
    void splitsSimpleStatements() {
        List<String> result = SqlSplitter.split("SELECT 1; SELECT 2;");
        assertEquals(2, result.size());
        assertEquals("SELECT 1", result.get(0));
        assertEquals("SELECT 2", result.get(1));
    }

    @Test
    void keepsSemicolonsInsideStrings() {
        List<String> result = SqlSplitter.split("INSERT INTO t VALUES ('a;b'); SELECT 1;");
        assertEquals(2, result.size());
        assertEquals("INSERT INTO t VALUES ('a;b')", result.get(0));
    }

    @Test
    void keepsSemicolonsInsideDollarQuotes() {
        String sql = "CREATE FUNCTION f() RETURNS void AS $$ BEGIN PERFORM 1; END $$ LANGUAGE plpgsql; SELECT 2;";
        List<String> result = SqlSplitter.split(sql);
        assertEquals(2, result.size());
        assertEquals("CREATE FUNCTION f() RETURNS void AS $$ BEGIN PERFORM 1; END $$ LANGUAGE plpgsql",
                result.get(0));
    }

    @Test
    void ignoresCommentedSemicolons() {
        List<String> result = SqlSplitter.split("SELECT 1 -- trailing; comment\n; SELECT 2 /* mid; */ ;");
        assertEquals(2, result.size());
    }

    @Test
    void handlesEscapedQuotes() {
        List<String> result = SqlSplitter.split("SELECT 'it''s; fine'; SELECT 2;");
        assertEquals(2, result.size());
        assertEquals("SELECT 'it''s; fine'", result.get(0));
    }

    @Test
    void trailingStatementWithoutSemicolon() {
        List<String> result = SqlSplitter.split("SELECT 1; SELECT 2");
        assertEquals(2, result.size());
    }

    @Test
    void emptyInput() {
        assertEquals(0, SqlSplitter.split("").size());
        assertEquals(0, SqlSplitter.split("  ;  ; ").size());
    }

    @Test
    void quotedIdentifiersKept() {
        List<String> result = SqlSplitter.split("SELECT \"weird;col\" FROM t; SELECT 2;");
        assertEquals(2, result.size());
        assertEquals("SELECT \"weird;col\" FROM t", result.get(0));
    }

    @Test
    void rangesPointBackIntoTheScript() {
        String sql = "  SELECT 1;\n\nSELECT 'a;b' ;  ";
        List<SqlSplitter.Statement> ranges = SqlSplitter.ranges(sql);
        assertEquals(2, ranges.size());
        for (SqlSplitter.Statement statement : ranges) {
            assertEquals(statement.text(), sql.substring(statement.start(), statement.end()));
        }
        assertEquals("SELECT 'a;b'", ranges.get(1).text());
    }

    @Test
    void statementAtCaretPrefersContainingThenPrevious() {
        String sql = "SELECT 1;\nSELECT 2;\n\n";
        assertEquals("SELECT 1", SqlSplitter.at(sql, 3).text());
        assertEquals("SELECT 2", SqlSplitter.at(sql, sql.indexOf("2")).text());
        assertEquals("SELECT 2", SqlSplitter.at(sql, sql.length()).text()); // blank line below
        assertEquals("SELECT 1", SqlSplitter.at("\n\nSELECT 1", 0).text()); // above the first
        assertEquals(null, SqlSplitter.at("  ", 1));
    }

    @Test
    void stripsLeadingCommentsOnly() {
        assertEquals("select * from t -- tail",
                SqlSplitter.stripLeadingComments("-- SQL for localhost\n /* note */\n  select * from t -- tail"));
        assertEquals("", SqlSplitter.stripLeadingComments("-- only a comment"));
    }
}
