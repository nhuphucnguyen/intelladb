package community.intelladb.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.options.Configurable;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPasswordField;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import community.intelladb.ai.AiCredentials;
import community.intelladb.ai.AiPreset;
import community.intelladb.ai.AiSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import java.awt.BorderLayout;
import java.awt.event.ItemEvent;

/**
 * Settings page: Tools → Intella DB — AI Provider.
 * Any OpenAI-compatible endpoint works; the API key goes to the IDE PasswordSafe.
 */
public final class AiProviderConfigurable implements Configurable {

    private final AiSettings settings = AiSettings.getInstance();

    private JComboBox<AiPreset> presetCombo;
    private JBTextField baseUrl;
    private JBPasswordField apiKey;
    private com.intellij.openapi.ui.ComboBox<String> model;
    private JSpinner temperature;
    private JSpinner maxTokens;
    private JBCheckBox includeSchema;
    private JBLabel testStatus;

    @Override
    public @NotNull String getDisplayName() {
        return "Intella DB — AI Provider";
    }

    @Override
    public @Nullable JComponent createComponent() {
        presetCombo = new JComboBox<>(AiPreset.ALL);
        presetCombo.setRenderer(new com.intellij.ui.SimpleListCellRenderer<>() {
            @Override
            public void customize(javax.swing.JList<? extends AiPreset> list, AiPreset value, int index,
                                  boolean selected, boolean hasFocus) {
                setText(value != null ? value.label() : "");
            }
        });
        baseUrl = new JBTextField();
        apiKey = new JBPasswordField();
        model = new com.intellij.openapi.ui.ComboBox<>();
        model.setEditable(true);
        temperature = new JSpinner(new SpinnerNumberModel(Double.valueOf(0.2), Double.valueOf(0), Double.valueOf(2), Double.valueOf(0.1)));
        maxTokens = new JSpinner(new SpinnerNumberModel(1024, 64, 32768, 64));
        includeSchema = new JBCheckBox("Include database schema in the AI prompt (recommended)", true);
        testStatus = new JBLabel(" ");
        javax.swing.JButton testButton = new javax.swing.JButton("Test Provider");
        testButton.addActionListener(e -> testProvider(testButton));

        presetCombo.addItemListener(e -> {
            if (e.getStateChange() == ItemEvent.SELECTED && presetCombo.getSelectedItem() instanceof AiPreset preset) {
                baseUrl.setText(preset.baseUrl());
                applyModelChoices(preset);
            }
        });

        JPanel testRow = new JPanel(new BorderLayout());
        testRow.add(testButton, BorderLayout.WEST);
        testStatus.setBorder(JBUI.Borders.emptyLeft(8));
        testRow.add(testStatus, BorderLayout.CENTER);

        return FormBuilder.createFormBuilder()
                .setHorizontalGap(8)
                .addLabeledComponent("Provider preset:", presetCombo)
                .addLabeledComponent("Base URL (OpenAI-compatible):", baseUrl)
                .addLabeledComponent("API key:", apiKey)
                .addComponent(new JBLabel("    Stored in the IDE secure credential store — never in project files."), 2)
                .addLabeledComponent("Model:", model)
                .addLabeledComponent("Temperature:", temperature)
                .addLabeledComponent("Max tokens:", maxTokens)
                .addComponent(includeSchema, 4)
                .addComponent(testRow, 10)
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
    }

    private void testProvider(javax.swing.JButton testButton) {
        apply(); // persist before pinging so the ping uses the edited values
        testStatus.setText("Testing…");
        testButton.setEnabled(false);
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            String message;
            boolean ok = false;
            try {
                String answer = AiCredentials.ping().replace('\n', ' ');
                message = "Provider answered: " + (answer.length() > 80 ? answer.substring(0, 80) + "…" : answer);
                ok = true;
            } catch (Exception ex) {
                message = "Failed: " + (ex.getMessage() == null ? ex.toString() : ex.getMessage());
            }
            String finalMessage = message;
            boolean success = ok;
            ApplicationManager.getApplication().invokeLater(() -> {
                testStatus.setText(finalMessage);
                testStatus.setForeground(success
                        ? JBUI.CurrentTheme.Label.foreground()
                        : JBUI.CurrentTheme.Label.errorForeground());
                testButton.setEnabled(true);
            });
        });
    }

    @Override
    public boolean isModified() {
        return !baseUrl.getText().equals(settings.baseUrl())
                || !selectedModel().equals(settings.model())
                || presetCombo.getSelectedIndex() != presetIndexOf(settings.presetId())
                || !String.valueOf(apiKey.getPassword()).isBlank()
                || ((Number) temperature.getValue()).doubleValue() != settings.temperature()
                || ((Number) maxTokens.getValue()).intValue() != settings.maxTokens()
                || includeSchema.isSelected() != settings.includeSchema();
    }

    @Override
    public void apply() {
        AiPreset preset = (AiPreset) presetCombo.getSelectedItem();
        String key = new String(apiKey.getPassword());
        settings.set(preset != null ? preset.id() : AiPreset.CUSTOM.id(),
                baseUrl.getText().trim(),
                selectedModel(),
                ((Number) temperature.getValue()).doubleValue(),
                ((Number) maxTokens.getValue()).intValue(),
                includeSchema.isSelected());
        if (!key.isBlank()) {
            AiCredentials.write(key);
            apiKey.setText("");
        }
        // Flush app-level state immediately so the provider config survives restarts
        // even when the IDE exits without a regular shutdown.
        com.intellij.openapi.application.ApplicationManager.getApplication().saveSettings();
    }

    @Override
    public void reset() {
        AiPreset preset = AiPreset.byId(settings.presetId());
        presetCombo.setSelectedItem(preset != null ? preset : AiPreset.CUSTOM);
        baseUrl.setText(settings.baseUrl());
        applyModelChoices(preset != null ? preset : AiPreset.CUSTOM);
        selectModel(settings.model());
        temperature.setValue(settings.temperature());
        maxTokens.setValue(settings.maxTokens());
        includeSchema.setSelected(settings.includeSchema());
        // PasswordSafe must not be read on the EDT; refresh the hint asynchronously.
        apiKey.getEmptyText().setText("Paste your API key");
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread(() -> {
            boolean saved = AiCredentials.read() != null;
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(() ->
                    apiKey.getEmptyText().setText(saved
                            ? "A key is saved (leave empty to keep it)" : "Paste your API key"));
        });
    }

    /** Fills the model dropdown with the preset's suggested ids; free text stays allowed. */
    private void applyModelChoices(@NotNull AiPreset preset) {
        model.removeAllItems();
        for (String id : preset.models()) {
            model.addItem(id);
        }
        selectModel(preset.defaultModel());
    }

    private void selectModel(@NotNull String id) {
        if (id.isBlank()) {
            model.setSelectedItem("");
            return;
        }
        model.setSelectedItem(id);
        // ensure the editor shows the value even when it is not among the suggestions
        if (!selectedModel().equals(id)) {
            model.getEditor().setItem(id);
        }
    }

    private @NotNull String selectedModel() {
        Object item = model.getEditor().getItem();
        return item == null ? "" : String.valueOf(item).trim();
    }

    private static int presetIndexOf(@NotNull String id) {
        AiPreset[] all = AiPreset.ALL;
        for (int i = 0; i < all.length; i++) {
            if (all[i].id().equals(id)) {
                return i;
            }
        }
        return -1;
    }
}
