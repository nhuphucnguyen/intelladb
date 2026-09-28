package community.intelladb.ai;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.ide.util.PropertiesComponent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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
    private static final String K_TOP_P = PREFIX + "topP";
    private static final String K_MAX_TOKENS = PREFIX + "maxTokens";
    /** Bumped when preset sampling defaults change; see {@link #migrateSamplingDefaults()}. */
    private static final String K_SAMPLING_VERSION = PREFIX + "samplingDefaultsVersion";
    private static final int SAMPLING_VERSION = 1;
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

    public AiSettings() {
        migrateSamplingDefaults();
    }

    /** The selected preset (Custom when the stored id is unknown). */
    public @NotNull AiPreset preset() {
        AiPreset preset = AiPreset.byId(presetId());
        return preset != null ? preset : AiPreset.CUSTOM;
    }

    public double temperature() {
        return parseDouble(props().getValue(K_TEMPERATURE), preset().sampling().temperature());
    }

    public double topP() {
        return parseDouble(props().getValue(K_TOP_P), preset().sampling().topP());
    }

    public int maxTokens() {
        String stored = props().getValue(K_MAX_TOKENS);
        try {
            return stored == null ? preset().sampling().maxTokens() : Integer.parseInt(stored);
        } catch (NumberFormatException e) {
            return preset().sampling().maxTokens();
        }
    }

    private static double parseDouble(@Nullable String stored, double fallback) {
        try {
            return stored == null ? fallback : Double.parseDouble(stored);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * One-time upgrade for Z.ai users: values saved before the GLM-5.3 recommendations
     * (temperature 0.2 and 1024/2048 max tokens — the old defaults) are dropped so the
     * preset's recommended values apply. Anything the user picked deliberately is kept.
     */
    private static void migrateSamplingDefaults() {
        PropertiesComponent p = props();
        if (p.getInt(K_SAMPLING_VERSION, 0) >= SAMPLING_VERSION) {
            return;
        }
        AiPreset preset = AiPreset.byId(p.getValue(K_PRESET, AiPreset.ZAI_CODING_PLAN.id()));
        if (preset != null && preset.sampling() == AiPreset.Sampling.GLM_5_3) {
            String temperature = p.getValue(K_TEMPERATURE);
            String maxTokens = p.getValue(K_MAX_TOKENS);
            boolean oldTemperature = temperature == null || temperature.equals("0.2");
            boolean oldMaxTokens = maxTokens == null || maxTokens.equals("2048") || maxTokens.equals("1024");
            if (oldTemperature && oldMaxTokens) {
                p.unsetValue(K_TEMPERATURE);
                p.unsetValue(K_MAX_TOKENS);
            }
        }
        p.setValue(K_SAMPLING_VERSION, SAMPLING_VERSION, 0);
    }

    public boolean includeSchema() {
        return props().getBoolean(K_INCLUDE_SCHEMA, true);
    }

    public void set(@NotNull String presetId, @NotNull String baseUrl, @NotNull String model,
                    double temperature, double topP, int maxTokens, boolean includeSchema) {
        PropertiesComponent p = props();
        p.setValue(K_PRESET, presetId);
        p.setValue(K_URL, baseUrl);
        p.setValue(K_MODEL, model);
        p.setValue(K_TEMPERATURE, String.valueOf(temperature));
        p.setValue(K_TOP_P, String.valueOf(topP));
        p.setValue(K_MAX_TOKENS, String.valueOf(maxTokens));
        p.setValue(K_INCLUDE_SCHEMA, includeSchema);
    }
}
