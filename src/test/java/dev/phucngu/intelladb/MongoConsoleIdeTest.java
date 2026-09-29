package dev.phucngu.intelladb;

import com.intellij.openapi.editor.highlighter.EditorHighlighter;
import com.intellij.openapi.editor.highlighter.HighlighterIterator;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import dev.phucngu.intelladb.mongo.MongoDialect;
import dev.phucngu.intelladb.mongo.MongoShellLanguage;
import dev.phucngu.intelladb.schema.ColumnMeta;
import dev.phucngu.intelladb.schema.SchemaCatalog;
import dev.phucngu.intelladb.schema.TableMeta;
import dev.phucngu.intelladb.sql.completion.CompletionScope;
import dev.phucngu.intelladb.sql.completion.SqlCompletionContributor;

import java.util.ArrayList;
import java.util.List;

/**
 * The MongoDB console through the real IDE machinery in a headless IntelliJ: the language's
 * highlighter and parser are registered, and completion inserts methods with parentheses.
 */
public class MongoConsoleIdeTest extends BasePlatformTestCase {

    private static final SchemaCatalog CATALOG = new SchemaCatalog(List.of(new SchemaCatalog.Schema("shop", List.of(
            new TableMeta("pets", TableMeta.Kind.TABLE, List.of(
                    new ColumnMeta("_id", "objectId", false, "", 1, true, ""),
                    new ColumnMeta("name", "string", false, "", 2, false, "")), "")))));

    private void console(String text) {
        myFixture.configureByText(MongoShellLanguage.FileType.INSTANCE, text);
        myFixture.getFile().getViewProvider().getVirtualFile().putUserData(SqlCompletionContributor.SCOPE,
                () -> CompletionScope.of(new MongoDialect(), CATALOG, "shop"));
    }

    public void testCollectionThenMethodWithParentheses() {
        console("db.pe<caret>");
        myFixture.completeBasic();
        myFixture.checkResult("db.pets<caret>");
        console("db.pets.countD<caret>");
        myFixture.completeBasic();
        myFixture.checkResult("db.pets.countDocuments(<caret>)");
    }

    public void testFieldsAndOperatorsInAFilter() {
        console("db.pets.find({na<caret>");
        myFixture.completeBasic();
        myFixture.checkResult("db.pets.find({name<caret>");
        console("db.pets.find({name: {$g<caret>");
        myFixture.completeBasic();
        List<String> items = myFixture.getLookupElementStrings();
        assertNotNull(items);
        assertEquals(List.of("$gt", "$gte"), items);
    }

    public void testHighlighterKnowsOperatorsStringsAndComments() {
        console("// pets\ndb.pets.find({name: 'Rex', $or: [], n: /^r/i})");
        EditorHighlighter highlighter = ((EditorEx) myFixture.getEditor()).getHighlighter();
        List<String> tokens = new ArrayList<>();
        for (HighlighterIterator it = highlighter.createIterator(0); !it.atEnd(); it.advance()) {
            String text = myFixture.getEditor().getDocument().getText().substring(it.getStart(), it.getEnd());
            if (!text.isBlank()) {
                tokens.add(it.getTokenType() + ":" + text);
            }
        }
        assertTrue(tokens.toString(), tokens.contains("IDB_MONGO_LINE_COMMENT:// pets"));
        assertTrue(tokens.toString(), tokens.contains("IDB_MONGO_KEYWORD:db"));
        assertTrue(tokens.toString(), tokens.contains("IDB_MONGO_FUNCTION:find"));
        assertTrue(tokens.toString(), tokens.contains("IDB_MONGO_PROPERTY:name"));
        assertTrue(tokens.toString(), tokens.contains("IDB_MONGO_STRING:'Rex'"));
        assertTrue(tokens.toString(), tokens.contains("IDB_MONGO_OPERATOR_NAME:$or"));
        assertTrue(tokens.toString(), tokens.contains("IDB_MONGO_REGEX:/^r/i"));
    }
}
