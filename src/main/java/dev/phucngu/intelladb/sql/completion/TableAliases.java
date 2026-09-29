package dev.phucngu.intelladb.sql.completion;

import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.Set;

/**
 * Short aliases for tables, as completion inserts them: the initials of the name's words
 * ({@code users} → {@code u}, {@code order_items} / {@code OrderItems} → {@code oi}), with a
 * number added while the alias is taken or a keyword ({@code u1}, {@code as1}).
 */
final class TableAliases {

    /**
     * @param taken    lower-case names already used in the statement (aliases and table names)
     * @param keywords lower-case words that cannot be an unquoted alias
     */
    static @NotNull String aliasFor(@NotNull String table, @NotNull Set<String> taken, @NotNull Set<String> keywords) {
        String base = initials(table);
        String alias = base;
        for (int n = 1; taken.contains(alias) || keywords.contains(alias); n++) {
            alias = base + n;
        }
        return alias;
    }

    /** First letter of each word; words are split at non-letters and lower→upper case changes. */
    private static @NotNull String initials(@NotNull String name) {
        StringBuilder initials = new StringBuilder();
        boolean wordStart = true;
        char previous = 0;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!Character.isLetter(c)) {
                wordStart = true;
            } else {
                boolean camelBoundary = Character.isUpperCase(c) && Character.isLowerCase(previous);
                if (wordStart || camelBoundary) {
                    initials.append(Character.toLowerCase(c));
                }
                wordStart = false;
            }
            previous = c;
        }
        return initials.isEmpty() ? "t" : initials.toString().toLowerCase(Locale.ROOT);
    }

    private TableAliases() {
    }
}
