package dev.phucngu.intelladb;

import dev.phucngu.intelladb.connection.MySqlDialect;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SuggestionEngineTest {

    private static final SchemaCatalog CATALOG = new SchemaCatalog(List.of(
            new SchemaCatalog.Schema("public", List.of(
                    table("users", TableMeta.Kind.TABLE, "id", "email", "created_at"),
                    table("active_users", TableMeta.Kind.VIEW, "id", "email")),
                    List.of(new SchemaCatalog.Routine("full_name", SchemaCatalog.Routine.Kind.FUNCTION,
                                    "u users", "text"),
                            new SchemaCatalog.Routine("cleanup", SchemaCatalog.Routine.Kind.PROCEDURE, "", "")),
                    List.of(), List.of(new SchemaCatalog.ObjectType("mood", "enum"))),
            new SchemaCatalog.Schema("sales", List.of(
                    table("orders", TableMeta.Kind.TABLE, "id", "user_id", "total"),
                    table("Order Items", TableMeta.Kind.TABLE, "order_id", "sku")))));

    private static TableMeta table(String name, TableMeta.Kind kind, String... columns) {
        List<ColumnMeta> metas = new java.util.ArrayList<>();
        for (int i = 0; i < columns.length; i++) {
            metas.add(new ColumnMeta(columns[i], "int4", true, "", i + 1, i == 0, ""));
        }
        return new TableMeta(name, kind, metas, "");
    }

    private static List<Suggestion> suggest(String sql, CompletionScope scope) {
        int caret = sql.indexOf('|');
        String text = sql.substring(0, caret) + sql.substring(caret + 1);
        return SuggestionEngine.suggest(CursorAnalyzer.analyze(text, caret, scope.splitterOptions()), scope);
    }

    private static List<Suggestion> postgres(String sql) {
        return suggest(sql, CompletionScope.of(new PostgresDialect(), CATALOG, "public"));
    }

    private static Set<String> inserts(List<Suggestion> suggestions, Kind kind) {
        return suggestions.stream().filter(s -> s.kind() == kind).map(Suggestion::insertText)
                .collect(Collectors.toSet());
    }

    @Test
    void tablesOfTheCurrentSchemaPlainOthersQualified() {
        List<Suggestion> s = postgres("SELECT * FROM |");
        assertEquals(Set.of("users u", "sales.orders o", "sales.\"Order Items\" oi"), inserts(s, Kind.TABLE));
        assertEquals(Set.of("active_users au"), inserts(s, Kind.VIEW));
        assertEquals(Set.of("public", "sales"), inserts(s, Kind.SCHEMA));
        assertTrue(s.stream().filter(x -> x.insertText().equals("users u")).findFirst().orElseThrow().priority()
                > s.stream().filter(x -> x.insertText().equals("sales.orders o")).findFirst().orElseThrow().priority());
        // The bare name is still a lookup string, so typing "ord" finds sales.orders.
        assertTrue(s.stream().anyMatch(x -> x.lookup().equals("orders") && x.insertText().equals("sales.orders o")));
    }

    @Test
    void schemaQualifierListsItsTables() {
        assertEquals(Set.of("orders o", "\"Order Items\" oi"), inserts(postgres("SELECT * FROM sales.|"), Kind.TABLE));
    }

    @Test
    void aliasesOnlyWhereTheClauseTakesOne() {
        assertEquals(Set.of("users", "sales.orders", "sales.\"Order Items\""),
                inserts(postgres("INSERT INTO |"), Kind.TABLE));
        assertEquals(Set.of("users", "sales.orders", "sales.\"Order Items\""),
                inserts(postgres("UPDATE |"), Kind.TABLE));
        // An alias is already written after the word being completed.
        assertTrue(inserts(postgres("SELECT * FROM us| x WHERE x.id = 1"), Kind.TABLE).contains("users"));
        // Unless the next word is a clause keyword.
        assertTrue(inserts(postgres("SELECT * FROM us| WHERE id = 1"), Kind.TABLE).contains("users u"));
        // Switched off.
        CompletionScope plain = CompletionScope.of(new PostgresDialect(), CATALOG, "public").withTableAliases(false);
        assertTrue(inserts(suggest("SELECT * FROM |", plain), Kind.TABLE).contains("users"));
    }

    @Test
    void columnsOfTheTablesInScope() {
        List<Suggestion> s = postgres("SELECT | FROM users u JOIN sales.orders o ON o.user_id = u.id");
        assertEquals(Set.of("email", "created_at", "user_id", "total"), inserts(s, Kind.COLUMN));
        assertEquals(Set.of("id"), inserts(s, Kind.KEY_COLUMN)); // both tables' ids, two items
        assertEquals(2, s.stream().filter(x -> x.kind() == Kind.KEY_COLUMN).count());
        assertEquals(Set.of("u", "o"), inserts(s, Kind.ALIAS));
        assertTrue(inserts(s, Kind.FUNCTION).contains("STRING_AGG"));
        assertEquals(Set.of("full_name"), inserts(s, Kind.ROUTINE)); // procedures are not expressions
    }

    @Test
    void aliasTableAndSchemaQualifiedColumns() {
        assertEquals(Set.of("user_id", "total"), inserts(postgres("SELECT o.| FROM sales.orders o"), Kind.COLUMN));
        assertEquals(Set.of("email", "created_at"), inserts(postgres("SELECT users.| FROM users"), Kind.COLUMN));
        assertEquals(Set.of("sku"), inserts(postgres("SELECT sales.\"Order Items\".| FROM x"), Kind.COLUMN));
        // A table not (yet) in FROM still resolves when qualified by its name.
        assertEquals(Set.of("email", "created_at"), inserts(postgres("SELECT users.|"), Kind.COLUMN));
    }

    @Test
    void insertColumnsComeFromTheTargetOnly() {
        List<Suggestion> s = postgres("INSERT INTO sales.orders (|");
        assertEquals(Set.of("user_id", "total"), inserts(s, Kind.COLUMN));
        assertTrue(inserts(s, Kind.KEYWORD).isEmpty());
    }

    @Test
    void keywordsFollowTheTypedCase() {
        assertTrue(inserts(postgres("sel|"), Kind.KEYWORD).contains("select"));
        assertTrue(inserts(postgres("SEL|"), Kind.KEYWORD).contains("SELECT"));
        assertTrue(inserts(postgres("|"), Kind.KEYWORD).contains("SELECT"));
        assertTrue(inserts(postgres("SELECT * FROM users wh|"), Kind.KEYWORD).contains("where"));
    }

    @Test
    void typesIncludeUserDefinedOnes() {
        Set<String> types = inserts(postgres("CREATE TABLE t (id |"), Kind.TYPE);
        assertTrue(types.containsAll(Set.of("JSONB", "UUID", "mood")));
    }

    @Test
    void dialectsHaveTheirOwnWords() {
        CompletionScope mysql = CompletionScope.of(new MySqlDialect(), CATALOG, null);
        assertTrue(inserts(suggest("|", mysql), Kind.KEYWORD).containsAll(Set.of("SHOW", "USE", "DESCRIBE")));
        assertFalse(inserts(postgres("|"), Kind.KEYWORD).contains("USE"));
        assertTrue(inserts(postgres("|"), Kind.KEYWORD).contains("VACUUM"));
        assertTrue(inserts(suggest("SELECT |", mysql), Kind.FUNCTION).contains("GROUP_CONCAT"));
        assertFalse(inserts(suggest("SELECT |", mysql), Kind.FUNCTION).contains("STRING_AGG"));
    }

    @Test
    void mysqlQuotesWithBackticksAndQualifiesWithoutACurrentDatabase() {
        CompletionScope mysql = CompletionScope.of(new MySqlDialect(), CATALOG, null);
        assertTrue(inserts(suggest("SELECT * FROM |", mysql), Kind.TABLE)
                .containsAll(Set.of("public.users u", "sales.`Order Items` oi")));
        CompletionScope inSales = CompletionScope.of(new MySqlDialect(), CATALOG, "sales");
        assertTrue(inserts(suggest("SELECT * FROM |", inSales), Kind.TABLE).contains("`Order Items` oi"));
    }

    // ------------------------------------------------------------------ joins

    /** app.users ← app.orders (user_id); app.order_lines ← app.shipments (composite); app.employees → itself. */
    private static final SchemaCatalog RELATED = new SchemaCatalog(List.of(new SchemaCatalog.Schema("app", List.of(
            related("users", List.of("id", "email")),
            related("orders", List.of("id", "user_id"),
                    new TableMeta.ForeignKey("orders_user_fk", List.of("user_id"), "app", "users", List.of("id"))),
            related("order_lines", List.of("order_id", "line_no")),
            related("shipments", List.of("id", "order_id", "line_no"),
                    new TableMeta.ForeignKey("shipments_line_fk", List.of("order_id", "line_no"), "app",
                            "order_lines", List.of("order_id", "line_no"))),
            related("employees", List.of("id", "manager_id"),
                    new TableMeta.ForeignKey("employees_manager_fk", List.of("manager_id"), "app", "employees",
                            List.of("id"))),
            related("uploads", List.of("id"))))));

    private static TableMeta related(String name, List<String> columns, TableMeta.ForeignKey... foreignKeys) {
        List<ColumnMeta> metas = new java.util.ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            metas.add(new ColumnMeta(columns.get(i), "int4", false, "", i + 1, i == 0, ""));
        }
        return new TableMeta(name, TableMeta.Kind.TABLE, metas, "", List.of(), List.of(foreignKeys), List.of(),
                List.of());
    }

    private static List<Suggestion> app(String sql) {
        return suggest(sql, CompletionScope.of(new PostgresDialect(), RELATED, "app"));
    }

    @Test
    void joinSuggestsRelatedTablesWithTheirCondition() {
        List<Suggestion> s = app("SELECT * FROM users u JOIN |");
        assertEquals(Set.of("orders o ON o.user_id = u.id"), inserts(s, Kind.JOIN));
        // Referenced rather than referencing: the condition reads from the new table's side.
        assertEquals(Set.of("users u ON u.id = o.user_id"), inserts(app("SELECT * FROM orders o JOIN |"), Kind.JOIN));
        // Related joins rank above the plain table list, which still has everything (aliased, not clashing with u).
        Suggestion join = s.stream().filter(x -> x.kind() == Kind.JOIN).findFirst().orElseThrow();
        assertTrue(s.stream().filter(x -> x.kind() == Kind.TABLE).allMatch(x -> x.priority() < join.priority()));
        assertTrue(inserts(s, Kind.TABLE).containsAll(Set.of("orders o", "uploads u1", "users u1")));
    }

    @Test
    void onSuggestsTheForeignKeyCondition() {
        List<Suggestion> s = app("SELECT * FROM users u JOIN orders o ON |");
        assertEquals(Set.of("o.user_id = u.id"), inserts(s, Kind.JOIN_CONDITION));
        int top = s.stream().mapToInt(Suggestion::priority).max().orElseThrow();
        assertEquals(Kind.JOIN_CONDITION, s.stream().filter(x -> x.priority() == top).findFirst().orElseThrow().kind());
        // Columns are still offered after it.
        assertTrue(inserts(s, Kind.COLUMN).contains("user_id"));
        // Typed a little: still the condition (the IDE filters by prefix).
        assertEquals(Set.of("o.user_id = u.id"), inserts(app("SELECT * FROM users u JOIN orders o ON o|"),
                Kind.JOIN_CONDITION));
    }

    @Test
    void compositeSelfAndUnaliasedJoins() {
        assertEquals(Set.of("s.order_id = ol.order_id AND s.line_no = ol.line_no"),
                inserts(app("SELECT * FROM order_lines ol JOIN shipments s ON |"), Kind.JOIN_CONDITION));
        assertEquals(Set.of("m.manager_id = e.id", "m.id = e.manager_id"),
                inserts(app("SELECT * FROM employees e JOIN employees m ON |"), Kind.JOIN_CONDITION));
        assertEquals(Set.of("orders.user_id = users.id"),
                inserts(app("SELECT * FROM users JOIN orders ON |"), Kind.JOIN_CONDITION));
        // Only tables before the join count.
        assertTrue(inserts(app("SELECT * FROM uploads x JOIN orders o ON |"), Kind.JOIN_CONDITION).isEmpty());
    }

    @Test
    void offlineHasKeywordsButNoSchemaObjects() {
        List<Suggestion> s = suggest("SELECT * FROM |", CompletionScope.offline());
        assertTrue(s.isEmpty());
        assertTrue(inserts(suggest("SELECT * FROM t |", CompletionScope.offline()), Kind.KEYWORD).contains("WHERE"));
    }

    @Test
    void nothingInsideAString() {
        assertTrue(postgres("SELECT 'fr|'").isEmpty());
    }
}
