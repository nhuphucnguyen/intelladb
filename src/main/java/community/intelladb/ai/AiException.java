package community.intelladb.ai;

import org.jetbrains.annotations.NotNull;

/** A failed AI call, with a message suitable for showing in the chat panel. */
public class AiException extends RuntimeException {

    public AiException(@NotNull String message) {
        super(message);
    }

    public AiException(@NotNull String message, @NotNull Throwable cause) {
        super(message, cause);
    }
}
