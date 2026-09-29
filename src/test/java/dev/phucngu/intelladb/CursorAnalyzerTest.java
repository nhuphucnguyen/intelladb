package dev.phucngu.intelladb;

import dev.phucngu.intelladb.sql.completion.CursorAnalyzer;
import dev.phucngu.intelladb.sql.completion.CursorContext;
import dev.phucngu.intelladb.sql.completion.CursorContext.Clause;
import dev.phucngu.intelladb.sql.completion.CursorContext.TableReference;
import dev.phucngu.intelladb.util.SqlSplitter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CursorAnalyzerTest {

    /** Analyzes {@code sql} with the caret at the '|' marker. */
    private static CursorContext at(String sql) {
        return at(sql, SqlSplitter.Options.POSTGRES);
    }

    private static CursorContext at(String sql, SqlSplitter.Options options) {
        int caret = sql.indexOf('|');
        return CursorAnalyzer.analyze(sql.substring(0, caret) + sql.substring(caret + 1), caret, options);
    }

    @Test
    void statementStart() {
        assertEquals(Clause.STATEMENT_START, at("|").clause());
        assertEquals(Clause.STATEMENT_START, at("sel|").clause());
        assertEquals("sel", at("sel|").prefix());
        assertEquals(Clause.STATEMENT_START, at("SELECT 1;\n|").clause());
        assertEquals(Clause.STATEMENT_START, at("EXPLAIN |").clause());
    }

    @Test
    void tableClauses() {
        assertEquals(Clause.TABLE, at("SELECT * FROM |").clause());
        assertEquals(Clause.TABLE, at("SELECT * FROM us|").clause());
        assertEquals(Clause.TABLE, at("SELECT * FROM a, |").clause());
        assertEquals(Clause.TABLE, at("SELECT * FROM a JOIN |").clause());
        assertEquals(Clause.TABLE, at("UPDATE |").clause());
        assertEquals(Clause.TABLE, at("INSERT INTO |").clause());
        assertEquals(Clause.TABLE, at("DESCRIBE |", SqlSplitter.Options.MYSQL).clause());
    }

    @Test
    void keywordAfterACompleteTableReference() {
        assertEquals(Clause.KEYWORD, at("SELECT * FROM users |").clause());
        assertEquals(Clause.KEYWORD, at("SELECT * FROM users u |").clause());
        assertEquals(Clause.KEYWORD, at("INSERT INTO users |").clause());
    }

    @Test
    void expressionClauses() {
        assertEquals(Clause.EXPRESSION, at("SELECT |").clause());
        assertEquals(Clause.EXPRESSION, at("SELECT id, |").clause());
        assertEquals(Clause.EXPRESSION, at("SELECT * FROM t WHERE |").clause());
        assertEquals(Clause.EXPRESSION, at("SELECT * FROM t WHERE a = 1 AND |").clause());
        assertEquals(Clause.EXPRESSION, at("SELECT * FROM a JOIN b ON |").clause());
        assertEquals(Clause.EXPRESSION, at("SELECT * FROM t ORDER BY |").clause());
        assertEquals(Clause.EXPRESSION, at("UPDATE t SET |").clause());
        assertEquals(Clause.EXPRESSION, at("SELECT count(|").clause());
        assertEquals(Clause.EXPRESSION, at("SELECT * FROM t WHERE id IN (|").clause());
    }

    @Test
    void parenthesesBeforeTheCaretAreSkipped() {
        assertEquals(Clause.EXPRESSION, at("SELECT * FROM t WHERE x IN (SELECT id FROM u) AND |").clause());
        assertEquals(Clause.TABLE, at("SELECT * FROM (SELECT 1) s JOIN |").clause());
        assertEquals(Clause.TABLE, at("SELECT * FROM t WHERE x IN (SELECT id FROM |").clause());
    }

    @Test
    void qualifiers() {
        CursorContext column = at("SELECT u.na| FROM users u");
        assertEquals(Clause.EXPRESSION, column.clause());
        assertEquals(List.of("u"), column.qualifier());
        assertEquals("na", column.prefix());
        assertEquals(List.of("public", "users"), at("SELECT public.users.| FROM public.users").qualifier());
        CursorContext schemaTable = at("SELECT * FROM public.|");
        assertEquals(Clause.TABLE, schemaTable.clause());
        assertEquals(List.of("public"), schemaTable.qualifier());
        assertEquals(List.of("my db"), at("SELECT * FROM `my db`.|", SqlSplitter.Options.MYSQL).qualifier());
    }

    @Test
    void tableReferencesAnywhereInTheStatement() {
        assertEquals(List.of(new TableReference(null, "users", "u"), new TableReference("s", "orders", "o")),
                at("SELECT | FROM users u JOIN s.orders AS o ON o.user_id = u.id").tables());
        assertEquals(List.of(new TableReference(null, "a", null), new TableReference(null, "b", "x")),
                at("SELECT * FROM a, b x WHERE |").tables());
        assertEquals(List.of(new TableReference(null, "t", null)), at("UPDATE t SET |").tables());
        // Clause words are not aliases.
        assertEquals(List.of(new TableReference(null, "t", null)), at("SELECT * FROM t WHERE |").tables());
        // Only the statement around the caret counts.
        assertEquals(List.of(new TableReference(null, "b", null)), at("SELECT * FROM a; SELECT | FROM b").tables());
    }

    @Test
    void joinFacts() {
        CursorContext join = at("SELECT * FROM users u JOIN |");
        assertEquals("join", join.keyword());
        assertNull(join.joinTarget());
        CursorContext on = at("SELECT * FROM users u LEFT JOIN app.orders o ON |");
        assertEquals("on", on.keyword());
        assertEquals(new TableReference("app", "orders", "o"), on.joinTarget());
        assertEquals(new TableReference(null, "orders", null), at("SELECT * FROM users JOIN orders ON o|").joinTarget());
        // Past the start of the condition: no longer "the join condition comes next".
        assertNull(at("SELECT * FROM users u JOIN orders o ON o.user_id = |").joinTarget());
    }

    @Test
    void aliasFollowsTheWordBeingCompleted() {
        assertEquals(true, at("SELECT * FROM us| u").aliasFollows());
        assertEquals(true, at("SELECT * FROM us| AS u").aliasFollows());
        assertEquals(true, at("SELECT * FROM sa|.orders").aliasFollows());
        assertEquals(false, at("SELECT * FROM us| WHERE id = 1").aliasFollows());
        assertEquals(false, at("SELECT * FROM us|").aliasFollows());
        assertEquals("from", at("SELECT * FROM a, |").keyword());
    }

    @Test
    void insertColumnList() {
        CursorContext context = at("INSERT INTO app.users (id, |) VALUES (1, 2)");
        assertEquals(Clause.INSERT_COLUMNS, context.clause());
        assertEquals(new TableReference("app", "users", null), context.insertTarget());
        assertEquals(Clause.EXPRESSION, at("INSERT INTO users (id) VALUES (|").clause());
    }

    @Test
    void types() {
        assertEquals(Clause.TYPE, at("CREATE TABLE t (id |").clause());
        assertEquals(Clause.TYPE, at("CREATE TABLE IF NOT EXISTS t (id int, name |").clause());
        assertEquals(Clause.KEYWORD, at("CREATE TABLE t (id int |").clause());
        assertEquals(Clause.TYPE, at("SELECT CAST(x AS |").clause());
    }

    @Test
    void nothingInsideStringsCommentsOrAliases() {
        assertEquals(Clause.NONE, at("SELECT 'abc|'").clause());
        assertEquals(Clause.NONE, at("SELECT 'abc|").clause());
        assertEquals(Clause.NONE, at("SELECT 1 -- from |").clause());
        assertEquals(Clause.NONE, at("SELECT 1 /* from | */").clause());
        assertEquals(Clause.NONE, at("SELECT 1 # from |", SqlSplitter.Options.MYSQL).clause());
        assertEquals(Clause.NONE, at("SELECT \"Mixed|").clause());
        assertEquals(Clause.NONE, at("SELECT id AS |").clause());
        assertEquals(Clause.NONE, at("SELECT 12|").clause());
    }

    @Test
    void dialectLexing() {
        // '#' is an operator in PostgreSQL, a comment in MySQL.
        assertEquals(Clause.EXPRESSION, at("SELECT a # |").clause());
        // A ';' inside a MySQL backtick identifier does not end the statement.
        assertEquals(List.of(new TableReference(null, "a;b", null)),
                at("SELECT | FROM `a;b`", SqlSplitter.Options.MYSQL).tables());
        assertNull(at("SELECT | FROM `a;b`", SqlSplitter.Options.MYSQL).insertTarget());
    }
}
