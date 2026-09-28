package community.intelladb.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.CustomShortcutSet;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.EditorTextField;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.table.TableView;
import com.intellij.util.ui.JBUI;
import community.intelladb.connection.DbConfig;
import community.intelladb.sql.SqlColumnValueAid;
import community.intelladb.connection.DbSession;
import community.intelladb.connection.SqlResult;
import community.intelladb.util.SqlSplitter;
import org.jetbrains.annotations.NotNull;

import javax.swing.JPanel;
import javax.swing.KeyStroke;
import java.awt.BorderLayout;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.List;

/**
 * SQL console: editor on top, results grid below, message log for multi-statement scripts.
 * Ctrl/Cmd+Enter runs the script.
 */
public final class ConsolePanel extends JPanel {

    private final Project project;
    private final DbExplorerPanel explorer;
    private final DbConfig config;
    private final EditorTextField editor;
    private final ResultsPanel results = new ResultsPanel();
    private final JBLabel status = new JBLabel(" ");

    public ConsolePanel(@NotNull Project project, @NotNull DbExplorerPanel explorer, @NotNull DbConfig config) {
        super(new BorderLayout());
        this.project = project;
        this.explorer = explorer;
        this.config = config;

        Document document = EditorFactory.getInstance().createDocument("-- SQL for " + config.describe() + "\n");
        document.putUserData(SqlColumnValueAid.CONSOLE_DOCUMENT, true);
        editor = new EditorTextField(document, project, sqlFileType(), false, false);
        editor.setOneLineMode(false);
        editor.setPreferredSize(JBUI.size(600, 180));
        editor.addSettingsProvider(ed ->
                ed.getDocument().putUserData(SqlColumnValueAid.CONSOLE_DOCUMENT, true));

        JPanel editorPane = new JPanel(new BorderLayout());
        editorPane.add(toolbar(), BorderLayout.NORTH);
        editorPane.add(editor, BorderLayout.CENTER);

        status.setBorder(JBUI.Borders.empty(4, 8));
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(results, BorderLayout.CENTER);
        bottom.add(status, BorderLayout.SOUTH);

        JBSplitter splitter = new JBSplitter(true, 0.4f);
        splitter.setFirstComponent(editorPane);
        splitter.setSecondComponent(bottom);

        add(splitter, BorderLayout.CENTER);

        RunAction run = new RunAction();
        run.registerCustomShortcutSet(new CustomShortcutSet(
                KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK)), editor);
        run.registerCustomShortcutSet(new CustomShortcutSet(
                KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.META_DOWN_MASK)), editor);
    }

    public @NotNull DbConfig config() {
        return config;
    }

    public void setSql(@NotNull String sql) {
        ApplicationManager.getApplication().runWriteAction(() ->
                editor.getDocument().replaceString(0, editor.getDocument().getTextLength(), sql));
    }

    public @NotNull String sql() {
        return editor.getText();
    }

    private static @NotNull FileType sqlFileType() {
        FileType sql = FileTypeManager.getInstance().findFileTypeByName("SQL");
        return sql != null ? sql : community.intelladb.sql.IntellaSqlFileType.INSTANCE;
    }

    private javax.swing.JComponent toolbar() {
        DefaultActionGroup group = new DefaultActionGroup();
        group.add(new RunAction());
        group.add(new AnAction("Clear Console", "Clear the console", AllIcons.Actions.GC) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                setSql("");
                status.setText(" ");
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }
        });
        ActionToolbar toolbar = ActionManager.getInstance()
                .createActionToolbar("IntellaDbConsole-" + config.id, group, true);
        toolbar.setTargetComponent(this);
        return toolbar.getComponent();
    }

    private final class RunAction extends AnAction {
        RunAction() {
            super("Run", "Execute the console script (Ctrl/Cmd+Enter)", AllIcons.Actions.Execute);
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            run();
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }
    }

    public void run() {
        String script = editor.getText();
        if (script.isBlank()) {
            return;
        }
        status.setText("Connecting…");
        explorer.withSession(config, session -> execute(session, script));
    }

    private void execute(@NotNull DbSession session, @NotNull String script) {
        List<String> statements = SqlSplitter.split(script);
        if (statements.isEmpty()) {
            status.setText("Nothing to execute.");
            return;
        }
        status.setText("Running " + statements.size() + " statement(s)…");
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            List<SqlResult> resultsList = statements.stream().map(session::execute).toList();
            ApplicationManager.getApplication().invokeLater(() -> show(session, resultsList));
        });
    }

    private void show(@NotNull DbSession session, @NotNull List<SqlResult> resultsList) {
        SqlResult grid = resultsList.stream().filter(r -> r.kind == SqlResult.Kind.ROWS).findFirst().orElse(null);
        if (grid != null) {
            results.showResult(grid);
        } else {
            SqlResult last = resultsList.get(resultsList.size() - 1);
            results.showResult(last);
        }
        SqlResult failed = resultsList.stream().filter(r -> !r.isSuccessful()).findFirst().orElse(null);
        long affected = resultsList.stream().filter(r -> r.kind == SqlResult.Kind.UPDATE_COUNT)
                .mapToLong(r -> r.updateCount).sum();
        long totalMs = resultsList.stream().mapToLong(r -> r.durationMs).sum();
        if (failed != null) {
            status.setText("Error: " + failed.text);
            status.setForeground(com.intellij.util.ui.JBUI.CurrentTheme.Label.errorForeground());
        } else {
            status.setText(resultsList.size() + " statement(s) OK"
                    + (affected > 0 ? "  ·  " + affected + " row(s) affected" : "")
                    + "  ·  " + totalMs + " ms  ·  " + session.serverVersion());
            status.setForeground(com.intellij.util.ui.JBUI.CurrentTheme.Label.foreground());
        }
    }
}
