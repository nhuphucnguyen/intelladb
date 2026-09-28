package dev.phucngu.intelladb.ui;

import com.intellij.icons.AllIcons;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.EditorSettings;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.fileTypes.PlainTextFileType;
import com.intellij.openapi.fileTypes.UnknownFileType;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFileWrapper;
import com.intellij.ui.JBColor;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.SimpleListCellRenderer;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBRadioButton;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import dev.phucngu.intelladb.connection.SqlResult;
import dev.phucngu.intelladb.sql.IntellaSqlFileType;
import dev.phucngu.intelladb.util.ResultExporter;
import dev.phucngu.intelladb.util.ResultExporter.Format;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * "Export Data" dialog modelled on IntelliJ's: source, extractor, Transpose / Add table
 * definition (DDL) options and the output file on the left; a live, syntax-highlighted
 * preview of the first rows on the right; Copy to Clipboard or Export to File.
 */
final class ExportDataDialog extends DialogWrapper {

    static final String EXTRACTOR_KEY = "intelladb.results.extractor";
    private static final String DIRECTORY_KEY = "intelladb.export.directory";
    private static final int PREVIEW_ROWS = 10;

    private final Project project;
    private final SqlResult result;
    /** All rows and the selected ones, both in the grid's display order. */
    private final List<Object[]> allRows;
    private final List<Object[]> selectedRows;
    private final @Nullable String insertTarget;
    private final @Nullable String ddl;
    private final String baseFileName;

    private final ComboBox<Format> extractor = new ComboBox<>(Format.values());
    private final JBRadioButton allRowsButton = new JBRadioButton();
    private final JBRadioButton selectedRowsButton = new JBRadioButton();
    private final JBCheckBox transpose = new JBCheckBox("Transpose");
    private final JBCheckBox addDdl = new JBCheckBox("Add table definition (DDL)");
    private final TextFieldWithBrowseButton outputFile = new TextFieldWithBrowseButton();
    private final JBTextArea sourceField = new JBTextArea(2, 30);
    private final Document previewDocument = EditorFactory.getInstance().createDocument("");
    private final EditorEx preview;

    /**
     * @param source       what the rows come from, shown read-only ({@code db.schema.table} or the query)
     * @param insertTarget table name used by SQL Inserts; null falls back to a placeholder
     * @param ddl          CREATE statement for "Add table definition", or null when unknown
     */
    ExportDataDialog(@NotNull Project project, @NotNull SqlResult result,
                     @NotNull List<Object[]> allRows, @NotNull List<Object[]> selectedRows,
                     @NotNull String source, @Nullable String insertTarget, @Nullable String ddl) {
        super(project, true);
        this.project = project;
        this.result = result;
        this.allRows = allRows;
        this.selectedRows = selectedRows;
        this.insertTarget = insertTarget;
        this.ddl = ddl;
        this.baseFileName = fileNameFor(source, insertTarget);
        this.preview = (EditorEx) EditorFactory.getInstance().createViewer(previewDocument, project);
        configurePreview();

        extractor.setSelectedItem(savedFormat());
        extractor.setRenderer(SimpleListCellRenderer.create("", format -> format.label));
        extractor.addActionListener(e -> onFormatChanged());
        transpose.addActionListener(e -> refreshPreview());
        addDdl.addActionListener(e -> refreshPreview());
        addDdl.setToolTipText(ddl == null ? "Only available for results read from a single known table" : null);
        outputFile.addActionListener(e -> browse());
        allRowsButton.setText("All rows (" + allRows.size() + ")");
        selectedRowsButton.setText("Selected rows (" + selectedRows.size() + ")");
        javax.swing.ButtonGroup scope = new javax.swing.ButtonGroup();
        scope.add(allRowsButton);
        scope.add(selectedRowsButton);
        // A partial selection most likely means "export these"; otherwise everything.
        boolean partial = !selectedRows.isEmpty() && selectedRows.size() < allRows.size();
        selectedRowsButton.setEnabled(!selectedRows.isEmpty());
        (partial ? selectedRowsButton : allRowsButton).setSelected(true);
        allRowsButton.addActionListener(e -> refreshPreview());
        selectedRowsButton.addActionListener(e -> refreshPreview());

        setTitle("Export Data");
        setOKButtonText("Export to File");
        sourceField.setText(source);
        init();
        onFormatChanged();
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected @NotNull JComponent createCenterPanel() {
        JPanel left = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = GridBagConstraints.RELATIVE;
        gbc.weightx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.NORTHWEST;

        gbc.insets = JBUI.insetsBottom(4);
        left.add(new JBLabel("Source:"), gbc);
        sourceField.setEditable(false);
        sourceField.setLineWrap(true);
        sourceField.setWrapStyleWord(false);
        sourceField.setBackground(UIUtil.getPanelBackground());
        sourceField.setBorder(JBUI.Borders.compound(JBUI.Borders.customLine(JBColor.border()),
                JBUI.Borders.empty(4, 6)));
        gbc.insets = JBUI.insetsBottom(14);
        left.add(sourceField, gbc);

        gbc.insets = JBUI.insetsBottom(4);
        left.add(new JBLabel("Extractor:"), gbc);
        gbc.insets = JBUI.insetsBottom(10);
        left.add(extractor, gbc);

        gbc.insets = JBUI.insetsBottom(4);
        left.add(new JBLabel("Rows:"), gbc);
        JPanel scopeRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
        scopeRow.add(allRowsButton);
        scopeRow.add(javax.swing.Box.createHorizontalStrut(JBUI.scale(12)));
        scopeRow.add(selectedRowsButton);
        gbc.insets = JBUI.insetsBottom(10);
        left.add(scopeRow, gbc);

        gbc.insets = JBUI.insetsBottom(4);
        left.add(transpose, gbc);
        gbc.insets = JBUI.insetsBottom(14);
        left.add(addDdl, gbc);

        gbc.insets = JBUI.insetsBottom(4);
        left.add(new JBLabel("Output file:"), gbc);
        gbc.insets = JBUI.insetsBottom(8);
        left.add(outputFile, gbc);

        if (result.truncated && allRowsButton.isSelected()) {
            JBLabel warning = new JBLabel("Only the first " + SqlResult.MAX_ROWS
                    + " fetched rows are exported.", AllIcons.General.Warning, JBLabel.LEFT);
            warning.setForeground(JBUI.CurrentTheme.ContextHelp.FOREGROUND);
            left.add(warning, gbc);
        }
        gbc.weighty = 1; // push everything up
        left.add(new JPanel(), gbc);
        left.setBorder(JBUI.Borders.emptyRight(12));

        JPanel right = new JPanel(new BorderLayout(0, JBUI.scale(4)));
        right.add(new JBLabel("Export preview (first " + PREVIEW_ROWS + " rows are shown):"), BorderLayout.NORTH);
        JComponent editorComponent = preview.getComponent();
        editorComponent.setBorder(JBUI.Borders.customLine(JBColor.border()));
        right.add(editorComponent, BorderLayout.CENTER);

        JBSplitter splitter = new JBSplitter(false, 0.36f);
        splitter.setFirstComponent(left);
        splitter.setSecondComponent(right);
        splitter.setPreferredSize(JBUI.size(1000, 520));
        return splitter;
    }

    private void configurePreview() {
        EditorSettings settings = preview.getSettings();
        settings.setLineNumbersShown(true);
        settings.setFoldingOutlineShown(false);
        settings.setLineMarkerAreaShown(false);
        settings.setIndentGuidesShown(false);
        settings.setCaretRowShown(false);
        settings.setAdditionalLinesCount(1);
        settings.setAdditionalColumnsCount(1);
        settings.setUseSoftWraps(false);
    }

    @Override
    protected Action @NotNull [] createActions() {
        return new Action[]{getCancelAction(), new CopyAction(), getOKAction()};
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return extractor;
    }

    // ------------------------------------------------------------------ behaviour

    private @NotNull Format format() {
        Object selected = extractor.getSelectedItem();
        return selected instanceof Format format ? format : Format.CSV;
    }

    private void onFormatChanged() {
        Format format = format();
        PropertiesComponent.getInstance().setValue(EXTRACTOR_KEY, format.name());
        transpose.setEnabled(ResultExporter.supportsTranspose(format));
        addDdl.setEnabled(format == Format.SQL_INSERTS && ddl != null);
        preview.setHighlighter(EditorHighlighterFactory.getInstance().createEditorHighlighter(project, fileType(format)));
        updateOutputExtension(format);
        refreshPreview();
    }

    private @NotNull ResultExporter.Options options() {
        Format format = format();
        boolean transposed = transpose.isEnabled() && transpose.isSelected();
        String definition = format == Format.SQL_INSERTS && addDdl.isEnabled() && addDdl.isSelected() ? ddl : null;
        return new ResultExporter.Options(transposed, definition);
    }

    /** The rows the export covers, in display order. */
    private @NotNull List<Object[]> rows() {
        return selectedRowsButton.isSelected() ? selectedRows : allRows;
    }

    private @NotNull String render(@NotNull List<Object[]> rows) {
        String text = ResultExporter.export(format(), result.columns, rows, insertTarget, options());
        return StringUtil.convertLineSeparators(text); // documents only accept \n
    }

    private void refreshPreview() {
        List<Object[]> rows = rows();
        String text = render(rows.subList(0, Math.min(PREVIEW_ROWS, rows.size())));
        CommandProcessor.getInstance().runUndoTransparentAction(() ->
                WriteAction.run(() -> previewDocument.setText(text)));
        preview.getScrollingModel().scrollVertically(0);
    }

    private @NotNull FileType fileType(@NotNull Format format) {
        if (format == Format.SQL_INSERTS) {
            return IntellaSqlFileType.INSTANCE;
        }
        FileType byExtension = FileTypeManager.getInstance().getFileTypeByExtension(format.extension);
        return byExtension instanceof UnknownFileType ? PlainTextFileType.INSTANCE : byExtension;
    }

    // ------------------------------------------------------------------ output file

    private static @NotNull Format savedFormat() {
        try {
            return Format.valueOf(PropertiesComponent.getInstance().getValue(EXTRACTOR_KEY, Format.CSV.name()));
        } catch (IllegalArgumentException unknown) {
            return Format.CSV;
        }
    }

    /** {@code db_schema_table} from the source, else {@code result}. */
    private static @NotNull String fileNameFor(@NotNull String source, @Nullable String insertTarget) {
        String base = insertTarget != null ? source : "result";
        String cleaned = base.replace("\"", "").replaceAll("[^\\w-]+", "_").replaceAll("^_+|_+$", "");
        return cleaned.isEmpty() ? "result" : cleaned;
    }

    private void updateOutputExtension(@NotNull Format format) {
        String current = outputFile.getText().trim();
        if (current.isEmpty()) {
            String directory = PropertiesComponent.getInstance()
                    .getValue(DIRECTORY_KEY, System.getProperty("user.home"));
            outputFile.setText(Path.of(directory, baseFileName + "." + format.extension).toString());
            return;
        }
        int slash = Math.max(current.lastIndexOf('/'), current.lastIndexOf('\\'));
        int dot = current.lastIndexOf('.');
        String stem = dot > slash ? current.substring(0, dot) : current;
        outputFile.setText(stem + "." + format.extension);
    }

    private void browse() {
        Format format = format();
        Path current = Path.of(outputFile.getText().trim().isEmpty() ? System.getProperty("user.home")
                : outputFile.getText().trim());
        VirtualFileWrapper target = FileChooserFactory.getInstance()
                .createSaveFileDialog(new FileSaverDescriptor("Output File", "Where to save the exported rows",
                        format.extension), project)
                .save(current.getParent(), current.getFileName() == null ? null : current.getFileName().toString());
        if (target != null) {
            outputFile.setText(target.getFile().getPath());
        }
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        String path = outputFile.getText().trim();
        if (path.isEmpty()) {
            return new ValidationInfo("Choose an output file", outputFile.getTextField());
        }
        Path parent = Path.of(path).toAbsolutePath().getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            return new ValidationInfo("Folder does not exist: " + parent, outputFile.getTextField());
        }
        return null;
    }

    @Override
    protected void doOKAction() {
        Path target = Path.of(outputFile.getText().trim()).toAbsolutePath();
        try {
            Files.writeString(target, render(rows()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            setErrorText("Export failed: " + e.getMessage(), outputFile.getTextField());
            return;
        }
        if (target.getParent() != null) {
            PropertiesComponent.getInstance().setValue(DIRECTORY_KEY, target.getParent().toString());
        }
        notify(rows().size() + " row(s) exported to " + target);
        super.doOKAction();
    }

    private void notify(@NotNull String message) {
        NotificationGroupManager.getInstance().getNotificationGroup("IntellaDB")
                .createNotification(message, NotificationType.INFORMATION).notify(project);
    }

    private final class CopyAction extends AbstractAction {
        CopyAction() {
            super("Copy to Clipboard");
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            CopyPasteManager.getInstance().setContents(new StringSelection(render(rows())));
            ExportDataDialog.this.notify(rows().size() + " row(s) copied to the clipboard as " + format().label);
            close(CANCEL_EXIT_CODE);
        }
    }

    @Override
    protected void dispose() {
        EditorFactory.getInstance().releaseEditor(preview);
        super.dispose();
    }
}
