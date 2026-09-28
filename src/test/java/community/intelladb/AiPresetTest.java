package community.intelladb;

import community.intelladb.ai.AiPreset;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AiPresetTest {

    @Test
    void zaiPresetsOfferGlm53Family() {
        for (AiPreset preset : List.of(AiPreset.ZAI_CODING_PLAN, AiPreset.ZAI_API)) {
            assertEquals(List.of("glm-5.3", "glm-5.3-flash"), preset.models());
            assertEquals("glm-5.3", preset.defaultModel());
        }
    }

    @Test
    void otherPresetsKeepSingleDefault() {
        assertEquals(List.of("deepseek-chat"), AiPreset.DEEPSEEK.models());
        assertEquals(List.of("llama3.1"), AiPreset.OLLAMA.models());
    }
}
