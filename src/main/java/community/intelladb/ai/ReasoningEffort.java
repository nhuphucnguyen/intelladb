package community.intelladb.ai;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * How hard a reasoning model should think, sent as the OpenAI-style {@code reasoning_effort}
 * (OpenAI, OpenRouter, Ollama, LM Studio, …). {@link #DEFAULT} sends nothing, so models and
 * providers without the parameter keep working unchanged.
 */
public enum ReasoningEffort {
    DEFAULT("Default", null),
    LOW("Low", "low"),
    MEDIUM("Medium", "medium"),
    HIGH("High", "high");

    public final String label;
    /** Value of {@code reasoning_effort}; null = leave it out of the request. */
    public final @Nullable String wireValue;

    ReasoningEffort(@NotNull String label, @Nullable String wireValue) {
        this.label = label;
        this.wireValue = wireValue;
    }

    /** The stored name back to a level; unknown or missing values are {@link #DEFAULT}. */
    public static @NotNull ReasoningEffort parse(@Nullable String name) {
        for (ReasoningEffort effort : values()) {
            if (effort.name().equals(name)) {
                return effort;
            }
        }
        return DEFAULT;
    }
}
