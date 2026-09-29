package dev.phucngu.intelladb.ui;

import com.intellij.execution.filters.TextConsoleBuilderFactory;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionPlaces;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.text.HtmlChunk;
import com.intellij.ui.tabs.JBTabs;
import com.intellij.ui.tabs.JBTabsFactory;
import com.intellij.ui.tabs.TabInfo;
import dev.phucngu.intelladb.IntellaDbIcons;
import dev.phucngu.intelladb.connection.SqlResult;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;

/**
 * Results of one console, as in IntelliJ's Services view: an "Output" tab logging every
 * statement with a timestamp and outcome, followed by one closable tab per result set.
 * A new run replaces the previous (unpinned) result tabs.
 */
final class ConsoleResultsView implements Disposable {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Project project;
    private final SqlConsole console;
    private final JBTabs tabs;
    private final ConsoleView output;
    private final TabInfo outputTab;

    ConsoleResultsView(@NotNull Project project, @NotNull SqlConsole console) {
        this.project = project;
        this.console = console;
        this.tabs = JBTabsFactory.createTabs(project, this);
        this.output = TextConsoleBuilderFactory.getInstance().createBuilder(project).getConsole();
        Disposer.register(this, output);
        outputTab = new TabInfo(output.getComponent()).setText("Output").setIcon(AllIcons.Debugger.Console);
        tabs.addTab(outputTab);
    }

    @NotNull SqlConsole console() {
        return console;
    }

    @NotNull JComponent component() {
        return tabs.getComponent();
    }

    // ------------------------------------------------------------------ result tabs

    /** Starts a run: drops result tabs that are not pinned. */
    void beginRun() {
        for (TabInfo tab : new ArrayList<>(tabs.getTabs())) {
            if (tab != outputTab && tab.getComponent() instanceof ResultsPanel panel && !panel.isPinned()) {
                tabs.removeTab(tab);
            }
        }
    }

    @NotNull ResultsPanel addResult(@NotNull SqlResult result, @NotNull String title, @NotNull ResultsPanel.Host host) {
        ResultsPanel panel = new ResultsPanel(project, host, true);
        panel.showResult(result);
        TabInfo tab = new TabInfo(panel).setText(title).setIcon(IntellaDbIcons.TABLE).setTooltipText(HtmlChunk.text(result.sql));
        DefaultActionGroup close = new DefaultActionGroup();
        close.add(new DumbAwareAction("Close", "Close this result", AllIcons.Actions.Close) {
            {
                getTemplatePresentation().setHoveredIcon(AllIcons.Actions.CloseHovered);
            }

            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                tabs.removeTab(tab);
            }
        });
        tab.setTabLabelActions(close, ActionPlaces.EDITOR_TAB);
        tabs.addTab(tab);
        tabs.select(tab, false);
        return panel;
    }

    void selectOutput() {
        tabs.select(outputTab, false);
    }

    // ------------------------------------------------------------------ output log
    //
    // One block per execution, a blank line between blocks:
    //
    //   -- 2026-09-29 18:50:02 · localhost · SELECT first_name, last_name FROM actor WHERE a…
    //      200 rows retrieved starting from 1 in 12 ms
    //
    // The header shows the statement's start on one line; the outcome is indented below it.

    /** Header characters of the statement shown; the rest is cut with "…". */
    private static final int HEADER_SQL_CHARS = 60;
    private static final String INDENT = "   ";

    /** Opens the block of one executed statement. */
    void logStatement(@NotNull String connection, @NotNull String sql) {
        // A leading "-- what this does" comment would fill the header; show the statement itself.
        String statement = dev.phucngu.intelladb.util.SqlSplitter.stripLeadingComments(sql,
                console.config().dialect().splitterOptions());
        header(connection + " · " + shorten(statement.isBlank() ? sql : statement));
    }

    /** Opens the block of a grid Submit, listing its statements in full (they are one line each). */
    void logSubmit(@NotNull String connection, @NotNull java.util.List<String> statements) {
        header(connection + " · Submit: " + statements.size() + " statement" + (statements.size() == 1 ? "" : "s"));
        for (String sql : statements) {
            output.print(INDENT + sql.strip() + "\n", ConsoleViewContentType.NORMAL_OUTPUT);
        }
    }

    /** Closes the current block with the statement's outcome. */
    void logResult(@NotNull SqlResult result) {
        switch (result.kind) {
            case ROWS -> outcome(result.rows.size() + " row" + (result.rows.size() == 1 ? "" : "s")
                    + " retrieved starting from 1 in " + result.durationMs + " ms"
                    + (result.truncated ? " (limited to " + SqlResult.MAX_ROWS + ")" : ""), false);
            case UPDATE_COUNT -> outcome(result.updateCount + " row" + (result.updateCount == 1 ? "" : "s")
                    + " affected in " + result.durationMs + " ms", false);
            case MESSAGE -> outcome((result.text == null || "OK".equals(result.text) ? "completed" : result.text)
                    + " in " + result.durationMs + " ms", false);
            case ERROR -> outcome(result.text, true);
        }
    }

    /** A block of its own for a note that isn't a statement (transaction mode, cancellation). */
    void logInfo(@NotNull String message) {
        header(message);
        output.print("\n", ConsoleViewContentType.NORMAL_OUTPUT);
    }

    /** A block of its own for an error outside a statement; switches to the Output tab. */
    void logError(@NotNull String message) {
        header("Error");
        outcome(message, true);
        selectOutput();
    }

    private void header(@NotNull String text) {
        output.print("-- " + LocalDateTime.now().format(TIME) + " · " + text + "\n", ConsoleViewContentType.SYSTEM_OUTPUT);
    }

    /** The outcome lines (multi-line messages stay indented), then the blank line ending the block. */
    private void outcome(@NotNull String message, boolean error) {
        StringBuilder text = new StringBuilder();
        for (String line : message.strip().split("\\R")) {
            text.append(INDENT).append(line).append('\n');
        }
        output.print(text.append('\n').toString(),
                error ? ConsoleViewContentType.ERROR_OUTPUT : ConsoleViewContentType.NORMAL_OUTPUT);
    }

    /** The statement's first {@link #HEADER_SQL_CHARS} characters on one line, whitespace collapsed. */
    static @NotNull String shorten(@NotNull String sql) {
        String line = sql.strip().replaceAll("\\s+", " ");
        return line.length() <= HEADER_SQL_CHARS ? line : line.substring(0, HEADER_SQL_CHARS).stripTrailing() + "…";
    }

    @Override
    public void dispose() {
    }
}
