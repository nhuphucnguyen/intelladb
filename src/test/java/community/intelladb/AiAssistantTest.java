package community.intelladb;

import community.intelladb.ai.AiAssistant;
import community.intelladb.ai.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiAssistantTest {

    @Test
    void extractsSqlBlock() {
        String answer = "Here is the query:\n\n```sql\nSELECT count(*) FROM orders;\n```\nHope that helps!";
        assertEquals("SELECT count(*) FROM orders;", AiAssistant.firstSqlBlock(answer));
    }

    @Test
    void extractsSqlBlockWithLanguageNoise() {
        String answer = "```postgresql\nSELECT 1\n```";
        assertEquals("SELECT 1", AiAssistant.firstSqlBlock(answer));
    }

    @Test
    void noSqlBlock() {
        assertNull(AiAssistant.firstSqlBlock("The customers table stores customer data."));
    }

    @Test
    void systemPromptContainsDdl() {
        String prompt = AiAssistant.systemPrompt(null, true).content();
        assertTrue(prompt.contains("Intella DB"));
    }

    @Test
    void conversationAppendsQuestionLast() {
        ChatMessage system = ChatMessage.system("sys");
        List<ChatMessage> messages = AiAssistant.conversation(system,
                List.of(ChatMessage.user("q1"), ChatMessage.assistant("a1")), "q2");
        assertEquals(4, messages.size());
        assertEquals("system", messages.get(0).role());
        assertEquals("q2", messages.get(3).content());
        assertEquals("user", messages.get(3).role());
    }

    @Test
    void trimKeepsTail() {
        List<ChatMessage> history = List.of(
                ChatMessage.user("1"), ChatMessage.assistant("1"),
                ChatMessage.user("2"), ChatMessage.assistant("2"),
                ChatMessage.user("3"), ChatMessage.assistant("3"));
        assertEquals(4, AiAssistant.trim(history, 4).size());
        assertEquals("2", AiAssistant.trim(history, 4).get(0).content());
        assertEquals(6, AiAssistant.trim(history, 100).size());
    }
}
