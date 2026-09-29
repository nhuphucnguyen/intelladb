package dev.phucngu.intelladb.ui;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.EditorSettings;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.fileTypes.PlainTextFileType;
import com.intellij.openapi.fileTypes.UnknownFileType;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.JBColor;
import com.intellij.ui.SimpleListCellRenderer;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import dev.phucngu.intelladb.util.JsonText;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.util.List;

/**
 * Full value viewer for a results-grid cell (double-click, or the {} icon of a JSON
 * cell). JSON is pretty-printed in a highlighted editor and its properties can be sorted
 * by name (A → Z / Z → A, at every nesting level) — handy for comparing documents whose
 * keys come in different orders. Other values are shown as-is, wrapped.
 * <p>
 * For a writable cell the editor is editable: Apply hands the new text back as a pending
 * edit of the cell (saved by the grid's Submit), Discard drops it. It is read-only otherwise.
 */
public final class CellValueDialog extends DialogWrapper {

    private static final String ORDER_KEY = "intelladb.jsonViewer.keyOrder";

    private final Project project;
    private final String text;
    private final boolean json;
    private final boolean editable;
    /** The text as first shown; the edit counts as a change only when the document differs from it. */
    private String initialShown;
    private final Document document = EditorFactory.getInstance().createDocument("");
    private final EditorEx viewer;
    private final ComboBox<JsonText.KeyOrder> order = new ComboBox<>(JsonText.KeyOrder.values());

    public CellValueDialog(@Nullable Project project, @NotNull String columnName, @NotNull String value) {
        this(project, columnName, value, false);
    }

    public CellValueDialog(@Nullable Project project, @NotNull String columnName, @NotNull String value,
                           boolean editable) {
        super(project);
        this.project = project;
        this.text = value;
        this.json = JsonText.isJson(value);
        this.editable = editable;
        this.viewer = (EditorEx) (editable
                ? EditorFactory.getInstance().createEditor(document, project)
                : EditorFactory.getInstance().createViewer(document, project));
        configureViewer();
        order.setRenderer(SimpleListCellRenderer.create("", o -> o.label));
        order.setSelectedItem(savedOrder());
        order.addActionListener(e -> {
            PropertiesComponent.getInstance().setValue(ORDER_KEY, selectedOrder().name());
            render();
        });
        setTitle((json ? "JSON — " : "Value — ") + columnName);
        if (editable) {
            setOKButtonText("Apply");
            setCancelButtonText("Discard");
        } else {
            setOKButtonText("Close");
        }
        render();
        initialShown = document.getText();
        init();
        if (editable && json) {
            initValidation(); // warns (without blocking Apply) while the JSON doesn't parse
        }
    }

    /**
     * The edited value, or null when nothing changed. JSON that was on one line comes back
     * compact, so a reformatted-but-equal document is no change and the cell stays one line.
     */
    public @Nullable String editedValue() {
        String edited = document.getText();
        if (edited.equals(initialShown)) {
            return null;
        }
        boolean oneLine = text.indexOf('\n') < 0 && text.indexOf('\r') < 0;
        String value = json && oneLine && JsonText.isJson(edited) ? JsonText.compactIfJson(edited) : edited;
        return value.equals(json && oneLine ? JsonText.compactIfJson(text) : text) ? null : value;
    }

    @Override
    protected @Nullable com.intellij.openapi.ui.ValidationInfo doValidate() {
        if (editable && json && !document.getText().isBlank() && !JsonText.isJson(document.getText())) {
            return new com.intellij.openapi.ui.ValidationInfo("Not valid JSON").asWarning().withOKEnabled();
        }
        return null;
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return editable ? viewer.getContentComponent() : super.getPreferredFocusedComponent();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel top = new JPanel(new BorderLayout());
        JBLabel hint = new JBLabel(json ? "JSON · " + text.length() + " chars" : text.length() + " chars");
        hint.setForeground(JBUI.CurrentTheme.ContextHelp.FOREGROUND);
        top.add(hint, BorderLayout.WEST);
        if (json) {
            JPanel sorting = new JPanel(new FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0));
            sorting.add(new JBLabel("Property order:"));
            sorting.add(order);
            top.add(sorting, BorderLayout.EAST);
        }
        top.setBorder(JBUI.Borders.emptyBottom(6));

        JComponent editor = viewer.getComponent();
        editor.setBorder(JBUI.Borders.customLine(JBColor.border()));
        editor.setPreferredSize(JBUI.size(720, 480));
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(top, BorderLayout.NORTH);
        panel.add(editor, BorderLayout.CENTER);
        return panel;
    }

    private void configureViewer() {
        EditorSettings settings = viewer.getSettings();
        settings.setLineNumbersShown(json);
        settings.setFoldingOutlineShown(false);
        settings.setLineMarkerAreaShown(false);
        settings.setIndentGuidesShown(json);
        settings.setCaretRowShown(false);
        settings.setUseSoftWraps(!json); // pretty JSON keeps its structure; other text wraps
        settings.setAdditionalLinesCount(0);
        FileType type = json ? FileTypeManager.getInstance().getFileTypeByExtension("json") : PlainTextFileType.INSTANCE;
        if (type instanceof UnknownFileType) {
            type = PlainTextFileType.INSTANCE;
        }
        viewer.setHighlighter(EditorHighlighterFactory.getInstance().createEditorHighlighter(project, type));
    }

    private @NotNull JsonText.KeyOrder selectedOrder() {
        return order.getSelectedItem() instanceof JsonText.KeyOrder selected ? selected : JsonText.KeyOrder.ORIGINAL;
    }

    private static @NotNull JsonText.KeyOrder savedOrder() {
        try {
            return JsonText.KeyOrder.valueOf(PropertiesComponent.getInstance()
                    .getValue(ORDER_KEY, JsonText.KeyOrder.ORIGINAL.name()));
        } catch (IllegalArgumentException unknown) {
            return JsonText.KeyOrder.ORIGINAL;
        }
    }

    /**
     * The text as shown: pretty-printed (and sorted) JSON, or the raw value. While editing,
     * the order applies to what is in the editor now (when it parses), so edits survive.
     */
    private @NotNull String shownText() {
        String source = editable && initialShown != null ? document.getText() : text;
        return json ? JsonText.prettyIfJson(source, selectedOrder()) : source;
    }

    private void render() {
        String shown = StringUtil.convertLineSeparators(shownText()); // documents accept \n only
        CommandProcessor.getInstance().runUndoTransparentAction(() -> WriteAction.run(() -> document.setText(shown)));
        viewer.getScrollingModel().scrollVertically(0);
    }

    @Override
    protected Action @NotNull [] createActions() {
        List<Action> actions = new java.util.ArrayList<>();
        if (!json) {
            actions.add(copyAction("Copy", editable ? document::getText : () -> text));
        } else {
            actions.add(copyAction("Copy Original", () -> text));
            actions.add(copyAction(editable ? "Copy Edited" : "Copy Formatted",
                    editable ? document::getText : this::shownText));
        }
        if (editable) {
            actions.add(getCancelAction());
        }
        actions.add(getOKAction());
        return actions.toArray(Action[]::new);
    }

    private static @NotNull Action copyAction(@NotNull String name, @NotNull java.util.function.Supplier<String> value) {
        return new AbstractAction(name) {
            @Override
            public void actionPerformed(@NotNull ActionEvent e) {
                CopyPasteManager.getInstance().setContents(new StringSelection(value.get()));
            }
        };
    }

    @Override
    public @Nullable Dimension getPreferredSize() {
        Dimension preferred = super.getPreferredSize();
        return new Dimension(Math.max(preferred.width, 760), Math.max(preferred.height, 560));
    }

    @Override
    protected void dispose() {
        EditorFactory.getInstance().releaseEditor(viewer);
        super.dispose();
    }
}
