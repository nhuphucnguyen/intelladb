package community.intelladb.ai;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A known OpenAI-compatible endpoint. The plugin speaks the OpenAI chat-completions wire
 * format, which covers Z.ai GLM (coding plan and standard API), OpenAI, DeepSeek, OpenRouter,
 * local Ollama, and any other compatible gateway.
 *
 * @param models   suggested model ids shown in the settings dropdown; any other id can still be typed.
 * @param sampling the provider's recommended generation settings (defaults of the settings page).
 */
public record AiPreset(@NotNull String id, @NotNull String label, @NotNull String baseUrl,
                       @NotNull String defaultModel, @NotNull List<String> models, boolean needsApiKey,
                       @NotNull Sampling sampling) {

    /**
     * Recommended generation settings.
     *
     * @param maxTokensLimit the most output tokens the models accept (upper bound of the setting)
     */
    public record Sampling(double temperature, double topP, int maxTokens, int maxTokensLimit) {
        /** Conservative defaults for SQL generation on generic OpenAI-compatible models. */
        public static final Sampling STANDARD = new Sampling(0.2, 1.0, 2048, 32768);
        /**
         * Z.ai's recommendation for GLM-5.3 / GLM-5.3-Flash: temperature 1.0, top_p 0.95, up
         * to 128K output tokens. Reasoning is always on and cannot be disabled, so the default
         * budget is generous — a small one can be spent entirely on reasoning_content.
         */
        public static final Sampling GLM_5_3 = new Sampling(1.0, 0.95, 32768, 131072);
    }

    public AiPreset(@NotNull String id, @NotNull String label, @NotNull String baseUrl,
                    @NotNull String defaultModel, boolean needsApiKey) {
        this(id, label, baseUrl, defaultModel, List.of(defaultModel), needsApiKey, Sampling.STANDARD);
    }

    public static final AiPreset CUSTOM =
            new AiPreset("custom", "Custom (OpenAI-compatible)", "", "", true);

    public static final AiPreset ZAI_CODING_PLAN =
            new AiPreset("zai-coding-plan", "Z.ai GLM — Coding Plan",
                    "https://api.z.ai/api/coding/paas/v4", "glm-5.3",
                    List.of("glm-5.3", "glm-5.3-flash"), true, Sampling.GLM_5_3);

    public static final AiPreset ZAI_API =
            new AiPreset("zai-api", "Z.ai GLM — Standard API",
                    "https://api.z.ai/api/paas/v4", "glm-5.3",
                    List.of("glm-5.3", "glm-5.3-flash"), true, Sampling.GLM_5_3);

    public static final AiPreset BIGMODEL =
            new AiPreset("bigmodel", "Zhipu BigModel (open.bigmodel.cn)",
                    "https://open.bigmodel.cn/api/paas/v4", "glm-4.6", true);

    public static final AiPreset OPENAI =
            new AiPreset("openai", "OpenAI", "https://api.openai.com/v1", "gpt-4o-mini", true);

    public static final AiPreset DEEPSEEK =
            new AiPreset("deepseek", "DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat", true);

    public static final AiPreset OPENROUTER =
            new AiPreset("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "", true);

    public static final AiPreset OLLAMA =
            new AiPreset("ollama", "Ollama (local)", "http://localhost:11434/v1", "llama3.1", false);

    public static final AiPreset LMSTUDIO =
            new AiPreset("lmstudio", "LM Studio (local)", "http://localhost:1234/v1", "", false);

    public static final AiPreset[] ALL = {
            ZAI_CODING_PLAN, ZAI_API, BIGMODEL, OPENAI, DEEPSEEK, OPENROUTER, OLLAMA, LMSTUDIO, CUSTOM
    };

    /**
     * Reasoning levels the picker offers for {@code model} on this provider (besides Default):
     * GLM-5.3 models take low / high / max, anything else the common low / medium / high.
     */
    public @NotNull List<ReasoningEffort> reasoningLevels(@NotNull String model) {
        return sampling == Sampling.GLM_5_3 && model.startsWith("glm-")
                ? ReasoningEffort.GLM_LEVELS : ReasoningEffort.STANDARD_LEVELS;
    }

    public static @Nullable AiPreset byId(@NotNull String id) {
        for (AiPreset preset : ALL) {
            if (preset.id.equals(id)) {
                return preset;
            }
        }
        return null;
    }
}
