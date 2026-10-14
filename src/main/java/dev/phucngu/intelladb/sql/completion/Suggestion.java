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
 * @param caretBack  for multi-part inserts (postfix templates): how far left of the inserted
 *                   text's end the caret lands (0: at the end)
 */
public record Suggestion(@NotNull String lookup, @NotNull String insertText, @NotNull Kind kind,
                         @NotNull String detail, @NotNull String location, int priority, int caretBack) {

    public Suggestion(@NotNull String lookup, @NotNull String insertText, @NotNull Kind kind,
                      @NotNull String detail, @NotNull String location, int priority) {
        this(lookup, insertText, kind, detail, location, priority, 0);
    }

    public enum Kind {
        KEYWORD, TYPE, SCHEMA, TABLE, VIEW, COLUMN, KEY_COLUMN, ALIAS, FUNCTION, ROUTINE,
        /** A whole join: {@code orders o ON o.user_id = u.id}. */
        JOIN,
        /** A join condition after ON: {@code o.user_id = u.id}. */
        JOIN_CONDITION,
        /** Every column of the insert target at once: {@code id, name, email}. */
        COLUMN_LIST,
        /** A postfix template: replaces {@code table.from} with {@code * from table}. */
        POSTFIX
    }
}
