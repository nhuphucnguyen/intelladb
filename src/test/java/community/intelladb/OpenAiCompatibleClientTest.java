package community.intelladb;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import community.intelladb.ai.AiException;
import community.intelladb.ai.ChatMessage;
import community.intelladb.ai.OpenAiCompatibleClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the OpenAI-compatible wire format against an in-process HTTP server:
 * URL path, Authorization header, JSON body shape, response parsing, and error handling.
 */
class OpenAiCompatibleClientTest {

    private HttpServer server;
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<String> lastAuth = new AtomicReference<>();
    private final AtomicReference<JsonObject> lastBody = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String responseBody = """
            {"choices":[{"message":{"role":"assistant","content":"The answer"}}]}
            """;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            lastPath.set(exchange.getRequestURI().getPath());
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            try (InputStream in = exchange.getRequestBody()) {
                lastBody.set(JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                        .getAsJsonObject());
            }
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, responseBody.getBytes(StandardCharsets.UTF_8).length);
            exchange.getResponseBody().write(responseBody.getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private OpenAiCompatibleClient client() {
        return new OpenAiCompatibleClient(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "sk-test-key", "glm-4.6", 0.2, 512);
    }

    @Test
    void sendsOpenAiShapedRequest() {
        String answer = client().chat(List.of(
                ChatMessage.system("You are a DB assistant"),
                ChatMessage.user("How many orders?")));
        assertEquals("The answer", answer);
        assertEquals("/v1/chat/completions", lastPath.get());
        assertEquals("Bearer sk-test-key", lastAuth.get());
        JsonObject body = lastBody.get();
        assertEquals("glm-4.6", body.get("model").getAsString());
        assertEquals(0.2, body.get("temperature").getAsDouble(), 1e-9);
        assertEquals(512, body.get("max_tokens").getAsInt());
        assertEquals(2, body.getAsJsonArray("messages").size());
        assertEquals("system", body.getAsJsonArray("messages").get(0).getAsJsonObject().get("role").getAsString());
        assertEquals("How many orders?",
                body.getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString());
    }

    @Test
    void httpErrorBecomesAiException() {
        status = 401;
        responseBody = "{\"error\":{\"message\":\"bad key\"}}";
        AiException exception = assertThrows(AiException.class, () ->
                client().chat(List.of(ChatMessage.user("hi"))));
        assertTrue(exception.getMessage().contains("401"));
        assertTrue(exception.getMessage().contains("bad key"));
    }

    @Test
    void malformedJsonBecomesAiException() {
        responseBody = "<html>gateway error</html>";
        assertThrows(AiException.class, () -> client().chat(List.of(ChatMessage.user("hi"))));
    }

    @Test
    void emptyChoicesBecomesAiException() {
        responseBody = "{\"choices\":[]}";
        assertThrows(AiException.class, () -> client().chat(List.of(ChatMessage.user("hi"))));
    }

    @Test
    void unreachableServerBecomesAiException() {
        OpenAiCompatibleClient dead = new OpenAiCompatibleClient(
                "http://127.0.0.1:1/v1", "", "m", 0.0, 16);
        AiException exception = assertThrows(AiException.class, () ->
                dead.chat(List.of(ChatMessage.user("hi"))));
        assertTrue(exception.getMessage().contains("Could not reach"));
    }
}
