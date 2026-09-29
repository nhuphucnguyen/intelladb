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

    @Test
    void mysqlBacktickIdentifiersMaySpanSemicolons() {
        List<String> result = SqlSplitter.split("SELECT `a;b` FROM t; SELECT 2;", SqlSplitter.Options.MYSQL);
        assertEquals(List.of("SELECT `a;b` FROM t", "SELECT 2"), result);
        // PostgreSQL has no backtick quoting: the same text splits inside the identifier.
        assertEquals(3, SqlSplitter.split("SELECT `a;b` FROM t; SELECT 2;").size());
    }

    @Test
    void mysqlHashStartsALineComment() {
        List<String> result = SqlSplitter.split("SELECT 1 # not; a split\n; SELECT 2;", SqlSplitter.Options.MYSQL);
        assertEquals(List.of("SELECT 1 # not; a split", "SELECT 2"), result);
        assertEquals(3, SqlSplitter.split("SELECT 1 # x; y\n; SELECT 2;").size(), "# is an operator in PostgreSQL");
    }

    @Test
    void mysqlBackslashEscapesInStrings() {
        List<String> result = SqlSplitter.split("SELECT 'it\\'s; fine'; SELECT \"a\\\"b;c\";", SqlSplitter.Options.MYSQL);
        assertEquals(List.of("SELECT 'it\\'s; fine'", "SELECT \"a\\\"b;c\""), result);
        // In PostgreSQL the backslash is an ordinary character, so the string ends at the escaped quote.
        assertEquals(2, SqlSplitter.split("SELECT 'a\\'; SELECT 2").size());
    }

    @Test
    void mysqlHasNoDollarQuoting() {
        List<String> result = SqlSplitter.split("SELECT '$$'; SELECT $$ ; $$", SqlSplitter.Options.MYSQL);
        assertEquals(List.of("SELECT '$$'", "SELECT $$", "$$"), result);
    }

    @Test
    void mysqlStripsHashCommentsBeforeTheStatement() {
        assertEquals("select 1", SqlSplitter.stripLeadingComments("# note\n select 1", SqlSplitter.Options.MYSQL));
    }
}
