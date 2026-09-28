package community.intelladb.ai;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.util.Disposer;
import com.intellij.ide.util.PropertiesComponent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Application-wide AI provider settings, persisted via {@link PropertiesComponent}
 * (stored in the IDE's options/other.xml, flushed on apply). Each provider preset keeps
 * its own base URL, model and sampling values, so several providers can be set up at
 * once; the chat uses the <em>active</em> provider + model, which its model picker
 * switches. API keys are kept out of this state, one per provider, in the IDE
 * PasswordSafe (see {@link AiCredentials}).
 */
@Service(Service.Level.APP)
public final class AiSettings {

    public static final String KEYRING_SERVICE = "Intella DB AI";

    private static final String PREFIX = "intelladb.ai.";
    /** Active provider + model (what the chat sends to). */
    private static final String K_PRESET = PREFIX + "presetId";
    private static final String K_MODEL = PREFIX + "model";
    private static final String K_INCLUDE_SCHEMA = PREFIX + "includeSchema";
    /** Per-provider values live under {@code intelladb.ai.provider.<presetId>.<name>}. */
    private static final String PROVIDER_PREFIX = PREFIX + "provider.";
    private static final String BASE_URL = "baseUrl";
    private static final String MODEL = "model";
    private static final String TEMPERATURE = "temperature";
    private static final String TOP_P = "topP";
    private static final String MAX_TOKENS = "maxTokens";
    /** Per model: {@code intelladb.ai.provider.<presetId>.reasoning.<model>}. */
    private static final String REASONING = "reasoning.";
    /** Pre-M23 global keys (they belonged to the then-selected preset). */
    private static final String LEGACY_URL = PREFIX + "baseUrl";
    private static final String LEGACY_TEMPERATURE = PREFIX + "temperature";
    private static final String LEGACY_TOP_P = PREFIX + "topP";
    private static final String LEGACY_MAX_TOKENS = PREFIX + "maxTokens";
    private static final String K_SETTINGS_VERSION = PREFIX + "samplingDefaultsVersion";
    private static final int SETTINGS_VERSION = 2;

    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    public AiSettings() {
        migrate();
    }

    public static @NotNull AiSettings getInstance() {
        return ApplicationManager.getApplication().getService(AiSettings.class);
    }

    private static @NotNull PropertiesComponent props() {
        return PropertiesComponent.getInstance();
    }

    private static @NotNull String key(@NotNull String presetId, @NotNull String name) {
        return PROVIDER_PREFIX + presetId + "." + name;
    }

    // ------------------------------------------------------------------ active provider

    public @NotNull String presetId() {
        return props().getValue(K_PRESET, AiPreset.ZAI_CODING_PLAN.id());
    }

    /** The active preset (Custom when the stored id is unknown). */
    public @NotNull AiPreset preset() {
        return presetOrCustom(presetId());
    }

    public @NotNull String model() {
        return props().getValue(K_MODEL, model(presetId()));
    }

    public @NotNull String baseUrl() {
        return baseUrl(presetId());
    }

    public double temperature() {
        return temperature(presetId());
    }

    public double topP() {
        return topP(presetId());
    }

    public int maxTokens() {
        return maxTokens(presetId());
    }

    /** Reasoning level of the active model. */
    public @NotNull ReasoningEffort reasoningEffort() {
        return reasoningEffort(presetId(), model());
    }

    /** Makes {@code presetId}/{@code model} what the chat sends to, thinking at {@code effort}. */
    public void setActive(@NotNull String presetId, @NotNull String model, @NotNull ReasoningEffort effort) {
        props().setValue(key(presetId, REASONING + model), effort.name(), ReasoningEffort.DEFAULT.name());
        setActive(presetId, model);
    }

    /** Makes {@code presetId}/{@code model} what the chat sends to (the chat's model picker). */
    public void setActive(@NotNull String presetId, @NotNull String model) {
        props().setValue(K_PRESET, presetId);
        props().setValue(K_MODEL, model);
        props().setValue(key(presetId, MODEL), model);
        fireChanged();
    }

    // ------------------------------------------------------------------ per provider

    private static @NotNull AiPreset presetOrCustom(@NotNull String presetId) {
        AiPreset preset = AiPreset.byId(presetId);
        return preset != null ? preset : AiPreset.CUSTOM;
    }

    public @NotNull String baseUrl(@NotNull String presetId) {
        return props().getValue(key(presetId, BASE_URL), presetOrCustom(presetId).baseUrl());
    }

    /** Last model used / typed for this provider (its default until then). */
    public @NotNull String model(@NotNull String presetId) {
        return props().getValue(key(presetId, MODEL), presetOrCustom(presetId).defaultModel());
    }

    /** Reasoning level picked for this model (Default until one is picked, or if the model does not offer it). */
    public @NotNull ReasoningEffort reasoningEffort(@NotNull String presetId, @NotNull String model) {
        ReasoningEffort stored = ReasoningEffort.parse(props().getValue(key(presetId, REASONING + model)));
        return presetOrCustom(presetId).reasoningLevels(model).contains(stored) ? stored : ReasoningEffort.DEFAULT;
    }

    public double temperature(@NotNull String presetId) {
        return parseDouble(props().getValue(key(presetId, TEMPERATURE)),
                presetOrCustom(presetId).sampling().temperature());
    }

    public double topP(@NotNull String presetId) {
        return parseDouble(props().getValue(key(presetId, TOP_P)), presetOrCustom(presetId).sampling().topP());
    }

    public int maxTokens(@NotNull String presetId) {
        String stored = props().getValue(key(presetId, MAX_TOKENS));
        int fallback = presetOrCustom(presetId).sampling().maxTokens();
        try {
            return stored == null ? fallback : Integer.parseInt(stored);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** True once the provider was saved from the settings page (or migrated as the old single provider). */
    public boolean isSetUp(@NotNull String presetId) {
        return props().getValue(key(presetId, BASE_URL)) != null;
    }

    /**
     * Models to offer for a provider: its suggested ids plus whatever was typed for it
     * (e.g. an OpenRouter or LM Studio model), without blanks or duplicates.
     */
    public @NotNull List<String> models(@NotNull String presetId) {
        Set<String> models = new LinkedHashSet<>(presetOrCustom(presetId).models());
        models.add(model(presetId));
        models.removeIf(String::isBlank);
        return new ArrayList<>(models);
    }

    /** Saves one provider from the settings page and makes it the chat's active provider. */
    public void saveProvider(@NotNull String presetId, @NotNull String baseUrl, @NotNull String model,
                             double temperature, double topP, int maxTokens) {
        PropertiesComponent p = props();
        p.setValue(key(presetId, BASE_URL), baseUrl);
        p.setValue(key(presetId, MODEL), model);
        p.setValue(key(presetId, TEMPERATURE), String.valueOf(temperature));
        p.setValue(key(presetId, TOP_P), String.valueOf(topP));
        p.setValue(key(presetId, MAX_TOKENS), String.valueOf(maxTokens));
        p.setValue(K_PRESET, presetId);
        p.setValue(K_MODEL, model);
        fireChanged();
    }

    // ------------------------------------------------------------------ general

    public boolean includeSchema() {
        return props().getBoolean(K_INCLUDE_SCHEMA, true);
    }

    public void setIncludeSchema(boolean includeSchema) {
        props().setValue(K_INCLUDE_SCHEMA, includeSchema, true);
    }

    /** Notified (on the caller's thread) whenever providers, keys or the active model change. */
    public void addChangeListener(@NotNull Runnable listener, @NotNull Disposable parent) {
        listeners.add(listener);
        Disposer.register(parent, () -> listeners.remove(listener));
    }

    public void fireChanged() {
        listeners.forEach(Runnable::run);
    }

    private static double parseDouble(@Nullable String stored, double fallback) {
        try {
            return stored == null ? fallback : Double.parseDouble(stored);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // ------------------------------------------------------------------ migration

    /**
     * v1: Z.ai users whose saved sampling still equals the pre-GLM-5.3 defaults
     * (temperature 0.2, 1024/2048 tokens) get the new recommendations instead.
     * v2: the old single-provider values (global keys) move under the provider they
     * belonged to, which also marks it as set up so it appears in the chat's model picker.
     */
    private static void migrate() {
        PropertiesComponent p = props();
        int version = p.getInt(K_SETTINGS_VERSION, 0);
        if (version >= SETTINGS_VERSION) {
            return;
        }
        String presetId = p.getValue(K_PRESET);
        if (version < 1 && presetId != null) {
            AiPreset preset = AiPreset.byId(presetId);
            if (preset != null && preset.sampling() == AiPreset.Sampling.GLM_5_3) {
                String temperature = p.getValue(LEGACY_TEMPERATURE);
                String maxTokens = p.getValue(LEGACY_MAX_TOKENS);
                boolean oldTemperature = temperature == null || temperature.equals("0.2");
                boolean oldMaxTokens = maxTokens == null || maxTokens.equals("2048") || maxTokens.equals("1024");
                if (oldTemperature && oldMaxTokens) {
                    p.unsetValue(LEGACY_TEMPERATURE);
                    p.unsetValue(LEGACY_MAX_TOKENS);
                }
            }
        }
        if (presetId != null && p.getValue(LEGACY_URL) != null) {
            moveLegacy(p, LEGACY_URL, key(presetId, BASE_URL));
            moveLegacy(p, LEGACY_TEMPERATURE, key(presetId, TEMPERATURE));
            moveLegacy(p, LEGACY_TOP_P, key(presetId, TOP_P));
            moveLegacy(p, LEGACY_MAX_TOKENS, key(presetId, MAX_TOKENS));
            String model = p.getValue(K_MODEL);
            if (model != null) {
                p.setValue(key(presetId, MODEL), model);
            }
        }
        p.setValue(K_SETTINGS_VERSION, SETTINGS_VERSION, 0);
    }

    private static void moveLegacy(@NotNull PropertiesComponent p, @NotNull String from, @NotNull String to) {
        String value = p.getValue(from);
        if (value != null && p.getValue(to) == null) {
            p.setValue(to, value);
        }
        p.unsetValue(from);
    }
}
