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

/**
 * Full value viewer for a results-grid cell (double-click, or the {} icon of a JSON
 * cell). JSON is pretty-printed in a highlighted, read-only editor and its properties can
 * be sorted by name (A → Z / Z → A, at every nesting level) — handy for comparing
 * documents whose keys come in different orders. Other values are shown as-is, wrapped.
 */
public final class CellValueDialog extends DialogWrapper {

    private static final String ORDER_KEY = "intelladb.jsonViewer.keyOrder";

    private final Project project;
    private final String text;
    private final boolean json;
    private final Document document = EditorFactory.getInstance().createDocument("");
    private final EditorEx viewer;
    private final ComboBox<JsonText.KeyOrder> order = new ComboBox<>(JsonText.KeyOrder.values());

    public CellValueDialog(@Nullable Project project, @NotNull String columnName, @NotNull String value) {
        super(project);
        this.project = project;
        this.text = value;
        this.json = JsonText.isJson(value);
        this.viewer = (EditorEx) EditorFactory.getInstance().createViewer(document, project);
        configureViewer();
        order.setRenderer(SimpleListCellRenderer.create("", o -> o.label));
        order.setSelectedItem(savedOrder());
        order.addActionListener(e -> {
            PropertiesComponent.getInstance().setValue(ORDER_KEY, selectedOrder().name());
            render();
        });
        setTitle((json ? "JSON — " : "Value — ") + columnName);
        setOKButtonText("Close");
        render();
        init();
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

    /** The text as shown: pretty-printed (and sorted) JSON, or the raw value. */
    private @NotNull String shownText() {
        return json ? JsonText.prettyIfJson(text, selectedOrder()) : text;
    }

    private void render() {
        String shown = StringUtil.convertLineSeparators(shownText()); // documents accept \n only
        CommandProcessor.getInstance().runUndoTransparentAction(() -> WriteAction.run(() -> document.setText(shown)));
        viewer.getScrollingModel().scrollVertically(0);
    }

    @Override
    protected Action @NotNull [] createActions() {
        if (!json) {
            return new Action[]{copyAction("Copy", () -> text), getOKAction()};
        }
        return new Action[]{copyAction("Copy Original", () -> text),
                copyAction("Copy Formatted", this::shownText), getOKAction()};
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
