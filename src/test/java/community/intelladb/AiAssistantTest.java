package community.intelladb;

import community.intelladb.ai.AiAssistant;
import community.intelladb.ai.ChatMessage;
import community.intelladb.connection.SqlResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
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
    void compactLeavesHistoryUntouchedWithinLimits() {
        List<ChatMessage> history = exchanges(3);
        assertSame(history, AiAssistant.compact(history, 10, 10_000));
    }

    @Test
    void compactDropsOldestHalfInOneStep() {
        List<ChatMessage> history = exchanges(6); // 12 messages > 10
        List<ChatMessage> compacted = AiAssistant.compact(history, 10, 10_000);
        assertEquals(4, compacted.size()); // down to <= half, whole exchanges only
        assertEquals("q4", compacted.get(0).content());
        assertEquals("user", compacted.get(0).role());
        assertEquals("a5", compacted.get(3).content());
    }

    @Test
    void appendAfterCompactionKeepsPrefixStable() {
        List<ChatMessage> history = AiAssistant.compact(exchanges(6), 10, 10_000);
        List<ChatMessage> before = List.copyOf(history);
        history.add(ChatMessage.user("next"));
        history.add(ChatMessage.assistant("answer"));
        assertSame(history, AiAssistant.compact(history, 10, 10_000));
        assertEquals(before, history.subList(0, before.size())); // same leading messages → cache hit
    }

    @Test
    void compactAlsoHonoursCharacterBudget() {
        List<ChatMessage> history = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            history.add(ChatMessage.user("q" + i));
            history.add(ChatMessage.assistant("x".repeat(100)));
        }
        List<ChatMessage> compacted = AiAssistant.compact(history, 100, 300);
        assertEquals(2, compacted.size());
        assertEquals("q3", compacted.get(0).content());
    }

    private static List<ChatMessage> exchanges(int count) {
        List<ChatMessage> history = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            history.add(ChatMessage.user("q" + i));
            history.add(ChatMessage.assistant("a" + i));
        }
        return history;
    }

    @Test
    void describesRowsAsCappedMarkdownTable() {
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{1, "a|b", null});
        rows.add(new Object[]{2, "line1\nline2", "x".repeat(300)});
        for (int i = 0; i < 60; i++) {
            rows.add(new Object[]{i, "r", "v"});
        }
        String text = AiAssistant.describeResult(" select * from t ",
                SqlResult.rows("select * from t", List.of("id", "name", "note"), rows, false, 3));
        assertTrue(text.startsWith("Query result of:\n```sql\nselect * from t\n```\n62 rows:"));
        assertTrue(text.contains("| id | name | note |\n| --- | --- | --- |\n| 1 | a\\|b | NULL |"));
        assertTrue(text.contains("| 2 | line1 line2 | " + "x".repeat(200) + "… |"));
        assertTrue(text.contains("(12 more rows not shown)"));
    }

    @Test
    void describesErrorsAndUpdateCounts() {
        assertTrue(AiAssistant.describeResult("bad", SqlResult.error("bad", "syntax error", 1))
                .endsWith("Error: syntax error\n"));
        assertTrue(AiAssistant.describeResult("update t set x = 1", SqlResult.update("update t set x = 1", 3, 1))
                .endsWith("3 rows affected\n"));
    }

    @Test
    void resultsGoAheadOfTheQuestion() {
        assertEquals("What next?", AiAssistant.withContext("", "What next?"));
        assertEquals("Query result of: x\n\nWhat next?", AiAssistant.withContext("Query result of: x\n", "What next?"));
    }
}
