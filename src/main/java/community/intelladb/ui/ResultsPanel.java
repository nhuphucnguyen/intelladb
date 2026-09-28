package community.intelladb.ui;

import com.intellij.icons.AllIcons;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.ex.ComboBoxAction;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.DumbAwareToggleAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFileWrapper;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import community.intelladb.connection.SqlResult;
import community.intelladb.util.ResultExporter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * A result set view: {@link ResultGrid} plus, when a {@link Host} is given, the results
 * toolbar of IntelliJ's database tools — rerun, cancel, pin, a data-extractor selector
 * (CSV / TSV / JSON / SQL Inserts / Markdown) with copy-to-clipboard and export-to-file,
 * and the row count / timing. Without a host it is the compact grid used inline by the
 * AI chat.
 */
public final class ResultsPanel extends JPanel {

    /** What the toolbar's Rerun / Cancel act on. */
    public interface Host {
        void rerun(@NotNull ResultsPanel panel);

        void cancel();
    }

    private static final String EXTRACTOR_KEY = "intelladb.results.extractor";

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
        right.add(new DumbAwareAction("Copy to Clipboard", "Copy all rows in the selected format",
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
        right.add(new DumbAwareAction("Export to File…", "Save all rows in the selected format",
                AllIcons.ToolbarDecorator.Export) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                exportToFile();
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
        return ResultExporter.export(selectedFormat(), result.columns, result.rows, sourceTable);
    }

    private void exportToFile() {
        String text = exportText();
        if (text == null) {
            return;
        }
        ResultExporter.Format format = selectedFormat();
        String baseName = sourceTable != null ? sourceTable.replaceAll("[^\\w.-]", "_") : "result";
        VirtualFileWrapper target = FileChooserFactory.getInstance()
                .createSaveFileDialog(new FileSaverDescriptor("Export Data", "Save rows as " + format.label,
                        format.extension), project)
                .save(baseName + "." + format.extension);
        if (target == null) {
            return;
        }
        try {
            Files.writeString(target.getFile().toPath(), text, StandardCharsets.UTF_8);
            notify(result.rows.size() + " row(s) exported to " + target.getFile().getName(), NotificationType.INFORMATION);
        } catch (IOException e) {
            notify("Export failed: " + e.getMessage(), NotificationType.ERROR);
        }
    }

    private void notify(@NotNull String message, @NotNull NotificationType type) {
        NotificationGroupManager.getInstance().getNotificationGroup("IntellaDB")
                .createNotification(message, type).notify(project);
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
