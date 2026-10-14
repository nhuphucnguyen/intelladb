package dev.phucngu.intelladb.ui.actions.explorer;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.ToggleAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.ui.awt.RelativePoint;
import dev.phucngu.intelladb.ui.ConnectionTreePanel;
import dev.phucngu.intelladb.ui.TreeViewOptions;
import org.jetbrains.annotations.NotNull;

import java.awt.event.MouseEvent;
import java.util.function.Consumer;

/**
 * The "eye" menu: live view options for the explorer tree, grouped like IntelliJ's
 * (View in Groups / Show Elements / Node Details). Every toggle rebuilds the tree.
 */
public final class ViewOptionsAction extends AnAction {

    private static final String PREFIX = "IntellaDb.ViewOptions.";

    private final Project project;
    private final ConnectionTreePanel treePanel;

    public ViewOptionsAction(@NotNull Project project, @NotNull ConnectionTreePanel treePanel) {
        super("View Options", "Configure how the tree groups objects and shows details",
                AllIcons.Actions.Show);
        this.project = project;
        this.treePanel = treePanel;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        DefaultActionGroup group = new DefaultActionGroup();
        Consumer<TreeViewOptions> onChange = options -> {
            options.save(project);
            treePanel.setViewOptions(options);
        };
        group.addSeparator("View in Groups");
        group.add(new OptionToggle("Group Objects by Kind",
                "Group tables, views, routines… into folders instead of one flat list",
                "groupByKind", true, onChange));
        group.addSeparator("Show Elements");
        group.add(new OptionToggle("Object Counts",
                "Show the number of objects beside each folder", "objectCounts", true, onChange));
        group.add(new OptionToggle("Empty Groups",
                "Show folders that hold no objects", "emptyGroups", false, onChange));
        group.add(new OptionToggle("Table Details",
                "Show columns, keys and indexes under each table", "tableDetails", true, onChange));
        group.addSeparator("Node Details");
        group.add(new OptionToggle("Column Types",
                "Show the data type beside each column", "columnTypes", true, onChange));

        var popup = JBPopupFactory.getInstance().createActionGroupPopup(
                "View Options", group, e.getDataContext(), JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true);
        if (e.getInputEvent() instanceof MouseEvent mouse) {
            popup.show(new RelativePoint(mouse));
        } else {
            popup.showInFocusCenter();
        }
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.EDT;
    }

    /** One checkbox backed by a persisted property; selecting reports the new full options. */
    private final class OptionToggle extends ToggleAction {

        private final String key;
        private final boolean defaultState;
        private final Consumer<TreeViewOptions> onChange;

        OptionToggle(@NotNull String text, @NotNull String description, @NotNull String key,
                     boolean defaultState, @NotNull Consumer<TreeViewOptions> onChange) {
            super(text);
            getTemplatePresentation().setDescription(description);
            this.key = key;
            this.defaultState = defaultState;
            this.onChange = onChange;
        }

        @Override
        public boolean isSelected(@NotNull AnActionEvent e) {
            return PropertiesComponent.getInstance(project).getBoolean(PREFIX + key, defaultState);
        }

        @Override
        public void setSelected(@NotNull AnActionEvent e, boolean state) {
            PropertiesComponent.getInstance(project).setValue(PREFIX + key, state, defaultState);
            onChange.accept(TreeViewOptions.load(project));
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }
    }
}
