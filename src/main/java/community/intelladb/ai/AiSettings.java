package community.intelladb.ai;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Application-wide AI provider settings. The API key itself is kept out of this state and
 * stored in the IDE PasswordSafe under service name {@link #KEYRING_SERVICE}.
 */
@Service(Service.Level.APP)
@State(name = "IntellaDbAiSettings", storages = @Storage("intella-db-ai.xml"))
public final class AiSettings implements PersistentStateComponent<AiSettings.State> {

    public static final String KEYRING_SERVICE = "Intella DB AI";

    /** XML-serializable state (no secrets). */
    public static final class State {
        public String presetId = AiPreset.ZAI_CODING_PLAN.id();
        public String baseUrl = AiPreset.ZAI_CODING_PLAN.baseUrl();
        public String model = AiPreset.ZAI_CODING_PLAN.defaultModel();
        public double temperature = 0.2;
        public int maxTokens = 2048;
        public boolean includeSchema = true;
    }

    private final State state = new State();

    public static @NotNull AiSettings getInstance() {
        return ApplicationManager.getApplication().getService(AiSettings.class);
    }

    @Override
    public @Nullable State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State loaded) {
        state.presetId = loaded.presetId;
        state.baseUrl = loaded.baseUrl;
        state.model = loaded.model;
        state.temperature = loaded.temperature;
        state.maxTokens = loaded.maxTokens;
        state.includeSchema = loaded.includeSchema;
    }

    public @NotNull String presetId() {
        return state.presetId;
    }

    public @NotNull String baseUrl() {
        return state.baseUrl;
    }

    public @NotNull String model() {
        return state.model;
    }

    public double temperature() {
        return state.temperature;
    }

    public int maxTokens() {
        return state.maxTokens;
    }

    public boolean includeSchema() {
        return state.includeSchema;
    }

    public void set(@NotNull String presetId, @NotNull String baseUrl, @NotNull String model,
                    double temperature, int maxTokens, boolean includeSchema) {
        state.presetId = presetId;
        state.baseUrl = baseUrl;
        state.model = model;
        state.temperature = temperature;
        state.maxTokens = maxTokens;
        state.includeSchema = includeSchema;
    }
}
