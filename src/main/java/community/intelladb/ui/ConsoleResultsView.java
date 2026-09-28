package community.intelladb.ui;

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
import community.intelladb.IntellaDbIcons;
import community.intelladb.connection.SqlResult;
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

    void logStatement(@NotNull String connection, @NotNull String sql) {
        output.print(connection + "> ", ConsoleViewContentType.SYSTEM_OUTPUT);
        output.print(sql.strip() + "\n", ConsoleViewContentType.NORMAL_OUTPUT);
    }

    void logResult(@NotNull SqlResult result) {
        switch (result.kind) {
            case ROWS -> log(result.rows.size() + " row" + (result.rows.size() == 1 ? "" : "s")
                    + " retrieved starting from 1 in " + result.durationMs + " ms"
                    + (result.truncated ? " (limited to " + SqlResult.MAX_ROWS + ")" : ""), false);
            case UPDATE_COUNT -> log(result.updateCount + " row" + (result.updateCount == 1 ? "" : "s")
                    + " affected in " + result.durationMs + " ms", false);
            case MESSAGE -> log((result.text == null || "OK".equals(result.text) ? "completed" : result.text)
                    + " in " + result.durationMs + " ms", false);
            case ERROR -> log(result.text, true);
        }
    }

    void logInfo(@NotNull String message) {
        log(message, false);
    }

    void logError(@NotNull String message) {
        log(message, true);
        selectOutput();
    }

    private void log(@NotNull String message, boolean error) {
        output.print("[" + LocalDateTime.now().format(TIME) + "] ", ConsoleViewContentType.SYSTEM_OUTPUT);
        output.print(message + "\n", error ? ConsoleViewContentType.ERROR_OUTPUT : ConsoleViewContentType.NORMAL_OUTPUT);
    }

    @Override
    public void dispose() {
    }
}
