package community.intelladb;

import community.intelladb.ai.AiPreset;
import community.intelladb.ai.ReasoningEffort;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiPresetTest {

    @Test
    void zaiPresetsOfferGlm53Family() {
        for (AiPreset preset : List.of(AiPreset.ZAI_CODING_PLAN, AiPreset.ZAI_API)) {
            assertEquals(List.of("glm-5.3", "glm-5.3-flash"), preset.models());
            assertEquals("glm-5.3", preset.defaultModel());
        }
    }

    @Test
    void zaiPresetsUseGlm53Recommendations() {
        for (AiPreset preset : List.of(AiPreset.ZAI_CODING_PLAN, AiPreset.ZAI_API)) {
            assertEquals(1.0, preset.sampling().temperature(), 1e-9);
            assertEquals(0.95, preset.sampling().topP(), 1e-9);
            assertEquals(131072, preset.sampling().maxTokensLimit()); // 128K output
            assertTrue(preset.sampling().maxTokens() <= preset.sampling().maxTokensLimit());
        }
    }

    @Test
    void otherPresetsKeepConservativeSampling() {
        for (AiPreset preset : List.of(AiPreset.OPENAI, AiPreset.DEEPSEEK, AiPreset.OLLAMA, AiPreset.BIGMODEL)) {
            assertEquals(AiPreset.Sampling.STANDARD, preset.sampling());
        }
    }

    @Test
    void otherPresetsKeepSingleDefault() {
        assertEquals(List.of("deepseek-chat"), AiPreset.DEEPSEEK.models());
        assertEquals(List.of("llama3.1"), AiPreset.OLLAMA.models());
    }

    @Test
    void glmModelsOfferLowHighMax() {
        assertEquals(List.of(ReasoningEffort.LOW, ReasoningEffort.HIGH, ReasoningEffort.MAX),
                AiPreset.ZAI_CODING_PLAN.reasoningLevels("glm-5.3"));
        assertEquals(List.of(ReasoningEffort.LOW, ReasoningEffort.HIGH, ReasoningEffort.MAX),
                AiPreset.ZAI_API.reasoningLevels("glm-5.3-flash"));
        assertEquals(List.of(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH),
                AiPreset.OPENAI.reasoningLevels("o4-mini"));
        assertEquals("max", ReasoningEffort.MAX.wireValue);
    }
}
