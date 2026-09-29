package dev.phucngu.intelladb.ui;

import com.intellij.icons.AllIcons;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.ex.ComboBoxAction;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.DumbAwareToggleAction;
import com.intellij.openapi.project.Project;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import dev.phucngu.intelladb.IntellaDbIcons;
import dev.phucngu.intelladb.connection.ConnectionManager;
import dev.phucngu.intelladb.connection.DbConfig;
import dev.phucngu.intelladb.connection.DbDialect;
import dev.phucngu.intelladb.connection.Dialects;
import dev.phucngu.intelladb.connection.DbSession;
import dev.phucngu.intelladb.connection.SessionOpener;
import dev.phucngu.intelladb.connection.SqlResult;
import dev.phucngu.intelladb.schema.DdlGenerator;
import dev.phucngu.intelladb.schema.SchemaCatalog;
import dev.phucngu.intelladb.schema.TableMeta;
import dev.phucngu.intelladb.util.ResultExporter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.datatransfer.StringSelection;
import java.util.List;

/**
 * A result set view: {@link ResultGrid} plus, when a {@link Host} is given, the results
 * toolbar of IntelliJ's database tools — rerun, cancel, pin, a data-extractor selector
 * (CSV / TSV / JSON / SQL Inserts / Markdown) with quick copy-to-clipboard, the Export
 * Data dialog, and the row count / timing. Without a host it is the compact grid used inline by the
 * AI chat.
 * <p>
 * Rows from one table with a primary (or NOT NULL unique) key whose key columns are all in
 * the result can be edited in the grid; Submit (Ctrl/Cmd+Enter) writes the changes back as
 * one UPDATE per row, all or nothing, and Revert drops them.
 */
public final class ResultsPanel extends JPanel {

    /** What the toolbar's Rerun / Cancel act on. */
    public interface Host {
        void rerun(@NotNull ResultsPanel panel);

        void cancel();

        /** False for a cached result (query history): Rerun / Cancel are hidden. */
        default boolean canRerun() {
            return true;
        }

        /** Connection the rows come from — names the export source and finds its DDL. */
        default @Nullable DbConfig config() {
            return null;
        }

        /** Edited rows were submitted: {@code statements} ran with {@code outcome}. */
        default void rowsUpdated(@NotNull List<String> statements, @NotNull SqlResult outcome) {
        }
    }

    private static final String EXTRACTOR_KEY = ExportDataDialog.EXTRACTOR_KEY;

    private final Project project;
    private final @Nullable Host host;
    private final ResultGrid grid;
    private final JBLabel info = new JBLabel("Run a query to see results here", SwingConstants.LEFT);
    private @Nullable SqlResult result;
    private @Nullable String sourceTable;
    private boolean running;
    private boolean pinned;
    /** Row count and timing of the result, shown when there are no pending edits. */
    private String resultInfo = "";
    /** Table edits are written to (qualified), or null when the result is read-only. */
    private @Nullable String editTable;
    /** Why the result can't be edited, for the Submit tooltip; null when it can. */
    private @Nullable String readOnlyReason;
    /** Key columns of {@link #editTable} → their index in the result. */
    private java.util.Map<String, Integer> keyColumns = java.util.Map.of();
    private boolean submitting;

    /** Compact grid (no toolbar). */
    public ResultsPanel(@NotNull Project project) {
        this(project, null, false);
    }

    public ResultsPanel(@NotNull Project project, @Nullable Host host, boolean pinnable) {
        super(new BorderLayout());
        this.project = project;
        this.host = host;
        this.grid = new ResultGrid(project);
        info.setForeground(JBUI.CurrentTheme.ContextHelp.FOREGROUND);
        add(grid.wrapInScrollPane(), BorderLayout.CENTER);
        if (host != null) {
            add(buildToolbar(pinnable), BorderLayout.NORTH);
            installEditActions();
        } else {
            info.setBorder(JBUI.Borders.empty(4, 8));
            add(info, BorderLayout.SOUTH);
        }
    }

    // ------------------------------------------------------------------ state

    public boolean isPinned() {
        return pinned;
    }

    public @Nullable SqlResult result() {
        return result;
    }

    /** Qualified table the rows come from — used as the target of "SQL Inserts". */
    public void setSourceTable(@Nullable String table) {
        this.sourceTable = table;
    }

    public void showRunning() {
        running = true;
        info.setText("Running…");
        info.setForeground(JBUI.CurrentTheme.Link.Foreground.ENABLED);
    }

    public void showMessage(@NotNull String message) {
        running = false;
        result = null;
        editTable = null;
        grid.setResult(null);
        grid.getEmptyText().setText(message);
        info.setText(message);
        info.setForeground(JBUI.CurrentTheme.ContextHelp.FOREGROUND);
    }

    public void showResult(@NotNull SqlResult result) {
        running = false;
        this.result = result;
        editTable = null;
        readOnlyReason = null;
        info.setForeground(JBUI.CurrentTheme.ContextHelp.FOREGROUND);
        switch (result.kind) {
            case ROWS -> {
                grid.setResult(result);
                grid.getEmptyText().setText("No rows");
                resultInfo = rowsInfo(result);
                info.setText(resultInfo);
                setUpEditing(result);
            }
            case UPDATE_COUNT -> {
                grid.setResult(null);
                grid.getEmptyText().setText(result.updateCount + " row(s) affected");
                info.setText(result.updateCount + " row(s) affected  ·  " + result.durationMs + " ms");
            }
            case MESSAGE -> {
                grid.setResult(null);
                grid.getEmptyText().setText("Completed");
                info.setText("Completed  ·  " + result.durationMs + " ms");
            }
            case ERROR -> {
                grid.setResult(null);
                grid.getEmptyText().setText("Query failed");
                info.setText("Error: " + result.text);
                info.setForeground(JBUI.CurrentTheme.Label.errorForeground());
            }
        }
    }

    private static @NotNull String rowsInfo(@NotNull SqlResult result) {
        int count = result.rows.size();
        String note = result.truncated ? " (first " + SqlResult.MAX_ROWS + ")" : "";
        return count + " row" + (count == 1 ? "" : "s") + note + "  ·  " + result.durationMs + " ms";
    }

    // ------------------------------------------------------------------ toolbar

    private @NotNull JComponent buildToolbar(boolean pinnable) {
        DefaultActionGroup left = new DefaultActionGroup();
        left.add(new DumbAwareAction("Rerun", "Execute the query of this result again", AllIcons.Actions.Refresh) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                if (host != null && result != null) {
                    host.rerun(ResultsPanel.this);
                }
            }

            @Override
            public void update(@NotNull AnActionEvent e) {
                e.getPresentation().setVisible(host != null && host.canRerun());
                e.getPresentation().setEnabled(!running && result != null);
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }
        });
        left.add(new DumbAwareAction("Cancel", "Cancel the running query", AllIcons.Actions.Suspend) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                if (host != null) {
                    host.cancel();
                }
            }

            @Override
            public void update(@NotNull AnActionEvent e) {
                e.getPresentation().setVisible(host != null && host.canRerun());
                e.getPresentation().setEnabled(running);
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }
        });
        if (pinnable) {
            left.addSeparator();
            left.add(new DumbAwareToggleAction("Pin Tab", "Keep this result when the console runs again",
                    AllIcons.General.Pin_tab) {
                @Override
                public boolean isSelected(@NotNull AnActionEvent e) {
                    return pinned;
                }

                @Override
                public void setSelected(@NotNull AnActionEvent e, boolean state) {
                    pinned = state;
                }

                @Override
                public @NotNull ActionUpdateThread getActionUpdateThread() {
                    return ActionUpdateThread.EDT;
                }
            });
        }

        left.addSeparator();
        left.add(deleteRowsAction);
        left.add(revertAction);
        left.add(submitAction);

        DefaultActionGroup right = new DefaultActionGroup();
        right.add(new ExtractorComboAction());
        right.add(new DumbAwareAction("Copy to Clipboard",
                "Copy the selected rows (or all rows) in the chosen format",
                AllIcons.Actions.Copy) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                String text = exportText();
                if (text != null) {
                    CopyPasteManager.getInstance().setContents(new StringSelection(text));
                }
            }

            @Override
            public void update(@NotNull AnActionEvent e) {
                e.getPresentation().setEnabled(hasRows());
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }
        });
        right.add(new DumbAwareAction("Export Data…", "Export all or the selected rows to a file or the clipboard",
                AllIcons.ToolbarDecorator.Export) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                openExportDialog();
            }

            @Override
            public void update(@NotNull AnActionEvent e) {
                e.getPresentation().setEnabled(hasRows());
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }
        });

        ActionToolbar leftBar = ActionManager.getInstance().createActionToolbar("IntellaDbResults", left, true);
        leftBar.setTargetComponent(this);
        ActionToolbar rightBar = ActionManager.getInstance().createActionToolbar("IntellaDbResultsExport", right, true);
        rightBar.setTargetComponent(this);

        info.setBorder(JBUI.Borders.empty(0, 8));
        JPanel west = new JPanel(new BorderLayout());
        west.setOpaque(false);
        west.add(leftBar.getComponent(), BorderLayout.WEST);
        west.add(info, BorderLayout.CENTER);

        JPanel bar = new JPanel(new BorderLayout());
        bar.add(west, BorderLayout.CENTER);
        bar.add(rightBar.getComponent(), BorderLayout.EAST);
        bar.setBorder(JBUI.Borders.customLineBottom(JBColor.border()));
        return bar;
    }

    private boolean hasRows() {
        return result != null && result.kind == SqlResult.Kind.ROWS;
    }

    private static @NotNull ResultExporter.Format selectedFormat() {
        String saved = PropertiesComponent.getInstance().getValue(EXTRACTOR_KEY, ResultExporter.Format.CSV.name());
        try {
            return ResultExporter.Format.valueOf(saved);
        } catch (IllegalArgumentException unknown) {
            return ResultExporter.Format.CSV;
        }
    }

    private @Nullable String exportText() {
        if (!hasRows()) {
            return null;
        }
        // Quick copy follows the grid: the selected rows if any, else all — as displayed.
        List<Object[]> selected = grid.selectedRowsInViewOrder();
        List<Object[]> rows = selected.isEmpty() ? grid.rowsInViewOrder() : selected;
        return ResultExporter.export(selectedFormat(), result.columns, rows, insertTarget(), dialect());
    }

    /** Table for SQL Inserts: from the driver's column metadata, else what the opener told us. */
    private @Nullable String insertTarget() {
        String fromResult = result == null ? null : result.qualifiedSource(dialect());
        return fromResult != null ? fromResult : sourceTable;
    }

    /** The connection's dialect; the default one for the compact grid without a host. */
    private @NotNull DbDialect dialect() {
        DbConfig config = host == null ? null : host.config();
        return config == null ? Dialects.all().get(0) : config.dialect();
    }

    private void openExportDialog() {
        if (!hasRows()) {
            return;
        }
        DbConfig config = host == null ? null : host.config();
        String target = insertTarget();
        String source;
        if (target != null) {
            source = config != null && !config.database.isBlank() ? config.database + "." + target : target;
        } else {
            source = result.sql.strip().replaceAll("\\s+", " ");
        }
        new ExportDataDialog(project, result, grid.rowsInViewOrder(), grid.selectedRowsInViewOrder(),
                source, target, ddlFor(config), dialect()).show();
    }

    /** CREATE TABLE for the result's source table, when the connection's catalog knows it. */
    private @Nullable String ddlFor(@Nullable DbConfig config) {
        SchemaCatalog.Schema source = config == null || result == null ? null : sourceTableMeta(config, result);
        return source == null ? null : DdlGenerator.generate(new SchemaCatalog(List.of(source)), config.dialect());
    }

    /**
     * The result's source table as the connection's catalog knows it (a schema holding just
     * that table), or null when the rows aren't from one table or the catalog isn't loaded.
     */
    private @Nullable SchemaCatalog.Schema sourceTableMeta(@NotNull DbConfig config, @NotNull SqlResult result) {
        if (result.sourceTable == null) {
            return null;
        }
        DbSession session = ConnectionManager.getInstance(project).session(config.id);
        SchemaCatalog catalog = session == null ? null : session.catalog();
        if (catalog == null) {
            return null;
        }
        for (SchemaCatalog.Schema schema : catalog.schemas()) {
            if (result.sourceSchema != null && !schema.name().equals(result.sourceSchema)) {
                continue;
            }
            for (TableMeta table : schema.tables()) {
                if (table.name.equals(result.sourceTable)) {
                    return new SchemaCatalog.Schema(schema.name(), List.of(table));
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ editing

    /** Makes the grid editable when the rows can be found again in their table, else notes why not. */
    private void setUpEditing(@NotNull SqlResult result) {
        readOnlyReason = editBlocker(result);
        if (readOnlyReason != null) {
            return;
        }
        TableMeta table = sourceTableMeta(host.config(), result).tables().get(0);
        java.util.Set<String> tableColumns = new java.util.HashSet<>();
        table.columns.forEach(column -> tableColumns.add(column.name));
        java.util.Map<String, Integer> seen = new java.util.HashMap<>();
        for (int c = 0; c < result.columns.size(); c++) {
            String base = result.sourceColumn(c);
            if (base != null) {
                seen.merge(base, 1, Integer::sum);
            }
        }
        boolean[] writable = new boolean[result.columns.size()];
        for (int c = 0; c < writable.length; c++) {
            String base = result.sourceColumn(c);
            // A column shown twice would get two conflicting edits: leave both read-only.
            writable[c] = base != null && tableColumns.contains(base) && seen.get(base) == 1;
        }
        editTable = result.qualifiedSource(dialect());
        grid.setWritableColumns(writable, this::editsChanged);
    }

    /** Why the rows of {@code result} can't be written back, or null when they can. */
    private @Nullable String editBlocker(@NotNull SqlResult result) {
        DbConfig config = host == null || !host.canRerun() ? null : host.config();
        if (config == null) {
            return "Read-only: not a live result";
        }
        if (config.readOnly) {
            return "Read-only: the connection is read-only";
        }
        if (result.sourceTable == null || result.sourceColumns.isEmpty()) {
            return "Read-only: the rows don't come from a single table";
        }
        SchemaCatalog.Schema source = sourceTableMeta(config, result);
        if (source == null) {
            return "Read-only: " + result.qualifiedSource(dialect()) + " is not in the loaded schema (refresh it)";
        }
        TableMeta table = source.tables().get(0);
        List<String> key = dev.phucngu.intelladb.sql.RowUpdates.rowKey(table);
        if (key == null) {
            return "Read-only: " + table.name + " has no primary key or NOT NULL unique key";
        }
        java.util.Map<String, Integer> indexes = new java.util.LinkedHashMap<>();
        for (String column : key) {
            int index = result.sourceColumns.indexOf(column);
            if (index < 0) {
                return "Read-only: the result doesn't include key column " + column;
            }
            indexes.put(column, index);
        }
        keyColumns = indexes;
        return null;
    }

    private void editsChanged() {
        if (submitting) {
            return;
        }
        info.setForeground(JBUI.CurrentTheme.ContextHelp.FOREGROUND);
        String pending = pendingSummary(grid.editedCellCount(), grid.deletedRowCount(), "changed cell", "deleted row");
        info.setText(pending.isEmpty() ? resultInfo : resultInfo + "  ·  " + pending + " pending — Submit to save");
    }

    /** "2 changed cells, 1 deleted row" (either part left out when zero). */
    private static @NotNull String pendingSummary(int first, int second, @NotNull String firstNoun,
                                                  @NotNull String secondNoun) {
        List<String> parts = new java.util.ArrayList<>();
        if (first > 0) {
            parts.add(first + " " + firstNoun + (first == 1 ? "" : "s"));
        }
        if (second > 0) {
            parts.add(second + " " + secondNoun + (second == 1 ? "" : "s"));
        }
        return String.join(", ", parts);
    }

    /** Edit action: shown when the host is a live result, enabled only while no cell editor is open. */
    private abstract class EditAction extends DumbAwareAction {
        EditAction(@NotNull String text, @NotNull String description, @Nullable javax.swing.Icon icon) {
            super(text, description, icon);
        }

        abstract boolean enabled();

        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setVisible(host != null && host.canRerun());
            e.getPresentation().setEnabled(!submitting && enabled());
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }
    }

    private final EditAction setNullAction = new EditAction("Set NULL", "Set the selected cells to NULL", null) {
        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            grid.setSelectedCellsNull();
        }

        @Override
        boolean enabled() {
            return grid.isWritable() && !grid.isEditing() && grid.getSelectedRowCount() > 0;
        }
    };

    private final EditAction deleteRowsAction = new EditAction("Delete Rows",
            "Mark the selected rows for deletion; Submit deletes them", AllIcons.General.Remove) {
        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            grid.deleteSelectedRows();
        }

        @Override
        boolean enabled() {
            return grid.isWritable() && !grid.isEditing() && grid.getSelectedRowCount() > 0;
        }
    };

    private final EditAction revertAction = new EditAction("Revert Changes",
            "Discard the edits and deletions not submitted yet", AllIcons.Actions.Rollback) {
        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            grid.revertEdits();
        }

        @Override
        boolean enabled() {
            return grid.hasEdits();
        }
    };

    private final EditAction submitAction = new EditAction("Submit",
            "Write the edited and deleted rows back to the database", IntellaDbIcons.SUBMIT) {
        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            submitEdits();
        }

        @Override
        boolean enabled() {
            return grid.hasEdits() || grid.isEditing();
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            super.update(e);
            e.getPresentation().setIcon(enabled() ? IntellaDbIcons.SUBMIT_PENDING : IntellaDbIcons.SUBMIT);
            e.getPresentation().setDescription(readOnlyReason != null ? readOnlyReason
                    : "Write the edited and deleted rows back to the database");
        }
    };

    /**
     * Shortcuts on the grid, as in IntelliJ's data editor — Submit Ctrl/Cmd+Enter (also while a
     * cell is being edited), Set NULL Ctrl+Alt+N / Cmd+Opt+N, Delete Rows Ctrl+Y / Cmd+Backspace —
     * and a context menu with the edit actions.
     */
    private void installEditActions() {
        int menu = com.intellij.openapi.util.SystemInfo.isMac ? java.awt.event.InputEvent.META_DOWN_MASK
                : java.awt.event.InputEvent.CTRL_DOWN_MASK;
        submitAction.registerCustomShortcutSet(new com.intellij.openapi.actionSystem.CustomShortcutSet(
                shortcut(java.awt.event.KeyEvent.VK_ENTER, java.awt.event.InputEvent.CTRL_DOWN_MASK),
                shortcut(java.awt.event.KeyEvent.VK_ENTER, java.awt.event.InputEvent.META_DOWN_MASK)), grid);
        setNullAction.registerCustomShortcutSet(new com.intellij.openapi.actionSystem.CustomShortcutSet(
                shortcut(java.awt.event.KeyEvent.VK_N, menu | java.awt.event.InputEvent.ALT_DOWN_MASK)), grid);
        deleteRowsAction.registerCustomShortcutSet(new com.intellij.openapi.actionSystem.CustomShortcutSet(
                com.intellij.openapi.util.SystemInfo.isMac
                        ? shortcut(java.awt.event.KeyEvent.VK_BACK_SPACE, java.awt.event.InputEvent.META_DOWN_MASK)
                        : shortcut(java.awt.event.KeyEvent.VK_Y, java.awt.event.InputEvent.CTRL_DOWN_MASK)), grid);
        DefaultActionGroup popup = new DefaultActionGroup();
        popup.add(setNullAction);
        popup.add(deleteRowsAction);
        popup.addSeparator();
        popup.add(revertAction);
        popup.add(submitAction);
        com.intellij.ui.PopupHandler.installPopupMenu(grid, popup, "IntellaDbResultsPopup");
    }

    private static @NotNull com.intellij.openapi.actionSystem.KeyboardShortcut shortcut(int key, int modifiers) {
        return new com.intellij.openapi.actionSystem.KeyboardShortcut(javax.swing.KeyStroke.getKeyStroke(key, modifiers), null);
    }

    /** Writes the pending edits back: one UPDATE per edited row and one DELETE per deleted row, all or nothing. */
    private void submitEdits() {
        if (submitting || editTable == null || host == null || host.config() == null || !grid.finishCellEditing()
                || !grid.hasEdits()) {
            return;
        }
        SqlResult shown = result;
        SessionOpener.getInstance(project).withSession(host.config(), session -> {
            if (result != shown || submitting || !grid.hasEdits()) {
                return; // replaced (rerun) or already submitted while connecting
            }
            List<String> statements = changeStatements();
            int updated = grid.edits().size();
            int deleted = grid.deletedRowCount();
            submitting = true;
            grid.setEditsLocked(true);
            info.setText("Saving " + statements.size() + " row" + (statements.size() == 1 ? "" : "s") + "…");
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                SqlResult outcome = session.applyRowUpdates(statements);
                ApplicationManager.getApplication().invokeLater(() ->
                        submitted(shown, statements, outcome, pendingSummary(updated, deleted, "row updated", "row deleted")));
            });
        });
    }

    private @NotNull List<String> changeStatements() {
        List<String> statements = new java.util.ArrayList<>();
        grid.edits().forEach((row, cells) -> {
            java.util.Map<String, Object> changes = new java.util.LinkedHashMap<>();
            cells.forEach((column, value) -> changes.put(result.sourceColumn(column), value));
            statements.add(dev.phucngu.intelladb.sql.RowUpdates.update(dialect(), editTable,
                    new dev.phucngu.intelladb.sql.RowUpdates.Edit(changes, loadedKey(row))));
        });
        for (int row : grid.deletedRows()) {
            statements.add(dev.phucngu.intelladb.sql.RowUpdates.delete(dialect(), editTable, loadedKey(row)));
        }
        return statements;
    }

    /** Key columns of model row {@code row} with the values it was loaded with. */
    private @NotNull java.util.Map<String, Object> loadedKey(int row) {
        Object[] loaded = grid.loadedRow(row);
        java.util.Map<String, Object> key = new java.util.LinkedHashMap<>();
        keyColumns.forEach((column, index) -> key.put(column, index < loaded.length ? loaded[index] : null));
        return key;
    }

    private void submitted(@NotNull SqlResult shown, @NotNull List<String> statements, @NotNull SqlResult outcome,
                           @NotNull String summary) {
        submitting = false;
        grid.setEditsLocked(false);
        if (host != null) {
            host.rowsUpdated(statements, outcome);
        }
        if (result != shown) {
            return; // the panel shows another result by now
        }
        if (outcome.isSuccessful()) {
            grid.acceptEdits();
            resultInfo = rowsInfo(result); // deleted rows are gone from the count
            boolean pending = outcome.text.contains("pending");
            info.setText(resultInfo + "  ·  " + summary + (pending ? " (pending: commit to keep)" : ""));
        } else {
            editsChanged();
            info.setText("Submit failed: " + outcome.text);
            info.setForeground(JBUI.CurrentTheme.Label.errorForeground());
        }
    }

    /** "CSV ▾" — picks the data extractor used by Copy and Export (remembered across sessions). */
    private static final class ExtractorComboAction extends ComboBoxAction {
        ExtractorComboAction() {
            setSmallVariant(true);
            getTemplatePresentation().setDescription("Data extractor used by Copy and Export");
        }

        @Override
        protected @NotNull DefaultActionGroup createPopupActionGroup(@NotNull JComponent button,
                                                                     @NotNull DataContext context) {
            DefaultActionGroup group = new DefaultActionGroup();
            for (ResultExporter.Format format : ResultExporter.Format.values()) {
                group.add(new DumbAwareAction(format.label) {
                    @Override
                    public void actionPerformed(@NotNull AnActionEvent e) {
                        PropertiesComponent.getInstance().setValue(EXTRACTOR_KEY, format.name());
                    }
                });
            }
            return group;
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setText(selectedFormat().label);
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }
    }
}
