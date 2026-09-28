package community.intelladb;

import community.intelladb.ai.ChatHistory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatHistoryTest {

    @TempDir
    Path dir;

    private static ChatHistory.Turn turn(String question) {
        return new ChatHistory.Turn("c1", "localhost", question, "Query result of: " + question,
                "Answer to " + question, "glm-5.3",
                LocalDateTime.of(2026, 9, 28, 10, 0));
    }

    @Test
    void conversationsSurviveAReload() throws Exception {
        Path file = dir.resolve("chat.json.gz");
        ChatHistory history = new ChatHistory(file);
        ChatHistory.Conversation first = history.start();
        history.addTurn(first, turn("How many orders?"));
        history.addTurn(first, turn("And per fund?"));
        ChatHistory.Conversation second = history.start();
        history.addTurn(second, turn("List the tables"));
        history.start(); // empty: not listed, not saved
        history.awaitSaved();

        ChatHistory reloaded = new ChatHistory(file);
        List<ChatHistory.Conversation> conversations = reloaded.conversations();
        assertEquals(2, conversations.size());
        assertEquals(second.id, conversations.get(0).id); // most recently updated first
        assertEquals("How many orders?", conversations.get(1).title());
        assertEquals(List.of(turn("How many orders?"), turn("And per fund?")), conversations.get(1).turns());
    }

    @Test
    void continuingAConversationMovesItToTheTop() {
        ChatHistory history = new ChatHistory((Path) null);
        ChatHistory.Conversation older = history.start();
        history.addTurn(older, turn("q1"));
        ChatHistory.Conversation newer = history.start();
        history.addTurn(newer, turn("q2"));
        history.addTurn(older, turn("q3"));
        assertEquals(older.id, history.conversations().get(0).id);
    }

    @Test
    void titleIsTheFirstQuestionOnOneLine() {
        ChatHistory history = new ChatHistory((Path) null);
        ChatHistory.Conversation conversation = history.start();
        history.addTurn(conversation, turn("  Which funds\n  closed today?  "));
        assertEquals("Which funds closed today?", conversation.title());
        history.clear();
        assertTrue(history.conversations().isEmpty());
    }
}
