package dev.phucngu.intelladb.ui;

import com.intellij.icons.AllIcons;
import com.intellij.ide.FileIconProvider;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.fileEditor.impl.EditorTabTitleProvider;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.fileEditor.impl.EditorTabColorProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import java.awt.Color;

/**
 * How console files look and behave in the editor: tab title {@code console [@name]},
 * a console icon, the console header toolbar installed whenever a console tab opens
 * (including reopen after close), and the connection's marker color as the tab tint.
 * Also records which console tabs are open, so they come back after a restart
 * (see {@link ConsoleRestoreStartup}).
 */
public final class ConsoleFilePresentation implements EditorTabTitleProvider, FileIconProvider,
        EditorTabColorProvider, FileEditorManagerListener {

    @Override
    public @Nullable Color getEditorTabColor(@NotNull Project project, @NotNull VirtualFile file) {
        SqlConsole console = file.getUserData(SqlConsole.KEY);
        return console == null ? null : ConnectionColors.decode(console.config().color);
    }

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
            ConsoleStore.getInstance(source.getProject()).setOpen(console.consoleId(), true);
        }
    }

    @Override
    public void fileClosed(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
        SqlConsole console = file.getUserData(SqlConsole.KEY);
        if (console != null) {
            ConsoleStore.getInstance(source.getProject()).setOpen(console.consoleId(), false);
        }
    }
}
