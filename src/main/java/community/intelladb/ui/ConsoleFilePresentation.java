package community.intelladb.ui;

import com.intellij.icons.AllIcons;
import com.intellij.ide.FileIconProvider;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.fileEditor.impl.EditorTabTitleProvider;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;

/**
 * How console files look and behave in the editor: tab title {@code console [@name]},
 * a console icon, and the console header toolbar installed whenever a console tab opens
 * (including reopen after close). Also records which console tabs are open, so they
 * come back after a restart (see {@link ConsoleRestoreStartup}).
 */
public final class ConsoleFilePresentation implements EditorTabTitleProvider, FileIconProvider,
        FileEditorManagerListener {

    @Override
    public @Nullable String getEditorTabTitle(@NotNull Project project, @NotNull VirtualFile file) {
        SqlConsole console = file.getUserData(SqlConsole.KEY);
        return console == null ? null : console.title();
    }

    @Override
    public @Nullable Icon getIcon(@NotNull VirtualFile file, int flags, @Nullable Project project) {
        return file.getUserData(SqlConsole.KEY) == null ? null : AllIcons.Nodes.Console;
    }

    @Override
    public void fileOpened(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
        SqlConsole console = file.getUserData(SqlConsole.KEY);
        if (console != null) {
            for (var editor : source.getEditors(file)) {
                console.installHeader(editor);
            }
            ConsoleStore.getInstance(source.getProject()).setOpen(console.config().id, true);
        }
    }

    @Override
    public void fileClosed(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
        SqlConsole console = file.getUserData(SqlConsole.KEY);
        if (console != null) {
            ConsoleStore.getInstance(source.getProject()).setOpen(console.config().id, false);
        }
    }
}
