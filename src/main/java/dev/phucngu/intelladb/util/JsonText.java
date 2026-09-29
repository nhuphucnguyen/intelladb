package dev.phucngu.intelladb.util;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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

    /** Order of object properties when pretty-printing. */
    public enum KeyOrder {
        ORIGINAL("Original order"),
        ASCENDING("Name A → Z"),
        DESCENDING("Name Z → A");

        public final String label;

        KeyOrder(@NotNull String label) {
            this.label = label;
        }
    }

    /** Pretty-prints the text when it is JSON; returns the input unchanged otherwise. */
    public static @NotNull String prettyIfJson(@NotNull String text) {
        return prettyIfJson(text, KeyOrder.ORIGINAL);
    }

    /**
     * Pretty-prints JSON with object properties in the given order — applied at every
     * nesting level, including objects inside arrays (array elements keep their order).
     * Names compare case-insensitively, ties broken case-sensitively. Non-JSON comes back unchanged.
     */
    public static @NotNull String prettyIfJson(@NotNull String text, @NotNull KeyOrder order) {
        if (!isJson(text)) {
            return text;
        }
        try {
            JsonElement element = JsonParser.parseString(text.trim());
            if (order != KeyOrder.ORIGINAL) {
                element = sorted(element, order);
            }
            return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create()
                    .toJson(element);
        } catch (Exception e) {
            return text;
        }
    }

    /** JSON on one line without extra whitespace (properties in their order); non-JSON comes back unchanged. */
    public static @NotNull String compactIfJson(@NotNull String text) {
        if (!isJson(text)) {
            return text;
        }
        try {
            return new GsonBuilder().disableHtmlEscaping().serializeNulls().create()
                    .toJson(JsonParser.parseString(text.trim()));
        } catch (Exception e) {
            return text;
        }
    }

    private static final Comparator<String> NAME_ORDER =
            String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder());

    private static @NotNull JsonElement sorted(@NotNull JsonElement element, @NotNull KeyOrder order) {
        if (element.isJsonObject()) {
            List<String> names = new ArrayList<>(element.getAsJsonObject().keySet());
            names.sort(order == KeyOrder.DESCENDING ? NAME_ORDER.reversed() : NAME_ORDER);
            JsonObject result = new JsonObject();
            for (String name : names) {
                result.add(name, sorted(element.getAsJsonObject().get(name), order));
            }
            return result;
        }
        if (element.isJsonArray()) {
            JsonArray result = new JsonArray();
            for (JsonElement item : element.getAsJsonArray()) {
                result.add(sorted(item, order));
            }
            return result;
        }
        return element;
    }
}
