package dev.phucngu.intelladb.sql.completion;

import dev.phucngu.intelladb.connection.DbDialect;
import dev.phucngu.intelladb.schema.IdentifierQuoting;
import dev.phucngu.intelladb.schema.SchemaCatalog;
import dev.phucngu.intelladb.sql.SqlVocabulary;
import dev.phucngu.intelladb.util.SqlSplitter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.UnaryOperator;

/**
 * Everything completion may suggest from, for one editor: the connection's schema snapshot,
 * the schema unqualified names resolve to, and the dialect's words, identifier quoting and
 * lexical rules. A console supplies it from its connection; any other SQL gets {@link #offline()}.
 *
 * @param catalog       the connection's loaded catalog; null when not connected (keywords only)
 * @param currentSchema where unqualified table names resolve; null when unknown
 * @param tableAliases  whether a table picked after FROM / JOIN gets a generated alias ({@code users u})
 */
public record CompletionScope(@Nullable SchemaCatalog catalog, @Nullable String currentSchema,
                              @NotNull SqlVocabulary vocabulary, @NotNull UnaryOperator<String> quoter,
                              @NotNull SqlSplitter.Options splitterOptions, boolean tableAliases) {

    public static @NotNull CompletionScope of(@NotNull DbDialect dialect, @Nullable SchemaCatalog catalog,
                                              @Nullable String currentSchema) {
        return new CompletionScope(catalog, currentSchema, dialect.vocabulary(), dialect::quote,
                dialect.splitterOptions(), true);
    }

    /** SQL with no connection behind it (e.g. a Liquibase changelog): ANSI words, no schema objects. */
    public static @NotNull CompletionScope offline() {
        return new CompletionScope(null, null, SqlVocabulary.ANSI, IdentifierQuoting::quote,
                SqlSplitter.Options.POSTGRES, true);
    }

    public @NotNull CompletionScope withTableAliases(boolean enabled) {
        return new CompletionScope(catalog, currentSchema, vocabulary, quoter, splitterOptions, enabled);
    }
}
