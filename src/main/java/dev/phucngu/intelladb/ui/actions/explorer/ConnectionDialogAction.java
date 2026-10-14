package dev.phucngu.intelladb.ui.actions.explorer;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import dev.phucngu.intelladb.connection.DbConfig;
import dev.phucngu.intelladb.ui.ConnectionTreePanel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;

/**
 * Base for actions that open the Data Sources dialog (IntelliJ's "Properties…"), with
 * an optional connection to select and an optional dialect to draft as new.
 */
abstract class ConnectionDialogAction extends AnAction {

    protected final Project project;
    protected final ConnectionTreePanel treePanel;

    ConnectionDialogAction(@Nullable Project project, @Nullable ConnectionTreePanel treePanel,
                           @Nullable String text, @Nullable Icon icon) {
        super(text, null, icon);
        this.project = project;
        this.treePanel = treePanel;
    }

    /** The connection whose entry the dialog selects; null just opens the dialog. */
    protected @Nullable DbConfig select() {
        return treePanel == null ? null : treePanel.selectedConfig();
    }

    /** Dialect of a new connection to draft, or null for none. */
    protected @Nullable String newDialectId() {
        return null;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        if (treePanel != null) {
            treePanel.showDataSourcesDialog(select(), newDialectId());
        }
    }
}
