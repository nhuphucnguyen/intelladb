package dev.phucngu.intelladb.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CustomShortcutSet;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.KeyboardShortcut;
import com.intellij.openapi.actionSystem.ex.ComboBoxAction;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.testFramework.LightVirtualFile;
import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;
import dev.phucngu.intelladb.IntellaDbIcons;
import dev.phucngu.intelladb.connection.DbConfig;
import dev.phucngu.intelladb.connection.DbDialect;
import dev.phucngu.intelladb.connection.DbSession;
import dev.phucngu.intelladb.connection.NamespaceModel;
import dev.phucngu.intelladb.connection.SqlResult;
import dev.phucngu.intelladb.history.QueryHistory;
import dev.phucngu.intelladb.schema.SchemaCatalog;
import dev.phucngu.intelladb.sql.IntellaSqlFileType;
import dev.phucngu.intelladb.sql.SqlColumnValueAid;
import dev.phucngu.intelladb.util.SqlSplitter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import java.awt.BorderLayout;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A SQL console the way IntelliJ's database tools do it: a plain text-editor tab (full
 * editor features, gutter, undo) with a header toolbar — Execute, Tx: Auto/Manual with
 * Commit/Rollback, Cancel, Playground/Script mode and the schema switcher — while every
 * result goes to the "DB Services" tool window (Output log + one tab per result set).
 * Executed statements get a ✓/✗ gutter mark and an inline timing.
 */
public final class SqlConsole implements Disposable, ResultsPanel.Host {

    /** Links a console file back to its console. */
    static final Key<SqlConsole> KEY = Key.create("intelladb.sqlConsole");
    private static final Key<Boolean> HEADER_INSTALLED = Key.create("intelladb.sqlConsole.header");
    private static final Pattern FROM_TABLE = Pattern.compile(
            "(?is)^\\s*(?:select|table)\\b.*?\\bfrom\\s+((?:\"[^\"]+\"|`[^`]+`|[\\w$]+)"
                    + "(?:\\.(?:\"[^\"]+\"|`[^`]+`|[\\w$]+))?)");

    enum TxMode { AUTO, MANUAL }

    enum RunMode {
        PLAYGROUND("Playground", "Execute the statement at the caret (or the selection)"),
        SCRIPT("Script", "Execute the whole console, statement by statement");

        final String label;
        final String description;

        RunMode(@NotNull String label, @NotNull String description) {
            this.label = label;
            this.description = description;
        }
    }

    private final Project project;
    private final DbExplorerPanel explorer;
    private final DbConfig config;
    private final LightVirtualFile file;
    private final Document document;
    private final ExecutionMarkers markers;

    private TxMode txMode;
    private RunMode runMode = RunMode.PLAYGROUND;
    private @Nullable String schema;
    /** Header toolbars of this console's open editors, refreshed when the schema changes. */
    private final List<ActionToolbar> toolbars = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile boolean running;
    private volatile boolean cancelled;

    SqlConsole(@NotNull Project project, @NotNull DbExplorerPanel explorer, @NotNull DbConfig config) {
        this.project = project;
        this.explorer = explorer;
        this.config = config;
        this.txMode = config.autoCommit ? TxMode.AUTO : TxMode.MANUAL;
        this.file = new LightVirtualFile("console.sql", IntellaSqlFileType.INSTANCE,
                "-- SQL for " + config.describe() + "\n");
        file.putUserData(KEY, this);
        this.document = FileDocumentManager.getInstance().getDocument(file);
        document.putUserData(SqlColumnValueAid.CONSOLE_DOCUMENT, true);
        this.markers = new ExecutionMarkers(project, document);
        restore();
    }

    /** Picks up the text and schema the console had before the IDE restarted; keeps them saved. */
    private void restore() {
        ConsoleStore store = ConsoleStore.getInstance(project);
        ConsoleStore.ConsoleState saved = store.find(config.id);
        if (saved != null) {
            if (!saved.sql.isEmpty()) {
                WriteCommandAction.runWriteCommandAction(project, () -> document.setText(saved.sql));
            }
            schema = saved.schema;
        }
        document.addDocumentListener(new com.intellij.openapi.editor.event.DocumentListener() {
            @Override
            public void documentChanged(@NotNull com.intellij.openapi.editor.event.DocumentEvent event) {
                store.setSql(config.id, document.getText());
            }
        }, this);
    }

    public @NotNull DbConfig config() {
        return config;
    }

    @NotNull LightVirtualFile file() {
        return file;
    }

    /** Tab / tree title, e.g. {@code console [@localhost]}. */
    @NotNull String title() {
        return "console [@" + config.name + "]";
    }

    public @NotNull String sql() {
        return document.getText();
    }

    public void setSql(@NotNull String sql) {
        // Documents may only change inside a command, not a bare write action.
        WriteCommandAction.runWriteCommandAction(project, () ->
                document.replaceString(0, document.getTextLength(), sql));
    }

    /** Makes {@code name} the console's default schema, as picking it in the schema switcher does. */
    public void setSchema(@NotNull String name) {
        schema = name;
        ConsoleStore.getInstance(project).setSchema(config.id, name);
        toolbars.forEach(ActionToolbar::updateActionsAsync); // show it now, not on the next UI tick
    }

    /**
     * Adds {@code comment} + {@code sql} at the very end of the console, a blank line after
     * the existing text, and puts the caret on the SQL so Execute (Playground) runs it.
     */
    public void appendSql(@NotNull String comment, @NotNull String sql) {
        WriteCommandAction.runWriteCommandAction(project, () -> {
            String text = document.getText();
            String separator = text.isBlank() ? "" : text.endsWith("\n\n") ? "" : text.endsWith("\n") ? "\n" : "\n\n";
            int start = document.getTextLength() + separator.length() + comment.length();
            document.insertString(document.getTextLength(), separator + comment + sql + "\n");
            Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
            if (editor != null && editor.getDocument() == document) {
                editor.getCaretModel().moveToOffset(start);
                editor.getScrollingModel().scrollToCaret(com.intellij.openapi.editor.ScrollType.MAKE_VISIBLE);
            }
        });
    }

    // ------------------------------------------------------------------ editor header

    /** Adds the console toolbar above a text editor showing this console (idempotent). */
    void installHeader(@NotNull FileEditor fileEditor) {
        if (!(fileEditor instanceof TextEditor textEditor) || fileEditor.getUserData(HEADER_INSTALLED) != null) {
            return;
        }
        fileEditor.putUserData(HEADER_INSTALLED, true);
        Editor editor = textEditor.getEditor();
        editor.getSettings().setLineMarkerAreaShown(true);

        ExecuteAction execute = new ExecuteAction(editor);
        execute.registerCustomShortcutSet(new CustomShortcutSet(
                new KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK), null),
                new KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.META_DOWN_MASK), null)),
                editor.getContentComponent(), fileEditor);

        DefaultActionGroup left = new DefaultActionGroup();
        left.add(execute);
        left.addSeparator();
        left.add(new TxModeAction());
        left.add(new EndTransactionAction(true));
        left.add(new EndTransactionAction(false));
        left.addSeparator();
        left.add(new DumbAwareAction("Cancel", "Cancel the running statement", AllIcons.Actions.Suspend) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                cancel();
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
        left.addSeparator();
        left.add(new RunModeAction());
        left.add(new DumbAwareAction("Query History", "Recent queries with their results",
                AllIcons.Vcs.History) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                ResultsHub.getInstance(project).showHistory();
            }
        });
        left.add(new DumbAwareAction("Show Results", "Show this console's results in DB Services",
                AllIcons.Toolwindows.ToolWindowServices) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                ResultsHub.getInstance(project).viewFor(SqlConsole.this);
                ResultsHub.getInstance(project).reveal(SqlConsole.this);
            }
        });

        DefaultActionGroup right = new DefaultActionGroup();
        right.add(new SchemaAction());

        ActionToolbar leftBar = ActionManager.getInstance().createActionToolbar("IntellaDbConsole", left, true);
        leftBar.setTargetComponent(editor.getContentComponent());
        ActionToolbar rightBar = ActionManager.getInstance().createActionToolbar("IntellaDbConsoleSchema", right, true);
        rightBar.setTargetComponent(editor.getContentComponent());
        toolbars.add(rightBar);
        com.intellij.openapi.util.Disposer.register(fileEditor, () -> toolbars.remove(rightBar));

        JPanel header = new JPanel(new BorderLayout());
        header.add(leftBar.getComponent(), BorderLayout.WEST);
        header.add(rightBar.getComponent(), BorderLayout.EAST);
        header.setBorder(JBUI.Borders.customLineBottom(JBColor.border()));
        FileEditorManager.getInstance(project).addTopComponent(fileEditor, header);
    }

    // ------------------------------------------------------------------ execution

    /** Execute (Ctrl/Cmd+Enter): statement at caret / selection (Playground) or everything (Script). */
    void execute(@NotNull Editor editor) {
        if (running) {
            return;
        }
        List<SqlSplitter.Statement> statements = pick(editor);
        if (statements.isEmpty()) {
            return;
        }
        explorer.withSession(config, session -> start(session, statements, null));
    }

    private @NotNull List<SqlSplitter.Statement> pick(@NotNull Editor editor) {
        String text = document.getText();
        SelectionModel selection = editor.getSelectionModel();
        if (selection.hasSelection()) {
            int base = selection.getSelectionStart();
            List<SqlSplitter.Statement> shifted = new ArrayList<>();
            for (SqlSplitter.Statement s : SqlSplitter.ranges(text.substring(base, selection.getSelectionEnd()),
                    config.dialect().splitterOptions())) {
                shifted.add(new SqlSplitter.Statement(base + s.start(), base + s.end(), s.text()));
            }
            return shifted;
        }
        if (runMode == RunMode.SCRIPT) {
            return SqlSplitter.ranges(text, config.dialect().splitterOptions());
        }
        SqlSplitter.Statement atCaret = SqlSplitter.at(text, editor.getCaretModel().getOffset(), config.dialect().splitterOptions());
        return atCaret == null ? List.of() : List.of(atCaret);
    }

    @Override
    public void rerun(@NotNull ResultsPanel panel) {
        SqlResult previous = panel.result();
        if (previous == null || running) {
            return;
        }
        explorer.withSession(config, session ->
                start(session, List.of(new SqlSplitter.Statement(-1, -1, previous.sql)), panel));
    }

    @Override
    public void cancel() {
        if (!running) {
            return;
        }
        cancelled = true;
        DbSession session = explorer.sessionOf(config);
        if (session != null) {
            ApplicationManager.getApplication().executeOnPooledThread(session::cancel);
        }
    }

    /**
     * Runs the statements on a pooled thread, streaming progress to the Output tab and each
     * result set into its own tab ({@code into} re-fills an existing tab instead — Rerun).
     * Stops at the first error, like a script run in IntelliJ.
     */
    private void start(@NotNull DbSession session, @NotNull List<SqlSplitter.Statement> statements,
                       @Nullable ResultsPanel into) {
        ResultsHub hub = ResultsHub.getInstance(project);
        ConsoleResultsView view = hub.viewFor(this);
        if (into == null) {
            view.beginRun();
        } else {
            into.showRunning();
        }
        hub.reveal(this);
        running = true;
        cancelled = false;
        boolean autoCommit = txMode == TxMode.AUTO;
        String targetSchema = schema;
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                session.setAutoCommit(autoCommit);
                applySchema(session, targetSchema);
            } catch (SQLException e) {
                edt(() -> view.logError(e.getMessage() == null ? e.toString() : e.getMessage()));
                edt(this::finish);
                return;
            }
            int resultIndex = 0;
            for (SqlSplitter.Statement statement : statements) {
                if (cancelled) {
                    edt(() -> view.logInfo("Execution cancelled"));
                    break;
                }
                edt(() -> view.logStatement(config.name, statement.text()));
                SqlResult result = session.execute(statement.text());
                int index = ++resultIndex;
                edt(() -> {
                    if (statement.start() >= 0) {
                        markers.mark(statement.start(), statement.end(), result);
                    }
                    view.logResult(result);
                    QueryHistory.getInstance(project).add(config, targetSchema, statement.text(), result);
                    if (into != null) {
                        into.showResult(result);
                    } else if (result.kind == SqlResult.Kind.ROWS) {
                        String fromDriver = result.qualifiedSource(config.dialect());
                        String table = fromDriver != null ? fromDriver : sourceTable(statement.text());
                        ResultsPanel panel = view.addResult(result,
                                table != null ? table : "Result " + index, this);
                        panel.setSourceTable(table);
                    } else if (result.kind == SqlResult.Kind.ERROR) {
                        view.selectOutput();
                    }
                });
                if (result.kind == SqlResult.Kind.ERROR) {
                    break;
                }
            }
            edt(this::finish);
        });
    }

    private void finish() {
        running = false;
        cancelled = false;
    }

    /**
     * Switches the session to the selected schema before every run (PostgreSQL: SET
     * search_path, MySQL: USE). Not cached: the session is shared between consoles, a
     * reconnect starts from the default, and a ROLLBACK undoes a SET made inside the
     * transaction — any of which silently put it back on the default schema.
     */
    private void applySchema(@NotNull DbSession session, @Nullable String target) throws SQLException {
        if (target == null) {
            return;
        }
        String statement = config.dialect().useNamespaceStatement(target);
        if (statement == null) {
            return;
        }
        SqlResult result = session.execute(statement);
        if (!result.isSuccessful()) {
            throw new SQLException(result.text);
        }
    }

    /**
     * Fallback when the driver cannot name one source table (e.g. a computed column):
     * {@code schema.table} for a simple SELECT … FROM t, else null (tab gets "Result n").
     */
    private @Nullable String sourceTable(@NotNull String sql) {
        DbDialect dialect = config.dialect();
        Matcher m = FROM_TABLE.matcher(SqlSplitter.stripLeadingComments(sql, dialect.splitterOptions()));
        if (!m.find()) {
            return null;
        }
        String table = m.group(1);
        if (table.contains(".")) {
            return table;
        }
        String effectiveSchema = schema != null ? schema : dialect.defaultSchema();
        return effectiveSchema == null ? table : dialect.quote(effectiveSchema) + "." + table;
    }

    private static void edt(@NotNull Runnable runnable) {
        ApplicationManager.getApplication().invokeLater(runnable);
    }

    private @NotNull List<String> schemaNames() {
        DbSession session = explorer.sessionOf(config);
        SchemaCatalog catalog = session == null ? null : session.catalog();
        if (catalog == null) {
            return List.of();
        }
        return catalog.schemas().stream().map(SchemaCatalog.Schema::name).toList();
    }

    @Override
    public void dispose() {
        markers.clearAll();
    }

    // ------------------------------------------------------------------ toolbar actions

    private final class ExecuteAction extends DumbAwareAction {
        private final Editor editor;

        ExecuteAction(@NotNull Editor editor) {
            super("Execute", "Execute (Ctrl/Cmd+Enter)", AllIcons.Actions.Execute);
            this.editor = editor;
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            execute(editor);
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabled(!running);
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }
    }

    /** "Tx: Auto ▾" — auto-commit vs. manual transaction. */
    private final class TxModeAction extends ComboBoxAction {
        TxModeAction() {
            setSmallVariant(true);
            getTemplatePresentation().setDescription("Transaction mode");
        }

        @Override
        protected @NotNull DefaultActionGroup createPopupActionGroup(@NotNull JComponent button,
                                                                     @NotNull DataContext context) {
            DefaultActionGroup group = new DefaultActionGroup();
            group.add(new DumbAwareAction("Auto", "Every statement commits on its own", null) {
                @Override
                public void actionPerformed(@NotNull AnActionEvent e) {
                    setTxMode(TxMode.AUTO);
                }
            });
            group.add(new DumbAwareAction("Manual", "Statements join a transaction until Commit/Rollback", null) {
                @Override
                public void actionPerformed(@NotNull AnActionEvent e) {
                    setTxMode(TxMode.MANUAL);
                }
            });
            return group;
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setText(txMode == TxMode.AUTO ? "Tx: Auto" : "Tx: Manual");
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }
    }

    private void setTxMode(@NotNull TxMode mode) {
        if (mode == txMode) {
            return;
        }
        txMode = mode;
        DbSession session = explorer.sessionOf(config);
        if (session == null) {
            return; // applied on the next run
        }
        ConsoleResultsView view = ResultsHub.getInstance(project).viewFor(this);
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                session.setAutoCommit(mode == TxMode.AUTO);
                edt(() -> view.logInfo(mode == TxMode.AUTO
                        ? "Auto-commit on (any pending transaction was committed)"
                        : "Manual transaction mode: use Commit / Rollback"));
            } catch (SQLException e) {
                edt(() -> view.logError(e.getMessage() == null ? e.toString() : e.getMessage()));
            }
        });
    }

    private final class EndTransactionAction extends DumbAwareAction {
        private final boolean commit;

        EndTransactionAction(boolean commit) {
            super(commit ? "Commit" : "Rollback",
                    commit ? "Commit the current transaction" : "Roll back the current transaction",
                    commit ? AllIcons.Actions.Commit : AllIcons.Actions.Rollback);
            this.commit = commit;
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            DbSession session = explorer.sessionOf(config);
            if (session == null) {
                return;
            }
            ConsoleResultsView view = ResultsHub.getInstance(project).viewFor(SqlConsole.this);
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                SqlResult result = commit ? session.commit() : session.rollback();
                edt(() -> view.logResult(result));
            });
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabled(txMode == TxMode.MANUAL && !running
                    && explorer.sessionOf(config) != null);
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }
    }

    /** "Playground ▾" — statement at caret vs. whole script. */
    private final class RunModeAction extends ComboBoxAction {
        RunModeAction() {
            setSmallVariant(true);
            getTemplatePresentation().setDescription("What Execute runs");
        }

        @Override
        protected @NotNull DefaultActionGroup createPopupActionGroup(@NotNull JComponent button,
                                                                     @NotNull DataContext context) {
            DefaultActionGroup group = new DefaultActionGroup();
            for (RunMode mode : RunMode.values()) {
                group.add(new DumbAwareAction(mode.label, mode.description, null) {
                    @Override
                    public void actionPerformed(@NotNull AnActionEvent e) {
                        runMode = mode;
                    }
                });
            }
            return group;
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setText(runMode.label);
            e.getPresentation().setDescription(runMode.description);
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }
    }

    /** "database.schema ▾" on the right of the header — default schema for this console. */
    private final class SchemaAction extends ComboBoxAction {
        SchemaAction() {
            setSmallVariant(true);
            getTemplatePresentation().setDescription("Default schema for this console");
        }

        @Override
        protected @NotNull DefaultActionGroup createPopupActionGroup(@NotNull JComponent button,
                                                                     @NotNull DataContext context) {
            DefaultActionGroup group = new DefaultActionGroup();
            List<String> names = schemaNames();
            if (names.isEmpty()) {
                group.add(new DumbAwareAction("Connect to Load Schemas", null, AllIcons.Actions.Execute) {
                    @Override
                    public void actionPerformed(@NotNull AnActionEvent e) {
                        explorer.withSession(config, session -> explorer.refreshTree());
                    }
                });
                return group;
            }
            for (String name : names) {
                group.add(new DumbAwareAction(name, null, IntellaDbIcons.SCHEMA) {
                    @Override
                    public void actionPerformed(@NotNull AnActionEvent e) {
                        setSchema(name);
                    }
                });
            }
            return group;
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            DbDialect dialect = config.dialect();
            String fallback = dialect.defaultSchema();
            if (fallback == null && dialect.namespaces() == NamespaceModel.SCHEMAS_ONLY) {
                fallback = config.database; // the database from the connection settings is the default
            }
            String shown = schema != null ? schema
                    : (fallback != null && schemaNames().contains(fallback) ? fallback : "<schema>");
            // With catalogs as schemas there is no database level to prefix.
            e.getPresentation().setText(dialect.namespaces() == NamespaceModel.SCHEMAS_ONLY ? shown
                    : (config.database.isBlank() ? config.name : config.database) + "." + shown);
            e.getPresentation().setIcon(IntellaDbIcons.SCHEMA);
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }
    }
}
