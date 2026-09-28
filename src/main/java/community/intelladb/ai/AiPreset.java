package community.intelladb.ai;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A known OpenAI-compatible endpoint. The plugin speaks the OpenAI chat-completions wire
 * format, which covers Z.ai GLM (coding plan and standard API), OpenAI, DeepSeek, OpenRouter,
 * local Ollama, and any other compatible gateway.
 *
 * @param models suggested model ids shown in the settings dropdown; any other id can still be typed.
 */
public record AiPreset(@NotNull String id, @NotNull String label, @NotNull String baseUrl,
                       @NotNull String defaultModel, @NotNull List<String> models, boolean needsApiKey) {

    public AiPreset(@NotNull String id, @NotNull String label, @NotNull String baseUrl,
                    @NotNull String defaultModel, boolean needsApiKey) {
        this(id, label, baseUrl, defaultModel, List.of(defaultModel), needsApiKey);
    }

    public static final AiPreset CUSTOM =
            new AiPreset("custom", "Custom (OpenAI-compatible)", "", "", true);

    public static final AiPreset ZAI_CODING_PLAN =
            new AiPreset("zai-coding-plan", "Z.ai GLM — Coding Plan",
                    "https://api.z.ai/api/coding/paas/v4", "glm-5.3",
                    List.of("glm-5.3", "glm-5.3-flash"), true);

    public static final AiPreset ZAI_API =
            new AiPreset("zai-api", "Z.ai GLM — Standard API",
                    "https://api.z.ai/api/paas/v4", "glm-5.3",
                    List.of("glm-5.3", "glm-5.3-flash"), true);

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

    public static @Nullable AiPreset byId(@NotNull String id) {
        for (AiPreset preset : ALL) {
            if (preset.id.equals(id)) {
                return preset;
            }
        }
        return null;
    }
}
