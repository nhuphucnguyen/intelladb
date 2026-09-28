package dev.phucngu.intelladb.ui;

import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorPolicy;
import com.intellij.openapi.fileEditor.FileEditorState;
import com.intellij.openapi.fileEditor.FileEditorStateLevel;
import com.intellij.openapi.fileEditor.FileEditorProvider;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.UserDataHolder;
import com.intellij.openapi.util.UserDataHolderBase;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import java.beans.PropertyChangeListener;
import java.util.function.Supplier;

/**
 * Hosts IntellaDB panels (SQL consoles, table data previews) as editor tabs in the main
 * editor area — the way IntelliJ's own database tools do it: the Database explorer stays
 * a tree, while consoles and grids open as editor tabs with standard close/split
 * behavior. A panel file carries a supplier that (re)creates or returns the existing
 * Swing component, so re-opening a tab re-attaches the live panel.
 */
public final class IntellaDbFileEditorProvider implements FileEditorProvider, DumbAware {

    /** Attach to a VirtualFile to make it open as an IntellaDB panel tab. */
    public static final Key<Supplier<JComponent>> PANEL_SUPPLIER =
            Key.create("intelladb.panelSupplier");

    public static void attach(@NotNull VirtualFile file, @NotNull Supplier<JComponent> panel) {
        file.putUserData(PANEL_SUPPLIER, panel);
    }

    public static boolean isPanelFile(@Nullable VirtualFile file) {
        return file != null && file.getUserData(PANEL_SUPPLIER) != null;
    }

    @Override
    public boolean accept(@NotNull Project project, @NotNull VirtualFile file) {
        return isPanelFile(file);
    }

    @Override
    public @NotNull FileEditor createEditor(@NotNull Project project, @NotNull VirtualFile file) {
        return new PanelFileEditor(file, file.getUserData(PANEL_SUPPLIER).get());
    }

    @Override
    public @NotNull String getEditorTypeId() {
        return "intelladb-panel";
    }

    @Override
    public @NotNull FileEditorPolicy getPolicy() {
        return FileEditorPolicy.PLACE_BEFORE_DEFAULT_EDITOR;
    }

    private static final class PanelFileEditor extends UserDataHolderBase implements FileEditor {
        private final VirtualFile file;
        private final JComponent component;

        PanelFileEditor(@NotNull VirtualFile file, @NotNull JComponent component) {
            this.file = file;
            this.component = component;
        }

        @Override
        public @NotNull VirtualFile getFile() {
            return file;
        }

        @Override
        public @NotNull JComponent getComponent() {
            return component;
        }

        @Override
        public @Nullable JComponent getPreferredFocusedComponent() {
            return component;
        }

        @Override
        public @NotNull String getName() {
            return "IntellaDB";
        }

        @Override
        public void setState(@Nullable FileEditorState state) {
        }

        private static final FileEditorState NO_STATE = new FileEditorState() {
            @Override
            public boolean canBeMergedWith(@NotNull FileEditorState other,
                                           @NotNull FileEditorStateLevel level) {
                return true;
            }
        };

        // must be non-null: the command log stores editor state after every command
        @Override
        public @NotNull FileEditorState getState(@NotNull FileEditorStateLevel level) {
            return NO_STATE;
        }

        @Override
        public boolean isModified() {
            return false;
        }

        @Override
        public boolean isValid() {
            return true;
        }

        @Override
        public void addPropertyChangeListener(@NotNull PropertyChangeListener listener) {
        }

        @Override
        public void removePropertyChangeListener(@NotNull PropertyChangeListener listener) {
        }

        @Override
        public void dispose() {
            // panel instances are owned by the explorer (consoles) or transient (grids)
        }
    }
}
