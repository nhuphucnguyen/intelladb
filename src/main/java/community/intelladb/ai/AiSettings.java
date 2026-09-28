package community.intelladb.ai;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.ide.util.PropertiesComponent;
import org.jetbrains.annotations.NotNull;

/**
 * Application-wide AI provider settings, persisted via {@link PropertiesComponent}
 * (stored in the IDE's options/other.xml, flushed on apply). The API key itself is kept
 * out of this state and stored in the IDE PasswordSafe under {@link #KEYRING_SERVICE}.
 */
@Service(Service.Level.APP)
public final class AiSettings {

    public static final String KEYRING_SERVICE = "Intella DB AI";

    private static final String PREFIX = "intelladb.ai.";
    private static final String K_PRESET = PREFIX + "presetId";
    private static final String K_URL = PREFIX + "baseUrl";
    private static final String K_MODEL = PREFIX + "model";
    private static final String K_TEMPERATURE = PREFIX + "temperature";
    private static final String K_MAX_TOKENS = PREFIX + "maxTokens";
    private static final String K_INCLUDE_SCHEMA = PREFIX + "includeSchema";

    public static @NotNull AiSettings getInstance() {
        return ApplicationManager.getApplication().getService(AiSettings.class);
    }

    private static @NotNull PropertiesComponent props() {
        return PropertiesComponent.getInstance();
    }

    public @NotNull String presetId() {
        return props().getValue(K_PRESET, AiPreset.ZAI_CODING_PLAN.id());
    }

    public @NotNull String baseUrl() {
        return props().getValue(K_URL, AiPreset.ZAI_CODING_PLAN.baseUrl());
    }

    public @NotNull String model() {
        return props().getValue(K_MODEL, AiPreset.ZAI_CODING_PLAN.defaultModel());
    }

    public double temperature() {
        return Double.parseDouble(props().getValue(K_TEMPERATURE, "0.2"));
    }

    public int maxTokens() {
        try {
            return Integer.parseInt(props().getValue(K_MAX_TOKENS, "2048"));
        } catch (NumberFormatException e) {
            return 2048;
        }
    }

    public boolean includeSchema() {
        return props().getBoolean(K_INCLUDE_SCHEMA, true);
    }

    public void set(@NotNull String presetId, @NotNull String baseUrl, @NotNull String model,
                    double temperature, int maxTokens, boolean includeSchema) {
        PropertiesComponent p = props();
        p.setValue(K_PRESET, presetId);
        p.setValue(K_URL, baseUrl);
        p.setValue(K_MODEL, model);
        p.setValue(K_TEMPERATURE, String.valueOf(temperature));
        p.setValue(K_MAX_TOKENS, String.valueOf(maxTokens));
        p.setValue(K_INCLUDE_SCHEMA, includeSchema);
    }
}
