package community.intelladb;

import community.intelladb.util.SqlSplitter;
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
}
