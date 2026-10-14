package dev.phucngu.intelladb.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Action;
import javax.swing.JComponent;
import java.awt.BorderLayout;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.util.List;

/** The statements Submit would run, before running them — IntelliJ's "Preview Pending Changes". */
final class PreviewChangesDialog extends DialogWrapper {

    private final Document document = EditorFactory.getInstance().createDocument("");
    private final EditorEx viewer;

    PreviewChangesDialog(@NotNull Project project, @NotNull List<String> statements) {
        super(project);
        this.viewer = (EditorEx) EditorFactory.getInstance().createViewer(document, project);
        String text = String.join(";\n", statements) + ";";
        ApplicationManager.getApplication().runWriteAction(() -> document.setText(text));
        document.setReadOnly(true);
        setTitle("Pending Changes");
        setOKButtonText("Close");
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JComponent panel = new javax.swing.JPanel(new BorderLayout());
        panel.setPreferredSize(JBUI.size(650, 320));
        panel.add(viewer.getComponent(), BorderLayout.CENTER);
        return panel;
    }

    @Override
    protected @NotNull Action[] createActions() {
        Action copy = new DialogWrapperAction("Copy to Clipboard") {
            @Override
            protected void doAction(@NotNull ActionEvent e) {
                CopyPasteManager.getInstance().setContents(new StringSelection(document.getText()));
            }
        };
        return new Action[]{copy, getOKAction()};
    }

    @Override
    public void dispose() {
        EditorFactory.getInstance().releaseEditor(viewer);
        super.dispose();
    }
}
