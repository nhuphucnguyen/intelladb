package dev.phucngu.intelladb.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.EditorSettings;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.CollectionListModel;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.JBColor;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import dev.phucngu.intelladb.IntellaDbIcons;
import dev.phucngu.intelladb.connection.ConnectionManager;
import dev.phucngu.intelladb.connection.DbConfig;
import dev.phucngu.intelladb.connection.SqlResult;
import dev.phucngu.intelladb.history.QueryHistory;
import dev.phucngu.intelladb.sql.IntellaSqlFileType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Query history inside the DB Services view: {@link #listComponent()} (recent queries,
 * newest first, with filter and "keep last N") sits under the connection/console tree;
 * {@link #detailComponent()} (the selected query's SQL and cached result) is shown on the
 * right. Moving through the list with the arrow keys swaps the result instantly —
 * nothing is re-run — so recent results can be skimmed and compared.
 */
final class QueryHistoryView implements Disposable {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final Project project;
    private final QueryHistory history;
    private final CollectionListModel<QueryHistory.Entry> listModel = new CollectionListModel<>();
    private final JBList<QueryHistory.Entry> list = new JBList<>(listModel);
    private final SearchTextField search = new SearchTextField(false);
    private final JBLabel summary = new JBLabel(" ");
    private final Document sqlDocument = EditorFactory.getInstance().createDocument("");
    private final EditorEx sqlViewer;
    private final ResultsPanel results;
    private final JPanel listSide;
    private final JPanel detailSide;
    private final Runnable onSelect;
    private @Nullable QueryHistory.Entry shown;

    /** @param onSelect called when the user picks an entry (the host shows {@link #detailComponent()}) */
    QueryHistoryView(@NotNull Project project, @NotNull Runnable onSelect) {
        this.project = project;
        this.onSelect = onSelect;
        this.history = QueryHistory.getInstance(project);
        this.sqlViewer = (EditorEx) EditorFactory.getInstance().createViewer(sqlDocument, project);
        this.results = new ResultsPanel(project, new ResultsPanel.Host() {
            @Override
            public boolean canRerun() {
                return false; // a cached result: "Open in Console" runs it again
            }

            @Override
            public void rerun(@NotNull ResultsPanel panel) {
            }

            @Override
            public void cancel() {
            }

            @Override
            public @Nullable DbConfig config() {
                return shown == null ? null : configOf(shown);
            }
        }, false);
        configureViewer();

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new EntryRenderer());
        list.getEmptyText().setText("No queries yet");
        list.getEmptyText().appendLine("Run a statement in a SQL console — it is kept here with its result",
                SimpleTextAttributes.GRAYED_ATTRIBUTES, null);
        list.addListSelectionListener(e -> {
            QueryHistory.Entry selected = list.getSelectedValue();
            if (!e.getValueIsAdjusting() && selected != null) {
                show(selected);
                onSelect.run();
            }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(@NotNull MouseEvent e) {
                if (e.getClickCount() == 2) {
                    openInConsole();
                }
            }
        });
        list.registerKeyboardAction(e -> openInConsole(),
                javax.swing.KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), JComponent.WHEN_FOCUSED);
        search.addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull javax.swing.event.DocumentEvent e) {
                reload();
            }
        });

        listSide = buildListSide();
        detailSide = buildDetailSide();
        history.addListener(this::reload, this);
        reload();
    }

    /** The history list with its filter and settings — placed under the console tree. */
    @NotNull JComponent listComponent() {
        return listSide;
    }

    /** SQL + cached result of the selected entry — placed in the right-hand content area. */
    @NotNull JComponent detailComponent() {
        return detailSide;
    }

    /** Deselects (the host switched to a console); the detail keeps its last content. */
    void clearSelection() {
        list.clearSelection();
    }

    boolean isEmptySelection() {
        return list.isSelectionEmpty();
    }

    /** Focuses the list, selecting the newest entry when nothing is selected. */
    void focusList() {
        if (list.isSelectionEmpty() && listModel.getSize() > 0) {
            list.setSelectedIndex(0);
        }
        list.requestFocusInWindow();
    }

    // ------------------------------------------------------------------ layout

    private @NotNull JPanel buildListSide() {
        JBLabel title = new JBLabel("Query History");
        title.setBorder(JBUI.Borders.empty(0, 2, 4, 0));
        search.getTextEditor().getEmptyText().setText("Filter by SQL or connection");
        JPanel top = new JPanel(new BorderLayout());
        top.setBorder(JBUI.Borders.empty(6, 4, 4, 4));
        top.add(title, BorderLayout.NORTH);
        top.add(search, BorderLayout.CENTER);

        SpinnerNumberModel limitModel = new SpinnerNumberModel(history.limit(),
                QueryHistory.MIN_LIMIT, QueryHistory.MAX_LIMIT, 5);
        JSpinner limit = new JSpinner(limitModel);
        limit.setToolTipText("How many recent queries (with their results) to keep");
        limit.addChangeListener(e -> {
            QueryHistory.setConfiguredLimit(((Number) limit.getValue()).intValue());
            history.limitChanged();
        });
        JPanel keep = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0));
        keep.add(new JBLabel("Keep last"));
        keep.add(limit);

        DefaultActionGroup clearGroup = new DefaultActionGroup();
        clearGroup.add(new DumbAwareAction("Clear History", "Forget all recorded queries and results",
                AllIcons.Actions.GC) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                history.clear();
            }

            @Override
            public void update(@NotNull AnActionEvent e) {
                e.getPresentation().setEnabled(listModel.getSize() > 0);
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }
        });
        ActionToolbar clearBar = ActionManager.getInstance().createActionToolbar("IntellaDbHistoryList", clearGroup, true);
        clearBar.setTargetComponent(list);

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(keep, BorderLayout.CENTER);
        bottom.add(clearBar.getComponent(), BorderLayout.EAST);
        bottom.setBorder(JBUI.Borders.compound(JBUI.Borders.customLineTop(JBColor.border()),
                JBUI.Borders.empty(2, 2)));

        JBScrollPane scroll = new JBScrollPane(list);
        scroll.setBorder(JBUI.Borders.customLineTop(JBColor.border()));
        JPanel side = new JPanel(new BorderLayout());
        side.add(top, BorderLayout.NORTH);
        side.add(scroll, BorderLayout.CENTER);
        side.add(bottom, BorderLayout.SOUTH);
        return side;
    }

    private @NotNull JPanel buildDetailSide() {
        DefaultActionGroup actions = new DefaultActionGroup();
        actions.add(new DumbAwareAction("Open in Console", "Put this query into its connection's console (Enter)",
                AllIcons.Actions.EditSource) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                openInConsole();
            }

            @Override
            public void update(@NotNull AnActionEvent e) {
                e.getPresentation().setEnabled(shown != null && configOf(shown) != null);
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }
        });
        actions.add(new DumbAwareAction("Copy SQL", "Copy the query text", AllIcons.Actions.Copy) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                if (shown != null) {
                    CopyPasteManager.getInstance().setContents(new StringSelection(shown.sql()));
                }
            }

            @Override
            public void update(@NotNull AnActionEvent e) {
                e.getPresentation().setEnabled(shown != null);
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }
        });
        ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar("IntellaDbHistory", actions, true);
        toolbar.setTargetComponent(sqlViewer.getContentComponent());

        summary.setForeground(JBUI.CurrentTheme.ContextHelp.FOREGROUND);
        summary.setBorder(JBUI.Borders.emptyLeft(8));
        JPanel header = new JPanel(new BorderLayout());
        header.add(summary, BorderLayout.CENTER);
        header.add(toolbar.getComponent(), BorderLayout.EAST);
        header.setBorder(JBUI.Borders.customLineBottom(JBColor.border()));

        JPanel sqlPane = new JPanel(new BorderLayout());
        sqlPane.add(header, BorderLayout.NORTH);
        sqlPane.add(sqlViewer.getComponent(), BorderLayout.CENTER);

        JBSplitter detail = new JBSplitter(true, 0.25f);
        detail.setFirstComponent(sqlPane);
        detail.setSecondComponent(results);
        JPanel side = new JPanel(new BorderLayout());
        side.add(detail, BorderLayout.CENTER);
        results.showMessage("Select a query in the history to see its result");
        return side;
    }

    private void configureViewer() {
        EditorSettings settings = sqlViewer.getSettings();
        settings.setLineNumbersShown(false);
        settings.setFoldingOutlineShown(false);
        settings.setLineMarkerAreaShown(false);
        settings.setIndentGuidesShown(false);
        settings.setCaretRowShown(false);
        settings.setAdditionalLinesCount(0);
        settings.setUseSoftWraps(true);
        sqlViewer.setHighlighter(EditorHighlighterFactory.getInstance()
                .createEditorHighlighter(project, IntellaSqlFileType.INSTANCE));
    }

    // ------------------------------------------------------------------ behaviour

    /** Re-applies the filter; keeps the selected entry, else selects the newest. */
    private void reload() {
        String filter = search.getText().trim().toLowerCase(Locale.ROOT);
        List<QueryHistory.Entry> entries = history.entries().stream()
                .filter(e -> filter.isEmpty()
                        || e.sql().toLowerCase(Locale.ROOT).contains(filter)
                        || e.connectionName().toLowerCase(Locale.ROOT).contains(filter))
                .toList();
        // Keeps the entry being viewed selected; never auto-selects, so a new query
        // arriving does not take the right-hand side away from the console's results.
        QueryHistory.Entry selected = list.getSelectedValue();
        listModel.replaceAll(entries);
        int index = selected == null ? -1 : indexOf(entries, selected.id());
        if (index >= 0) {
            list.setSelectedIndex(index);
            list.ensureIndexIsVisible(index);
        }
    }

    private static int indexOf(@NotNull List<QueryHistory.Entry> entries, long id) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).id() == id) {
                return i;
            }
        }
        return -1;
    }

    private void show(@Nullable QueryHistory.Entry entry) {
        if (entry != null && shown != null && entry.id() == shown.id()) {
            return;
        }
        shown = entry;
        String sql = entry == null ? "" : StringUtil.convertLineSeparators(entry.sql());
        CommandProcessor.getInstance().runUndoTransparentAction(() ->
                WriteAction.run(() -> sqlDocument.setText(sql)));
        if (entry == null) {
            summary.setText(" ");
            results.showMessage("Select a query to see its result");
            return;
        }
        summary.setText(entry.executedAt().format(TIME) + "  ·  @" + entry.connectionName()
                + (entry.schema() != null ? "  ·  " + entry.schema() : "") + "  ·  " + outcome(entry.result()));
        results.showResult(entry.result());
    }

    private void openInConsole() {
        QueryHistory.Entry entry = shown;
        DbConfig config = entry == null ? null : configOf(entry);
        DbExplorerPanel explorer = DbExplorerPanel.find(project);
        if (config != null && explorer != null) {
            explorer.openConsole(config, entry.sql());
        }
    }

    private @Nullable DbConfig configOf(@NotNull QueryHistory.Entry entry) {
        for (DbConfig config : ConnectionManager.getInstance(project).configs()) {
            if (config.id.equals(entry.connectionId())) {
                return config;
            }
        }
        return null; // connection deleted since
    }

    private static @NotNull String outcome(@NotNull SqlResult result) {
        return switch (result.kind) {
            case ROWS -> result.rows.size() + " row" + (result.rows.size() == 1 ? "" : "s")
                    + (result.truncated ? "+" : "") + " · " + result.durationMs + " ms";
            case UPDATE_COUNT -> result.updateCount + " affected · " + result.durationMs + " ms";
            case MESSAGE -> "completed · " + result.durationMs + " ms";
            case ERROR -> "failed";
        };
    }

    private static final class EntryRenderer extends ColoredListCellRenderer<QueryHistory.Entry> {
        @Override
        protected void customizeCellRenderer(@NotNull JList<? extends QueryHistory.Entry> list,
                                             QueryHistory.Entry entry, int index, boolean selected, boolean focus) {
            setIcon(iconOf(entry.result()));
            append(entry.executedAt().format(TIME) + "  ", SimpleTextAttributes.GRAYED_ATTRIBUTES);
            String oneLine = entry.sql().replaceAll("\\s+", " ");
            append(StringUtil.shortenTextWithEllipsis(oneLine, 140, 0), SimpleTextAttributes.REGULAR_ATTRIBUTES);
            append("  " + outcome(entry.result()),
                    entry.result().kind == SqlResult.Kind.ERROR
                            ? SimpleTextAttributes.ERROR_ATTRIBUTES : SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
            setToolTipText("@" + entry.connectionName() + " · " + entry.executedAt().format(TIME) + "\n" + entry.sql());
        }

        private static @NotNull Icon iconOf(@NotNull SqlResult result) {
            return switch (result.kind) {
                case ROWS -> IntellaDbIcons.TABLE;
                case ERROR -> AllIcons.General.Error;
                default -> AllIcons.General.InspectionsOK;
            };
        }
    }

    @Override
    public void dispose() {
        EditorFactory.getInstance().releaseEditor(sqlViewer);
    }
}
