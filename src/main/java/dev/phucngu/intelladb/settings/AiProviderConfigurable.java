package dev.phucngu.intelladb.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.options.Configurable;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPasswordField;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import dev.phucngu.intelladb.ai.AiCredentials;
import dev.phucngu.intelladb.ai.AiPreset;
import dev.phucngu.intelladb.ai.AiSettings;
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
    private JSpinner topP;
    private JSpinner maxTokens;
    private SpinnerNumberModel maxTokensModel;
    private JBLabel samplingHint;
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
        topP = new JSpinner(new SpinnerNumberModel(Double.valueOf(1.0), Double.valueOf(0), Double.valueOf(1), Double.valueOf(0.05)));
        maxTokensModel = new SpinnerNumberModel(2048, 64, 32768, 512);
        maxTokens = new JSpinner(maxTokensModel);
        samplingHint = new JBLabel(" ");
        samplingHint.setForeground(JBUI.CurrentTheme.ContextHelp.FOREGROUND);
        includeSchema = new JBCheckBox("Include database schema in the AI prompt (recommended)", true);
        testStatus = new JBLabel(" ");
        javax.swing.JButton testButton = new javax.swing.JButton("Test Provider");
        testButton.addActionListener(e -> testProvider(testButton));

        presetCombo.addItemListener(e -> {
            if (e.getStateChange() == ItemEvent.SELECTED && presetCombo.getSelectedItem() instanceof AiPreset preset) {
                loadProvider(preset);
            }
        });
        javax.swing.JButton removeKey = new javax.swing.JButton("Remove Key");
        removeKey.setToolTipText("Forget this provider's API key (it disappears from the AI Assistant's model picker)");
        removeKey.addActionListener(e -> {
            AiCredentials.write(shownPreset().id(), null);
            apiKey.setText("");
            refreshKeyHint(shownPreset());
        });
        JPanel keyRow = new JPanel(new BorderLayout(JBUI.scale(6), 0));
        keyRow.add(apiKey, BorderLayout.CENTER);
        keyRow.add(removeKey, BorderLayout.EAST);
        JBLabel providerHint = new JBLabel("Each provider keeps its own key and settings. Every provider with a key "
                + "is offered in the AI Assistant's model picker; Apply also makes the provider shown here active.");
        providerHint.setForeground(JBUI.CurrentTheme.ContextHelp.FOREGROUND);
        providerHint.setAllowAutoWrapping(true);

        JPanel testRow = new JPanel(new BorderLayout());
        testRow.add(testButton, BorderLayout.WEST);
        testStatus.setBorder(JBUI.Borders.emptyLeft(8));
        testRow.add(testStatus, BorderLayout.CENTER);

        return FormBuilder.createFormBuilder()
                .setHorizontalGap(8)
                .addLabeledComponent("Provider:", presetCombo)
                .addComponent(providerHint, 2)
                .addLabeledComponent("Base URL (OpenAI-compatible):", baseUrl)
                .addLabeledComponent("API key:", keyRow)
                .addComponent(new JBLabel("    Stored in the IDE secure credential store — never in project files."), 2)
                .addLabeledComponent("Model:", model)
                .addLabeledComponent("Temperature:", temperature)
                .addLabeledComponent("Top P:", topP)
                .addLabeledComponent("Max output tokens:", maxTokens)
                .addComponent(samplingHint, 2)
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
        String id = shownPreset().id();
        return !id.equals(settings.presetId()) // Apply makes the shown provider active
                || !baseUrl.getText().trim().equals(settings.baseUrl(id))
                || !selectedModel().equals(settings.model(id))
                || !String.valueOf(apiKey.getPassword()).isBlank()
                || ((Number) temperature.getValue()).doubleValue() != settings.temperature(id)
                || ((Number) topP.getValue()).doubleValue() != settings.topP(id)
                || ((Number) maxTokens.getValue()).intValue() != settings.maxTokens(id)
                || includeSchema.isSelected() != settings.includeSchema();
    }

    @Override
    public void apply() {
        AiPreset preset = shownPreset();
        String key = new String(apiKey.getPassword());
        if (!key.isBlank()) {
            AiCredentials.write(preset.id(), key);
            apiKey.setText("");
        }
        settings.setIncludeSchema(includeSchema.isSelected());
        settings.saveProvider(preset.id(),
                baseUrl.getText().trim(),
                selectedModel(),
                ((Number) temperature.getValue()).doubleValue(),
                ((Number) topP.getValue()).doubleValue(),
                ((Number) maxTokens.getValue()).intValue());
        refreshKeyHint(preset);
        // Flush app-level state immediately so the provider config survives restarts
        // even when the IDE exits without a regular shutdown.
        com.intellij.openapi.application.ApplicationManager.getApplication().saveSettings();
    }

    @Override
    public void reset() {
        AiPreset active = settings.preset();
        presetCombo.setSelectedItem(active); // fires loadProvider only when the selection changes
        loadProvider(active);
        includeSchema.setSelected(settings.includeSchema());
    }

    private @NotNull AiPreset shownPreset() {
        return presetCombo.getSelectedItem() instanceof AiPreset preset ? preset : AiPreset.CUSTOM;
    }

    /** Shows one provider's saved values (its recommended defaults until first saved). */
    private void loadProvider(@NotNull AiPreset preset) {
        String id = preset.id();
        baseUrl.setText(settings.baseUrl(id));
        applyModelChoices(preset);
        selectModel(settings.model(id));
        showSamplingLimits(preset);
        temperature.setValue(settings.temperature(id));
        topP.setValue(settings.topP(id));
        maxTokens.setValue(Math.min(settings.maxTokens(id), preset.sampling().maxTokensLimit()));
        apiKey.setText("");
        refreshKeyHint(preset);
    }

    /** PasswordSafe must not be read on the EDT; the key hint updates asynchronously. */
    private void refreshKeyHint(@NotNull AiPreset preset) {
        if (!preset.needsApiKey()) {
            apiKey.getEmptyText().setText("Not needed for a local provider");
            return;
        }
        apiKey.getEmptyText().setText("Paste your API key");
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            boolean saved = AiCredentials.read(preset.id()) != null;
            ApplicationManager.getApplication().invokeLater(() -> {
                if (shownPreset() == preset) {
                    apiKey.getEmptyText().setText(saved
                            ? "A key is saved (leave empty to keep it)" : "Paste your API key");
                    apiKey.repaint();
                }
            });
        });
    }

    /** Output-token upper bound and the "recommended" hint for the preset. */
    private void showSamplingLimits(@NotNull AiPreset preset) {
        AiPreset.Sampling sampling = preset.sampling();
        maxTokensModel.setMaximum(sampling.maxTokensLimit());
        samplingHint.setText("Recommended for this provider: temperature " + sampling.temperature()
                + ", top P " + sampling.topP() + ", up to " + sampling.maxTokensLimit() + " output tokens"
                + (sampling == AiPreset.Sampling.GLM_5_3 ? " (GLM-5.3 always reasons first)." : "."));
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
}
