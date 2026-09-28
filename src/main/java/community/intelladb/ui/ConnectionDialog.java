package community.intelladb.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.JBIntSpinner;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPasswordField;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import community.intelladb.connection.ConnectionManager;
import community.intelladb.connection.DbConfig;
import community.intelladb.connection.DbDialect;
import community.intelladb.connection.Dialects;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.event.ItemEvent;
import java.sql.DriverManager;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

/** Add/Edit connection dialog with a live "Test Connection" button. */
public final class ConnectionDialog extends DialogWrapper {

    private final ConnectionManager manager;
    private final DbConfig original;   // null when creating

    private final JBTextField nameField = new JBTextField();
    private final JComboBox<String> dialectCombo = new JComboBox<>();
    private final JBTextField hostField = new JBTextField("localhost");
    private final JBIntSpinner portSpinner = new JBIntSpinner(5432, 1, 65535);
    private final JBTextField databaseField = new JBTextField();
    private final JBTextField userField = new JBTextField();
    private final JBPasswordField passwordField = new JBPasswordField();
    private final JBCheckBox savePassword = new JBCheckBox("Save password in IDE credential store", true);
    private final JBCheckBox sslMode = new JBCheckBox("Require SSL", false);
    private final JBLabel testStatus = new JBLabel(" ");
    private final javax.swing.JButton testButton = new javax.swing.JButton("Test Connection");
    private String originalPassword = "";

    public ConnectionDialog(@NotNull Project project, @Nullable DbConfig config) {
        super(project);
        this.manager = ConnectionManager.getInstance(project);
        this.original = config == null ? null : config.copy();
        setTitle(original == null ? "New Database Connection" : "Edit Connection '" + original.name + "'");
        setOKButtonText("Save");
        init();
        loadFrom(original);
    }

    private void loadFrom(@Nullable DbConfig config) {
        for (DbDialect dialect : Dialects.all()) {
            dialectCombo.addItem(dialect.displayName());
        }
        dialectCombo.addItemListener(e -> {
            if (e.getStateChange() == ItemEvent.SELECTED) {
                portSpinner.setNumber(Dialects.all().get(dialectCombo.getSelectedIndex()).defaultPort());
            }
        });

        if (config != null) {
            nameField.setText(config.name);
            for (int i = 0; i < Dialects.all().size(); i++) {
                if (Dialects.all().get(i).id().equals(config.dialectId)) {
                    dialectCombo.setSelectedIndex(i);
                }
            }
            hostField.setText(config.host);
            portSpinner.setNumber(config.port);
            databaseField.setText(config.database);
            userField.setText(config.user);
            savePassword.setSelected(config.savePassword);
            sslMode.setSelected(config.sslMode);
            String saved = config.savePassword ? manager.readPassword(config) : null;
            if (saved != null) {
                passwordField.setText(saved);
                originalPassword = saved;
            }
        }
        testButton.addActionListener(e -> testConnection());
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel left = new JPanel(new BorderLayout());
        left.add(testButton, BorderLayout.NORTH);
        testStatus.setBorder(JBUI.Borders.emptyTop(4));

        return FormBuilder.createFormBuilder()
                .setHorizontalGap(8)
                .addLabeledComponent("Name:", nameField)
                .addLabeledComponent("Dialect:", dialectCombo)
                .addLabeledComponent("Host:", hostField)
                .addLabeledComponent("Port:", portSpinner)
                .addLabeledComponent("Database:", databaseField)
                .addLabeledComponent("User:", userField)
                .addLabeledComponent("Password:", passwordField)
                .addComponent(savePassword, 4)
                .addComponent(sslMode, 4)
                .addComponent(left, 8)
                .addComponent(testStatus, 2)
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
    }

    @Override
    protected void doOKAction() {
        if (nameField.getText().isBlank() || databaseField.getText().isBlank()) {
            testStatus.setText("Name and database are required.");
            return;
        }
        DbConfig target = original != null ? original : new DbConfig();
        String password = new String(passwordField.getPassword());
        applyTo(target, password);
        boolean passwordChanged = !password.equals(originalPassword);
        manager.saveConfig(target, password, passwordChanged);
        super.doOKAction();
    }

    private void applyTo(@NotNull DbConfig config, @NotNull String password) {
        config.name = nameField.getText().trim();
        config.dialectId = Dialects.all().get(dialectCombo.getSelectedIndex()).id();
        config.host = hostField.getText().trim();
        config.port = portSpinner.getNumber();
        config.database = databaseField.getText().trim();
        config.user = userField.getText().trim();
        config.savePassword = savePassword.isSelected();
        config.sslMode = sslMode.isSelected();
        if (!config.savePassword && !password.isBlank()) {
            // keep for this session only; not persisted anywhere
            manager.rememberPasswordInMemory(config, password);
        }
    }

    private void testConnection() {
        if (testButtonDisabled()) {
            return;
        }
        DbConfig probe = original != null ? original.copy() : new DbConfig();
        applyTo(probe, new String(passwordField.getPassword()));
        DbDialect dialect = probe.dialect();
        testStatus.setText("Testing…");
        testStatus.setForeground(com.intellij.ui.JBColor.BLUE);
        testButton.setEnabled(false);
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            AtomicBoolean ok = new AtomicBoolean(false);
            String message;
            try {
                dialect.loadDriver();
                Properties props = new Properties();
                props.setProperty("loginTimeout", "5");
                props.setProperty("connectTimeout", "5");
                if (!probe.user.isBlank()) {
                    props.setProperty("user", probe.user);
                }
                String pwd = new String(passwordField.getPassword());
                if (!pwd.isBlank()) {
                    props.setProperty("password", pwd);
                }
                try (var connection = DriverManager.getConnection(dialect.jdbcUrl(probe), props);
                     var st = connection.createStatement();
                     var rs = st.executeQuery("select version()")) {
                    rs.next();
                    message = "Connected — " + rs.getString(1);
                    ok.set(true);
                }
            } catch (Exception ex) {
                message = "Failed: " + (ex.getMessage() == null ? ex.toString() : ex.getMessage());
            }
            String finalMessage = message;
            boolean success = ok.get();
            // The dialog is modal: a plain invokeLater would queue until it closes.
            ApplicationManager.getApplication().invokeLater(() -> {
                testStatus.setText(finalMessage);
                testStatus.setForeground(success
                        ? JBUI.CurrentTheme.Label.foreground()
                        : JBUI.CurrentTheme.Label.errorForeground());
                testButton.setEnabled(true);
            }, ModalityState.stateForComponent(getContentPane()));
        });
    }

    private boolean testButtonDisabled() {
        return !testButton.isEnabled();
    }
}
