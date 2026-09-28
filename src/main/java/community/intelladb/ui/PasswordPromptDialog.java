package community.intelladb.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPasswordField;
import community.intelladb.connection.DbConfig;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;

/**
 * Password prompt shown when a saved connection has no usable credential (no live
 * session, nothing in memory, nothing in the PasswordSafe). Offers to remember the
 * answer, so ticking the box turns the per-restart prompt into a one-time event.
 */
public final class PasswordPromptDialog extends DialogWrapper {

    private final JBPasswordField passwordField = new JBPasswordField();
    private final JBCheckBox remember;
    private final DbConfig config;

    public PasswordPromptDialog(@Nullable Project project, @NotNull DbConfig config) {
        super(project);
        this.config = config;
        setTitle("Connect to " + config.dialect().displayName());
        setOKButtonText("Connect");
        remember = new JBCheckBox("Save password in the IDE credential store", config.savePassword);
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.add(new JBLabel("Password for " + config.user + "@" + config.describe() + ":"),
                BorderLayout.NORTH);
        panel.add(passwordField, BorderLayout.CENTER);
        panel.add(remember, BorderLayout.SOUTH);
        return panel;
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return passwordField;
    }

    public @NotNull String password() {
        return new String(passwordField.getPassword());
    }

    public boolean rememberPassword() {
        return remember.isSelected();
    }
}
