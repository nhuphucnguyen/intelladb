package community.intelladb.ai;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.Credentials;
import com.intellij.ide.passwordSafe.PasswordSafe;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads/writes AI provider API keys via the IDE PasswordSafe — one key per provider preset —
 * and runs provider pings. Every method touches the keychain, so none may run on the EDT.
 */
public final class AiCredentials {

    /** The single pre-M23 key; it belonged to whichever preset was selected then. */
    @SuppressWarnings("deprecation") // must match the attributes the key was stored with
    private static CredentialAttributes legacyAttributes() {
        return new CredentialAttributes(AiSettings.KEYRING_SERVICE, "apiKey", AiCredentials.class, false);
    }

    private static CredentialAttributes attributes(@NotNull String presetId) {
        return new CredentialAttributes(AiSettings.KEYRING_SERVICE, "apiKey." + presetId);
    }

    /** Key of the chat's active provider. */
    public static @Nullable String read() {
        return read(AiSettings.getInstance().presetId());
    }

    public static @Nullable String read(@NotNull String presetId) {
        try {
            String key = passwordOf(PasswordSafe.getInstance().get(attributes(presetId)));
            if (key == null && presetId.equals(AiSettings.getInstance().presetId())) {
                key = migrateLegacyKey(presetId);
            }
            return key;
        } catch (Exception e) {
            return null;
        }
    }

    /** Stores (or, for a blank key, removes) the key of one provider. */
    public static void write(@NotNull String presetId, @Nullable String key) {
        try {
            PasswordSafe.getInstance().set(attributes(presetId),
                    key == null || key.isBlank() ? null : new Credentials("apiKey", key));
        } catch (Exception ignored) {
        }
        AiSettings.getInstance().fireChanged();
    }

    /**
     * Providers the chat may use: set up in settings and, when the provider needs one,
     * with a saved API key. Local providers (Ollama, LM Studio) need no key.
     */
    public static @NotNull List<AiPreset> usablePresets() {
        AiSettings settings = AiSettings.getInstance();
        List<AiPreset> usable = new ArrayList<>();
        for (AiPreset preset : AiPreset.ALL) {
            if (!settings.isSetUp(preset.id()) || settings.models(preset.id()).isEmpty()) {
                continue;
            }
            String key = preset.needsApiKey() ? read(preset.id()) : null;
            if (!preset.needsApiKey() || (key != null && !key.isBlank())) {
                usable.add(preset);
            }
        }
        return usable;
    }

    private static @Nullable String migrateLegacyKey(@NotNull String presetId) {
        String legacy = passwordOf(PasswordSafe.getInstance().get(legacyAttributes()));
        if (legacy != null) {
            PasswordSafe.getInstance().set(attributes(presetId), new Credentials("apiKey", legacy));
            PasswordSafe.getInstance().set(legacyAttributes(), null);
        }
        return legacy;
    }

    private static @Nullable String passwordOf(@Nullable Credentials credentials) {
        String password = credentials != null ? credentials.getPasswordAsString() : null;
        return password == null || password.isBlank() ? null : password;
    }

    /** Quick sanity check used by the settings page's Test button. Must not run on the EDT. */
    public static @NotNull String ping() {
        AiSettings settings = AiSettings.getInstance();
        String key = read();
        // GLM reasoning models always think before answering (reasoning_content) — give the
        // ping enough budget to reach the answer, but keep it bounded.
        OpenAiCompatibleClient client = new OpenAiCompatibleClient(
                settings.baseUrl(), key == null ? "" : key, settings.model(),
                settings.temperature(), settings.topP(), Math.min(settings.maxTokens(), 4096));
        return client.chat(List.of(ChatMessage.user("Reply with exactly: OK")));
    }

    private AiCredentials() {
    }
}
