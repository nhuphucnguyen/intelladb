package dev.phucngu.intelladb.ui;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Explorer tree view options — IntelliJ's database "eye" menu: how the tree groups
 * objects and which details it shows. Persisted per project.
 */
public record TreeViewOptions(boolean groupByKind, boolean objectCounts, boolean emptyGroups,
                              boolean tableDetails, boolean columnTypes) {

    public static final TreeViewOptions DEFAULTS = new TreeViewOptions(true, true, false, true, true);

    private static final String PREFIX = "IntellaDb.ViewOptions.";

    public static @NotNull TreeViewOptions load(@NotNull Project project) {
        PropertiesComponent properties = PropertiesComponent.getInstance(project);
        return new TreeViewOptions(
                properties.getBoolean(PREFIX + "groupByKind", true),
                properties.getBoolean(PREFIX + "objectCounts", true),
                properties.getBoolean(PREFIX + "emptyGroups", false),
                properties.getBoolean(PREFIX + "tableDetails", true),
                properties.getBoolean(PREFIX + "columnTypes", true));
    }

    public void save(@NotNull Project project) {
        PropertiesComponent properties = PropertiesComponent.getInstance(project);
        properties.setValue(PREFIX + "groupByKind", groupByKind, true);
        properties.setValue(PREFIX + "objectCounts", objectCounts, true);
        properties.setValue(PREFIX + "emptyGroups", emptyGroups, false);
        properties.setValue(PREFIX + "tableDetails", tableDetails, true);
        properties.setValue(PREFIX + "columnTypes", columnTypes, true);
    }
}
