package community.intelladb.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNull;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Minimal client for the OpenAI chat-completions wire format
 * ({@code POST {baseUrl}/chat/completions}). Works with every OpenAI-compatible
 * provider: Z.ai GLM, OpenAI, DeepSeek, OpenRouter, Ollama, LM Studio, vLLM, …
 */
public final class OpenAiCompatibleClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(120);

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final double temperature;
    private final int maxTokens;
    private final HttpClient http;

    public OpenAiCompatibleClient(@NotNull String baseUrl, @NotNull String apiKey,
                                  @NotNull String model, double temperature, int maxTokens) {
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.apiKey = apiKey;
        this.model = model;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    /** Sends the conversation and returns the assistant's message content. Blocking; not for the EDT. */
    public @NotNull String chat(@NotNull List<ChatMessage> messages) {
        if (baseUrl.isBlank()) {
            throw new AiException("No AI provider base URL configured.\n" +
                    "Settings → Tools → Intella DB — AI Provider.");
        }
        if (model.isBlank()) {
            throw new AiException("No model configured.\n" +
                    "Settings → Tools → Intella DB — AI Provider.");
        }
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("temperature", temperature);
        body.addProperty("max_tokens", maxTokens);
        JsonArray array = new JsonArray();
        for (ChatMessage message : messages) {
            JsonObject m = new JsonObject();
            m.addProperty("role", message.role());
            m.addProperty("content", message.content());
            array.add(m);
        }
        body.add("messages", array);

        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        if (!apiKey.isBlank()) {
            request.header("Authorization", "Bearer " + apiKey);
        }

        HttpResponse<String> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new AiException("Could not reach the AI provider: " + rootMessage(e), e);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new AiException("AI provider returned HTTP " + response.statusCode()
                    + (response.body() == null || response.body().isBlank()
                    ? "" : "\n" + abbreviate(response.body())));
        }
        try {
            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            JsonArray choices = json.getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) {
                throw new AiException("AI provider returned no choices.");
            }
            JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
            String content = message != null && message.has("content") && !message.get("content").isJsonNull()
                    ? message.get("content").getAsString() : "";
            if (content.isBlank()) {
                throw new AiException("AI provider returned an empty answer.");
            }
            return content;
        } catch (AiException e) {
            throw e;
        } catch (Exception e) {
            throw new AiException("Could not parse the AI provider response.\n" + abbreviate(response.body()), e);
        }
    }

    private static @NotNull String trimTrailingSlash(@NotNull String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static @NotNull String rootMessage(@NotNull Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t.getMessage() == null ? t.toString() : t.getMessage();
    }

    private static @NotNull String abbreviate(@NotNull String s) {
        String oneLine = s.replace('\n', ' ').strip();
        return oneLine.length() > 400 ? oneLine.substring(0, 400) + "…" : oneLine;
    }
}
