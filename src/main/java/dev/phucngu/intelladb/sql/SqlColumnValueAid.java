package dev.phucngu.intelladb.sql;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Caret;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.editor.event.CaretEvent;
import com.intellij.openapi.editor.event.CaretListener;
import com.intellij.openapi.editor.markup.HighlighterLayer;
import com.intellij.openapi.editor.markup.HighlighterTargetArea;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.editor.colors.EditorColors;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.injected.editor.DocumentWindow;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * "Which value belongs to which column" aid for INSERT statements. Active in .sql files,
 * Liquibase XML (including injected &lt;sql&gt; fragments) and the plugin's SQL console:
 * <ul>
 *   <li>caret on a column in the column list → that column and the matching value in every
 *       VALUES tuple are highlighted (and vice versa: caret on a value → its column);</li>
 *   <li>while the caret is inside an INSERT with 3+ columns, inline hints render the
 *       column name before each value.</li>
 * </ul>
 * Everything is editor-level (no PSI), so it works regardless of which SQL plugin parses the file.
 */
public final class SqlColumnValueAid implements CaretListener, DocumentListener {

    /** Marks the plugin's console documents as SQL. */
    public static final Key<Boolean> CONSOLE_DOCUMENT = Key.create("IntellaDb.consoleSqlDocument");

    private static final Key<State> STATE = Key.create("IntellaDb.sqlAidState");
    private static final int MAX_DOCUMENT_LENGTH = 200_000;
    private static final int MIN_COLUMNS_FOR_INLAYS = 3;
    private static final int MAX_INLAYS = 400;

    private static final SqlColumnValueAid INSTANCE = new SqlColumnValueAid();

    public static SqlColumnValueAid getInstance() {
        return INSTANCE;
    }

    private SqlColumnValueAid() {
    }

    /** Registers the multicaster listeners; called once per opened project. */
    public void attach(@NotNull com.intellij.openapi.project.Project project) {
        var multicaster = com.intellij.openapi.editor.EditorFactory.getInstance().getEventMulticaster();
        multicaster.addCaretListener(this, project);
        multicaster.addDocumentListener(this, project);
    }

    @Override
    public void caretPositionChanged(@NotNull CaretEvent event) {
        process(event.getEditor());
    }

    @Override
    public void documentChanged(@NotNull DocumentEvent event) {
        for (Editor editor : com.intellij.openapi.editor.EditorFactory.getInstance().getEditors(event.getDocument())) {
            schedule(editor);
        }
    }

    private void schedule(@NotNull Editor editor) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (!editor.isDisposed()) {
                process(editor);
            }
        });
    }

    private void process(@NotNull Editor editor) {
        State state = editor.getUserData(STATE);
        if (!applies(editor)) {
            if (state != null) {
                state.clear();
            }
            return;
        }
        if (state == null) {
            state = new State();
            editor.putUserData(STATE, state);
        }
        Caret caret = editor.getCaretModel().getCurrentCaret();
        int offset = caret.isValid() ? caret.getOffset() : -1;
        update(editor, state, offset);
    }

    private void update(@NotNull Editor editor, @NotNull State state, int offset) {
        state.clear();
        Document document = editor.getDocument();
        if (offset < 0 || document.getTextLength() > MAX_DOCUMENT_LENGTH) {
            return;
        }
        List<InsertStatements.Statement> statements =
                InsertStatements.parse(document.getText());
        InsertStatements.Statement current = statementAt(statements, offset);
        if (current == null) {
            return;
        }
        InsertStatements.Pairing pairing = InsertStatements.pairingFor(current, offset);
        if (pairing != null) {
            addHighlight(editor, state, pairing.columnRange(), EditorColors.SEARCH_RESULT_ATTRIBUTES);
            if (pairing.caretValueRange() != null) {
                addHighlight(editor, state, pairing.caretValueRange(), EditorColors.SEARCH_RESULT_ATTRIBUTES);
            }
            for (TextRange value : pairing.sameSlotValues()) {
                if (!value.equals(pairing.caretValueRange())) {
                    addHighlight(editor, state, value, EditorColors.TEXT_SEARCH_RESULT_ATTRIBUTES);
                }
            }
        }
        if (current.columns.size() >= MIN_COLUMNS_FOR_INLAYS) {
            addColumnHints(editor, state, current);
        }
    }

    private void addHighlight(@NotNull Editor editor, @NotNull State state, @NotNull TextRange range,
                              @NotNull TextAttributesKey key) {
        if (range.getStartOffset() < 0 || range.getEndOffset() > editor.getDocument().getTextLength()
                || range.getLength() == 0) {
            return;
        }
        RangeHighlighter highlighter = editor.getMarkupModel().addRangeHighlighter(
                key, range.getStartOffset(), range.getEndOffset(),
                HighlighterLayer.SELECTION - 1, HighlighterTargetArea.EXACT_RANGE);
        state.highlighters.add(highlighter);
    }

    private void addColumnHints(@NotNull Editor editor, @NotNull State state,
                                @NotNull InsertStatements.Statement statement) {
        int added = 0;
        for (List<TextRange> tuple : statement.tuples) {
            for (int v = 0; v < tuple.size() && v < statement.columns.size(); v++) {
                if (added >= MAX_INLAYS) {
                    return;
                }
                TextRange value = tuple.get(v);
                String column = columnName(statement.columns.get(v), editor.getDocument());
                if (column == null) {
                    continue;
                }
                Inlay<?> inlay = editor.getInlayModel().addInlineElement(
                        value.getStartOffset(), false, new ColumnHintRenderer(column));
                if (inlay != null) {
                    state.inlays.add(inlay);
                    added++;
                }
            }
        }
    }

    private @Nullable String columnName(@NotNull TextRange columnRange, @NotNull Document document) {
        if (columnRange.getLength() > 64) {
            return null;
        }
        String raw = document.getText(columnRange).trim();
        // strip quoted identifiers and table prefixes for a compact hint
        raw = raw.replace("\"", "").replace("`", "").replace("'", "");
        int dot = raw.lastIndexOf('.');
        if (dot >= 0) {
            raw = raw.substring(dot + 1);
        }
        if (raw.isBlank() || raw.length() > 32) {
            return null;
        }
        return raw;
    }

    private static @Nullable InsertStatements.Statement statementAt(
            @NotNull List<InsertStatements.Statement> statements, int offset) {
        for (InsertStatements.Statement statement : statements) {
            if (offset >= statement.startOffset && offset <= statement.endOffset) {
                return statement;
            }
        }
        return null;
    }

    private static boolean applies(@NotNull Editor editor) {
        Document document = editor.getDocument();
        if (document.getUserData(CONSOLE_DOCUMENT) == Boolean.TRUE) {
            return true;
        }
        Document effective = document instanceof DocumentWindow window ? window.getDelegate() : document;
        VirtualFile file = FileDocumentManager.getInstance().getFile(effective);
        if (file == null) {
            return false;
        }
        String extension = file.getExtension() == null ? "" : file.getExtension().toLowerCase();
        String path = file.getPath().toLowerCase();
        if (extension.equals("sql")) {
            return true;
        }
        return extension.equals("xml") && path.contains("liquibase");
    }

    /** Per-editor disposable decoration state. */
    private static final class State {
        final List<RangeHighlighter> highlighters = new ArrayList<>();
        final List<Inlay<?>> inlays = new ArrayList<>();

        void clear() {
            for (RangeHighlighter highlighter : highlighters) {
                if (highlighter.isValid()) {
                    highlighter.dispose();
                }
            }
            highlighters.clear();
            for (Inlay<?> inlay : inlays) {
                if (inlay.isValid()) {
                    inlay.dispose();
                }
            }
            inlays.clear();
        }
    }
}
