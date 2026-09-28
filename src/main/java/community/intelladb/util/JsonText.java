package community.intelladb.util;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNull;

/**
 * JSON helpers for cell viewing: recognizes JSON text and pretty-prints it. Anything
 * that is not complete JSON comes back unchanged.
 */
public final class JsonText {

    private JsonText() {
    }

    /** True when the text is a complete JSON object or array (scanner-level check). */
    public static boolean isJson(@NotNull String text) {
        String trimmed = text.trim();
        if (trimmed.isEmpty()
                || (!trimmed.startsWith("{") && !trimmed.startsWith("["))) {
            return false;
        }
        try {
            JsonElement element = JsonParser.parseString(trimmed);
            return element.isJsonObject() || element.isJsonArray();
        } catch (Exception e) {
            return false;
        }
    }

    /** Pretty-prints the text when it is JSON; returns the input unchanged otherwise. */
    public static @NotNull String prettyIfJson(@NotNull String text) {
        if (!isJson(text)) {
            return text;
        }
        try {
            JsonElement element = JsonParser.parseString(text.trim());
            return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(element);
        } catch (Exception e) {
            return text;
        }
    }
}
