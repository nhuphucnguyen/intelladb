package dev.phucngu.intelladb.sql.completion;

import org.jetbrains.annotations.NotNull;

/**
 * One completion item, independent of the IDE's lookup API.
 *
 * @param lookup     what is matched against the typed prefix and shown
 * @param insertText what replaces the prefix (quoted and/or schema-qualified as needed)
 * @param detail     right-aligned hint, e.g. a column's type
 * @param location   grey text after the name, e.g. the table a column belongs to
 * @param priority   higher sorts first
 */
public record Suggestion(@NotNull String lookup, @NotNull String insertText, @NotNull Kind kind,
                         @NotNull String detail, @NotNull String location, int priority) {

    public enum Kind {
        KEYWORD, TYPE, SCHEMA, TABLE, VIEW, COLUMN, KEY_COLUMN, ALIAS, FUNCTION, ROUTINE,
        /** A whole join: {@code orders o ON o.user_id = u.id}. */
        JOIN,
        /** A join condition after ON: {@code o.user_id = u.id}. */
        JOIN_CONDITION
    }
}
