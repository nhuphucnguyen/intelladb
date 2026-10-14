package dev.phucngu.intelladb;

import dev.phucngu.intelladb.connection.PostgresDialect;
import dev.phucngu.intelladb.schema.ColumnMeta;
import dev.phucngu.intelladb.schema.SchemaCatalog;
import dev.phucngu.intelladb.schema.TableMeta;
import dev.phucngu.intelladb.sql.completion.CompletionScope;
import dev.phucngu.intelladb.sql.completion.CursorAnalyzer;
import dev.phucngu.intelladb.sql.completion.Suggestion;
import dev.phucngu.intelladb.sql.completion.Suggestion.Kind;
import dev.phucngu.intelladb.sql.completion.SuggestionEngine;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The completion behaviors IntelliJ's database tools are known for, on our engine: fuzzy joins,
 * CTEs and subquery aliases, insert column lists at once, alias suggestions, smart completion
 * and postfix templates.
 */
class CompletionParityTest {

    /** Typed tables, no declared foreign keys: joins here can only come from naming. */
    private static final SchemaCatalog FUZZY = new SchemaCatalog(List.of(
            new SchemaCatalog.Schema("public", List.of(
                    table("users", List.of(
                            column("id", "int4", true), column("email", "varchar(255)", false),
                            column("created_at", "timestamptz", false))),
                    table("orders", List.of(
                            column("id", "int4", true), column("user_id", "int4", false),
                            column("total", "numeric(10,2)", false), column("note", "varchar(100)", false)))),
                    List.of(), List.of(), List.of())));

    /** The same tables, with orders.user_id → users.id declared, and a string-keyed notes table. */
    private static final SchemaCatalog DECLARED = new SchemaCatalog(List.of(
            new SchemaCatalog.Schema("public", List.of(
                    withFks(table("users", List.of(
                            column("id", "int4", true), column("email", "varchar(255)", false))),
                            Set.of()),
                    withFks(table("orders", List.of(
                            column("id", "int4", true), column("user_id", "int4", false))),
                            Set.of(new TableMeta.ForeignKey("orders_user_fk", List.of("user_id"),
                                    "public", "users", List.of("id")))),
                    withFks(table("notes", List.of(
                            column("id", "int4", true), column("user_email", "varchar(255)", false))),
                            Set.of(new TableMeta.ForeignKey("notes_email_fk", List.of("user_email"),
                                    "public", "users", List.of("email"))))),
                    List.of(), List.of(), List.of())));

    private static ColumnMeta column(String name, String type, boolean primaryKey) {
        return new ColumnMeta(name, type, true, "", 0, primaryKey, "");
    }

    private static TableMeta table(String name, List<ColumnMeta> columns) {
        return new TableMeta(name, TableMeta.Kind.TABLE, columns, "");
    }

    private static TableMeta withFks(TableMeta t, Set<TableMeta.ForeignKey> fks) {
        return new TableMeta(t.name, t.kind, t.columns, t.remarks, List.of(), List.of(fks.toArray(TableMeta.ForeignKey[]::new)),
                List.of(), List.of());
    }

    private static List<Suggestion> suggest(String sql, SchemaCatalog catalog, boolean smart) {
        int caret = sql.indexOf('|');
        String text = sql.substring(0, caret) + sql.substring(caret + 1);
        CompletionScope scope = CompletionScope.of(new PostgresDialect(), catalog, "public");
        return SuggestionEngine.suggest(CursorAnalyzer.analyze(text, caret, scope), scope, smart);
    }

    private static List<Suggestion> basic(String sql) {
        return suggest(sql, FUZZY, false);
    }

    private static Set<String> inserts(List<Suggestion> suggestions, Kind kind) {
        return suggestions.stream().filter(s -> s.kind() == kind).map(Suggestion::insertText)
                .collect(Collectors.toSet());
    }

    /** Column suggestions of both kinds: primary-key columns get their own icon kind. */
    private static Set<String> columnsOf(List<Suggestion> suggestions) {
        return suggestions.stream()
                .filter(s -> s.kind() == Kind.COLUMN || s.kind() == Kind.KEY_COLUMN)
                .map(Suggestion::insertText).collect(Collectors.toSet());
    }

    private static List<Suggestion> smart(String sql) {
        return suggest(sql, FUZZY, true);
    }

    // ------------------------------------------------------------------ fuzzy joins

    @Test
    void joinConditionsFromNamingWhenThereIsNoForeignKey() {
        List<Suggestion> joins = basic("SELECT * FROM users u JOIN |");
        Set<String> conditions = inserts(joins, Kind.JOIN);
        assertTrue(conditions.contains(
                "orders o ON o.user_id = u.id"), conditions.toString()); // id ↔ <table>_id
        assertTrue(conditions.contains("orders o ON o.id = u.id")); // the same column name on both sides
    }

    @Test
    void nameCoincidencesRankBelowDeclaredForeignKeys() {
        List<Suggestion> declared = suggest("SELECT * FROM users u JOIN |", DECLARED, false);
        Suggestion fk = declared.stream().filter(s -> s.kind() == Kind.JOIN && s.insertText().contains("user_id"))
                .findFirst().orElseThrow();
        assertEquals(110, fk.priority());
        List<Suggestion> fuzzy = basic("SELECT * FROM users u JOIN |");
        Suggestion guess = fuzzy.stream().filter(s -> s.kind() == Kind.JOIN && s.insertText().contains("user_id"))
                .findFirst().orElseThrow();
        assertTrue(guess.priority() < fk.priority());
    }

    @Test
    void integerJoinsRankAboveStringJoins() {
        List<Suggestion> joins = suggest("SELECT * FROM users u JOIN |", DECLARED, false);
        Suggestion integer = joins.stream().filter(s -> s.kind() == Kind.JOIN && s.insertText().contains("user_id"))
                .findFirst().orElseThrow();
        Suggestion string = joins.stream().filter(s -> s.kind() == Kind.JOIN && s.insertText().contains("user_email"))
                .findFirst().orElseThrow();
        assertTrue(integer.priority() > string.priority());
    }

    @Test
    void singularizingTableNames() {
        assertEquals("purchase", SuggestionEngine.singularized("purchases"));
        assertEquals("company", SuggestionEngine.singularized("companies"));
        assertEquals("status", SuggestionEngine.singularized("status"));
        assertEquals("user", SuggestionEngine.singularized("users"));
    }

    // ------------------------------------------------------------------ CTEs and subqueries

    @Test
    void cteColumnsCompleteLikeATable() {
        Set<String> columns = columnsOf(basic(
                "WITH recent AS (SELECT id, email FROM users) SELECT | FROM recent"));
        assertEquals(Set.of("id", "email"), columns);
    }

    @Test
    void cteStarExpandsThroughTheCatalog() {
        Set<String> columns = columnsOf(basic(
                "WITH everything AS (SELECT * FROM users) SELECT e.| FROM everything e"));
        assertEquals(Set.of("id", "email", "created_at"), columns);
    }

    @Test
    void cteDeclaredColumnNamesWin() {
        Set<String> columns = columnsOf(basic(
                "WITH t(a, b) AS (SELECT id, email FROM users) SELECT | FROM t"));
        assertEquals(Set.of("a", "b"), columns);
    }

    @Test
    void subqueryAliasColumnsComplete() {
        Set<String> columns = columnsOf(basic("SELECT x.| FROM (SELECT id, email FROM users) x"));
        assertEquals(Set.of("id", "email"), columns);
        assertEquals(Set.of("n"), columnsOf(basic(
                "SELECT x.| FROM (SELECT count(*) AS n FROM users) x")));
    }

    // ------------------------------------------------------------------ column lists, aliases

    @Test
    void insertSuggestsTheWholeColumnListAtOnce() {
        Set<String> lists = inserts(basic("INSERT INTO users (|"), Kind.COLUMN_LIST);
        assertEquals(Set.of("id, email, created_at"), lists);
    }

    @Test
    void theAliasWeWouldGenerateIsSuggestedWhileTypingIt() {
        Set<String> aliases = inserts(basic("SELECT * FROM users u|"), Kind.ALIAS);
        assertEquals(Set.of("u"), aliases);
        assertTrue(inserts(basic("SELECT * FROM users em|"), Kind.ALIAS).isEmpty());
    }

    // ------------------------------------------------------------------ smart completion

    @Test
    void smartCompletionMatchesTheOtherSideOfTheComparison() {
        assertEquals(Set.of("email"), columnsOf(smart("SELECT * FROM users u WHERE u.email = |")));
        assertEquals(Set.of("id"), columnsOf(smart("SELECT * FROM users u WHERE u.id = |")));
        assertEquals(Set.of("id"), columnsOf(smart("SELECT * FROM users WHERE id = |")));
        // A literal on the other side decides by its own kind.
        assertEquals(Set.of("email"), columnsOf(smart("SELECT * FROM users WHERE 'x' = |")));
        assertEquals(Set.of("id", "user_id"), columnsOf(smart(
                "SELECT * FROM users u, orders o WHERE u.id = |")));
    }

    @Test
    void smartCompletionHasNothingWithoutAComparison() {
        assertTrue(smart("SELECT * FROM users u WHERE |").stream()
                .filter(s -> s.kind() == Kind.COLUMN || s.kind() == Kind.KEY_COLUMN).findAny().isEmpty());
    }

    // ------------------------------------------------------------------ postfix templates

    @Test
    void postfixFromRewritesTheChain() {
        assertEquals(Set.of("* from users"), inserts(basic("SELECT users.fr|"), Kind.POSTFIX));
        assertEquals(Set.of("* from u"), inserts(basic("SELECT u.fr| FROM users u"), Kind.POSTFIX));
    }

    @Test
    void postfixColumnVariants() {
        assertEquals(Set.of("id, email, created_at from users"),
                inserts(basic("SELECT users.cf|"), Kind.POSTFIX));
        List<Suggestion> cast = basic("SELECT users.ca|").stream()
                .filter(s -> s.kind() == Kind.POSTFIX).toList();
        assertEquals(1, cast.size());
        assertEquals("cast(users as )", cast.get(0).insertText());
        assertEquals(1, cast.get(0).caretBack()); // the caret lands inside the parentheses
    }

    @Test
    void postfixJoinAddsTheJoinClause() {
        Set<String> joins = inserts(suggest("SELECT users.j|", DECLARED, false), Kind.POSTFIX);
        assertTrue(joins.contains("* from users join orders o ON o.user_id = users.id"), joins.toString());
    }
}
