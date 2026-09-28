package community.intelladb.ai;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.Credentials;
import com.intellij.ide.passwordSafe.PasswordSafe;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Reads/writes the AI provider API key via the IDE PasswordSafe; runs provider pings. */
public final class AiCredentials {

    private static CredentialAttributes attributes() {
        return new CredentialAttributes(AiSettings.KEYRING_SERVICE, "apiKey", AiCredentials.class, false);
    }

    public static @Nullable String read() {
        try {
            Credentials credentials = PasswordSafe.getInstance().get(attributes());
            return credentials != null ? credentials.getPasswordAsString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    public static void write(@Nullable String key) {
        try {
            PasswordSafe.getInstance().set(attributes(),
                    key == null || key.isBlank() ? null : new Credentials("apiKey", key));
        } catch (Exception ignored) {
        }
    }

    /** Quick sanity check used by the settings page's Test button. Must not run on the EDT. */
    public static @NotNull String ping() {
        AiSettings settings = AiSettings.getInstance();
        String key = read();
        // GLM reasoning models spend tokens on reasoning_content before content — budget enough.
        OpenAiCompatibleClient client = new OpenAiCompatibleClient(
                settings.baseUrl(), key == null ? "" : key, settings.model(), 0.0, 512);
        return client.chat(List.of(ChatMessage.user("Reply with exactly: OK")));
    }

    private AiCredentials() {
    }
}
