package dev.phucngu.intelladb;

import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.codeInsight.template.impl.TemplateSettings;
import com.intellij.codeInsight.lookup.Lookup;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import dev.phucngu.intelladb.connection.MySqlDialect;
import dev.phucngu.intelladb.connection.PostgresDialect;
import dev.phucngu.intelladb.schema.ColumnMeta;
import dev.phucngu.intelladb.schema.SchemaCatalog;
import dev.phucngu.intelladb.schema.TableMeta;
import dev.phucngu.intelladb.sql.IntellaSqlFileType;
import dev.phucngu.intelladb.sql.completion.CompletionScope;
import dev.phucngu.intelladb.sql.completion.SqlCompletionContributor;

import java.util.List;

/**
 * Completion through the real IDE machinery (contributor registration, prefix matching,
 * lookup insertion) in a headless IntelliJ, against a hand-built catalog.
 */
public class SqlCompletionIdeTest extends BasePlatformTestCase {

    private static final SchemaCatalog CATALOG = new SchemaCatalog(List.of(
            new SchemaCatalog.Schema("public", List.of(table("users", "id", "email", "created_at"))),
            new SchemaCatalog.Schema("sales", List.of(withForeignKey(table("orders", "id", "user_id", "total"),
                    new TableMeta.ForeignKey("orders_user_fk", List.of("user_id"), "public", "users",
                            List.of("id")))))));

    private static TableMeta table(String name, String... columns) {
        List<ColumnMeta> metas = new java.util.ArrayList<>();
        for (int i = 0; i < columns.length; i++) {
            metas.add(new ColumnMeta(columns[i], "int4", true, "", i + 1, i == 0, ""));
        }
        return new TableMeta(name, TableMeta.Kind.TABLE, metas, "");
    }

    private static TableMeta withForeignKey(TableMeta t, TableMeta.ForeignKey fk) {
        return new TableMeta(t.name, t.kind, t.columns, t.remarks, List.of(), List.of(fk), List.of(), List.of());
    }

    /** Opens {@code text} as a console file whose connection is {@code scope}. */
    private void console(String text, CompletionScope scope) {
        myFixture.configureByText(IntellaSqlFileType.INSTANCE, text);
        myFixture.getFile().getViewProvider().getVirtualFile().putUserData(SqlCompletionContributor.SCOPE, () -> scope);
    }

    private void postgres(String text) {
        console(text, CompletionScope.of(new PostgresDialect(), CATALOG, "public"));
    }

    public void testTablesAfterFrom() {
        postgres("SELECT * FROM <caret>");
        myFixture.completeBasic();
        List<String> items = myFixture.getLookupElementStrings();
        assertNotNull(items);
        assertTrue(items.toString(), items.containsAll(List.of("users u", "sales.orders o", "public", "sales")));
        assertEquals("users u", items.get(0)); // current schema's tables rank first
    }

    public void testBareNameFindsQualifiedTableAndAddsAnAlias() {
        postgres("SELECT * FROM ord<caret>");
        myFixture.completeBasic(); // single match: inserted right away
        myFixture.checkResult("SELECT * FROM sales.orders o<caret>");
    }

    public void testJoinInsertsTheRelatedTableWithItsCondition() {
        postgres("SELECT * FROM users u JOIN ord<caret>");
        myFixture.completeBasic();
        List<String> items = myFixture.getLookupElementStrings();
        assertNotNull(items);
        assertEquals(items.toString(), "sales.orders o ON o.user_id = u.id", items.get(0));
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR);
        myFixture.checkResult("SELECT * FROM users u JOIN sales.orders o ON o.user_id = u.id<caret>");
    }

    public void testConditionOutranksAnExactAliasMatch() {
        postgres("SELECT * FROM users u JOIN sales.orders o ON o<caret>");
        myFixture.completeBasic();
        List<String> items = myFixture.getLookupElementStrings();
        assertNotNull(items);
        assertEquals(items.toString(), "o.user_id = u.id", items.get(0));
    }

    public void testExactKeywordStillBeatsColumns() {
        postgres("SELECT * FROM users u WHERE u.id = 1 or<caret>");
        myFixture.completeBasic();
        List<String> items = myFixture.getLookupElementStrings();
        assertNotNull(items);
        assertEquals(items.toString(), "or", items.get(0));
    }

    public void testOnInsertsTheForeignKeyCondition() {
        postgres("SELECT * FROM users u JOIN sales.orders o ON <caret>");
        myFixture.completeBasic();
        List<String> items = myFixture.getLookupElementStrings();
        assertNotNull(items);
        assertEquals(items.toString(), "o.user_id = u.id", items.get(0));
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR);
        myFixture.checkResult("SELECT * FROM users u JOIN sales.orders o ON o.user_id = u.id<caret>");
    }

    public void testAliasQualifiedColumns() {
        postgres("SELECT o.<caret> FROM sales.orders o");
        myFixture.completeBasic();
        assertSameElements(myFixture.getLookupElementStrings(), "id", "user_id", "total");
    }

    public void testColumnInsertion() {
        postgres("SELECT u.em<caret> FROM users u");
        myFixture.completeBasic();
        myFixture.checkResult("SELECT u.email<caret> FROM users u");
    }

    public void testFunctionGetsParentheses() {
        postgres("SELECT string_ag<caret> FROM users");
        myFixture.completeBasic();
        myFixture.checkResult("SELECT string_agg(<caret>) FROM users");
    }

    public void testKeywordCaseFollowsTyping() {
        postgres("SELECT * FROM users wher<caret>");
        myFixture.completeBasic();
        myFixture.checkResult("SELECT * FROM users where<caret>");
    }

    public void testMySqlBackticksAndDialectWords() {
        SchemaCatalog catalog = new SchemaCatalog(List.of(
                new SchemaCatalog.Schema("shop", List.of(table("order", "id", "placed_at")))));
        console("SELECT * FROM <caret>", CompletionScope.of(new MySqlDialect(), catalog, "shop"));
        myFixture.completeBasic();
        List<String> items = myFixture.getLookupElementStrings();
        assertNotNull(items);
        assertTrue(items.toString(), items.contains("`order` o")); // a reserved word, quoted
        myFixture.getLookup().setCurrentItem(myFixture.getLookupElements()[items.indexOf("`order` o")]);
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR);
        myFixture.checkResult("SELECT * FROM `order` o<caret>");

        console("sho<caret>", CompletionScope.of(new MySqlDialect(), catalog, "shop"));
        myFixture.completeBasic();
        myFixture.checkResult("show<caret>");
    }

    public void testNoConnectionStillCompletesKeywords() {
        myFixture.configureByText(IntellaSqlFileType.INSTANCE, "SELECT * FROM t wher<caret>");
        myFixture.completeBasic();
        myFixture.checkResult("SELECT * FROM t where<caret>");
    }

    public void testPostfixTemplateRewritesThroughTheLookup() {
        postgres("SELECT users.fr<caret>");
        myFixture.completeBasic();
        if (myFixture.getLookup() != null) { // more than one match: choose the postfix item
            List<String> items = myFixture.getLookupElementStrings();
            assertNotNull(items);
            myFixture.getLookup().setCurrentItem(myFixture.getLookupElements()[items.indexOf("* from users")]);
            myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR);
        } // a single match is inserted straight away
        myFixture.checkResult("SELECT * from users<caret>");
    }

    public void testSmartCompletionSuggestsMatchingColumns() {
        SchemaCatalog catalog = new SchemaCatalog(List.of(
                new SchemaCatalog.Schema("public", List.of(typedTable("users",
                        "id:int4", "email:varchar(255)", "note:text")))));
        console("SELECT * FROM users WHERE email = <caret>",
                CompletionScope.of(new PostgresDialect(), catalog, "public"));
        myFixture.complete(CompletionType.SMART, 1);
        // text is a string category too; only the integer column is out
        assertSameElements(myFixture.getLookupElementStrings(), "email", "note");
    }

    public void testLiveTemplatesAreDiscovered() {
        TemplateSettings settings = TemplateSettings.getInstance();
        assertNotNull("the shipped SQL live templates must be found on the plugin classpath",
                settings.getTemplate("sel", "Intella DB"));
        assertEquals("select * from $table$$END$;", settings.getTemplate("sel", "Intella DB").getString());
        for (String key : List.of("selw", "selc", "lim", "ins", "upd", "del", "tab", "view", "col")) {
            assertNotNull(key + " is missing", settings.getTemplate(key, "Intella DB"));
        }
    }

    private static TableMeta typedTable(String name, String... columns) {
        List<ColumnMeta> metas = new java.util.ArrayList<>();
        for (int i = 0; i < columns.length; i++) {
            String[] parts = columns[i].split(":", 2);
            metas.add(new ColumnMeta(parts[0], parts[1], true, "", i + 1, i == 0, ""));
        }
        return new TableMeta(name, TableMeta.Kind.TABLE, metas, "");
    }
}
