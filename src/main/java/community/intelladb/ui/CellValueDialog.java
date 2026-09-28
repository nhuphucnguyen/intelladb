package community.intelladb.ui;

import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;
import community.intelladb.util.JsonText;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;

/**
 * Full value viewer for a results-grid cell (opened by double-click). JSON values are
 * pretty-printed; everything else is shown as-is. Offers Copy for taking the value out.
 */
public final class CellValueDialog extends DialogWrapper {

    private final String text;

    public CellValueDialog(@Nullable Project project, @NotNull String columnName, @NotNull String value) {
        super(project);
        this.text = value;
        setTitle("Value — " + columnName);
        setOKButtonText("Close");
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        boolean json = JsonText.isJson(text);
        String shown = json ? JsonText.prettyIfJson(text) : text;

        JBTextArea area = new JBTextArea(shown);
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, area.getFont().getSize()));
        area.setLineWrap(!json); // pretty JSON keeps its line structure; other text wraps

        JPanel panel = new JPanel(new BorderLayout(0, 4));
        JBLabel hint = new JBLabel(json ? "JSON · pretty-printed · " + text.length() + " chars"
                : text.length() + " chars");
        hint.setForeground(JBUI.CurrentTheme.Label.disabledForeground());
        panel.add(hint, BorderLayout.NORTH);
        javax.swing.JScrollPane scroller = new JBScrollPane(area);
        scroller.setPreferredSize(JBUI.size(720, 480));
        panel.add(scroller, BorderLayout.CENTER);
        return panel;
    }

    @Override
    protected Action @NotNull [] createActions() {
        Action copy = new AbstractAction("Copy") {
            @Override
            public void actionPerformed(@NotNull ActionEvent e) {
                CopyPasteManager.getInstance().setContents(new StringSelection(text));
            }
        };
        return new Action[]{copy, getOKAction()};
    }

    @Override
    public @Nullable Dimension getPreferredSize() {
        Dimension preferred = super.getPreferredSize();
        return new Dimension(Math.max(preferred.width, 760), Math.max(preferred.height, 560));
    }
}
