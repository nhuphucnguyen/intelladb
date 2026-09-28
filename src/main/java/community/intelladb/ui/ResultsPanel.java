package community.intelladb.ui;

import com.intellij.icons.AllIcons;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.ex.ComboBoxAction;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.DumbAwareToggleAction;
import com.intellij.openapi.project.Project;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import community.intelladb.connection.ConnectionManager;
import community.intelladb.connection.DbConfig;
import community.intelladb.connection.DbSession;
import community.intelladb.connection.SqlResult;
import community.intelladb.schema.DdlGenerator;
import community.intelladb.schema.SchemaCatalog;
import community.intelladb.schema.TableMeta;
import community.intelladb.util.ResultExporter;
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
 */
public final class ResultsPanel extends JPanel {

    /** What the toolbar's Rerun / Cancel act on. */
    public interface Host {
        void rerun(@NotNull ResultsPanel panel);

        void cancel();

        /** Connection the rows come from — names the export source and finds its DDL. */
        default @Nullable DbConfig config() {
            return null;
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
        grid.setResult(null);
        grid.getEmptyText().setText(message);
        info.setText(message);
        info.setForeground(JBUI.CurrentTheme.ContextHelp.FOREGROUND);
    }

    public void showResult(@NotNull SqlResult result) {
        running = false;
        this.result = result;
        info.setForeground(JBUI.CurrentTheme.ContextHelp.FOREGROUND);
        switch (result.kind) {
            case ROWS -> {
                grid.setResult(result);
                grid.getEmptyText().setText("No rows");
                int count = result.rows.size();
                String note = result.truncated ? " (first " + SqlResult.MAX_ROWS + ")" : "";
                info.setText(count + " row" + (count == 1 ? "" : "s") + note + "  ·  " + result.durationMs + " ms");
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
        return ResultExporter.export(selectedFormat(), result.columns, rows, insertTarget());
    }

    /** Table for SQL Inserts: from the driver's column metadata, else what the opener told us. */
    private @Nullable String insertTarget() {
        String fromResult = result == null ? null : result.qualifiedSource();
        return fromResult != null ? fromResult : sourceTable;
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
                source, target, ddlFor(config)).show();
    }

    /** CREATE TABLE for the result's source table, when the connection's catalog knows it. */
    private @Nullable String ddlFor(@Nullable DbConfig config) {
        if (config == null || result == null || result.sourceTable == null) {
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
                    return DdlGenerator.generate(new SchemaCatalog(List.of(
                            new SchemaCatalog.Schema(schema.name(), List.of(table)))));
                }
            }
        }
        return null;
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
