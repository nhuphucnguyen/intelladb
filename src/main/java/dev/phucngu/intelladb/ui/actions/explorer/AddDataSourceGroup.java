package dev.phucngu.intelladb.ui.actions.explorer;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.project.Project;
import dev.phucngu.intelladb.IntellaDbIcons;
import dev.phucngu.intelladb.connection.Dialects;
import dev.phucngu.intelladb.ui.ConnectionTreePanel;
import org.jetbrains.annotations.NotNull;

/**
 * The toolbar's "+" menu: one entry per dialect, each opening the Data Sources dialog
 * with a new connection of that dialect already drafted — the way IntelliJ's database
 * tools add data sources.
 */
public final class AddDataSourceGroup extends DefaultActionGroup {

    public AddDataSourceGroup(@NotNull Project project, @NotNull ConnectionTreePanel treePanel) {
        setPopup(true);
        getTemplatePresentation().setText("+");
        getTemplatePresentation().setDescription("New data source");
        getTemplatePresentation().setIcon(AllIcons.General.Add);
        for (var dialect : Dialects.all()) {
            String id = dialect.id();
            add(new NewConnectionAction(project, treePanel, id,
                    "New " + dialect.displayName() + " Connection…", IntellaDbIcons.connection(id, false)));
        }
    }

    private static final class NewConnectionAction extends ConnectionDialogAction {
        private final String dialectId;

        NewConnectionAction(@NotNull Project project, @NotNull ConnectionTreePanel treePanel,
                            @NotNull String dialectId, @NotNull String text, @NotNull javax.swing.Icon icon) {
            super(project, treePanel, text, icon);
            this.dialectId = dialectId;
        }

        @Override
        protected @NotNull String newDialectId() {
            return dialectId;
        }
    }
}
