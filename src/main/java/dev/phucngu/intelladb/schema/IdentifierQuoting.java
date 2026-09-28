package dev.phucngu.intelladb.schema;

import org.jetbrains.annotations.NotNull;

/** Quotes SQL identifiers only when they are not plain lowercase names. */
public final class IdentifierQuoting {

    public static @NotNull String quote(@NotNull String identifier) {
        return identifier.matches("[a-z_][a-z0-9_]*") ? identifier : '"' + identifier + '"';
    }

    private IdentifierQuoting() {
    }
}
