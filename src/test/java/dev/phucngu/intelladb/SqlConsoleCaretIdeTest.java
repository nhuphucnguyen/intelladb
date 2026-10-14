package dev.phucngu.intelladb;

import com.intellij.openapi.editor.Editor;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import dev.phucngu.intelladb.sql.IntellaSqlFileType;
import dev.phucngu.intelladb.ui.SqlConsole;

/**
 * Where the caret lands when a console opens: the end, on a fresh line — a new console's
 * header comment must not be in the way of the first statement.
 */
public class SqlConsoleCaretIdeTest extends BasePlatformTestCase {

    public void testCaretLandsAtTheEndOfANewConsole() {
        String text = "-- SQL for postgres@localhost\n";
        myFixture.configureByText(IntellaSqlFileType.INSTANCE, text);
        Editor editor = myFixture.getEditor();
        editor.getCaretModel().moveToOffset(0); // what opening a file leaves the caret at
        SqlConsole.caretAtFreshLine(getProject(), editor);
        assertEquals(text, editor.getDocument().getText());
        assertEquals(text.length(), editor.getCaretModel().getOffset());
    }

    public void testTextWithoutATrailingNewlineGetsOne() {
        myFixture.configureByText(IntellaSqlFileType.INSTANCE, "SELECT 1;");
        Editor editor = myFixture.getEditor();
        SqlConsole.caretAtFreshLine(getProject(), editor);
        assertEquals("SELECT 1;\n", editor.getDocument().getText());
        assertEquals("SELECT 1;\n".length(), editor.getCaretModel().getOffset());
    }

    public void testAnEmptyDocumentIsLeftAlone() {
        myFixture.configureByText(IntellaSqlFileType.INSTANCE, "");
        Editor editor = myFixture.getEditor();
        SqlConsole.caretAtFreshLine(getProject(), editor);
        assertTrue(editor.getDocument().getText().isEmpty());
        assertEquals(0, editor.getCaretModel().getOffset());
    }
}
